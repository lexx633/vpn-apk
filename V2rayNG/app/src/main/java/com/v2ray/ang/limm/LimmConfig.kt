package com.v2ray.ang.limm

import android.content.Context
import android.provider.Settings
import com.tencent.mmkv.MMKV
import com.v2ray.ang.BuildConfig
import java.util.UUID

/**
 * limm VPN — central config for the embedded server profile and the monitoring check-in.
 * Secrets (UUID/token) arrive via BuildConfig from the gitignored limm.properties.
 * Non-secret REALITY params are baked at build time too.
 */
object LimmConfig {
    private const val MMKV_ID = "limm"
    private const val KEY_CLIENT_UID = "client_uid"

    private val mmkv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE) }

    val collectorUrl: String get() = BuildConfig.LIMM_COLLECTOR_URL.trimEnd('/')
    val token: String get() = BuildConfig.LIMM_TOKEN
    val serverIp: String get() = BuildConfig.LIMM_SERVER_IP
    val serverName: String get() = BuildConfig.LIMM_SERVER_NAME

    /** Friendly device label for the dashboard; empty falls back to "android-<model>". */
    val label: String get() = BuildConfig.LIMM_LABEL

    /** Short git SHA of the build, so the dashboard can tell fresh data from stale-app data. */
    val build: String get() = BuildConfig.LIMM_BUILD

    /** First 4 hex chars of build SHA — matches the #XXXX shown on limm.space/stat footer. */
    val buildTag: String get() = BuildConfig.LIMM_BUILD.take(4)

    /** Human-readable version with build tag shown in update checker, e.g. "2.2.3.3 #c14a". */
    val displayVersion: String get() = "${BuildConfig.VERSION_NAME} #${buildTag}"

    /** app_version string sent in every check-in/log payload. */
    val appVersion: String get() = "limm-android-1.2+${BuildConfig.LIMM_BUILD}"

    /** True if the build was provided a server UUID (otherwise auto-import/check-in are no-ops). */
    fun isConfigured(): Boolean = BuildConfig.LIMM_VLESS_UUID.isNotEmpty()

    /**
     * Stable per-device client id — survives app reinstall.
     * Uses Android ID (unique per device, reset only on factory reset).
     * Falls back to a persisted random UUID on devices where ANDROID_ID is unreliable.
     */
    fun clientUid(ctx: Context): String {
        val androidId = try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.length > 4 && it != "9774d56d682e549c" } // filter known bad emulator value
        } catch (_: Exception) { null }
        if (androidId != null) return "android-$androidId"
        // Fallback: random UUID persisted in MMKV
        var v = mmkv.decodeString(KEY_CLIENT_UID, "") ?: ""
        if (v.isEmpty()) { v = UUID.randomUUID().toString(); mmkv.encode(KEY_CLIENT_UID, v) }
        return v
    }

    /** Builds the VLESS+REALITY share link consumed by AngConfigManager.importBatchConfig. */
    fun vlessLink(): String {
        val q = "type=tcp&security=reality" +
            "&pbk=${BuildConfig.LIMM_REALITY_PBK}" +
            "&fp=${BuildConfig.LIMM_REALITY_FP}" +
            "&sni=${BuildConfig.LIMM_REALITY_SNI}" +
            "&sid=${BuildConfig.LIMM_REALITY_SID}" +
            "&flow=${BuildConfig.LIMM_REALITY_FLOW}" +
            "&encryption=none"
        return "vless://${BuildConfig.LIMM_VLESS_UUID}@" +
            "${BuildConfig.LIMM_SERVER_IP}:${BuildConfig.LIMM_SERVER_PORT}?$q#limm"
    }
}
