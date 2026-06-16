package com.v2ray.ang.limm

import android.content.Context
import android.provider.Settings
import com.tencent.mmkv.MMKV
import com.v2ray.ang.BuildConfig
import java.net.URLEncoder
import java.util.UUID

/**
 * limm VPN — central config for the embedded server profile and the monitoring check-in.
 * Secrets (UUID/token) arrive via BuildConfig from the gitignored limm.properties.
 * Non-secret server params (IP, REALITY keys) come from [LimmRemoteConfig]
 * which fetches server-config.json and caches in MMKV — BuildConfig is the offline fallback.
 */
object LimmConfig {
    private const val MMKV_ID = "limm"
    private const val KEY_CLIENT_UID = "client_uid"

    private val mmkv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE) }

    val collectorUrl: String get() = BuildConfig.LIMM_COLLECTOR_URL.trimEnd('/')
    val token: String get() = BuildConfig.LIMM_TOKEN

    /**
     * Update/download mirrors in priority order: direct origin (www, grey-cloud → RU1 IP)
     * first, then Cloudflare. An ISP may block one path while the other stays reachable,
     * so version-check and APK download try both. www.limm.space bypasses CF entirely;
     * limm.space rides CF. Both serve identical /vpn/* and /api/* on RU1.
     */
    val updateBases: List<String> get() =
        listOf("https://www.limm.space", "https://vpn.limm.space", "https://limm.space")

    /** Hosts recognised as the limm download site (same RU1 origin, different SNI/cloud paths). */
    private val mirrorHosts = setOf("limm.space", "www.limm.space", "vpn.limm.space")

    /**
     * Mirrors an absolute limm.space / www.limm.space / vpn.limm.space URL across all hosts
     * in priority order (direct www first, then vpn+CF, then bare limm+CF). Non-limm URLs
     * (or unparseable) are returned unchanged.
     */
    fun mirrorUrls(url: String): List<String> {
        val scheme = url.substringBefore("://", "")
        if (scheme.isEmpty()) return listOf(url)
        val rest = url.substringAfter("://")
        val host = rest.substringBefore("/")
        val path = rest.substringAfter("/", "")
        if (host !in mirrorHosts) return listOf(url)
        return updateBases.map { base -> if (path.isEmpty()) base else "$base/$path" }
    }
    /** Resolved via LimmRemoteConfig (MMKV cache → BuildConfig fallback). */
    val serverIp: String get() = LimmRemoteConfig.serverIp
    val serverName: String get() = LimmRemoteConfig.serverName

    /** Friendly device label for the dashboard; empty falls back to "android-<model>". */
    val label: String get() = BuildConfig.LIMM_LABEL

    /** Short git SHA of the build, so the dashboard can tell fresh data from stale-app data. */
    val build: String get() = BuildConfig.LIMM_BUILD

    /** First 3 hex chars of build SHA — matches the #XXX shown on limm.space/stat footer. */
    val buildTag: String get() = BuildConfig.LIMM_BUILD.take(3)

    /** Human-readable version with build tag shown in update checker, e.g. "2.2.3.3 #c14a". */
    val displayVersion: String get() = "${BuildConfig.VERSION_NAME} #${buildTag}"

    /** app_version string sent in every check-in/log payload. */
    val appVersion: String get() = "${BuildConfig.VERSION_NAME}+${buildTag}"

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

    /** Builds the VLESS+REALITY share link consumed by AngConfigManager.importBatchConfig.
     *  Server params resolved via LimmRemoteConfig (MMKV → BuildConfig fallback). */
    fun vlessLink(): String {
        // L3: URL-encode query values so REALITY params with reserved chars stay valid.
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val q = "type=tcp&security=reality" +
            "&pbk=${enc(LimmRemoteConfig.realityPbk)}" +
            "&fp=${enc(LimmRemoteConfig.realityFp)}" +
            "&sni=${enc(LimmRemoteConfig.realitySni)}" +
            "&sid=${enc(LimmRemoteConfig.realitySid)}" +
            "&flow=${enc(LimmRemoteConfig.realityFlow)}" +
            "&encryption=none"
        return "vless://${BuildConfig.LIMM_VLESS_UUID}@" +
            "${LimmRemoteConfig.serverIp}:${LimmRemoteConfig.serverPort}?$q#limm"
    }
}
