package com.v2ray.ang.limm

import android.content.Context
import android.util.Base64
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.util.LogUtil

/**
 * LimmAWGTunnel — AmneziaWG (FR1-awg) userspace tunnel control for the SINGLE existing VpnService.
 *
 * ── Why this is possible (feasibility gate §C.2, RESOLVED POSITIVE) ────────────────────────────
 * Android allows only ONE active VpnService and v2rayNG's [com.v2ray.ang.service.CoreVpnService]
 * already owns it (it calls Builder().establish() and keeps the ParcelFileDescriptor `mInterface`).
 * We CANNOT spawn a second VpnService for AWG.
 *
 * amneziawg-android's high-level `org.amnezia.awg.backend.GoBackend` DOES create its own
 * android.net.VpnService — but only as a *consumer* of the native bridge. The actual Go/JNI entry
 * point is fd-agnostic:
 *
 *     private static native int  awgTurnOn(String ifName, int tunFd, String settings);  // wg-go
 *     private static native void awgTurnOff(int handle);
 *     private static native String awgGetConfig(int handle);
 *
 * i.e. `awgTurnOn` takes a *raw int fd*. GoBackend obtains that fd via `tun.detachFd()` from its own
 * VpnService.Builder().establish(); we instead hand it the fd of CoreVpnService's already-established
 * TUN. So: stop xray-core, then start the AWG userspace tunnel on the SAME tun fd — no 2nd service.
 *
 * ── Wiring status ──────────────────────────────────────────────────────────────────────────────
 * The amneziawg-android `tunnel` library is NOT yet on the classpath (see the TODO block in
 * app/build.gradle.kts for the two wiring options). Until it is, every native call here is made
 * REFLECTIVELY and degrades to a no-op returning false — so the failover ladder safely skips
 * FR1-awg rather than crashing. Once the AAR/JNI is wired, flip [isAvailable] to a real check and
 * the reflective calls resolve to the real `org.amnezia.awg.backend.GoBackend` natives.
 *
 * ── Key handling ───────────────────────────────────────────────────────────────────────────────
 * The client private key is NEVER in git. It is baked at build time from the AWG_CLIENT_PRIVKEY
 * build secret (limm.properties, gitignored) into BuildConfig.LIMM_AWG_PRIVKEY — same mechanism as
 * LIMM_TOKEN / LIMM_VLESS_UUID. The obfuscation params (Jc/Jmin/Jmax/S1/S2/H1-H4) are non-secret and
 * baked in clear; they MUST match the server (awg0.conf) and mirror C:\_vpn\client\awg-fr1.conf.
 */
object LimmAWGTunnel {

    private const val TAG = "LimmAWGTunnel"

    /** Logical interface name passed to awgTurnOn (cosmetic, shows up in wg-go logs). */
    private const val IF_NAME = "awg-fr1"

    /** Native backend class from amneziawg-android once it is on the classpath. */
    private const val BACKEND_CLASS = "org.amnezia.awg.backend.GoBackend"

    /** Handle returned by awgTurnOn (>=0 when up). -1 = down. Guarded by [lock]. */
    @Volatile
    private var tunnelHandle: Int = -1

    private val lock = Any()

    /** True while the AWG userspace tunnel owns the TUN fd (xray stopped, AWG up). */
    val isActive: Boolean get() = tunnelHandle >= 0

