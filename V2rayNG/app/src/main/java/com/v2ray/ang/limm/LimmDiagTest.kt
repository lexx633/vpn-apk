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
 * Per-profile connectivity test (Full Test).
 *
 * Sequence:
 *  1. Stop VPN if running, baseline check-in (VPN off)
 *  2. Warm up VpnService once (F2.3)
 *  3. For each profile:
 *       - TCP pre-ping host:port for TCP transports; skip dead ones without starting the core (F3.1)
 *       - stop the profile left running from the previous iteration (F1.1), then start this one
 *       - wait for SOCKS, fetch egress IP via SOCKS (own /api/myip, ipify fallback)
 *       - liveness gate: generate_204 via SOCKS; egress-ok-but-204-fail → degraded (F2.2)
 *  4. Post a per-profile report to /api/fulltest; pick best (non-degraded, then lowest latency),
 *     reusing its tunnel if still up (F1.1); post-test check-in + log upload while VPN is on
 *  5. Restore original profile / VPN state
 *
 * ok = any egress IP returned (tunnel carries traffic); profiles may exit through different nodes,
 * so egress is NOT compared to a single server IP. Per-profile budget: ~4–6s healthy, up to ~25s
 * on a flaky one. Note: latency_ms = wall-clock (start + egress retries), NOT a clean tunnel RTT.
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
    private const val EGRESS_TIMEOUT_SEC = 5L        // не-xhttp: 5s × 3 = ~15s
    // 2→3: интермиттентные silent-дропы TCP-коннекта к DE1 (77.90.52.123) на части сетей
    // съедали обе попытки → DE1/DE1-xhttp (всегда первые в очереди, + холодный старт
    // VpnService) ложно краснели, хотя DE1 достижим (cf-ws/hy2 проходят). Каждый re-try =
    // новый REALITY-дозвон; 3-я попытка добивает потерю. Бюджет растёт только на падающих.
    private const val EGRESS_RETRY_MAX = 3
    private const val EGRESS_RETRY_DELAY_MS = 400L
    // XHTTP профили теперь mode=stream-up (h2 только, без h3-зондирования).
    // Подключение занимает 2-5s вместо ~15s (которые давал h3-probe timeout в mode=auto).
    private const val XHTTP_EGRESS_TIMEOUT_SEC = 8L  // xhttp stream-up: быстрый h2, 8s с запасом
    private const val XHTTP_EGRESS_RETRY_MAX = 3     // 2→3: то же, что и для tcp (DE1-xhttp флапал)
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
            .dns(LimmDns.IPV4_ONLY)
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
        // F2.1b: own /api/myip first to drop the sole dependency on ipify. The probe runs THROUGH
        // the tunnel (exit is outside RU), so the CF path (collectorUrl = limm.space) is always
        // reachable and returns the exit-node IP via CF-Connecting-IP. We deliberately do NOT use
        // the www/vpn mirrors here — through the :443 stream-mux they echo 127.0.0.1 (no real-peer
        // propagation). One myip call + one ipify fallback keeps the per-profile budget tight.
        try {
            client.newCall(Request.Builder().url("${LimmConfig.collectorUrl}/api/myip").build())
                .execute().use { r ->
                    if (r.isSuccessful) parseMyIp(r.body?.string())?.let { return it }
                }
        } catch (e: Exception) { /* fall through to ipify */ }
        try {
            client.newCall(Request.Builder().url("https://api.ipify.org").build())
                .execute().use { r -> if (r.isSuccessful) return r.body?.string()?.trim() }
        } catch (e: Exception) { /* timeout/fail → null */ }
        return null
    }

    /** Parse {"ip":"..."} from /api/myip, rejecting blank/loopback (defends against the www/mux
     *  path that echoes 127.0.0.1 instead of the real egress). */
    private fun parseMyIp(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val ip = JSONObject(body).optString("ip", "").trim()
            if (ip.isEmpty() || ip.startsWith("127.") || ip == "::1" || ip == "0.0.0.0") null else ip
        } catch (e: Exception) { null }
    }

    /** F3.1: raw TCP reachability of the transport's host:port before spinning up the core —
     *  a dead TCP transport can't tunnel, so skip the heavy egress probe. 1 retry. */
    private fun tcpPrePing(host: String, port: Int, timeoutMs: Int = 1500): Boolean {
        repeat(2) { attempt ->
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); return true }
            } catch (e: Exception) {
                if (attempt == 0) try { Thread.sleep(200) } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt(); return false
                }
            }
        }
        return false
    }

    /** F2.2: second liveness gate through the tunnel — generate_204 reachability, to tell
     *  "egress endpoint answered" from "general browsing actually works". */
    private fun liveness204ViaSocks(socksPort: Int, timeoutSec: Long = 5L): Boolean = try {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        OkHttpClient.Builder()
            .proxy(proxy)
            .dns(LimmDns.IPV4_ONLY)
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
            .newCall(Request.Builder().url("https://www.gstatic.com/generate_204").build())
            .execute().use { it.isSuccessful || it.code == 204 }
    } catch (e: Exception) { false }

    private data class ProfileResult(
        val name: String,
        val ok: Boolean,
        val latencyMs: Long?,
        val egressIp: String? = null,
        val degraded: Boolean = false,
        val guid: String = "",
    )

    private fun postFullTest(ctx: Context, profiles: List<ProfileResult>) {
        if (profiles.isEmpty()) return
        try {
            val arr = JSONArray()
            for (p in profiles) {
                val obj = JSONObject().apply {
                    put("name", p.name)
                    put("ok", if (p.ok) 1 else 0)
                    // §7.7 contract field: raw L4 liveness (generate_204 ok through the tunnel).
                    // browser_ok = profile up AND 204 passed; degraded (kept for continuity) = ok && !browser_ok.
                    put("browser_ok", if (p.ok && !p.degraded) 1 else 0)
                    if (p.latencyMs != null) put("latency_ms", p.latencyMs)
                    if (p.egressIp != null) put("egress_ip", p.egressIp)
                    if (p.degraded) put("degraded", 1)
                }
                arr.put(obj)
            }
            val payload = JSONObject().apply {
                put("client_uid", LimmConfig.clientUid(ctx))
                put("kind", "android")
                put("profiles", arr)
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            OkHttpClient.Builder().dns(LimmDns.IPV4_ONLY).connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
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
            onProgress("⏹ Останавливаю VPN…")
            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext
                CoreServiceManager.stopVService(ctx)
            }
            delay(2000)
        }

        Log.i(TAG, "=== LIMM DIAG TEST START ===")

        onProgress("  Чек-ин (VPN выкл)…")
        val (ok0, msg0) = LimmCheckinWorker.sendNow(ctx)
        onProgress(if (ok0) " ✓ $msg0" else " ✗ $msg0")
        Log.i(TAG, "checkin-off: ok=$ok0 $msg0")

        val guids = MmkvManager.decodeAllServerList()
        val savedGuid = MmkvManager.getSelectServer()
        val socksPort = try { SettingsManager.getSocksPort() } catch (e: Exception) { 10808 }

        onProgress("\n── Профили (${guids.size}) ──")

        // F2.3: warm up VpnService once so the first profile doesn't pay the cold-start penalty.
        if (savedGuid != null) {
            onProgress("🔥 Прогрев…")
            withContext(Dispatchers.Main) { if (!isActive) return@withContext; CoreServiceManager.startVService(ctx) }
            waitForSocks(socksPort)
            withContext(Dispatchers.Main) { if (!isActive) return@withContext; CoreServiceManager.stopVService(ctx) }
            delay(500)
            withContext(Dispatchers.IO) { waitForSocksClosed(socksPort) }
        }

        val profileResults = mutableListOf<ProfileResult>()
        // F1.1: profile left running from the previous iteration (stopped at the top of the next
        // one) so the post-test phase can reuse the best tunnel instead of restarting it.
        var runningGuid: String? = null

        for (guid in guids) {
            val cfg = MmkvManager.decodeServerConfig(guid)
            val name = cfg?.remarks?.takeIf { it.isNotEmpty() } ?: guid.take(8)

            // Skip any leftover -awg profiles: AWG was removed, so testing one as plain xray
            // WireGuard would just report a broken tunnel and pollute the verdict.
            if (name.endsWith("-awg", ignoreCase = true)) {
                onProgress("▸ $name (AWG)")
                continue
            }

            // F3.1: TCP pre-ping for TCP transports — skip the heavy egress probe when host:port
            // doesn't even answer on TCP (a dead TCP server can't tunnel). UDP (hy2/tuic) excluded
            // (TCP may be silent on a healthy UDP service); CF-fronted cf-ws connects via the edge
            // regardless of origin, so pre-ping is a harmless no-op there.
            val isUdp = name.endsWith("-hy2", true) || name.endsWith("-tc", true)
            if (!isUdp) {
                val host = cfg?.server
                val port = cfg?.serverPort?.toIntOrNull()
                if (host != null && port != null && !withContext(Dispatchers.IO) { tcpPrePing(host, port) }) {
                    onProgress("\r▸ $name (TCP $host:$port недоступен)")
                    Log.w(TAG, "profile $name: TCP pre-ping failed $host:$port")
                    profileResults.add(ProfileResult(name, false, null, guid = guid))
                    continue
                }
            }

            // F1.1: stop the profile left running from the previous iteration before starting this.
            if (runningGuid != null) {
                withContext(Dispatchers.Main) { if (!isActive) return@withContext; CoreServiceManager.stopVService(ctx) }
                delay(500)
                withContext(Dispatchers.IO) { waitForSocksClosed(socksPort) }
                runningGuid = null
            }

            onProgress("▸ $name")
            Log.i(TAG, "--- profile: $name ($guid) ---")

            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext
                MmkvManager.setSelectServer(guid)
                CoreServiceManager.startVService(ctx)
            }
            runningGuid = guid
            delay(100)

            val socksReady = waitForSocks(socksPort)
            if (!socksReady) {
                onProgress("\r▸ $name (SOCKS timeout)")
                Log.w(TAG, "profile $name: SOCKS timeout")
                profileResults.add(ProfileResult(name, false, null, guid = guid))
                // F1.1: leave the stop to the top of the next iteration (runningGuid stays set).
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
                    Log.d(TAG, "profile $name: egress attempt $attempt failed, retrying")
                    delay(EGRESS_RETRY_DELAY_MS)
                }
            }
            val ms = System.currentTimeMillis() - t0
            val vpnOk = egress != null
            // F2.2: second liveness gate — real reachability through the tunnel (generate_204).
            // egress OK but 204 fail → degraded (tunnel up, browsing flaky), not a full fail.
            val live = if (vpnOk) withContext(Dispatchers.IO) { liveness204ViaSocks(socksPort) } else false
            val degraded = vpnOk && !live
            if (vpnOk) {
                onProgress("\r▸ $name [${ms}ms] ✓${if (degraded) " ⚠no-204" else ""}")
            } else {
                onProgress("\r▸ $name (нет ответа)")
            }
            Log.i(TAG, "profile $name: egress=$egress ok=$vpnOk live204=$live ms=$ms")
            profileResults.add(ProfileResult(name, vpnOk, if (vpnOk) ms else null, egress, degraded, guid))

            // F1.1: do NOT stop here — leave the tunnel up for the next iteration / post-test reuse.
        }

        // Cache results for LimmLogReporter — included in applog (auto + manual "Send log" button)
        LimmLogReporter.cachedDiagResults = JSONArray().also { arr ->
            for (p in profileResults) arr.put(JSONObject().apply {
                put("name", p.name)
                put("ok", if (p.ok) 1 else 0)
                put("browser_ok", if (p.ok && !p.degraded) 1 else 0)
                p.latencyMs?.let { put("latency_ms", it) }
                p.egressIp?.let { put("egress_ip", it) }
                if (p.degraded) put("degraded", 1)
            })
        }

        // Upload profile test results to /api/fulltest
        withContext(Dispatchers.IO) { postFullTest(ctx, profileResults) }

        // Post-test checkin: switch to the best working profile (non-degraded first, then lowest
        // latency) and run a full checkin so the dashboard shows correct Статус / Сервисы / Пинг.
        val bestResult = profileResults
            .filter { it.ok }
            .sortedWith(compareBy({ it.degraded }, { it.latencyMs ?: Long.MAX_VALUE }))
            .firstOrNull()
        var logUploaded = false
        if (bestResult != null) {
            val bestGuid = bestResult.guid
            val bestName = bestResult.name
            onProgress("\n⏳ Чекин (VPN on · $bestName)…")
            Log.i(TAG, "post-test checkin: best=$bestName ($bestGuid)")
            // F1.1: reuse the tunnel if best is the one still running from the loop.
            val reuse = runningGuid == bestGuid && withContext(Dispatchers.IO) { socksAccepting(socksPort) }
            if (reuse) {
                onProgress(" ↺ туннель уже поднят")
            } else {
                if (runningGuid != null) {
                    withContext(Dispatchers.Main) { if (!isActive) return@withContext; CoreServiceManager.stopVService(ctx) }
                    delay(500)
                    withContext(Dispatchers.IO) { waitForSocksClosed(socksPort) }
                    runningGuid = null
                }
                withContext(Dispatchers.Main) {
                    if (!isActive) return@withContext
                    MmkvManager.setSelectServer(bestGuid)
                    CoreServiceManager.startVService(ctx)
                }
                runningGuid = bestGuid
            }
            val ckReady = reuse || waitForSocks(socksPort)
            if (ckReady) {
                val (ckOk, ckMsg) = withContext(Dispatchers.IO) { LimmCheckinWorker.sendNow(ctx) }
                onProgress(if (ckOk) " ✓ $ckMsg" else " ✗ $ckMsg")
                Log.i(TAG, "post-test checkin: ok=$ckOk $ckMsg")
                // Upload log while VPN is still on — limm.space may not resolve without the tunnel.
                onProgress("\n📤 Отправляю лог на сервер…")
                val (logOk, logMsg) = withContext(Dispatchers.IO) { LimmLogReporter.send(ctx) }
                onProgress(if (logOk) " ✓ Лог отправлен" else " ✗ $logMsg")
                logUploaded = true
            } else {
                onProgress(" ✗ SOCKS не поднялся для чекина")
            }
            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext
                CoreServiceManager.stopVService(ctx)
            }
            delay(500)
            withContext(Dispatchers.IO) { waitForSocksClosed(socksPort) }
            runningGuid = null
        } else if (runningGuid != null) {
            // No working profile, but a tunnel may still be up from the loop — stop it.
            withContext(Dispatchers.Main) { if (!isActive) return@withContext; CoreServiceManager.stopVService(ctx) }
            delay(500)
            withContext(Dispatchers.IO) { waitForSocksClosed(socksPort) }
            runningGuid = null
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
            onProgress("\n▶ Восстанавливаю VPN…")
            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext
                CoreServiceManager.startVService(ctx)
            }
        }

        Log.i(TAG, "=== LIMM DIAG TEST END ===")

        // Fallback: upload log without VPN (only when no working profile was found).
        if (!logUploaded) {
            onProgress("\n📤 Отправляю лог на сервер…")
            val (logOk, logMsg) = withContext(Dispatchers.IO) { LimmLogReporter.send(ctx) }
            onProgress(if (logOk) " ✓ Лог отправлен" else " ✗ $logMsg")
        }
        } finally {
            isRunning = false
        }
    }
}
