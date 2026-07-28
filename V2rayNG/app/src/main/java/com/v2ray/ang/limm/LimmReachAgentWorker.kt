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
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * Важно: командный канал (limm.space на RU1) — это ОТДЕЛЬНАЯ история от диагностируемого
 * VPS-IP. Иногда сам оператор режет и RU1 (тот же общий DPI/блокировки), и тогда телефон с
 * мобильной сети не может даже получить команду/цель или отправить отчёт — сотовый транспорт
 * годится для тестов target-IP, но не гарантирован для связи с сервером. Поэтому getCommand/
 * getTargets/postReport сперва пробуют дефолтную сеть, а при неудаче — explicit TRANSPORT_WIFI
 * (см. controlCall/requestNetworkByTransport ниже). Диагностические же проверки (tcp/tls/...)
 * ВСЕГДА идут через cellular — иначе тест через Wi-Fi не покажет реальную картину блокировки.
 *
 * Цикл (сервер = api.py, эндпоинты reach-agent):
 *   1. GET /api/reach-agent/command?wait=N — long-poll, ждёт команду check_now либо таймаут.
 *   2. Если команда пришла — забираем актуальную цель GET /api/reach-agent/targets.
 *   3. Гоняем батарею проверок (tcp/tls/http на 443/8443/8444, icmp, quic, sanity) на cellular.
 *   4. POST /api/reach-agent/report — сервер сам вычисляет диагноз (diagnose_reach) и решает
 *      про ротацию IP; телефон только репортит сырые коды, ничего не решает сам.
 *
 * Живой статус (setStage/startStatusTicker): пока приложение открыто (MainActivity resumed)
 * И тумблер включён, лёгкий тикер раз в ~20с шлёт POST /api/event {event_type: "reach_stage",
 * note: <текущий этап>} — так на сервере видно, что происходит прямо сейчас ("idle: жду
 * плановый цикл", "long-poll команды", "прогоняю tcp_443", "отправляю отчёт" и т.п.), не
 * дожидаясь конца 15-минутного окна WorkManager. Это НЕ новый эндпоинт — переиспользует уже
 * существующий /api/event (см. LimmFailover.postSwitchEvent), просто с другим event_type.
 */
class LimmReachAgentWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!LimmConfig.isConfigured() || LimmConfig.token.isEmpty()) return Result.success()
        // Уважаем тумблер "Диагностика блокировок (reach-agent)" в настройках (по умолчанию выкл.).
        // Уже запланированная периодика становится no-op при выключении — реальную отмену делает
        // reconcile() из MainActivity.onResume.
        if (!enabled()) return Result.success()
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

        /** Тумблер "Диагностика блокировок (reach-agent)" в настройках — по умолчанию выключен. */
        fun enabled(): Boolean =
            com.v2ray.ang.handler.MmkvManager.decodeSettingsBool(com.v2ray.ang.AppConfig.PREF_LIMM_REACH_AGENT, false)

        /** Периодический запуск — минимум, что даёт WorkManager (15 мин). Не планирует ничего,
         *  если тумблер выключен (см. [enabled]). */
        fun schedule(ctx: Context) {
            if (!LimmConfig.isConfigured()) return
            if (!enabled()) { cancel(ctx); return }
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

        /** Re-применяет тумблер в рантайме (вызывается из MainActivity.onResume,
         *  как и LimmCheckinWorker.reconcile). */
        fun reconcile(ctx: Context) {
            if (enabled()) schedule(ctx) else cancel(ctx)
        }

        // ── Живой статус / тикер (см. doc-comment файла) ─────────────────────────

        @Volatile private var currentStage: String = "idle"
        // Последний УЖЕ проверенный IP — чтобы при повторных итерациях foreground-цикла не
        // гонять одну и ту же батарею проверок вхолостую, а сразу реагировать на смену цели.
        @Volatile private var lastCheckedIp: String? = null
        private var tickerJob: Job? = null
        private val tickerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        /** Обновляет текущий этап и СРАЗУ (не по таймеру) шлёт его на сервер — событие
         *  fire-and-forget, не блокирует основной поток проверки. */
        private fun setStage(ctx: Context, s: String) {
            currentStage = s
            LogUtil.d(TAG, "reach-agent stage: $s")
            tickerScope.launch { postStageEvent(ctx, s) }
        }

        /**
         * Запускает НЕПРЕРЫВНЫЙ foreground-цикл — вызывать из MainActivity.onResume.
         * НЕТ ожидания 15 минут: сразу при старте проверяем, назначена ли цель; если да —
         * сразу гоняем батарею проверок и шлём отчёт; сразу же после — снова проверяем,
         * не появился ли НОВЫЙ IP, и так по кругу, пока приложение открыто. Пейсинг между
         * итерациями даёт long-poll getCommand(...) — он и так возвращается мгновенно, как
         * только сервер выставит check_now (см. handle_reach_agent_trigger), либо максимум
         * через ~15с по своему таймауту — 15-минутный WorkManager-цикл (см. [schedule]) здесь
         * не участвует вообще, это отдельный fallback только для случая, когда приложение
         * закрыто/свёрнуто (Android не даёт настоящих частых background-циклов без foreground-service).
         * No-op, если тумблер выключен или цикл уже запущен.
         */
        fun startStatusTicker(ctx: Context) {
            if (!enabled() || !LimmConfig.isConfigured()) return
            if (tickerJob?.isActive == true) return
            val appCtx = ctx.applicationContext
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            setStage(appCtx, "idle: приложение открыто, ищу цель")
            tickerJob = tickerScope.launch {
                while (isActive) {
                    try {
                        foregroundIteration(appCtx, cm)
                    } catch (e: Exception) {
                        LogUtil.w(TAG, "reach-agent foreground iteration failed: ${e.message}")
                        setStage(appCtx, "ошибка цикла: ${e.message}")
                        delay(5_000)
                    }
                }
            }
        }

        /** Останавливает foreground-цикл — вызывать из MainActivity.onPause, чтобы не гонять
         *  проверки, пока приложение свёрнуто (см. запрос: "в процессе работы приложения"). */
        fun stopStatusTicker() {
            tickerJob?.cancel()
            tickerJob = null
        }

        /** Одна итерация непрерывного foreground-цикла: цель → (если новая) проверка+отчёт →
         *  снова ждать цель. Без фиксированных пауз — реагирует немедленно на новый IP. */
        private fun foregroundIteration(ctx: Context, cm: ConnectivityManager) {
            setStage(ctx, "проверяю, назначена ли цель (targets)")
            val targets = getTargets(cm)
            val ip = targets?.ip
            if (!ip.isNullOrBlank() && ip != lastCheckedIp) {
                setStage(ctx, "новый IP $ip — беру в работу")
                runDiagnostic(ctx, cm, ip, targets.ports, targets.vless)
                lastCheckedIp = ip
                setStage(ctx, "idle: $ip проверен, жду новый IP")
            } else {
                setStage(
                    ctx,
                    if (ip.isNullOrBlank()) "idle: цель ещё не назначена сервером"
                    else "idle: $ip уже проверен, жду новый"
                )
            }
            // Пейсинг БЕЗ 15-минутного ожидания: long-poll возвращается сразу по check_now
            // (принудительный триггер) либо максимум через ~15с — и в обоих случаях идём на
            // следующую итерацию немедленно.
            val cmd = getCommand(cm, 15)
            if (cmd == "check_now") lastCheckedIp = null // форс-перепроверка даже того же IP
        }

        /** Лёгкое best-effort событие на уже существующий /api/event (см. LimmFailover.
         *  postSwitchEvent) — новый эндпоинт не заводим, event_type="reach_stage" достаточно
         *  отличает эти записи в ленте events на сервере. */
        private fun postStageEvent(ctx: Context, stage: String) {
            if (!LimmConfig.isConfigured() || LimmConfig.token.isEmpty()) return
            try {
                val payload = JSONObject().apply {
                    put("client_uid", LimmConfig.clientUid(ctx))
                    put("event_type", "reach_stage")
                    put("note", stage)
                    put("app_version", LimmConfig.appVersion)
                }
                val client = OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .build()
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("${LimmConfig.collectorUrl}/api/event")
                    .header("Authorization", "Bearer ${LimmConfig.token}")
                    .header("User-Agent", "limm-android/1.0")
                    .post(body)
                    .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) LogUtil.w(TAG, "reach-stage post: ${r.code}")
                }
            } catch (e: Exception) {
                LogUtil.w(TAG, "reach-stage post failed: ${e.message}")
            }
        }

        // ── Основной цикл ────────────────────────────────────────────────────────

        private fun runCycle(ctx: Context) {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // 1) Long-poll в ожидании команды. Большую часть окна проводим здесь —
            // сервер отдаёт ответ, как только выставлен command=check_now (см. api.py
            // handle_reach_agent_command), либо по своему внутреннему таймауту (55с/попытка).
            // Это background-fallback (WorkManager, минимум 15 мин между запусками) — работает,
            // только когда приложение закрыто/свёрнуто. Пока приложение открыто, используется
            // непрерывный foregroundIteration()-цикл без этого ожидания (см. startStatusTicker).
            setStage(ctx, "long-poll: жду команду check_now")
            val deadline = System.currentTimeMillis() + LONGPOLL_BUDGET_MS
            var got = false
            while (System.currentTimeMillis() < deadline) {
                val cmd = getCommand(cm, COMMAND_WAIT_SEC)
                if (cmd == "check_now") { got = true; break }
                // idle/ошибка сети — короткая пауза перед следующей long-poll итерацией
                if (cmd == null) try { Thread.sleep(5000) } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt(); return
                }
            }
            // Даже без явной команды — прогоняем цикл раз в 15 мин (обычный интервал),
            // чтобы иметь свежие данные, если цель вообще выставлена.
            if (!got) LogUtil.d(TAG, "reach-agent: команда не пришла за окно — плановый прогон")

            setStage(ctx, "получаю цель проверки (targets)")
            val targets = getTargets(cm) ?: run { setStage(ctx, "idle: сервер недоступен (targets)"); return }
            val ip = targets.ip
            val ports = targets.ports
            if (ip.isNullOrBlank()) {
                setStage(ctx, "idle: цель ещё не назначена сервером")
                return
            }
            runDiagnostic(ctx, cm, ip, ports, targets.vless)
            lastCheckedIp = ip
            setStage(ctx, "idle: отчёт отправлен, жду следующее окно")
        }

        // bindProcessToNetwork() — операция НА ВЕСЬ ПРОЦЕСС, а не на отдельный вызов. Фоновый
        // WorkManager-цикл (runCycle) и foreground-цикл (foregroundIteration) — это ДВЕ разные
        // корутины/потока, и если оба одновременно решат гонять диагностику (напр. на свежей
        // установке WorkManager стартует почти сразу же, как открыто приложение), их bind/unbind
        // друг друга перебивают — один отвязывает сеть, пока другой ещё держит соединения, и всё
        // виснет. Этот lock гарантирует, что реально выполняется только один runDiagnostic разом;
        // второй вызов просто тихо пропускается (у него будет следующая попытка).
        private val diagnosticInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Батарея проверок + отправка отчёта для одного IP. Общая для background-цикла
         *  ([runCycle]) и непрерывного foreground-цикла ([foregroundIteration]). */
        private fun runDiagnostic(
            ctx: Context, cm: ConnectivityManager, ip: String, ports: List<Int>,
            vless: ReachVlessParams? = null
        ) {
            if (!diagnosticInProgress.compareAndSet(false, true)) {
                setStage(ctx, "idle: другая проверка уже идёт, пропускаю")
                return
            }
            try {
                runDiagnosticLocked(ctx, cm, ip, ports, vless)
            } finally {
                diagnosticInProgress.set(false)
            }
        }

        private fun runDiagnosticLocked(
            ctx: Context, cm: ConnectivityManager, ip: String, ports: List<Int>,
            vless: ReachVlessParams? = null
        ) {
            setStage(ctx, "привязка к сотовой сети для проверок")
            val network = bindCellular(cm) // может вернуть null, если cellular недоступен
            try {
                val reportId = UUID.randomUUID().toString()
                val checks = mutableListOf<JSONObject>()
                val operator = telephonyOperator(ctx)
                val netType = if (network != null) "4G" else "unknown"

                setStage(ctx, "проверка $ip: sanity/icmp")
                checks += check(ip, null, "sanity", reportId, operator, netType) { sanityCheck(network) }
                checks += check(ip, null, "icmp", reportId, operator, netType) { icmpCheck(ip, network) }
                for (p in ports) {
                    setStage(ctx, "проверка $ip:$p — tcp/tls")
                    checks += check(ip, p, "tcp_$p", reportId, operator, netType) { tcpCheck(ip, p, network) }
                    checks += check(ip, p, "tls_$p", reportId, operator, netType) { tlsCheck(ip, p, network) }
                }
                setStage(ctx, "проверка $ip:443 — http/quic")
                checks += check(ip, 443, "http_443", reportId, operator, netType) { httpCheck(ip, 443, network) }
                checks += check(ip, 443, "quic", reportId, operator, netType) { quicCheck(ip, 443, network) }
                if (vless != null) {
                    // Самый сильный сигнал: реальное VLESS+REALITY-соединение (не голый TLS-хендшейк).
                    // Если сервер не прислал vless_params (старая цель/set-target без них) — пропускаем,
                    // чтобы не ломать обратную совместимость.
                    setStage(ctx, "проверка $ip:${vless.port} — реальное VLESS-соединение")
                    checks += check(ip, vless.port, "vless_connect", reportId, operator, netType) {
                        vlessConnectCheck(ip, vless)
                    }
                }

                setStage(ctx, "отправка отчёта на сервер")
                postReport(ctx, cm, ip, reportId, checks, operator, netType)
            } finally {
                // Обязательно снимаем process-wide bind: он держится на весь app-процесс (тот же
                // UID, что и ядро xray), и если оставить его висеть — основной VPN-трафик рискует
                // застрять на cellular даже когда пользователь на Wi-Fi.
                if (network != null) {
                    try { cm.bindProcessToNetwork(null) } catch (e: Exception) { }
                    try { cm.unregisterNetworkCallback(lastCallback!!) } catch (e: Exception) { }
                }
            }
        }

        // ── HTTP к серверу (командный канал + отчёт) ─────────────────────────────
        // Командный канал НЕ привязан к cellular: если оператор режет и сам limm.space,
        // пробуем сначала дефолтную сеть, а при неудаче — явный Wi-Fi (см. controlCall).

        private fun getCommand(cm: ConnectivityManager, waitSec: Int): String? {
            return controlCall(cm) { network ->
                val c = controlClient(network)
                    .readTimeout((waitSec + 10).toLong(), TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url("${LimmConfig.collectorUrl}/api/reach-agent/command?wait=$waitSec")
                    .header("Authorization", "Bearer ${LimmConfig.token}")
                    .header("User-Agent", "limm-android/1.0")
                    .get().build()
                c.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@controlCall null
                    val body = JSONObject(r.body?.string() ?: "{}")
                    body.optString("command", "idle")
                }
            }
        }

        /** Параметры реального VLESS+REALITY-инбаунда цели (см. api.py set-target) — нужны,
         *  чтобы поднять НАСТОЯЩЕЕ соединение (vlessConnectCheck), а не только TCP/TLS-пробу. */
        data class ReachVlessParams(
            val uuid: String, val pbk: String, val sid: String, val sni: String, val port: Int
        )

        /** Цель диагностики: IP, список портов для TCP/TLS-проб, и опционально vless-параметры
         *  реального REALITY-инбаунда (null — сервер не прислал, значит vless_connect пропускаем). */
        data class ReachTargets(val ip: String?, val ports: List<Int>, val vless: ReachVlessParams?)

        /** Возвращает цель либо null при ошибке сети (обеих — cellular и Wi-Fi). */
        private fun getTargets(cm: ConnectivityManager): ReachTargets? {
            return controlCall(cm) { network ->
                val c = controlClient(network).build()
                val req = Request.Builder()
                    .url("${LimmConfig.collectorUrl}/api/reach-agent/targets")
                    .header("Authorization", "Bearer ${LimmConfig.token}")
                    .header("User-Agent", "limm-android/1.0")
                    .get().build()
                c.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@controlCall null
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
                    val vlessObj = body.optJSONObject("vless")
                    val vless = if (vlessObj != null) {
                        val uuid = vlessObj.optString("uuid", "")
                        val pbk = vlessObj.optString("pbk", "")
                        val sid = vlessObj.optString("sid", "")
                        val sni = vlessObj.optString("sni", "")
                        val vp = vlessObj.optInt("port", -1)
                        if (uuid.isNotBlank() && pbk.isNotBlank() && sni.isNotBlank() && vp > 0) {
                            ReachVlessParams(uuid, pbk, sid, sni, vp)
                        } else null
                    } else null
                    ReachTargets(ip, ports, vless)
                }
            }
        }

        private fun postReport(
            ctx: Context, cm: ConnectivityManager, ip: String, reportId: String,
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
            val ok = controlCall(cm) { network ->
                val c = controlClient(network).build()
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("${LimmConfig.collectorUrl}/api/reach-agent/report")
                    .header("Authorization", "Bearer ${LimmConfig.token}")
                    .header("User-Agent", "limm-android/1.0")
                    .post(body)
                    .build()
                c.newCall(req).execute().use { r -> if (r.isSuccessful) true else null }
            }
            if (ok != true) LogUtil.w(TAG, "reach-agent report: не удалось отправить (ни default, ни Wi-Fi)")
        }

        private fun controlClient(network: Network?): OkHttpClient.Builder {
            val b = OkHttpClient.Builder()
                .dns(LimmDns.IPV4_ONLY)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
            if (network != null) b.socketFactory(network.socketFactory)
            return b
        }

        /**
         * Пробует [action] на дефолтной сети (network=null); если результат null (ошибка/таймаут),
         * запрашивает явный TRANSPORT_WIFI и повторяет. Возвращает null, если недоступно и то и то
         * (нет Wi-Fi рядом либо и он не достаёт RU1 — тогда цикл просто откладывается на следующий раз).
         */
        private fun <T> controlCall(cm: ConnectivityManager, action: (Network?) -> T?): T? {
            val direct = try { action(null) } catch (e: Exception) { null }
            if (direct != null) return direct
            var cb: ConnectivityManager.NetworkCallback? = null
            return try {
                val (wifi, callback) = requestNetworkByTransport(cm, NetworkCapabilities.TRANSPORT_WIFI)
                cb = callback
                if (wifi == null) null else try { action(wifi) } catch (e: Exception) { null }
            } finally {
                if (cb != null) try { cm.unregisterNetworkCallback(cb) } catch (e: Exception) { }
            }
        }

        /** Запрашивает сеть заданного транспорта; возвращает (network, callback) — callback нужно
         *  отписать после использования (см. controlCall/runCycle). */
        private fun requestNetworkByTransport(
            cm: ConnectivityManager, transport: Int, timeoutSec: Long = 6
        ): Pair<Network?, ConnectivityManager.NetworkCallback> {
            val request = NetworkRequest.Builder()
                .addTransportType(transport)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val latch = CountDownLatch(1)
            var net: Network? = null
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { net = network; latch.countDown() }
            }
            return try {
                cm.requestNetwork(request, cb)
                latch.await(timeoutSec, TimeUnit.SECONDS)
                net to cb
            } catch (e: Exception) { null to cb }
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
            val (net, cb) = requestNetworkByTransport(cm, NetworkCapabilities.TRANSPORT_CELLULAR, timeoutSec = 8)
            lastCallback = cb
            return net?.also { cm.bindProcessToNetwork(it) }
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

        /**
         * Реальное VLESS+REALITY-соединение через ядро xray (Libv2ray.measureOutboundDelay,
         * тот же механизм, что и RealPingWorkerService для speed-теста профилей). Строит
         * временный outbound-only конфиг (без inbounds/routing-правил — только outbound-цель),
         * не трогает основной CoreServiceManager.coreController — движок сам поднимает и рвёт
         * временный инстанс внутри одного вызова. bindProcessToNetwork(cellular), выставленный
         * выше в runDiagnosticLocked, действует на весь процесс — Go-сокеты внутри тоже пойдут
         * через привязанную сотовую сеть.
         */
        private fun vlessConnectCheck(ip: String, v: ReachVlessParams): String {
            return try {
                val config = buildVlessRealityConfig(ip, v)
                val delay = CoreNativeManager.measureOutboundDelay(config, "https://www.gstatic.com/generate_204")
                if (delay >= 0) "VLESS_OK" else "VLESS_FAIL"
            } catch (e: Exception) { "VLESS_FAIL" }
        }

        private fun buildVlessRealityConfig(ip: String, v: ReachVlessParams): String {
            val user = JSONObject().apply {
                put("id", v.uuid)
                put("encryption", "none")
                put("flow", "xtls-rprx-vision")
            }
            val vnext = JSONObject().apply {
                put("address", ip)
                put("port", v.port)
                put("users", JSONArray().put(user))
            }
            val settings = JSONObject().apply { put("vnext", JSONArray().put(vnext)) }
            val reality = JSONObject().apply {
                put("show", false)
                put("serverName", v.sni)
                put("publicKey", v.pbk)
                put("shortId", v.sid)
                put("fingerprint", "chrome")
            }
            val streamSettings = JSONObject().apply {
                put("network", "tcp")
                put("security", "reality")
                put("realitySettings", reality)
            }
            val outbound = JSONObject().apply {
                put("tag", "proxy")
                put("protocol", "vless")
                put("settings", settings)
                put("streamSettings", streamSettings)
            }
            return JSONObject().apply {
                put("log", JSONObject().put("loglevel", "warning"))
                put("inbounds", JSONArray())
                put("outbounds", JSONArray().put(outbound))
                put("routing", JSONObject().apply {
                    put("domainStrategy", "AsIs")
                    put("rules", JSONArray())
                })
            }.toString()
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