    /**
     * True if the native amneziawg-android backend is actually present on the classpath.
     * While false, [startTunnel] no-ops and the ladder must NOT route to FR1-awg (LimmFailover
     * filters the ladder by this — see LimmFailover.activeLadder()).
     */
    val isAvailable: Boolean by lazy {
        try {
            Class.forName(BACKEND_CLASS)
            // Loading the shared lib is the backend's responsibility (SharedLibraryLoader "wg-go").
            // Presence of the class is our cheap gate; real availability is confirmed on first start.
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Build the wg-userspace UAPI config string (key=value lines) that awgTurnOn expects.
     * This mirrors org.amnezia.awg.config.Config#toAwgUserspaceString output so we can construct it
     * directly from BuildConfig without importing the config parser. Keys are hex/base64 as the
     * userspace API requires; AmneziaWG obfuscation params are appended on the interface section.
     *
     * WireGuard UAPI (and amneziawg-go) require keys as lowercase hex, not base64.
     * BuildConfig bakes them as base64 (from limm.properties/CI secret) → convert here.
     */
    private fun String.b64toHex(): String =
        Base64.decode(this, Base64.DEFAULT).joinToString("") { "%02x".format(it) }

    private fun buildUapiConfig(): String {
        val priv = BuildConfig.LIMM_AWG_PRIVKEY.b64toHex()
        val peerPub = BuildConfig.LIMM_AWG_SERVER_PUBKEY.b64toHex()
        val endpoint = BuildConfig.LIMM_AWG_ENDPOINT
        // UAPI uses snake_case keys; AmneziaWG adds jc/jmin/jmax/s1/s2/h1..h4 on the interface.
        return buildString {
            append("private_key=").append(priv).append('\n')
            append("jc=").append(BuildConfig.LIMM_AWG_JC).append('\n')
            append("jmin=").append(BuildConfig.LIMM_AWG_JMIN).append('\n')
            append("jmax=").append(BuildConfig.LIMM_AWG_JMAX).append('\n')
            append("s1=").append(BuildConfig.LIMM_AWG_S1).append('\n')
            append("s2=").append(BuildConfig.LIMM_AWG_S2).append('\n')
            append("h1=").append(BuildConfig.LIMM_AWG_H1).append('\n')
            append("h2=").append(BuildConfig.LIMM_AWG_H2).append('\n')
            append("h3=").append(BuildConfig.LIMM_AWG_H3).append('\n')
            append("h4=").append(BuildConfig.LIMM_AWG_H4).append('\n')
            append("replace_peers=true").append('\n')
            append("public_key=").append(peerPub).append('\n')
            append("endpoint=").append(endpoint).append('\n')
            append("persistent_keepalive_interval=25").append('\n')
            append("allowed_ip=0.0.0.0/0").append('\n')
        }
    }

    /**
     * Start the AWG userspace tunnel on an EXISTING TUN fd (from CoreVpnService.mInterface).
     *
     * Caller contract (see LimmFailover FR1-awg path):
     *   1. xray-core must already be STOPPED (it owned the fd; we take it over).
     *   2. [tunFd] is the int fd of the live ParcelFileDescriptor — do NOT detach/close it here;
     *      ownership/lifecycle stays with CoreVpnService.
     *
     * @return true if the native tunnel came up (handle >= 0), false if unavailable or failed.
     */
    fun startTunnel(ctx: Context, tunFd: Int): Boolean {
        if (!isAvailable) {
            LogUtil.w(TAG, "AWG backend not on classpath — FR1-awg unavailable, skipping")
            return false
        }
        if (BuildConfig.LIMM_AWG_PRIVKEY.isEmpty()) {
            LogUtil.w(TAG, "AWG private key not baked (AWG_CLIENT_PRIVKEY missing) — cannot start")
            return false
        }
        synchronized(lock) {
            if (tunnelHandle >= 0) {
                LogUtil.i(TAG, "AWG tunnel already up (handle=$tunnelHandle)")
                return true
            }
            return try {
                val cfg = buildUapiConfig()
                // Reflective call to the native bridge until the AAR is compile-time wired.
                // Real signature: int awgTurnOn(String ifName, int tunFd, String settings)
                val backendCls = Class.forName(BACKEND_CLASS)
                val m = backendCls.getDeclaredMethod(
                    "awgTurnOn",
                    String::class.java, Int::class.javaPrimitiveType, String::class.java
                )
                m.isAccessible = true
                val handle = m.invoke(null, IF_NAME, tunFd, cfg) as Int
                if (handle < 0) {
                    LogUtil.e(TAG, "awgTurnOn returned $handle — tunnel did not come up")
                    return false
                }
                tunnelHandle = handle
                LogUtil.i(TAG, "AWG userspace tunnel UP on fd=$tunFd (handle=$handle)")
                true
            } catch (e: Throwable) {
                LogUtil.e(TAG, "Failed to start AWG tunnel: ${e.message}", e)
                tunnelHandle = -1
                false
            }
        }
    }

    /**
     * Stop the AWG userspace tunnel (releases the fd back). Call this BEFORE restarting xray on the
     * same fd when failing over off FR1-awg. Idempotent.
     */
    fun stopTunnel() {
        synchronized(lock) {
            val h = tunnelHandle
            if (h < 0) return
            try {
                val backendCls = Class.forName(BACKEND_CLASS)
                val m = backendCls.getDeclaredMethod("awgTurnOff", Int::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(null, h)
                LogUtil.i(TAG, "AWG userspace tunnel DOWN (handle=$h)")
            } catch (e: Throwable) {
                LogUtil.e(TAG, "Failed to stop AWG tunnel: ${e.message}", e)
            } finally {
                tunnelHandle = -1
            }
        }
    }
}
