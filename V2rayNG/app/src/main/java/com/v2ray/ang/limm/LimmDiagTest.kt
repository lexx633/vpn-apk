package com.v2ray.ang.limm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Per-profile connectivity test.
 *
 * Sequence:
 *  1. Stop VPN if running
 *  2. Baseline check-in (VPN off)
 *  3. For each profile:
 *       - switch to profile
 *       - start VPN, wait for SOCKS (up to 10s)
 *       - test egress IP via SOCKS → api.ipify.org (must equal server IP)
 *       - stop VPN
 *  4. Restore original profile
 *  5. Upload applog
 *
 * Max time per profile: ~25s (6s SOCKS wait + 2×8s egress retries + overhead).
 * XHTTP may return no data on the first request — up to EGRESS_RETRY_MAX attempts are made.
 * ok=true if any egress IP is returned (tunnel up); egress_ip recorded for multi-server analysis.
 */
object LimmDiagTest {

    /** True while a Full Test is running — CoreServiceManager checks this to suppress the
     *  allowInsecure deprecation red-toast (profiles start back-to-back during the test). */
    @Volatile
    var isRunning = false

    private const val TAG = "LimmDiag"
    private const val SOCKS_WAIT_MAX_MS = 6_000L   // 10s → 6s: большинство профилей поднимаются за 2-3s
    private const val SOCKS_POLL_MS = 150L
    private const val SOCKS_CLOSE_MAX_MS = 3_000L  // 6s → 3s: ждём гибели старого xray
    // Бюджет на профиль ограничен (~12s макс): egressViaSocks пробует ОДИН url (ipify), без
    // второго фолбэка — раньше 2 url × таймаут × ретраи давали до 90s на одном профиле («борщ»).
    private const val EGRESS_TIMEOUT_SEC = 5L        // не-xhttp: 5s × 2 = ~10s
    private const val EGRESS_RETRY_MAX = 2
    private const val EGRESS_RETRY_DELAY_MS = 400L
    // XHTTP профили теперь mode=stream-up (h2 только, без h3-зондирования).
    // Подключение занимает 2-5s вместо ~15s (которые давал h3-probe timeout в mode=auto).
    private const val XHTTP_EGRESS_TIMEOUT_SEC = 8L  // xhttp stream-up: быстрый h2, 8s с запасом
    private const val XHTTP_EGRESS_RETRY_MAX = 2     // одна попытка повтора на случай transient
    // hy2 (UDP-handshake) иногда чуть дольше базовых 10s — даём 12s, чтобы не флапал в fail.
    private const val HY2_EGRESS_TIMEOUT_SEC = 6L    // hy2: 6s × 2 = ~12s
    private const val HY2_EGRESS_RETRY_MAX = 2

