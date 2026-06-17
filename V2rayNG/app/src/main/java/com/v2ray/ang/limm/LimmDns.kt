package com.v2ray.ang.limm

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * IPv4-only resolver for limm probes (check-in / Full Test / log).
 *
 * Why: the egress/dest probes run through the local SOCKS inbound. OkHttp resolves the target
 * hostname locally and hands the resolved IP to the SOCKS proxy. If it resolves to an IPv6
 * address, the node (only DE1 has global IPv6) egresses over IPv6 — the reported egress then
 * falls outside the IPv4 node set and the dashboard shows a false `tunnel_broken` (E-152).
 * Forcing IPv4 here keeps every check-in / Full Test egress on IPv4, matching the node IP set.
 * (Server xray already uses domainStrategy=UseIPv4, but that only applies when xray itself
 * resolves a domain — not when the client pre-resolves to an IPv6 literal.)
 */
object LimmDns {
    val IPV4_ONLY = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val v4 = all.filterIsInstance<Inet4Address>()
            // Fallback to the full list only if the host has no A record at all (IPv6-only host),
            // so we never break reachability — our probe hosts all have IPv4.
            return if (v4.isNotEmpty()) v4 else all
        }
    }
}
