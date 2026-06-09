package com.v2ray.ang.limm

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Periodic diagnostic check-in for the limm.space/stat dashboard.
 * Ports the L0->L4 ladder of client/vpn-agent.py. The verdict is computed
 * server-side from these ladder values, so we just report raw signals.
 *
 * Note: the VPN app excludes *itself* from its own tunnel (to avoid a routing loop), so a
 * plain socket from this process egresses on the real ISP IP. To validate the tunnel we route
 * the egress/handshake probes through the local SOCKS inbound (127.0.0.1:socksPort), which DOES
 * traverse the tunnel — its egress IP equals the server IP exactly when the tunnel is up.
 */
class LimmCheckinWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!LimmConfig.isConfigured() || LimmConfig.token.isEmpty()) return Result.success()
        // Skip silently if debug mode is off — periodic work may still be in queue from
        // a previous session; this is the cheapest safety net without cancelling the chain.
        if (!com.v2ray.ang.handler.MmkvManager.decodeSettingsBool(com.v2ray.ang.AppConfig.PREF_LIMM_DEBUG, false)) return Result.success()
        // M4: the ladder does blocking OkHttp/Socket/Thread.sleep work; keep it off the
        // default CoroutineWorker dispatcher to avoid starving the shared pool.
        return withContext(Dispatchers.IO) {
            try {
                post(runLadder(applicationContext))
                Result.success()
            } catch (e: Exception) {
                Result.retry()
            }
        }
    }

    companion object {
        private const val UNIQUE = "limm_checkin"

        /** Schedules the periodic check-in (min interval enforced by Android is 15 min).
         *  Does nothing (and cancels any existing work) when debug mode is off. */
        fun schedule(ctx: Context) {
            if (!LimmConfig.isConfigured()) return
            val debugMode = com.v2ray.ang.handler.MmkvManager.decodeSettingsBool(
                com.v2ray.ang.AppConfig.PREF_LIMM_DEBUG, false
            )
            if (!debugMode) {
                // Cancel periodic work so check-ins stop when debug is off
                WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE)
                WorkManager.getInstance(ctx).cancelUniqueWork("${UNIQUE}_now")
                return
            }
            val req = PeriodicWorkRequestBuilder<LimmCheckinWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
            // Immediate one-shot so a fresh reading lands right after launch/connect
            // instead of waiting for the first 15-min periodic window.
            val now = OneTimeWorkRequestBuilder<LimmCheckinWorker>().build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("${UNIQUE}_now", ExistingWorkPolicy.REPLACE, now)
            // Schedule update checker (every 6h — downloads APK and notifies if newer version)
            LimmUpdateWorker.schedule(ctx)
        }

        /**
         * Runs the ladder + posts the check-in synchronously and returns a human-readable
         * summary. Safe to call from a background dispatcher; used by the manual "Send check-in"
         * menu button so the user gets immediate feedback.
         */
        fun sendNow(ctx: Context): Pair<Boolean, String> {
            if (!LimmConfig.isConfigured() || LimmConfig.token.isEmpty()) {
                return false to "limm not configured"
            }
            return try {
                val payload = runLadder(ctx)
                post(payload)
                val l3 = payload.opt("l3_tunnel")
                val vpnRunning = payload.optInt("vpn_running", 0)
                val egressRaw = payload.opt("egress_ip")
                val egress = if (egressRaw == null || egressRaw == JSONObject.NULL) null else egressRaw.toString()
                val tun = when {
                    l3 == 1         -> "туннель OK ($egress)"
                    vpnRunning == 0 -> "VPN выключен"
                    else            -> "туннель НЕ через сервер (${egress ?: "нет ответа"})"
                }
                true to "Чек-ин отправлен: $tun"
            } catch (e: Exception) {
                false to (e.localizedMessage ?: e.toString())
            }
        }

        private fun tcpOk(host: String, port: Int, timeoutMs: Int = 5000): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
        } catch (e: Exception) {
            false
        }

        /** TCP connect via SOCKS5 proxy to measure pure tunnel RTT. No DNS, no TLS — just TCP handshake. */
        private fun tcpLatencyViaSocks(host: String, port: Int, proxyPort: Int, timeoutMs: Int = 5000): Long? = try {
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort))
            val t0 = System.currentTimeMillis()
            Socket(proxy).use { s -> s.connect(InetSocketAddress(host, port), timeoutMs) }
            System.currentTimeMillis() - t0
        } catch (e: Exception) { null }

        private fun httpGet(url: String, timeoutSec: Long = 8): Pair<Boolean, String> = try {
            val c = OkHttpClient.Builder()
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build()
            c.newCall(Request.Builder().url(url).build()).execute().use { r ->
                (r.isSuccessful) to (r.body?.string()?.trim() ?: "")
            }
        } catch (e: Exception) {
            false to ""
        }

        /**
         * Same as httpGet but routed through the local SOCKS proxy so it traverses the tunnel.
         * Retries a couple of times with a generous timeout: right after connect the tunnel +
         * tunneled-DNS are still warming up, so a single short attempt would falsely report a
         * handshake failure. We give it up to 3 tries before declaring L2/L3 down.
         */
        private fun httpGetViaSocks(url: String, socksPort: Int, timeoutSec: Long = 12, tries: Int = 3): Pair<Boolean, String> {
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val c = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build()
            try {
                repeat(tries) { attempt ->
                    try {
                        c.newCall(Request.Builder().url(url).build()).execute().use { r ->
                            if (r.isSuccessful) return true to (r.body?.string()?.trim() ?: "")
                        }
                    } catch (e: Exception) {
                        // swallow and retry
                    }
                    if (attempt < tries - 1) try { Thread.sleep(1500) } catch (e: InterruptedException) {
                        // L2: restore interrupt status so worker cancellation propagates.
                        Thread.currentThread().interrupt()
                        return false to ""
                    }
                }
                return false to ""
            } finally {
                c.dispatcher.executorService.shutdown()
                c.connectionPool.evictAll()
            }
        }

        /**
         * System-level ground truth for "a VPN tunnel is up": any network known to
         * ConnectivityManager carries TRANSPORT_VPN. Reliable cross-process AND independent of how
         * the core bridges traffic — works for both the SOCKS-inbound and the hev-tun path (the
         * latter has NO local SOCKS listener, which is exactly what made the socket probe report a
         * false vpn_off). We scan allNetworks (not just the active one) because the app excludes
         * itself from its own tunnel, so its own default network is the underlying ISP link.
         */
        private fun vpnTransportUp(ctx: Context): Boolean = try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.allNetworks.any { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (e: Exception) { false }

        /**
         * Проверка пользовательского сервиса через туннель: тянем страницу и классифицируем
         * "ok|blocked|down". Гео-блок отдаёт 451 либо текст-маркер ("not available in your
         * country"). Cloudflare anti-bot (403 "just a moment") — НЕ гео-блок, край достижим → ok.
         * Исключение (нет соединения / DNS / таймаут) → down.
         */
        private fun probeServiceViaSocks(url: String, socksPort: Int, markers: List<String>, timeoutSec: Long = 12): String {
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val c = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            return try {
                c.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (limm-probe)").build())
                    .execute().use { r ->
                        val code = r.code
                        val body = (try { r.body?.string() ?: "" } catch (e: Exception) { "" }).lowercase()
                        if (code == 451 || markers.any { body.contains(it) }) "blocked" else "ok"
                    }
            } catch (e: Exception) {
                "down"
            }
        }

        private fun runLadder(ctx: Context): JSONObject {
            val srvIp = LimmConfig.serverIp
            val socksPort = try { com.v2ray.ang.handler.SettingsManager.getSocksPort() } catch (e: Exception) { 10808 }
            // Is the VPN tunnel actually up? Three independent signals, OR'd:
            //  1) CoreServiceManager.isRunning() — only true in-process; UNRELIABLE from :bg (the
            //     core runs in :RunSoLibV2RayDaemon), kept as a cheap same-process shortcut.
            //  2) a local TCP connect to 127.0.0.1:socksPort — true when a SOCKS inbound is served,
            //     but FALSE under hev-tun (no SOCKS listener) → used to cause a false "vpn_off ⚪".
            //  3) ConnectivityManager TRANSPORT_VPN — system-level truth, covers the hev-tun case.
            val coreFlag: Boolean = try { com.v2ray.ang.core.CoreServiceManager.isRunning() } catch (e: Exception) { false }
            val running: Boolean = coreFlag || tcpOk("127.0.0.1", socksPort, 1500) || vpnTransportUp(ctx)
            val vpnRunning: Any = if (running) 1 else 0
            val l0 = if (tcpOk("1.1.1.1", 443)) 1 else 0
            var l1: Int? = null
            var l2: Int? = null
            var l3: Int? = null
            var l4: Int? = null
            var egress: String? = null
            var latency: Long? = null
            var tunnelMs: Long? = null

            var browserOk: Int? = null
            var browserHost: String? = null
            var destGoogle: Int? = null
            var destTelegram: Int? = null
            var services: JSONObject? = null

            if (l0 == 1) {
                // 3 direct TCP connects → average RTT (app excluded from own TUN → bypasses VPN).
                val samples = mutableListOf<Long>()
                var anyOk = false
                repeat(3) {
                    val t0 = System.currentTimeMillis()
                    if (tcpOk(srvIp, 443)) { samples.add(System.currentTimeMillis() - t0); anyOk = true }
                }
                l1 = if (anyOk) 1 else 0
                if (samples.isNotEmpty()) latency = samples.sum() / samples.size
            }
            // Tunnel probes only make sense when the VPN is actually on. When it's off, the SOCKS
            // inbound isn't serving, so every probe would burn 3×12s of timeouts for nothing
            // (battery on mobile). Server maps vpn_running==0 → vpn_off regardless of l2..l4.
            if (l0 == 1 && l1 == 1 && running) {

                // L2/L3 via the local SOCKS proxy → this traffic goes THROUGH the tunnel.
                // A successful response = REALITY handshake is up (L2). Egress == server IP = L3.
                val (ok, body) = httpGetViaSocks("https://api.ipify.org", socksPort)
                l2 = if (ok) 1 else 0
                if (ok) {
                    egress = body
                    l3 = if (egress == srvIp) 1 else 0
                }
                // tunnel_ms — 3 HTTP roundtrips through VPN tunnel → average
                val tmsSamples = mutableListOf<Long>()
                repeat(3) {
                    val t0 = System.currentTimeMillis()
                    val (ok, _) = httpGetViaSocks("https://www.gstatic.com/generate_204", socksPort, timeoutSec = 5, tries = 1)
                    if (ok) tmsSamples.add(System.currentTimeMillis() - t0)
                }
                if (tmsSamples.isNotEmpty()) tunnelMs = tmsSamples.sum() / tmsSamples.size

                // Browser-like reachability test through the tunnel — run ALWAYS (not gated on l3),
                // so we can tell "tunnel up but no traffic" apart from "no tunnel". Mirrors a page load.
                val (g204, _) = httpGetViaSocks("https://www.google.com/generate_204", socksPort)
                l4 = if (g204) 1 else 0
                val (site, _) = httpGetViaSocks("https://www.gstatic.com/generate_204", socksPort)
                browserOk = if (g204 || site) 1 else 0
                browserHost = if (g204) "google" else if (site) "gstatic" else "none"
                // Сервисы через туннель (в raw, без новых колонок в БД): TG / Google / ChatGPT
                // с классификацией ok|blocked|down (реально тянем страницу, см. probeServiceViaSocks).
                val chgptMarkers = listOf(
                    "unsupported_country", "not available in your country",
                    "is not available in your", "openai's services are not available"
                )
                services = JSONObject().apply {
                    put("tg", probeServiceViaSocks("https://web.telegram.org/", socksPort, emptyList()))
                    put("ggl", probeServiceViaSocks("https://www.google.com/search?q=test", socksPort, emptyList()))
                    put("chgpt", probeServiceViaSocks("https://chatgpt.com/", socksPort, chgptMarkers))
                }
                destGoogle = if (services.getString("ggl") == "ok") 1 else 0
                destTelegram = if (services.getString("tg") == "ok") 1 else 0
            }

            return JSONObject().apply {
                put("client_uid", LimmConfig.clientUid(ctx))
                put("kind", "android")
                put("label", LimmConfig.label.ifEmpty { "android-" + (Build.MODEL ?: "device") })
                put("server", LimmConfig.serverName)
                put("l0_local_net", l0)
                put("l1_tcp443", l1 ?: JSONObject.NULL)
                put("l2_handshake", l2 ?: JSONObject.NULL)
                put("l3_tunnel", l3 ?: JSONObject.NULL)
                put("l4_dest", l4 ?: JSONObject.NULL)
                put("egress_ip", egress ?: JSONObject.NULL)
                put("latency_ms", latency ?: JSONObject.NULL)
                put("reconnect_try", 0)
                put("reconnect_ok", JSONObject.NULL)
                put("vpn_running", vpnRunning)
                put("browser_ok", browserOk ?: JSONObject.NULL)
                put("browser_host", browserHost ?: JSONObject.NULL)
                put("raw", JSONObject().apply {
                    put("dest_google", destGoogle ?: JSONObject.NULL)
                    put("dest_telegram", destTelegram ?: JSONObject.NULL)
                    put("services", services ?: JSONObject.NULL)
                    put("tunnel_ms", tunnelMs ?: JSONObject.NULL)
                })
                put("app_version", LimmConfig.appVersion)
                put("os_version", "Android ${Build.VERSION.RELEASE}")
            }
        }

        private fun post(payload: JSONObject) {
            val c = OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${LimmConfig.collectorUrl}/api/checkin")
                .header("Authorization", "Bearer ${LimmConfig.token}")
                .header("User-Agent", "limm-android/1.0")
                .post(body)
                .build()
            c.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("checkin HTTP ${r.code}")
            }
        }
    }
}
