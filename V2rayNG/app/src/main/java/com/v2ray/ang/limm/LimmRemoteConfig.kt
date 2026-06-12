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
 * Purpose: lets us update server params (IP, REALITY keys, AWG pubkey/endpoint, obfuscation)
 * without rebuilding the APK. When migrating to a new server, just update server-config.json on
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
 * Secrets NOT in this file: UUID, token, AWG client private key — those never leave BuildConfig
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
    private const val KEY_AWG_SERVER_PUBKEY = "awg_server_pubkey"
    private const val KEY_AWG_ENDPOINT    = "awg_endpoint"
    private const val KEY_AWG_ADDRESS     = "awg_address"
    private const val KEY_AWG_DNS         = "awg_dns"
    private const val KEY_AWG_JC          = "awg_jc"
    private const val KEY_AWG_JMIN        = "awg_jmin"
    private const val KEY_AWG_JMAX        = "awg_jmax"
    private const val KEY_AWG_S1          = "awg_s1"
    private const val KEY_AWG_S2          = "awg_s2"
    private const val KEY_AWG_H1          = "awg_h1"
    private const val KEY_AWG_H2          = "awg_h2"
    private const val KEY_AWG_H3          = "awg_h3"
    private const val KEY_AWG_H4          = "awg_h4"

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
    val awgServerPubkey: String get() = str(KEY_AWG_SERVER_PUBKEY, BuildConfig.LIMM_AWG_SERVER_PUBKEY)
    val awgEndpoint: String     get() = str(KEY_AWG_ENDPOINT,     BuildConfig.LIMM_AWG_ENDPOINT)
    val awgAddress: String      get() = str(KEY_AWG_ADDRESS,      BuildConfig.LIMM_AWG_ADDRESS)
    val awgDns: String          get() = str(KEY_AWG_DNS,          BuildConfig.LIMM_AWG_DNS)
    val awgJc: String           get() = str(KEY_AWG_JC,           BuildConfig.LIMM_AWG_JC)
    val awgJmin: String         get() = str(KEY_AWG_JMIN,         BuildConfig.LIMM_AWG_JMIN)
    val awgJmax: String         get() = str(KEY_AWG_JMAX,         BuildConfig.LIMM_AWG_JMAX)
    val awgS1: String           get() = str(KEY_AWG_S1,           BuildConfig.LIMM_AWG_S1)
    val awgS2: String           get() = str(KEY_AWG_S2,           BuildConfig.LIMM_AWG_S2)
    val awgH1: String           get() = str(KEY_AWG_H1,           BuildConfig.LIMM_AWG_H1)
    val awgH2: String           get() = str(KEY_AWG_H2,           BuildConfig.LIMM_AWG_H2)
    val awgH3: String           get() = str(KEY_AWG_H3,           BuildConfig.LIMM_AWG_H3)
    val awgH4: String           get() = str(KEY_AWG_H4,           BuildConfig.LIMM_AWG_H4)

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
        s("awg_server_pubkey", KEY_AWG_SERVER_PUBKEY)
        s("awg_endpoint",      KEY_AWG_ENDPOINT)
        s("awg_address",       KEY_AWG_ADDRESS)
        s("awg_dns",           KEY_AWG_DNS)
        s("awg_jc",            KEY_AWG_JC)
        s("awg_jmin",          KEY_AWG_JMIN)
        s("awg_jmax",          KEY_AWG_JMAX)
        s("awg_s1",            KEY_AWG_S1)
        s("awg_s2",            KEY_AWG_S2)
        s("awg_h1",            KEY_AWG_H1)
        s("awg_h2",            KEY_AWG_H2)
        s("awg_h3",            KEY_AWG_H3)
        s("awg_h4",            KEY_AWG_H4)
        kv.encode(KEY_TS, System.currentTimeMillis())
    }
}
