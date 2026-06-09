package com.v2ray.ang.limm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * Max time per profile: ~25s (10s SOCKS wait + 15s egress timeout).
 */
object LimmDiagTest {

    private const val TAG = "LimmDiag"
    private const val SOCKS_WAIT_MAX_MS = 10_000L
    private const val SOCKS_POLL_MS = 150L
    private const val EGRESS_TIMEOUT_SEC = 15L

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

    /** Wait until the SOCKS port stops accepting — old Xray is fully dead. Max 6s. */
    private suspend fun waitForSocksClosed(port: Int) {
        val deadline = System.currentTimeMillis() + 6_000L
        while (System.currentTimeMillis() < deadline) {
            if (!socksAccepting(port)) return
            delay(250)
        }
    }

    private fun egressViaSocks(socksPort: Int): String? = try {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(EGRESS_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(EGRESS_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
            .newCall(Request.Builder().url("https://api.ipify.org").build())
            .execute().use { r -> if (r.isSuccessful) r.body?.string()?.trim() else null }
    } catch (e: Exception) { null }

    private data class ProfileResult(val name: String, val ok: Boolean, val latencyMs: Long?)

    private fun postFullTest(ctx: Context, profiles: List<ProfileResult>) {
        if (profiles.isEmpty()) return
        try {
            val arr = JSONArray()
            for (p in profiles) {
                val obj = JSONObject().apply {
                    put("name", p.name)
                    put("ok", if (p.ok) 1 else 0)
                    if (p.latencyMs != null) put("latency_ms", p.latencyMs)
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
                .execute().use { }
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
            withContext(Dispatchers.Main) { CoreServiceManager.stopVService(ctx) }
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

            withContext(Dispatchers.Main) {
                MmkvManager.setSelectServer(guid)
                CoreServiceManager.startVService(ctx)
            }
            delay(100)

            val socksReady = waitForSocks(socksPort)
            if (!socksReady) {
                onProgress("    ✗  ▸ $name  (SOCKS :$socksPort не поднялся за 10s)")
                Log.w(TAG, "profile $name: SOCKS timeout")
                profileResults.add(ProfileResult(name, false, null))
                withContext(Dispatchers.Main) { CoreServiceManager.stopVService(ctx) }
                delay(1500)
                continue
            }

            val t0 = System.currentTimeMillis()
            val egress = withContext(Dispatchers.IO) { egressViaSocks(socksPort) }
            val ms = System.currentTimeMillis() - t0
            val vpnOk = egress == serverIp
            val note = when {
                egress == serverIp -> "$egress  = VPN ✓  [${ms}ms]"
                egress != null     -> "$egress  ≠ $serverIp  [${ms}ms]"
                else               -> "нет ответа от api.ipify.org  [${ms}ms]"
            }
            onProgress("    ${if (vpnOk) "✓" else "✗"}  ▸ $name  ($note)")
            Log.i(TAG, "profile $name: egress=$egress expected=$serverIp ok=$vpnOk ms=$ms")
            profileResults.add(ProfileResult(name, vpnOk, if (vpnOk) ms else null))

            withContext(Dispatchers.Main) { CoreServiceManager.stopVService(ctx) }
            delay(500)
            // Wait for old Xray to fully die before starting next profile.
            // Without this, old Xray's SOCKS inbound may still serve the next profile's
            // egress check → reports ok=1 through the previous (working) tunnel.
            withContext(Dispatchers.IO) { waitForSocksClosed(socksPort) }
        }

        // Upload profile test results to /api/fulltest
        withContext(Dispatchers.IO) { postFullTest(ctx, profileResults) }

        // Post-test checkin: switch to best working profile, run full checkin so the
        // dashboard shows correct Статус / Сервисы / Пинг after Full Test.
        val bestIdx = profileResults.indexOfFirst { it.ok }
        var logUploaded = false
        if (bestIdx >= 0) {
            val bestGuid = guids[bestIdx]
            val bestName = profileResults[bestIdx].name
            onProgress("\n⏳  Чекин (VPN on · $bestName)…")
            Log.i(TAG, "post-test checkin: switching to $bestName ($bestGuid)")
            withContext(Dispatchers.Main) {
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
            withContext(Dispatchers.Main) { CoreServiceManager.stopVService(ctx) }
            delay(500)
            withContext(Dispatchers.IO) { waitForSocksClosed(socksPort) }
        }

        // Restore original profile
        if (savedGuid != null) {
            withContext(Dispatchers.Main) { MmkvManager.setSelectServer(savedGuid) }
        }

        // M1: if the VPN was running when the test started, bring it back up — the test
        // only restored the selected profile, leaving the service itself off.
        if (wasRunning) {
            onProgress("\n▶  Восстанавливаю VPN…")
            withContext(Dispatchers.Main) { CoreServiceManager.startVService(ctx) }
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
