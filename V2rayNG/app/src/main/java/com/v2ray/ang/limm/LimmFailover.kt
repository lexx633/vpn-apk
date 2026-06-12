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

    /** Порядок транспортов: первый — предпочтительный, последний — последний резерв. */
    val TRANSPORT_LADDER = listOf("FR1-xhttp", "FR1-cf", "FR1-hy2", "FR1")

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
        val currentRemark = currentProfile.remarks

        // Проверяем, входит ли текущий профиль в лестницу.
        val ladderIdx = TRANSPORT_LADDER.indexOf(currentRemark)
        if (ladderIdx < 0) {
            LogUtil.i(TAG, "Текущий профиль «$currentRemark» не в лестнице — failover не применяется")
            return
        }

        // Берём следующий профиль циклически.
        val nextRemark = TRANSPORT_LADDER[(ladderIdx + 1) % TRANSPORT_LADDER.size]
        LogUtil.i(TAG, "L3 FAIL: переключение $currentRemark → $nextRemark")

        // Ищем GUID профиля с нужным remark среди всех серверов.
        val nextGuid = findGuidByRemark(nextRemark)
        if (nextGuid == null) {
            LogUtil.w(TAG, "Профиль «$nextRemark» не найден среди сохранённых серверов — пропускаем")
            return
        }

        // Переключаем активный профиль и перезапускаем сервис.
        try {
            MmkvManager.setSelectServer(nextGuid)
            MessageUtil.sendMsg2Service(ctx, AppConfig.MSG_STATE_RESTART, "")
            LogUtil.i(TAG, "Профиль переключён: $currentRemark → $nextRemark (guid=$nextGuid)")

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
