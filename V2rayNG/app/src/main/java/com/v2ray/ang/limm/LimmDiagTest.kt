package com.v2ray.ang.limm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.MessageUtil
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

    private const val TAG = "LimmDiag"
    private const val SOCKS_WAIT_MAX_MS = 6_000L   // 10s → 6s: большинство профилей поднимаются за 2-3s
    private const val SOCKS_POLL_MS = 150L
    private const val SOCKS_CLOSE_MAX_MS = 3_000L  // 6s → 3s: ждём гибели старого xray
    private const val EGRESS_TIMEOUT_SEC = 8L       // 15s → 8s: нерабочий профиль не должен висеть
    private const val EGRESS_RETRY_MAX = 2           // 3 → 2: экономим ~8s на провальных профилях
    private const val EGRESS_RETRY_DELAY_MS = 500L
    // XHTTP (mode=auto, h2/h3 поверх REALITY) медленный на первый байт — даём больше времени и попыток.
    private const val XHTTP_EGRESS_TIMEOUT_SEC = 15L
    private const val XHTTP_EGRESS_RETRY_MAX = 3
    private const val AWG_WAIT_MAX_MS = 8_000L       // ожидание подъёма AmneziaWG userspace-туннеля
    private const val AWG_POLL_MS = 200L

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
        for (url in listOf("https://api.ipify.org", "https://checkip.amazonaws.com")) {
            try {
                client.newCall(Request.Builder().url(url).build())
                    .execute().use { r -> if (r.isSuccessful) return r.body?.string()?.trim() }
            } catch (e: Exception) { /* try next */ }
        }
        return null
    }

    /**
     * Egress probe WITHOUT a SOCKS proxy — used for AmneziaWG, which is a full-TUN tunnel
     * (allowed_ip 0.0.0.0/0) with no local SOCKS inbound. Traffic routes through the TUN directly.
     */
    private fun egressDirect(): String? {
        val client = OkHttpClient.Builder()
            .connectTimeout(EGRESS_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(EGRESS_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
        for (url in listOf("https://api.ipify.org", "https://checkip.amazonaws.com")) {
            try {
                client.newCall(Request.Builder().url(url).build())
                    .execute().use { r -> if (r.isSuccessful) return r.body?.string()?.trim() }
            } catch (e: Exception) { /* try next */ }
        }
        return null
    }

    /** Poll until the AmneziaWG userspace tunnel reports active, or timeout. */
    private suspend fun waitAwgActive(): Boolean {
        val deadline = System.currentTimeMillis() + AWG_WAIT_MAX_MS
        while (System.currentTimeMillis() < deadline) {
            if (LimmAWGTunnel.isActive) return true
            delay(AWG_POLL_MS)
        }
        return LimmAWGTunnel.isActive
    }

    private data class ProfileResult(val name: String, val ok: Boolean, val latencyMs: Long?, val egressIp: String? = null)

    /**
     * AmneziaWG profile test. AWG is NOT an xray-SOCKS profile — it's a userspace TUN tunnel
     * (LimmAWGTunnel) that takes over the VpnService's TUN fd. Sequence:
     *   1. select the awg profile + start VpnService (xray-wg establishes the TUN fd; SOCKS up)
     *   2. set per-node AWG params, send MSG_STATE_SWITCH_AWG → service hands the fd to LimmAWGTunnel
     *   3. probe egress DIRECTLY (no SOCKS), since the tunnel is full-TUN
     *   4. teardown: stop AWG, re-register the service receiver (startVService), then stop service
     */
    private suspend fun testAwgProfile(
        ctx: Context, guid: String, name: String, cfg: ProfileItem, socksPort: Int,
        onProgress: (String) -> Unit
    ): ProfileResult {
        if (!LimmAWGTunnel.isAvailable) {
            onProgress("    ⚪  ▸ $name  (AWG-бэкенд недоступен)")
            Log.w(TAG, "profile $name: AWG backend unavailable")
            return ProfileResult(name, false, null)
        }
        val host = cfg.server.orEmpty()
        val port = cfg.serverPort.orEmpty()
        val priv = cfg.secretKey.orEmpty()
        val peer = cfg.publicKey.orEmpty()
        if (host.isEmpty() || port.isEmpty() || priv.isEmpty() || peer.isEmpty()) {
            onProgress("    ✗  ▸ $name  (нет endpoint/ключей в профиле)")
            Log.w(TAG, "profile $name: missing awg params host=$host port=$port priv=${priv.isNotEmpty()} peer=${peer.isNotEmpty()}")
            return ProfileResult(name, false, null)
        }

        // 1. Establish TUN via xray (select profile + start service). SOCKS coming up == TUN ready.
        LimmAWGTunnel.pendingConfig = LimmAWGTunnel.AwgConfig("$host:$port", priv, peer)
        withContext(Dispatchers.Main) {
            if (!isActive) return@withContext
            MmkvManager.setSelectServer(guid)
            CoreServiceManager.startVService(ctx)
        }
        val tunReady = waitForSocks(socksPort)
        if (!tunReady) {
            onProgress("    ✗  ▸ $name  (VPN/TUN не поднялся)")
            Log.w(TAG, "profile $name: TUN not ready before AWG switch")
            LimmAWGTunnel.pendingConfig = null
            withContext(Dispatchers.Main) { if (isActive) CoreServiceManager.stopVService(ctx) }
            delay(1500)
            withContext(Dispatchers.IO) { waitForSocksClosed(socksPort) }
            return ProfileResult(name, false, null)
        }

        // 2. Switch tunnel mode to AmneziaWG on the same fd.
        MessageUtil.sendMsg2Service(ctx, AppConfig.MSG_STATE_SWITCH_AWG, "")
        val awgUp = waitAwgActive()

        // 3. Direct egress probe (no SOCKS — AWG is full-TUN).
        val t0 = System.currentTimeMillis()
        var egress: String? = null
        if (awgUp) {
            for (attempt in 1..EGRESS_RETRY_MAX) {
                egress = withContext(Dispatchers.IO) { egressDirect() }
                if (egress != null) break
                if (attempt < EGRESS_RETRY_MAX) {
                    onProgress("    ↻  попытка ${attempt + 1}/$EGRESS_RETRY_MAX…")
                    delay(EGRESS_RETRY_DELAY_MS)
                }
            }
        }
        val ms = System.currentTimeMillis() - t0
        val ok = egress != null
        val note = if (ok) "$egress  [${ms}ms]" else if (awgUp) "нет ответа  [${ms}ms]" else "AWG не поднялся"
        onProgress("    ${if (ok) "✓" else "✗"}  ▸ $name  ($note)")
        Log.i(TAG, "profile $name (awg): up=$awgUp egress=$egress ok=$ok ms=$ms")

        // 4. Teardown: stop AWG, re-register service receiver (startVService restores it — same
        //    recovery the MSG_STATE_SWITCH_AWG failure branch uses), then stop the service.
        LimmAWGTunnel.stopTunnel()
        delay(300)
        withContext(Dispatchers.Main) {
            if (!isActive) return@withContext
            CoreServiceManager.startVService(ctx)
        }
        delay(500)
        withContext(Dispatchers.Main) {
            if (!isActive) return@withContext
            CoreServiceManager.stopVService(ctx)
        }
        delay(500)
        withContext(Dispatchers.IO) { waitForSocksClosed(socksPort) }
        return ProfileResult(name, ok, if (ok) ms else null, egress)
    }

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

            onProgress("⏳  ▸ $name…")
            Log.i(TAG, "--- profile: $name ($guid) ---")

            // AmneziaWG profiles use the userspace-TUN path, not xray-SOCKS.
            val isAwg = cfg?.configType == EConfigType.WIREGUARD && name.endsWith("-awg", ignoreCase = true)
            if (isAwg && cfg != null) {
                profileResults.add(testAwgProfile(ctx, guid, name, cfg, socksPort, onProgress))
                continue
            }

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
            // XHTTP (mode=auto, h2/h3 поверх REALITY) медленный на первый байт → больше времени и
            // попыток, иначе ложный fail. Остальным транспортам хватает базовых значений.
            val isXhttp = name.endsWith("-xhttp", ignoreCase = true)
            val egTimeout = if (isXhttp) XHTTP_EGRESS_TIMEOUT_SEC else EGRESS_TIMEOUT_SEC
            val egRetries = if (isXhttp) XHTTP_EGRESS_RETRY_MAX else EGRESS_RETRY_MAX
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
        // Exclude awg from "best": the post-test checkin uses the regular xray path, which can't
        // serve an AmneziaWG profile (it would start as plain WireGuard and report a broken tunnel).
        val bestResult = profileResults
            .filter { it.ok && !it.name.endsWith("-awg", ignoreCase = true) }
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
    }
}
