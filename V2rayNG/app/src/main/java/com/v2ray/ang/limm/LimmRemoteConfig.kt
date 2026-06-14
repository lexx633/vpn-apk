package com.v2ray.ang.limm

import android.util.Log
import com.tencent.mmkv.MMKV
import com.v2ray.ang.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Remote server config — fetches https://limm.space/vpn/server-config.json and caches in MMKV.
 *
 * Purpose: lets us update server params (IP, REALITY keys) without rebuilding the APK.
 * When migrating to a new server, just update server-config.json on
 * limm.space → all active apps pick it up within 24 h via the periodic checkin worker.
 *
 * Architecture:
 *   1. [refresh] fetches the JSON and stores each field in MMKV with a timestamp.
 *   2. All getters read MMKV first; fall back to BuildConfig if MMKV is empty (first launch /
 *      offline / parse error). BuildConfig = current primary server at build time = guaranteed
 *      sane fallback.
 *   3. [isStale] is true when there is no cached data or when the cache is older than 24 h.
 *      LimmCheckinWorker calls [refresh] when stale (runs on IO dispatcher, blocking is fine).
 *
 * Secrets NOT in this file: UUID, token — those never leave BuildConfig
 * (baked from the gitignored limm.properties via CI). The JSON contains only non-secret infra
 * params that change when moving between servers.
 */
object LimmRemoteConfig {
    private const val TAG = "LimmRemoteConfig"
    private const val MMKV_ID = "limm_remote_cfg"

    /** Public endpoint — no auth needed (contains no secrets). */
    private const val CONFIG_URL = "https://limm.space/vpn/server-config.json"

    /** Refresh if cached data is older than 24 h or absent. */
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

    private val kv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE) }

    // ── MMKV keys ──────────────────────────────────────────────────────────────────────────────
    private const val KEY_TS              = "ts"
    private const val KEY_SERVER_IP       = "server_ip"
    private const val KEY_SERVER_PORT     = "server_port"
    private const val KEY_SERVER_NAME     = "server_name"
    private const val KEY_REALITY_PBK     = "reality_pbk"
    private const val KEY_REALITY_SNI     = "reality_sni"
    private const val KEY_REALITY_SID     = "reality_sid"
    private const val KEY_REALITY_FLOW    = "reality_flow"
    private const val KEY_REALITY_FP      = "reality_fp"

    // ── Helpers ────────────────────────────────────────────────────────────────────────────────

    /** Read string from MMKV; use [fallback] if key is absent or empty. */
    private fun str(key: String, fallback: String): String =
        (kv.decodeString(key, null) ?: "").ifEmpty { fallback }

    // ── Getters (MMKV → BuildConfig fallback) ─────────────────────────────────────────────────

    val serverIp: String        get() = str(KEY_SERVER_IP,        BuildConfig.LIMM_SERVER_IP)
    val serverPort: String      get() = str(KEY_SERVER_PORT,      BuildConfig.LIMM_SERVER_PORT)
    val serverName: String      get() = str(KEY_SERVER_NAME,      BuildConfig.LIMM_SERVER_NAME)
    val realityPbk: String      get() = str(KEY_REALITY_PBK,      BuildConfig.LIMM_REALITY_PBK)
    val realitySni: String      get() = str(KEY_REALITY_SNI,      BuildConfig.LIMM_REALITY_SNI)
    val realitySid: String      get() = str(KEY_REALITY_SID,      BuildConfig.LIMM_REALITY_SID)
    val realityFlow: String     get() = str(KEY_REALITY_FLOW,     BuildConfig.LIMM_REALITY_FLOW)
    val realityFp: String       get() = str(KEY_REALITY_FP,       BuildConfig.LIMM_REALITY_FP)

    // ── Cache freshness ────────────────────────────────────────────────────────────────────────

    /**
     * True when no remote config has been fetched yet, or when the cached data is older than
     * [CACHE_TTL_MS] (24 h). LimmCheckinWorker calls [refresh] when this returns true.
     */
    fun isStale(): Boolean {
        val ts = kv.decodeLong(KEY_TS, 0L)
        return System.currentTimeMillis() - ts > CACHE_TTL_MS
    }

    // ── Fetch & store ──────────────────────────────────────────────────────────────────────────

    /**
     * Fetches [CONFIG_URL], parses the JSON, and stores all fields in MMKV.
     * Must be called on a background thread (blocking HTTP). Returns true on success.
     * On failure the old cached values (or BuildConfig fallback) remain in effect.
     */
    fun refresh(): Boolean {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url(CONFIG_URL)
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "limm-android/1.0")
                .build()
            val body = client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w(TAG, "Remote config HTTP ${r.code}")
                    return false
                }
                r.body?.string() ?: return false
            }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            parseAndStore(body)
            Log.i(TAG, "Remote config refreshed (server=${str(KEY_SERVER_NAME, "?")})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Remote config refresh failed: ${e.message}")
            false
        }
    }

    private fun parseAndStore(json: String) {
        val j = JSONObject(json)
        fun s(key: String, mmkvKey: String) {
            val v = j.optString(key, "")
            if (v.isNotEmpty()) kv.encode(mmkvKey, v)
        }
        s("server_ip",         KEY_SERVER_IP)
        s("server_port",       KEY_SERVER_PORT)
        s("server_name",       KEY_SERVER_NAME)
        s("reality_pbk",       KEY_REALITY_PBK)
        s("reality_sni",       KEY_REALITY_SNI)
        s("reality_sid",       KEY_REALITY_SID)
        s("reality_flow",      KEY_REALITY_FLOW)
        s("reality_fp",        KEY_REALITY_FP)
        kv.encode(KEY_TS, System.currentTimeMillis())
    }
}
