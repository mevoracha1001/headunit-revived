package com.adamate.aaforaaos.connection

import android.content.Context
import com.adamate.aaforaaos.utils.AppLog
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * In-app mDNS service advertiser using JmDNS.
 *
 * Replaces NsdManager for _aawireless._tcp registration. No GMS or system
 * dependency — pure Java mDNS. Use when NsdManager is restricted or unavailable.
 *
 * Requires MulticastLock to be held (caller's responsibility).
 */
class JmdnsServiceAdvertiser(private val context: Context) {

    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null

    /**
     * Registers _aawireless._tcp on [port].
     */
    fun register(port: Int, serviceName: String = "AAWireless"): Boolean {
        try {
            val bindAddr = getLocalNetworkAddress() ?: InetAddress.getLocalHost()
            val info = ServiceInfo.create(
                "_aawireless._tcp.",
                serviceName,
                port,
                0,
                0,
                false,
                emptyMap<String, Any>()
            )
            serviceInfo = info

            jmdns = JmDNS.create(bindAddr).apply {
                registerService(info)
            }
            AppLog.i("JmDNS: Registered _aawireless._tcp ($serviceName) on port $port (bind: $bindAddr)")
            return true
        } catch (e: Exception) {
            AppLog.e("JmDNS: Registration failed", e)
            return false
        }
    }

    private fun getLocalNetworkAddress(): InetAddress? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (addr is Inet4Address) return addr
                }
            }
            null
        } catch (e: Exception) {
            AppLog.w("JmDNS: Could not get local address", e)
            null
        }
    }

    fun unregister() {
        try {
            serviceInfo?.let { info ->
                jmdns?.unregisterService(info)
                AppLog.i("JmDNS: Unregistered _aawireless._tcp")
            }
            jmdns?.close()
        } catch (e: Exception) {
            AppLog.w("JmDNS: Unregister error", e)
        } finally {
            jmdns = null
            serviceInfo = null
        }
    }
}
