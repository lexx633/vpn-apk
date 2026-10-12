package com.v2ray.ang.limm

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil

/**
 * Цепочки «резидентный прокси РФ → наша нода» без ручной настройки.
 *
 * В `/vpn/sub` есть SOCKS5-профиль [GW_REMARK] (hproxy, домашние IP РФ). Когда провайдер режет
 * прямой канал до ноды, к ней подключаются через этот прокси. После каждого обновления Limm-подписки
 * для каждого VLESS+REALITY профиля подписки создаётся PROXYCHAIN `<нода> via RU-resi`
 * (первое звено — прокси, второе — нода; порядок как в ServerProxyChainActivity).
 *
 * Цепочки хранятся вне подписки (subscriptionId пустой): обновление подписки их не стирает,
 * выбранная пользователем цепочка остаётся выбранной. Upsert по remarks. Пропал прокси из sub —
 * цепочки удаляются. Только TCP: Hysteria2/TUIC через SOCKS не ходят.
 */
object LimmResiChain {
    const val GW_REMARK = "RU-resi-gw"
    private const val SUFFIX = " via RU-resi"

    fun sync() {
        try {
            val subGuids = MmkvManager.decodeServerList(LimmBootstrap.SUB_GUID)
            val subProfiles = subGuids.mapNotNull { MmkvManager.decodeServerConfig(it) }
            val hasGw = subProfiles.any { it.remarks == GW_REMARK }
            val targets = if (hasGw) {
                subProfiles.filter {
                    it.configType == EConfigType.VLESS && it.security == AppConfig.REALITY
                }.map { it.remarks }
            } else {
                emptyList()
            }
            val wanted = targets.associateBy { it + SUFFIX }

            // Существующие наши цепочки: remarks → guid
            val existing = mutableMapOf<String, String>()
            for (guid in MmkvManager.decodeAllServerList()) {
                val p = MmkvManager.decodeServerConfig(guid) ?: continue
                if (p.configType == EConfigType.PROXYCHAIN && p.remarks.endsWith(SUFFIX)) {
                    existing[p.remarks] = guid
                }
            }

            for ((remarks, guid) in existing) {
                if (remarks !in wanted) MmkvManager.removeServer(guid)
            }
            for ((remarks, node) in wanted) {
                val guid = existing[remarks].orEmpty()
                val cfg = (if (guid.isNotEmpty()) MmkvManager.decodeServerConfig(guid) else null)
                    ?: ProfileItem.create(EConfigType.PROXYCHAIN)
                cfg.remarks = remarks
                cfg.proxyChainProfiles = "$GW_REMARK,$node"
                MmkvManager.encodeServerConfig(guid, cfg)
            }
            LogUtil.i(AppConfig.TAG, "LimmResiChain: gw=$hasGw chains=${wanted.size} removed=${existing.keys.count { it !in wanted }}")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LimmResiChain sync failed", e)
        }
    }
}
