package com.v2ray.ang.limm

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Автоматическое переключение транспорта при деградации туннеля (L3 failure).
 *
 * Лестница приоритетов (от лучшего к fallback):
 *   FR1-xhttp → FR1-cf → FR1-hy2 → FR1
 *
 * Алгоритм:
 *  - Если l3ok == true — ничего не делать, залипнуть на текущем профиле.
 *  - Если l3ok == false:
 *      1. Проверить кулдаун (≥5 мин с последнего переключения) — защита от флапа.
 *      2. Найти текущий активный профиль по remarks.
 *      3. Если профиль входит в лестницу — взять следующий циклически.
 *      4. Если профиль не в лестнице (кастомный) — не трогать.
 *      5. Переключить активный профиль через MmkvManager + MSG_STATE_RESTART.
 *      6. Отправить событие transport_switch на коллектор мониторинга.
 */
object LimmFailover {

    private const val TAG = "LimmFailover"

    /** Маркер AWG-режима: это НЕ обычный xray-профиль, а команда «переключить туннель на AmneziaWG
     *  внутри того же VpnService» (см. LimmAWGTunnel, §C.4). В лестнице стоит перед FR1-wg. */
    const val AWG_REMARK = "FR1-awg"

    /**
     * Полная лестница. FR1-awg обфусцирован → «сильнее» голого FR1-wg, поэтому идёт перед ним.
     * Но FR1-awg включается в АВТО-лестницу только если нативный AWG-бэкенд реально слинкован
     * (LimmAWGTunnel.isAvailable) — иначе [activeLadder] его выкидывает и failover идёт мимо
     * (поведение fallback §C.6 без падений, пока AAR не подключён).
     */
    val TRANSPORT_LADDER = listOf("FR1-xhttp", "FR1-cf", "FR1-hy2", AWG_REMARK, "FR1-wg", "FR1")

    /** Лестница с учётом доступности AWG: без FR1-awg, пока нативный бэкенд не слинкован. */
    private fun activeLadder(): List<String> =
        if (LimmAWGTunnel.isAvailable) TRANSPORT_LADDER
        else TRANSPORT_LADDER.filter { it != AWG_REMARK }

    /** Минимальный интервал между переключениями (мс). */
    private const val COOLDOWN_MS = 5 * 60 * 1000L // 5 минут

    /** SharedPreferences — ключи состояния failover. */
    private const val PREFS_NAME = "limm_failover"
    private const val KEY_LAST_SWITCH_TS = "last_switch_ts"
    private const val KEY_LAST_SWITCH_FROM = "last_switch_from"
    private const val KEY_LAST_SWITCH_TO = "last_switch_to"

    /**
     * Основная точка входа. Вызывается из LimmCheckinWorker после вычисления L-уровней.
     *
     * @param ctx     контекст приложения
     * @param l3ok    true — туннель проверен (egress == server IP); false — туннель сломан или null
     * @param vpnRunning  true — VPN сервис запущен (пользователь не выключил его вручную)
     */
    fun evaluate(ctx: Context, l3ok: Boolean, vpnRunning: Boolean) {
        if (l3ok) {
            // Туннель работает — залипаем, не трогаем профиль.
            LogUtil.d(TAG, "L3 OK — failover не нужен")
            return
        }
        if (!vpnRunning) {
            // Пользователь выключил VPN — не переключать автоматически.
            LogUtil.d(TAG, "VPN выключен пользователем — failover пропущен")
            return
        }

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Проверка кулдауна — защита от флапа при нестабильной связи.
        val lastSwitchTs = prefs.getLong(KEY_LAST_SWITCH_TS, 0L)
        val elapsed = System.currentTimeMillis() - lastSwitchTs
        if (elapsed < COOLDOWN_MS) {
            val remainSec = (COOLDOWN_MS - elapsed) / 1000
            LogUtil.d(TAG, "Failover в кулдауне, осталось ${remainSec}с — пропускаем")
            return
        }

        // Определяем текущий активный профиль.
        val currentGuid = MmkvManager.getSelectServer()
        if (currentGuid.isNullOrBlank()) {
            LogUtil.w(TAG, "Нет активного профиля — failover невозможен")
            return
        }
        val currentProfile = MmkvManager.decodeServerConfig(currentGuid)
        if (currentProfile == null) {
            LogUtil.w(TAG, "Профиль $currentGuid не найден в хранилище")
            return
        }
        // Если активен AWG-режим — текущего «профиля» в MMKV нет (AWG это не xray-профиль),
        // determineCurrentRemark разрулит это ниже. Берём «эффективный» текущий remark.
        val currentRemark = if (LimmAWGTunnel.isActive) AWG_REMARK else currentProfile.remarks

        val ladder = activeLadder()

        // Проверяем, входит ли текущий профиль в лестницу.
        val ladderIdx = ladder.indexOf(currentRemark)
        if (ladderIdx < 0) {
            LogUtil.i(TAG, "Текущий профиль «$currentRemark» не в лестнице — failover не применяется")
            return
        }

        // Берём следующий профиль циклически.
        val nextRemark = ladder[(ladderIdx + 1) % ladder.size]
        LogUtil.i(TAG, "L3 FAIL: переключение $currentRemark → $nextRemark")

        try {
            // §C.4: FR1-awg — это НЕ выбор xray-профиля, а переключение режима туннеля внутри
            // того же VpnService. Поэтому путь переключения раздвоен:
            when {
                // (a) Уходим НА AWG: остановить xray-ядро и поднять AWG userspace поверх того же
                //     TUN-fd. Реальный handover fd делает сервис (см. open questions в коммите).
                nextRemark == AWG_REMARK -> {
                    if (!switchToAwg(ctx)) {
                        LogUtil.w(TAG, "Переход на AWG не удался — лестница продолжит на след. шаге")
                        return
                    }
                }
                // (b) Уходим С AWG на любой xray-транспорт: погасить AWG, вернуть fd, выбрать
                //     xray-профиль и перезапустить сервис как обычно.
                currentRemark == AWG_REMARK -> {
                    LimmAWGTunnel.stopTunnel()
                    if (!selectXrayProfile(ctx, nextRemark)) return
                }
                // (c) Обычный xray→xray переход — как раньше.
                else -> {
                    if (!selectXrayProfile(ctx, nextRemark)) return
                }
            }

            // Сохраняем состояние переключения.
            prefs.edit()
                .putLong(KEY_LAST_SWITCH_TS, System.currentTimeMillis())
                .putString(KEY_LAST_SWITCH_FROM, currentRemark)
                .putString(KEY_LAST_SWITCH_TO, nextRemark)
                .apply()

            // Отправляем событие на коллектор мониторинга.
            postSwitchEvent(ctx, currentRemark, nextRemark)

        } catch (e: Exception) {
            LogUtil.e(TAG, "Ошибка при переключении профиля: ${e.message}", e)
        }
    }

