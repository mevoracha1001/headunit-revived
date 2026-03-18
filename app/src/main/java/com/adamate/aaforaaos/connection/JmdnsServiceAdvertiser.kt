package com.adamate.aaforaaos.connection

import android.os.Build
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
 *
 * Note: mDNS often fails on phone hotspots (multicast blocked). The TCP server
 * still runs; use Manual mode and enter the head unit's IP directly to connect.
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
            // Use warning: mDNS fails on phone hotspots (multicast blocked). TCP server still works;
            // user can connect via Manual mode with IP. Avoid scary error notification.
            AppLog.w("JmDNS: Registration failed (common on phone hotspots). Use Manual mode with IP to connect.")
            AppLog.d("JmDNS: ${e.message}")
            return false
        }
    }

    private fun getLocalNetworkAddress(): InetAddress? {
        // Prefer the active WiFi interface when connected (e.g. to phone hotspot). API 23+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val network = cm?.activeNetwork
                if (network != null) {
                    val caps = cm.getNetworkCapabilities(network)
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        val linkProps = cm.getLinkProperties(network)
                        linkProps?.linkAddresses?.forEach { linkAddr ->
                            val addr = linkAddr.address
                            if (addr is Inet4Address && !addr.isLoopbackAddress) return addr
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.d("JmDNS: Could not get active network address: ${e.message}")
            }
        }

        // Fallback: first non-loopback IPv4
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