    private fun vpnTransportUp(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (e: Exception) { false }

    private fun socksAccepting(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300); true }
    } catch (e: Exception) { false }

    private suspend fun waitForSocks(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + SOCKS_WAIT_MAX_MS
        while (System.currentTimeMillis() < deadline) {
            if (socksAccepting(port)) return true
            delay(SOCKS_POLL_MS)
        }
        return false
    }

    /** Wait until the SOCKS port stops accepting — old Xray is fully dead. */
    private suspend fun waitForSocksClosed(port: Int) {
        val deadline = System.currentTimeMillis() + SOCKS_CLOSE_MAX_MS
        while (System.currentTimeMillis() < deadline) {
            if (!socksAccepting(port)) return
            delay(250)
        }
    }

    private fun egressViaSocks(socksPort: Int, timeoutSec: Long = EGRESS_TIMEOUT_SEC): String? {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
        // Single endpoint (no amazonaws fallback): 2 urls × timeout × retries ballooned to ~90s
        // on a dead profile. One url keeps the per-profile budget tight.
        try {
            client.newCall(Request.Builder().url("https://api.ipify.org").build())
                .execute().use { r -> if (r.isSuccessful) return r.body?.string()?.trim() }
        } catch (e: Exception) { /* timeout/fail → null */ }
        return null
    }

    private data class ProfileResult(val name: String, val ok: Boolean, val latencyMs: Long?, val egressIp: String? = null)

    private fun postFullTest(ctx: Context, profiles: List<ProfileResult>) {
        if (profiles.isEmpty()) return
        try {
            val arr = JSONArray()
            for (p in profiles) {
                val obj = JSONObject().apply {
                    put("name", p.name)
                    put("ok", if (p.ok) 1 else 0)
                    if (p.latencyMs != null) put("latency_ms", p.latencyMs)
                    if (p.egressIp != null) put("egress_ip", p.egressIp)
                }
                arr.put(obj)
            }
            val payload = JSONObject().apply {
                put("client_uid", LimmConfig.clientUid(ctx))
                put("kind", "android")
                put("profiles", arr)
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
                .newCall(Request.Builder()
                    .url("${LimmConfig.collectorUrl}/api/fulltest")
                    .header("Authorization", "Bearer ${LimmConfig.token}")
                    .post(body).build())
                .execute().use { r ->
                    if (!r.isSuccessful) Log.w(TAG, "postFullTest: server returned ${r.code}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "postFullTest: ${e.message}")
        }
    }

    suspend fun run(ctx: Context, onProgress: (String) -> Unit) {
        val permIntent = android.net.VpnService.prepare(ctx)
        if (permIntent != null) {
            onProgress("❌  Нет разрешения VPN.\n\nСначала подключитесь вручную, затем запустите тест снова.")
            return
        }

        isRunning = true   // suppress allowInsecure red-toast while the test churns profiles
        try {

        val wasRunning = CoreServiceManager.isRunning() || vpnTransportUp(ctx)
        if (wasRunning) {
            onProgress("⏹  Останавливаю VPN…")
            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext
                CoreServiceManager.stopVService(ctx)
            }
            delay(2000)
        }

        Log.i(TAG, "=== LIMM DIAG TEST START ===")

        onProgress("📡  Чек-ин (VPN выкл)…")
        val (ok0, msg0) = LimmCheckinWorker.sendNow(ctx)
        onProgress(if (ok0) "    ✓ $msg0" else "    ✗ $msg0")
        Log.i(TAG, "checkin-off: ok=$ok0 $msg0")

        val guids = MmkvManager.decodeAllServerList()
        val savedGuid = MmkvManager.getSelectServer()
        val socksPort = try { SettingsManager.getSocksPort() } catch (e: Exception) { 10808 }
        val serverIp = LimmConfig.serverIp

        onProgress("\n── Профили (${guids.size}) ──\n")

        val profileResults = mutableListOf<ProfileResult>()

        for (guid in guids) {
            val cfg = MmkvManager.decodeServerConfig(guid)
            val name = cfg?.remarks?.takeIf { it.isNotEmpty() } ?: guid.take(8)

            // Skip any leftover -awg profiles: AWG was removed, so testing one as plain xray
            // WireGuard would just report a broken tunnel and pollute the verdict.
            if (name.endsWith("-awg", ignoreCase = true)) {
                onProgress("    ⚪  ▸ $name  (AWG больше не поддерживается)")
                continue
            }

            onProgress("⏳  ▸ $name…")
            Log.i(TAG, "--- profile: $name ($guid) ---")

            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext
                MmkvManager.setSelectServer(guid)
                CoreServiceManager.startVService(ctx)
            }
            delay(100)

            val socksReady = waitForSocks(socksPort)
            if (!socksReady) {
                onProgress("    ✗  ▸ $name  (SOCKS :$socksPort не поднялся за ${SOCKS_WAIT_MAX_MS / 1000}s)")
                Log.w(TAG, "profile $name: SOCKS timeout")
                profileResults.add(ProfileResult(name, false, null))
                withContext(Dispatchers.Main) {
                    if (!isActive) return@withContext
                    CoreServiceManager.stopVService(ctx)
                }
                delay(1500)
                continue
            }

            val t0 = System.currentTimeMillis()
            // XHTTP профили mode=stream-up (h2, без h3-probe) — быстрый, но чуть больше времени чем tcp.
            val isXhttp = name.endsWith("-xhttp", ignoreCase = true)
            val isHy2 = name.endsWith("-hy2", ignoreCase = true)
            val egTimeout = when { isXhttp -> XHTTP_EGRESS_TIMEOUT_SEC; isHy2 -> HY2_EGRESS_TIMEOUT_SEC; else -> EGRESS_TIMEOUT_SEC }
            val egRetries = when { isXhttp -> XHTTP_EGRESS_RETRY_MAX; isHy2 -> HY2_EGRESS_RETRY_MAX; else -> EGRESS_RETRY_MAX }
            var egress: String? = null
            for (attempt in 1..egRetries) {
                egress = withContext(Dispatchers.IO) { egressViaSocks(socksPort, egTimeout) }
                if (egress != null) break
                if (attempt < egRetries) {
                    onProgress("    ↻  попытка ${attempt + 1}/$egRetries…")
                    Log.d(TAG, "profile $name: egress attempt $attempt failed, retrying")
                    delay(EGRESS_RETRY_DELAY_MS)
                }
            }
            val ms = System.currentTimeMillis() - t0
            // ok = tunnel carried traffic (any egress returned). We don't compare to serverIp
            // because profiles may exit through different servers (FR / DE1 / etc.).
            val vpnOk = egress != null
            val note = when {
                egress != null -> "$egress  [${ms}ms]${if (egress == serverIp) "  = DE1 ✓" else ""}"
                else           -> "нет ответа  [${ms}ms, $egRetries попытки]"
            }
            onProgress("    ${if (vpnOk) "✓" else "✗"}  ▸ $name  ($note)")
            Log.i(TAG, "profile $name: egress=$egress serverIp=$serverIp ok=$vpnOk ms=$ms")
            profileResults.add(ProfileResult(name, vpnOk, if (vpnOk) ms else null, egress))

            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext
                CoreServiceManager.stopVService(ctx)
            }
            delay(500)
            // Wait for old Xray to fully die before starting next profile.
            // Without this, old Xray's SOCKS inbound may still serve the next profile's
            // egress check → reports ok=1 through the previous (working) tunnel.
            withContext(Dispatchers.IO) { waitForSocksClosed(socksPort) }
        }

        // Cache results for LimmLogReporter — included in applog (auto + manual "Send log" button)
        LimmLogReporter.cachedDiagResults = JSONArray().also { arr ->
            for (p in profileResults) arr.put(JSONObject().apply {
                put("name", p.name)
                put("ok", if (p.ok) 1 else 0)
                p.latencyMs?.let { put("latency_ms", it) }
                p.egressIp?.let { put("egress_ip", it) }
            })
        }

        // Upload profile test results to /api/fulltest
        withContext(Dispatchers.IO) { postFullTest(ctx, profileResults) }

        // Post-test checkin: switch to best working profile, run full checkin so the
        // dashboard shows correct Статус / Сервисы / Пинг after Full Test.
        val bestResult = profileResults
            .filter { it.ok }
            .minByOrNull { it.latencyMs ?: Long.MAX_VALUE }
        val bestIdx = if (bestResult != null) profileResults.indexOf(bestResult) else -1
        var logUploaded = false
        if (bestIdx >= 0) {
            val bestGuid = guids[bestIdx]
            val bestName = profileResults[bestIdx].name
            onProgress("\n⏳  Чекин (VPN on · $bestName)…")
            Log.i(TAG, "post-test checkin: switching to $bestName ($bestGuid)")
            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext
                MmkvManager.setSelectServer(bestGuid)
                CoreServiceManager.startVService(ctx)
            }
            val ckReady = waitForSocks(socksPort)
            if (ckReady) {
                val (ckOk, ckMsg) = withContext(Dispatchers.IO) { LimmCheckinWorker.sendNow(ctx) }
                onProgress(if (ckOk) "    ✓ $ckMsg" else "    ✗ $ckMsg")
                Log.i(TAG, "post-test checkin: ok=$ckOk $ckMsg")
                // Upload log while VPN is still on — limm.space may not resolve without the tunnel.
                onProgress("\n📤  Отправляю лог на сервер…")
                val (logOk, logMsg) = withContext(Dispatchers.IO) { LimmLogReporter.send(ctx) }
                onProgress(if (logOk) "    ✓ Лог отправлен" else "    ✗ $logMsg")
                logUploaded = true
            } else {
                onProgress("    ✗ SOCKS не поднялся для чекина")
            }
            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext
                CoreServiceManager.stopVService(ctx)
            }
            delay(500)
            withContext(Dispatchers.IO) { waitForSocksClosed(socksPort) }
        }

        // Restore original profile
        if (savedGuid != null) {
            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext
                MmkvManager.setSelectServer(savedGuid)
            }
        }

        // M1: if the VPN was running when the test started, bring it back up — the test
        // only restored the selected profile, leaving the service itself off.
        if (wasRunning) {
            onProgress("\n▶  Восстанавливаю VPN…")
            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext
                CoreServiceManager.startVService(ctx)
            }
        }

        Log.i(TAG, "=== LIMM DIAG TEST END ===")

        // Fallback: upload log without VPN (only when no working profile was found).
        if (!logUploaded) {
            onProgress("\n📤  Отправляю лог на сервер…")
            val (logOk, logMsg) = withContext(Dispatchers.IO) { LimmLogReporter.send(ctx) }
            onProgress(if (logOk) "    ✓ Лог отправлен" else "    ✗ $logMsg")
        }
        } finally {
            isRunning = false
        }
    }
}