    /**
     * Обычный путь xray-профиля: setSelectServer + MSG_STATE_RESTART. Возвращает false, если
     * профиль с таким remark не найден среди сохранённых серверов.
     */
    private fun selectXrayProfile(ctx: Context, remark: String): Boolean {
        val nextGuid = findGuidByRemark(remark)
        if (nextGuid == null) {
            LogUtil.w(TAG, "Профиль «$remark» не найден среди сохранённых серверов — пропускаем")
            return false
        }
        MmkvManager.setSelectServer(nextGuid)
        MessageUtil.sendMsg2Service(ctx, AppConfig.MSG_STATE_RESTART, "")
        LogUtil.i(TAG, "xray-профиль переключён → $remark (guid=$nextGuid)")
        return true
    }

    /**
     * §C.4 — переход НА AmneziaWG внутри того же VpnService.
     *
     * Здесь только посылается КОМАНДА сервису сменить режим туннеля; сам захват существующего
     * TUN-fd и остановка xray-ядра выполняются в CoreVpnService (он владеет mInterface). Это
     * требует нового сообщения сервису (напр. MSG_STATE_SWITCH_AWG) и обработчика, который:
     *   1) останавливает xray-ядро (CoreServiceManager.stopCoreLoop), НЕ закрывая mInterface;
     *   2) зовёт LimmAWGTunnel.startTunnel(ctx, mInterface.fd).
     * Пока этот канал/обработчик не реализован (см. open questions), метод возвращает false и
     * автолестница безопасно НЕ уходит на AWG.
     *
     * TODO(awg-handover): добавить MSG_STATE_SWITCH_AWG в AppConfig + обработку в CoreVpnService;
     *   до этого FR1-awg в авто-режиме недостижим (но код пути готов).
     */
    private fun switchToAwg(ctx: Context): Boolean {
        if (!LimmAWGTunnel.isAvailable) return false
        // Канал handover fd сервису ещё не реализован — безопасно отказываемся.
        LogUtil.w(TAG, "switchToAwg: TUN-fd handover в CoreVpnService ещё не реализован (TODO)")
        return false
    }

    /**
     * Ищет GUID профиля по его remark среди всех сохранённых серверов.
     * Перебирает все подписки + ungrouped серверы.
     */
    private fun findGuidByRemark(remark: String): String? {
        val allGuids = MmkvManager.decodeAllServerList()
        for (guid in allGuids) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            if (profile.remarks == remark) return guid
        }
        return null
    }

    /**
     * Отправляет событие transport_switch на коллектор мониторинга (async, best-effort).
     * Ошибки сети не прокидываются наверх — failover уже выполнен, событие вторично.
     */
    private fun postSwitchEvent(ctx: Context, from: String, to: String) {
        if (!LimmConfig.isConfigured() || LimmConfig.token.isEmpty()) return
        try {
            val payload = JSONObject().apply {
                put("client_uid", LimmConfig.clientUid(ctx))
                put("event_type", "transport_switch")
                put("note", "$from→$to")
                put("server", LimmConfig.serverName)
                put("app_version", LimmConfig.appVersion)
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${LimmConfig.collectorUrl}/api/event")
                .header("Authorization", "Bearer ${LimmConfig.token}")
                .header("User-Agent", "limm-android/1.0")
                .post(body)
                .build()
            client.newCall(req).execute().use { r ->
                if (r.isSuccessful) {
                    LogUtil.d(TAG, "Событие transport_switch отправлено ($from→$to)")
                } else {
                    LogUtil.w(TAG, "Коллектор вернул ${r.code} для transport_switch")
                }
            }
        } catch (e: Exception) {
            // Best-effort: не падаем при проблемах с сетью.
            LogUtil.w(TAG, "Не удалось отправить событие transport_switch: ${e.message}")
        }
    }
}
