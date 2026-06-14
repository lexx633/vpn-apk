package com.v2ray.ang.limm

import android.content.Context
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Collects an on-device diagnostic bundle (xray/app logcat + active profile + generated
 * core config + a live L0..L4 reachability probe + device info) and uploads it to the
 * limm collector so the bundle can be analysed server-side. Triggered manually from the
 * Logcat screen ("Send to limm server").
 *
 * Everything goes to our own server over TLS with the monitoring bearer token, so the
 * embedded UUID inside the generated config is acceptable here.
 */
object LimmLogReporter {

    /**
     * Last full-test results set by LimmDiagTest after each run.
     * Automatically included in the next applog — both from the post-test auto-upload
     * and from any manual "Send to server" press within the same app session.
     */
    var cachedDiagResults: JSONArray? = null

    /** Runs blocking network/logcat work — call from a background dispatcher. */
    fun send(context: Context): Pair<Boolean, String> {
        if (!LimmConfig.isConfigured() || LimmConfig.token.isEmpty()) {
            return false to "limm not configured"
        }
        return try {
            val payload = build(context)
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${LimmConfig.collectorUrl}/api/applog")
                .header("Authorization", "Bearer ${LimmConfig.token}")
                .header("User-Agent", "limm-android/1.1")
                .post(body)
                .build()
            client.newCall(req).execute().use { r ->
                if (r.isSuccessful) {
                    true to "Log sent (#${LimmConfig.clientUid(context).take(8)})"
                } else {
                    false to "Server returned ${r.code}"
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "limm: log report failed", e)
            false to (e.localizedMessage ?: e.toString())
        }
    }

    private fun build(context: Context): JSONObject {
        val guid = MmkvManager.getSelectServer()
        val profile = guid?.let { MmkvManager.decodeServerConfig(it) }
        val configContent = if (guid != null) {
            try {
                val res = CoreConfigManager.getV2rayConfig(context, guid)
                if (res.status) res.content else "config-gen failed: ${res.errorMessage}"
            } catch (e: Exception) {
                "config-gen exception: ${e.message}"
            }
        } else "no server selected"

        return JSONObject().apply {
            put("client_uid", LimmConfig.clientUid(context))
            put("label", LimmConfig.label.ifEmpty { "android-" + (Build.MODEL ?: "device") })
            put("kind", "android")
            put("ts", System.currentTimeMillis() / 1000)
            put("app_version", LimmConfig.appVersion)
            put("os_version", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("selected_guid", guid ?: JSONObject.NULL)
            put("profile", if (profile != null) JsonUtil.toJson(profile) else JSONObject.NULL)
            put("core_config", configContent)
            put("probe", probe())
            put("browser_trace", browserTrace())
            put("logcat", JSONArray(readLogcat()))
            cachedDiagResults?.let { put("diag_results", it) }
        }
    }

    /**
     * Live reachability snapshot, mirroring the check-in ladder.
     *
     * IMPORTANT: the VPN app excludes *itself* from its own tunnel (standard, to avoid a
     * routing loop), so a plain socket from this process egresses on the real ISP IP and
     * would falsely report tunnel_broken. To measure the tunnel honestly we send the egress
     * probe THROUGH the app's local SOCKS inbound (127.0.0.1:socksPort) — that traffic does
     * traverse the tunnel, so its egress IP should equal the server IP when the tunnel is up.
     */
    private fun probe(): JSONObject {
        val srvIp = LimmConfig.serverIp
        val o = JSONObject()
        o.put("l0_local_net", if (tcpOk("1.1.1.1", 443)) 1 else 0)
        o.put("l1_tcp443", if (tcpOk(srvIp, 443)) 1 else 0)

        // direct (non-tunneled) egress — expected to be the real ISP IP, kept for reference
        val (dOk, dIp) = httpGet("https://api.ipify.org")
        o.put("egress_ip_direct", if (dOk) dIp else JSONObject.NULL)

        // tunneled egress through the local SOCKS proxy — this is the real L3 signal
        val socksPort = try { com.v2ray.ang.handler.SettingsManager.getSocksPort() } catch (e: Exception) { 10808 }
        o.put("socks_port", socksPort)
        val (tOk, tIp) = httpGetViaSocks("https://api.ipify.org", socksPort)
        o.put("egress_ip", if (tOk) tIp else JSONObject.NULL)
        o.put("egress_ip_tunnel", if (tOk) tIp else JSONObject.NULL)
        o.put("l3_tunnel", if (tOk) 1 else 0)

        val (g, _) = httpGetViaSocks("https://www.google.com/generate_204", socksPort, timeoutSec = 5, tries = 1)
        o.put("l4_browser", if (g) 1 else 0)
        return o
    }

    /**
     * Detailed browser-style connectivity trace so the server side can see *where* a page
     * load breaks: DNS resolution (and address family), TCP connect, TLS+HTTP round-trip,
     * HTTP status and per-target error string + latency. This is what lets us tell apart
     * "DNS fails", "IPv6-only answer with no v6 egress", "TLS handshake stalls", "routing
     * black-holes the tunnel", etc.
     */
    private fun browserTrace(): JSONObject {
        val out = JSONObject()

        // 1) DNS resolution — does name resolution work through the tunnel, and what
        //    address families come back? An A-only host that resolves to AAAA-only (or
        //    a v6 address the tunnel can't egress) is a classic "page won't open" cause.
        val dns = JSONObject()
        for (host in listOf("www.google.com", "www.gstatic.com", "cloudflare.com", "example.com")) {
            dns.put(host, resolveDns(host))
        }
        out.put("dns", dns)

        // 2) Per-target full HTTP fetch with timing + error detail.
        val targets = JSONArray()
        for (url in listOf(
            "https://www.google.com/generate_204",
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://example.com/",
            "http://connectivitycheck.gstatic.com/generate_204" // plain HTTP, no TLS
        )) {
            targets.put(traceUrl(url))
        }
        out.put("targets", targets)
        return out
    }

    private fun resolveDns(host: String): JSONObject {
        val o = JSONObject()
        return try {
            val t0 = System.currentTimeMillis()
            val addrs = java.net.InetAddress.getAllByName(host)
            if (addrs.isEmpty()) return o
            o.put("ms", System.currentTimeMillis() - t0)
            val arr = JSONArray()
            var v4 = 0; var v6 = 0
            for (a in addrs) {
                arr.put(a.hostAddress)
                if (a is java.net.Inet6Address) v6++ else v4++
            }
            o.put("addrs", arr); o.put("v4", v4); o.put("v6", v6)
            o
        } catch (e: Exception) {
            o.put("error", e.javaClass.simpleName + ": " + (e.message ?: "")); o
        }
    }

    private fun traceUrl(url: String): JSONObject {
        val o = JSONObject()
        o.put("url", url)
        val t0 = System.currentTimeMillis()
        return try {
            val c = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            c.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 limm-probe").build())
                .execute().use { r ->
                    o.put("ms", System.currentTimeMillis() - t0)
                    o.put("code", r.code)
                    o.put("ok", r.isSuccessful || r.code == 204)
                    o.put("proto", r.protocol.toString())
                    val server = java.net.InetSocketAddress(r.request.url.host, r.request.url.port)
                    o.put("peer", server.toString())
                    val bodyLen = try { r.body?.bytes()?.size ?: 0 } catch (e: Exception) { -1 }
                    o.put("body_len", bodyLen)
                }
            o
        } catch (e: Exception) {
            o.put("ms", System.currentTimeMillis() - t0)
            o.put("ok", false)
            o.put("error", e.javaClass.simpleName + ": " + (e.message ?: ""))
            o
        }
    }

    private fun execLogcat(args: Array<String>): List<String> {
        val proc = Runtime.getRuntime().exec(args)
        return try { proc.inputStream.bufferedReader().use { it.readLines() } }
        finally { proc.destroyForcibly() }
    }

    private fun readLogcat(limit: Int = 2000): List<String> = try {
        // Pass 1: sparse Limm diagnostics only. These tags emit few lines, so a deep -t reaches
        // back through the WHOLE test even when GoLog floods the buffer.
        val diag = execLogcat(arrayOf(
            "logcat", "-d", "-v", "time", "-t", "20000", "-s", "LimmDiag"
        ))
        // Pass 2: full context (xray GoLog + app), bounded.
        val full = execLogcat(arrayOf(
            "logcat", "-d", "-v", "time", "-t", limit.toString(),
            "-s", "GoLog,LimmDiag,$ANG_PACKAGE,AndroidRuntime,System.err,tun2socks"
        ))
        buildList {
            add("=== LimmDiag (deep) ===")
            addAll(diag)
            add("=== full tail ===")
            addAll(full)
        }
    } catch (e: Exception) {
        listOf("logcat read failed: ${e.message}")
    }

    private fun tcpOk(host: String, port: Int, timeoutMs: Int = 5000): Boolean = try {
        java.net.Socket().use {
            it.connect(java.net.InetSocketAddress(host, port), timeoutMs); true
        }
    } catch (e: Exception) {
        false
    }

    private fun httpGet(url: String, timeoutSec: Long = 8): Pair<Boolean, String> {
        val c = OkHttpClient.Builder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
        return try {
            c.newCall(Request.Builder().url(url).build()).execute().use { r ->
                (r.isSuccessful) to (r.body?.string()?.trim() ?: "")
            }
        } catch (e: Exception) {
            false to ""
        } finally {
            c.dispatcher.executorService.shutdown()
            c.connectionPool.evictAll()
        }
    }

    /** Same as httpGet but routed through the local SOCKS proxy so it traverses the tunnel.
     *  Retries with a generous timeout — the tunnel + tunneled DNS need a moment to warm up. */
    private fun httpGetViaSocks(url: String, socksPort: Int, timeoutSec: Long = 12, tries: Int = 3): Pair<Boolean, String> {
        val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", socksPort))
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
                if (attempt < tries - 1) try { Thread.sleep(1500) } catch (e: InterruptedException) {}
            }
            return false to ""
        } finally {
            c.dispatcher.executorService.shutdown()
            c.connectionPool.evictAll()
        }
    }
}
