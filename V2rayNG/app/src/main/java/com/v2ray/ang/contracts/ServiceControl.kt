package com.v2ray.ang.contracts

import android.app.Service

interface ServiceControl {
    /**
     * Gets the service instance.
     * @return The service instance.
     */
    fun getService(): Service

    /**
     * Starts the service.
     */
    fun startService()

    /**
     * Stops the service.
     */
    fun stopService()

    /**
     * Protects the VPN socket.
     * @param socket The socket to protect.
     * @return True if the socket is protected, false otherwise.
     */
    fun vpnProtect(socket: Int): Boolean

    /**
     * Returns the raw int fd of the established TUN ParcelFileDescriptor, or -1 if not in VPN mode.
     * Used by the AmneziaWG failover path (MSG_STATE_SWITCH_AWG) to hand the existing TUN fd to
     * the AWG userspace tunnel without opening a second VpnService.
     */
    fun getTunFd(): Int = -1
}
