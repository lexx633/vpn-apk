package com.v2ray.ang.limm

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.telephony.TelephonyManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Reach-agent: диагностика блокировок VPS-адреса с мобильной сети (см. docs/TZ-ip-rotation-final.md).
 *
 * Модуль живёт внутри форка v2rayNG (не отдельное приложение — решение зафиксировано в TZ,
 * п.2 «Принятые решения»). Не требует VPN-toggle: по умолчанию (per-app-proxy выключен,
 * см. CoreVpnService.kt:396-398) само приложение уже исключено из собственного туннеля —
 * значит обычный сокет из этого процесса и так идёт мимо VPN, напрямую через мобильную сеть.
 * Единственное, что нужно дополнительно — привязать процесс к транспорту TRANSPORT_CELLULAR,
 * чтобы исключить Wi-Fi (решение TZ, п.1): без активного Wi-Fi-соединения это не требуется,
 * но при наличии Wi-Fi ConnectivityManager может выбрать его как дефолтную сеть.
 *
 * Цикл (сервер = api.py, эндпоинты reach-agent):
 *   1. GET /api/reach-agent/command?wait=N — long-poll, ждёт команду check_now либо таймаут.
 *   2. Если команда пришла — забираем актуальную цель GET /api/reach-agent/targets.
 *   3. Гоняем батарею проверок (tcp/tls/http на 443/8443/8444, icmp, quic, sanity) на cellular.
 *   4. POST /api/reach-agent/report — сервер сам вычисляет диагноз (diagnose_reach) и решает
 *      про ротацию IP; телефон только репортит сырые коды, ничего не решает сам.
 */
class LimmReachAgentWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!LimmConfig.isConfigured() || LimmConfig.token.isEmpty()) return Result.success()
        return withContext(Dispatchers.IO) {
            try {
                runCycle(applicationContext)
                Result.success()
            } catch (e: Exception) {
                LogUtil.w(TAG, "reach-agent cycle failed: ${e.message}")
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "LimmReachAgent"
        private const val UNIQUE = "limm_reach_agent"

        // Long-poll на команду держим большую часть 15-минутного окна WorkManager, но с запасом
        // на сами проверки (~30-40с) и системные ограничения по времени выполнения воркера.
        private const val COMMAND_WAIT_SEC = 55
        private const val LONGPOLL_BUDGET_MS = 8 * 60 * 1000L // 8 мин из 15 отведено на long-poll

        /** Периодический запуск — минимум, что даёт WorkManager (15 мин). */
        fun schedule(ctx: Context) {
            if (!LimmConfig.isConfigured()) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<LimmReachAgentWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE)
        }

        // ── Основной цикл ────────────────────────────────────────────────────────

        private fun runCycle(ctx: Context) {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // 1) Long-poll в ожидании команды. Большую часть окна проводим здесь —
            // сервер отдаёт ответ, как только выставлен command=check_now (см. api.py
            // handle_reach_agent_command), либо по своему внутреннему таймауту (55с/попытка).
            val deadline = System.currentTimeMillis() + LONGPOLL_BUDGET_MS
            var got = false
            while (System.currentTimeMillis() < deadline) {
                val cmd = getCommand(COMMAND_WAIT_SEC)
                if (cmd == "check_now") { got = true; break }
                // idle/ошибка сети — короткая пауза перед следующей long-poll итерацией
                if (cmd == null) try { Thread.sleep(5000) } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt(); return
                }
            }
            // Даже без явной команды — прогоняем цикл раз в 15 мин (обычный интервал),
            // чтобы иметь свежие данные, если цель вообще выставлена.
            if (!got) LogUtil.d(TAG, "reach-agent: команда не пришла за окно — плановый прогон")

            val targets = getTargets() ?: return
            val ip = targets.first
            val ports = targets.second
            if (ip.isNullOrBlank()) return // сервер ещё не назначил цель — нечего проверять

            val network = bindCellular(cm) // может вернуть null, если cellular недоступен
            try {
                val reportId = UUID.randomUUID().toString()
                val checks = mutableListOf<JSONObject>()
                val operator = telephonyOperator(ctx)
                val netType = if (network != null) "4G" else "unknown"

                checks += check(ip, null, "sanity", reportId, operator, netType) { sanityCheck(network) }
                checks += check(ip, null, "icmp", reportId, operator, netType) { icmpCheck(ip, network) }
                for (p in ports) {
                    checks += check(ip, p, "tcp_$p", reportId, operator, netType) { tcpCheck(ip, p, network) }
                    checks += check(ip, p, "tls_$p", reportId, operator, netType) { tlsCheck(ip, p, network) }
                }
                checks += check(ip, 443, "http_443", reportId, operator, netType) { httpCheck(ip, 443, network) }
                checks += check(ip, 443, "quic", reportId, operator, netType) { quicCheck(ip, 443, network) }

                postReport(ctx, ip, reportId, checks, operator, netType)
            } finally {
                if (network != null) try { cm.unregisterNetworkCallback(lastCallback!!) } catch (e: Exception) { }
            }
        }

        // ── HTTP к серверу (командный канал + отчёт) ────────────────────────────

        private fun getCommand(waitSec: Int): String? {
            return try {
                val c = OkHttpClient.Builder()
                    .dns(LimmDns.IPV4_ONLY)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout((waitSec + 10).toLong(), TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url("${LimmConfig.collectorUrl}/api/reach-agent/command?wait=$waitSec")
                    .header("Authorization", "Bearer ${LimmConfig.token}")
                    .header("User-Agent", "limm-android/1.0")
                    .get().build()
                c.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return null
                    val body = JSONObject(r.body?.string() ?: "{}")
                    body.optString("command", "idle")
                }
            } catch (e: Exception) { null }
        }

        /** Возвращает (ip, ports) либо null при ошибке сети. */
        private fun getTargets(): Pair<String?, List<Int>>? {
            return try {
                val c = OkHttpClient.Builder()
                    .dns(LimmDns.IPV4_ONLY)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url("${LimmConfig.collectorUrl}/api/reach-agent/targets")
                    .header("Authorization", "Bearer ${LimmConfig.token}")
                    .header("User-Agent", "limm-android/1.0")
                    .get().build()
                c.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return null
                    val body = JSONObject(r.body?.string() ?: "{}")
                    val ip = body.optString("target_ip", "").ifBlank { null }
                    val portsArr = body.optJSONArray("ports") ?: JSONArray()
                    val ports = mutableListOf<Int>()
                    for (i in 0 until portsArr.length()) {
                        val entry = portsArr.optJSONObject(i)
                        val p = entry?.optInt("port", -1) ?: -1
                        if (p > 0) ports.add(p)
                    }
                    if (ports.isEmpty()) ports.addAll(listOf(443, 8443, 8444))
                    ip to ports
                }
            } catch (e: Exception) { null }
        }

        private fun postReport(
            ctx: Context, ip: String, reportId: String,
            checks: List<JSONObject>, operator: String?, netType: String
        ) {
            val payload = JSONObject().apply {
                put("client_uid", LimmConfig.clientUid(ctx))
                put("report_id", reportId)
                put("target_ip", ip)
                put("operator", operator ?: JSONObject.NULL)
                put("network_type", netType)
                put("checks", JSONArray(checks))
            }
            val c = OkHttpClient.Builder()
                .dns(LimmDns.IPV4_ONLY)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${LimmConfig.collectorUrl}/api/reach-agent/report")
                .header("Authorization", "Bearer ${LimmConfig.token}")
                .header("User-Agent", "limm-android/1.0")
                .post(body)
                .build()
            c.newCall(req).execute().use { r ->
                if (!r.isSuccessful) LogUtil.w(TAG, "report HTTP ${r.code}")
            }
        }

        // ── Cellular bind ────────────────────────────────────────────────────────

        // NetworkCallback держим статично на время одного check-цикла, чтобы отвязаться в finally.
        @Volatile private var lastCallback: ConnectivityManager.NetworkCallback? = null

        /**
         * Привязывает процесс к сотовой сети (см. TZ п.1 — решение в пользу cellular-bind
         * вместо Device Owner/Wi-Fi toggle). Best-effort: если TRANSPORT_CELLULAR недоступен
         * (нет SIM/только Wi-Fi), возвращает null — проверки идут на текущей дефолтной сети.
         */
        private fun bindCellular(cm: ConnectivityManager): Network? {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val latch = CountDownLatch(1)
            var net: Network? = null
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    net = network
                    latch.countDown()
                }
            }
            lastCallback = cb
            return try {
                cm.requestNetwork(request, cb)
                latch.await(8, TimeUnit.SECONDS)
                net?.also { cm.bindProcessToNetwork(it) }
            } catch (e: Exception) { null }
        }

        private fun telephonyOperator(ctx: Context): String? = try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.networkOperatorName?.ifBlank { null }
        } catch (e: Exception) { null }

        // ── Проверки (каждая возвращает код результата, см. таблицу в TZ) ───────────

        private inline fun check(
            ip: String, port: Int?, name: String, reportId: String, operator: String?, netType: String,
            body: () -> String
        ): JSONObject {
            val code = try { body() } catch (e: Exception) { "ERROR" }
            return JSONObject().apply {
                put("target_ip", ip)
                put("target_port", port ?: JSONObject.NULL)
                put("check_name", name)
                put("result_code", code)
            }
        }

        /** Общая связность мобильной сети — если её нет, остальные коды сервер должен игнорировать. */
        private fun sanityCheck(network: Network?): String = try {
            val sock = openSocket(network)
            sock.connect(InetSocketAddress("ya.ru", 443), 5000)
            sock.close()
            "SANITY_OK"
        } catch (e: Exception) { "SANITY_FAIL" }

        /**
         * android.net.Network не даёт isReachable(addr) напрямую (ICMP нужен raw-сокет с root).
         * Полагаемся на process-level bindProcessToNetwork(cellular), уже выставленный в
         * bindCellular() — InetAddress.isReachable() уйдёт через привязанную сеть.
         */
        private fun icmpCheck(ip: String, network: Network?): String {
            return try {
                val addr = java.net.InetAddress.getByName(ip)
                if (addr.isReachable(3000)) "ICMP_OK" else "ICMP_TIMEOUT"
            } catch (e: Exception) { "ICMP_UNREACHABLE" }
        }

        private fun tcpCheck(ip: String, port: Int, network: Network?): String {
            return try {
                val sock = openSocket(network)
                sock.use { it.connect(InetSocketAddress(ip, port), 5000) }
                "TCP_OK"
            } catch (e: SocketTimeoutException) {
                "TCP_TIMEOUT"
            } catch (e: java.net.ConnectException) {
                if (e.message?.contains("refused", ignoreCase = true) == true) "TCP_REFUSED" else "TCP_TIMEOUT"
            } catch (e: IOException) {
                "TCP_RST"
            } catch (e: Exception) { "TCP_TIMEOUT" }
        }

        private fun tlsCheck(ip: String, port: Int, network: Network?): String {
            var raw: Socket? = null
            var ssl: SSLSocket? = null
            return try {
                raw = openSocket(network)
                raw.connect(InetSocketAddress(ip, port), 5000)
                val ctx2 = SSLContext.getInstance("TLS")
                ctx2.init(null, arrayOf<TrustManager>(TrustAllManager), SecureRandom())
                ssl = ctx2.socketFactory.createSocket(raw, ip, port, true) as SSLSocket
                ssl.soTimeout = 6000
                ssl.startHandshake()
                "TLS_OK"
            } catch (e: SocketTimeoutException) {
                "TLS_TIMEOUT"
            } catch (e: javax.net.ssl.SSLHandshakeException) {
                "TLS_FAIL"
            } catch (e: IOException) {
                "TLS_RST"
            } catch (e: Exception) {
                "TLS_FAIL"
            } finally {
                try { ssl?.close() } catch (e: Exception) { }
                try { raw?.close() } catch (e: Exception) { }
            }
        }

        /** Голый HTTP HEAD — не через VLESS, только чтобы отличить TLS_FAIL от «TLS ок, но 403 от DPI-прокси». */
        private fun httpCheck(ip: String, port: Int, network: Network?): String {
            var raw: Socket? = null
            var ssl: SSLSocket? = null
            return try {
                raw = openSocket(network)
                raw.connect(InetSocketAddress(ip, port), 5000)
                val ctx2 = SSLContext.getInstance("TLS")
                ctx2.init(null, arrayOf<TrustManager>(TrustAllManager), SecureRandom())
                ssl = ctx2.socketFactory.createSocket(raw, ip, port, true) as SSLSocket
                ssl.soTimeout = 6000
                ssl.startHandshake()
                ssl.outputStream.write("HEAD / HTTP/1.1\r\nHost: $ip\r\nConnection: close\r\n\r\n".toByteArray())
                ssl.outputStream.flush()
                val resp = ssl.inputStream.bufferedReader().readLine() ?: ""
                when {
                    resp.contains(" 403") -> "HTTP_403"
                    resp.startsWith("HTTP/") -> "HTTP_OK"
                    else -> "HTTP_OTHER"
                }
            } catch (e: Exception) {
                "HTTP_FAIL"
            } finally {
                try { ssl?.close() } catch (e: Exception) { }
                try { raw?.close() } catch (e: Exception) { }
            }
        }

        /** Best-effort: шлём UDP-пакет на 443 (QUIC initial-подобный), смотрим — ICMP unreachable сразу = REJECT. */
        private fun quicCheck(ip: String, port: Int, network: Network?): String {
            return try {
                val sock = DatagramSocket()
                network?.bindSocket(sock)
                sock.soTimeout = 3000
                val payload = ByteArray(64) { 0 }
                sock.send(DatagramPacket(payload, payload.size, InetSocketAddress(ip, port)))
                val buf = ByteArray(256)
                try {
                    sock.receive(DatagramPacket(buf, buf.size))
                    "QUIC_OK"
                } catch (e: SocketTimeoutException) {
                    "QUIC_TIMEOUT"
                } finally {
                    sock.close()
                }
            } catch (e: Exception) { "QUIC_TIMEOUT" }
        }

        private fun openSocket(network: Network?): Socket {
            val s = Socket()
            network?.bindSocket(s)
            return s
        }

        private object TrustAllManager : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
    }
}
