package com.v2ray.ang.dto

data class UrlContentRequest(
    val url: String?,
    val timeout: Int = 15000,
    val httpPort: Int = 0,
    val proxyUsername: String? = null,
    val proxyPassword: String? = null,
    val userAgent: String? = null,
    // HWID device-identification headers (Remnawave anti-sharing device-limit).
    // hwid = stable per-device id (x-hwid). Others are optional, for panel display only.
    val hwid: String? = null,
    val deviceOs: String? = null,
    val verOs: String? = null,
    val deviceModel: String? = null
)