package com.v2ray.ang.limm

import android.content.Context
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil

/**
 * On first launch, register the limm subscription (https://limm.space/vpn/sub) as the
 * default. The subscription carries every available profile — VLESS+REALITY/TCP (FR1..FR3)
 * and the VLESS+REALITY/XHTTP profile (FR1-xhttp) — so the user gets both transports and
 * they stay in sync automatically. No manual link pasting required.
 *
 * The single embedded VLESS link is kept only as an offline fallback if the first
 * subscription fetch fails (e.g. no network at very first launch).
 */
object LimmBootstrap {
    private const val MMKV_ID = "limm"

    // Bumped from the old "server_imported" flag so existing installs re-run the
    // subscription registration once after updating to this build.
    private const val KEY_SUB_IMPORTED = "sub_imported_v2"

    // Stable guid for the limm subscription entry in v2rayNG's subscription storage.
    private const val SUB_GUID = "limm00000000000000000000000000001"

    private val mmkv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE) }

    fun ensureServerImported(ctx: Context) {
        try {
            if (!LimmConfig.isConfigured()) return
            if (mmkv.decodeBool(KEY_SUB_IMPORTED, false)) return

            // Register / refresh the limm subscription and enable periodic auto-update.
            val sub = SubscriptionItem(
                remarks = "limm",
                url = "${LimmConfig.collectorUrl}/vpn/sub",
                enabled = true,
                autoUpdate = true,
                updateInterval = 720, // 12h
            )
            MmkvManager.encodeSubscription(SUB_GUID, sub)
            SubscriptionUpdater.syncOne(ctx, SUB_GUID)

            // First import does network I/O — keep it off the main thread.
            Thread {
                try {
                    val res = AngConfigManager.updateConfigViaSub(SubscriptionCache(SUB_GUID, sub))
                    if (res.configCount > 0) {
                        mmkv.encode(KEY_SUB_IMPORTED, true)
                        LogUtil.i(AppConfig.TAG, "limm: subscription imported (${res.configCount})")
                    } else if (MmkvManager.decodeAllServerList().isEmpty()) {
                        // Offline fallback: embed the single REALITY/TCP profile so at least
                        // one working server exists; the subscription will fill the rest later.
                        val (count, _) = AngConfigManager.importBatchConfig(LimmConfig.vlessLink(), SUB_GUID, false)
                        if (count > 0) LogUtil.i(AppConfig.TAG, "limm: fallback embedded import ($count)")
                    }
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "limm: subscription import failed", e)
                }
            }.start()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "limm: bootstrap failed", e)
        }
    }
}
