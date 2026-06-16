package com.v2ray.ang.limm

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Subscription host fallback. v2rayNG's built-in updater fetches whatever URL is stored on the
 * subscription; if that host (e.g. limm.space via Cloudflare) is ISP-blocked the server list
 * silently stops refreshing. This probes the mirror hosts (www direct → vpn Bunny → limm CF),
 * picks the first that serves a VALID subscription, and pins the stored URL to it so the next
 * auto-update succeeds.
 *
 * Safety: AngConfigManager.parseBatchConfig only removes servers when it parsed >0 configs, so
 * a provider block-page (0 parseable configs) can't erase the list — this only restores a
 * successful refresh; it never deletes servers on its own.
 */
object LimmSubFallback {
    private const val SUB_PATH = "/vpn/sub"

    private fun isSocksOpen(port: Int): Boolean = try {
        java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400); true }
    } catch (e: Exception) { false }

    /** Probes mirror hosts; returns the first base URL that serves a VALID sub, or null. */
    fun resolveWorkingBase(): String? {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
        // Ride the tunnel when it's up (app is excluded from its own VPN → direct CF can hang).
        val socksPort = try { SettingsManager.getSocksPort() } catch (e: Exception) { 0 }
        if (socksPort > 0 && isSocksOpen(socksPort)) {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
        }
        val client = builder.build()
        for (base in LimmConfig.updateBases) {
            try {
                client.newCall(
                    Request.Builder().url("$base$SUB_PATH")
                        .header("Cache-Control", "no-cache").build()
                ).execute().use { r ->
                    if (r.isSuccessful) {
                        val body = r.body?.string() ?: ""
                        if (LimmConfig.isValidSub(body)) return base
                    }
                }
            } catch (e: Exception) { /* try next mirror */ }
        }
        return null
    }

    /**
     * Pins the limm subscription's stored URL to the first reachable mirror host and triggers a
     * refresh when the host changed. No-op when nothing is reachable (keeps the current URL).
     */
    fun pinWorkingHost(ctx: Context) {
        try {
            if (!LimmConfig.isConfigured()) return
            val sub = MmkvManager.decodeSubscription(LimmBootstrap.SUB_GUID) ?: return
            val base = resolveWorkingBase() ?: return
            val desired = "$base$SUB_PATH"
            if (sub.url.trim() == desired) return
            sub.url = desired
            MmkvManager.encodeSubscription(LimmBootstrap.SUB_GUID, sub)
            LogUtil.i(AppConfig.TAG, "limm: sub host pinned -> $desired")
            SubscriptionUpdater.syncOne(ctx, LimmBootstrap.SUB_GUID)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "limm: sub host pin failed", e)
        }
    }
}
