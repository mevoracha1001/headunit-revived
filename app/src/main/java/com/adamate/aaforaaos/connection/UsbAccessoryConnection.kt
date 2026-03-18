package com.adamate.aaforaaos.connection

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import com.adamate.aaforaaos.utils.AppLog
import com.adamate.aaforaaos.utils.AutomotiveUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UsbAccessoryConnection(
    private val usbMgr: UsbManager,
    private val device: UsbDevice,
    private val context: Context? = null
) : AccessoryConnection {

    private val isAaos: Boolean get() = context?.let { AutomotiveUtils.isAutomotiveOs(it) } ?: false
    // @Volatile so isConnected / isDeviceRunning see the latest value without a lock.
    @Volatile private var usbDeviceConnected: UsbDeviceCompat? = null
    @Volatile private var usbDeviceConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    // @Volatile so sendBlocking / recvBlocking see updates from connect() / resetInterface()
    // without holding sStateLock during the transfer.
    @Volatile private var endpointIn: UsbEndpoint? = null
    @Volatile private var endpointOut: UsbEndpoint? = null

    // Internal buffer — 32KB for car USB (often higher latency). Only accessed by poll thread.
    private val internalBuffer = ByteArray(32768)
    private var internalBufferPos = 0
    private var internalBufferAvailable = 0

    fun isDeviceRunning(device: UsbDevice): Boolean {
        synchronized(sStateLock) {
            val connected = usbDeviceConnected ?: return false
            return UsbDeviceCompat.getUniqueName(device) == connected.uniqueName
        }
    }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        return@withContext try {
            connect(device)
        } catch (e: UsbOpenException) {
            AppLog.e(e)
            false
        }
    }

    @Throws(UsbOpenException::class)
    private fun connect(device: UsbDevice): Boolean {
        if (usbDeviceConnection != null) {
            disconnect()
        }
        synchronized(sStateLock) {
            try {
                usbOpen(device)
            } catch (e: UsbOpenException) {
                disconnect()
                throw e
            }

            val ret = initEndpoint()
            if (ret < 0) {
                disconnect()
                return false
            }

            usbDeviceConnected = UsbDeviceCompat(device)
            return true
        }
    }

    @Throws(UsbOpenException::class)
    private fun usbOpen(device: UsbDevice) {
        var connection: UsbDeviceConnection? = null
        var lastError: Throwable? = null

        // Car USB ports can be slow to enumerate; retry with increasing delay.
        // On AAOS (GM cars), use more retries and longer delays.
        val maxAttempts = if (isAaos) 7 else 5
        for (i in 0 until maxAttempts) {
            try {
                connection = usbMgr.openDevice(device)
                if (connection != null) break
            } catch (t: Throwable) {
                lastError = t
                AppLog.w("Attempt ${i+1}/$maxAttempts to openDevice failed: ${t.message}")
            }
            if (i < maxAttempts - 1) {
                val delayMs = if (isAaos) 400L + (i * 500L) else 300L + (i * 400L)
                try { Thread.sleep(delayMs) } catch (_: Exception) {}
            }
        }

        usbDeviceConnection = connection ?: throw UsbOpenException(lastError ?: Throwable("openDevice: connection is null"))

        AppLog.i("Established connection: " + usbDeviceConnection!!)

        try {
            val interfaceCount = device.interfaceCount
            if (interfaceCount <= 0) {
                AppLog.e("interfaceCount: $interfaceCount")
                throw UsbOpenException("No usb interfaces")
            }
            AppLog.i("interfaceCount: $interfaceCount")

            // Find the accessory interface: AOA uses class 0xFF (vendor-specific). Fallback: first
            // interface with bulk IN+OUT. Some devices (ADB+accessory) have interface 0=accessory, 1=ADB.
            usbInterface = findAccessoryInterface(device)
                ?: throw UsbOpenException("No suitable accessory interface (need bulk IN+OUT)")

            val ifaceClass = getInterfaceClassSafe(usbInterface!!)
            AppLog.i("Using accessory interface (class=${if (ifaceClass >= 0) "0x${Integer.toHexString(ifaceClass)}" else "unknown"})")

            if (!usbDeviceConnection!!.claimInterface(usbInterface, true)) {
                throw UsbOpenException("Error claiming interface")
            }
        } catch (e: Throwable) {
            AppLog.e(e)
            throw UsbOpenException(e)
        }
    }

    /** AOA accessory interface is class 0xFF. API 26+ has getInterfaceClass(). */
    private fun getInterfaceClassSafe(iface: UsbInterface): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) iface.interfaceClass else -1

    /** Find interface with bulk IN and OUT endpoints. Prefer class 0xFF (AOA accessory). */
    private fun findAccessoryInterface(device: UsbDevice): UsbInterface? {
        var fallback: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var hasIn = false
            var hasOut = false
            for (j in 0 until iface.endpointCount) {
                when (iface.getEndpoint(j).direction) {
                    UsbConstants.USB_DIR_IN -> hasIn = true
                    UsbConstants.USB_DIR_OUT -> hasOut = true
                }
            }
            if (hasIn && hasOut) {
                fallback = iface
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && iface.interfaceClass == 0xFF) {
                    AppLog.i("Found AOA accessory interface at index $i (class 0xFF)")
                    return iface
                }
            }
        }
        return fallback
    }

    private fun initEndpoint(): Int {
        AppLog.i("Check accessory endpoints")
        endpointIn = null
        endpointOut = null

        for (i in 0 until usbInterface!!.endpointCount) {
            val ep = usbInterface!!.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN) {
                if (endpointIn == null) endpointIn = ep
            } else {
                if (endpointOut == null) endpointOut = ep
            }
        }
        if (endpointIn == null || endpointOut == null) {
            AppLog.e("Unable to find bulk endpoints")
            return -1
        }

        AppLog.i("Connected have EPs")
        return 0
    }

    private fun resetInterface() {
        if (usbDeviceConnection == null) return
        synchronized(sStateLock) {
            val connection = usbDeviceConnection ?: return
            val iface = usbInterface ?: return
            AppLog.w("Attempting USB interface soft-reset...")
            try {
                connection.releaseInterface(iface)
                Thread.sleep(100)
                if (connection.claimInterface(iface, true)) {
                    AppLog.i("USB interface re-claimed successfully")
                    internalBufferPos = 0
                    internalBufferAvailable = 0
                    initEndpoint()
                } else {
                    AppLog.e("Failed to re-claim USB interface — disconnecting")
                    disconnect()
                }
            } catch (e: Exception) {
                AppLog.e("Error during USB reset: ${e.message}")
            }
        }
    }

    override fun disconnect() {
        synchronized(sStateLock) {
            if (usbDeviceConnected != null) {
                AppLog.i(usbDeviceConnected!!.toString())
            }
            endpointIn = null
            endpointOut = null

            if (usbDeviceConnection != null && usbInterface != null) {
                val released = try {
                    usbDeviceConnection!!.releaseInterface(usbInterface)
                } catch (e: Exception) {
                    AppLog.e("Error releaseInterface(): ${e.message}")
                    false
                }
                when {
                    released -> AppLog.i("OK releaseInterface()")
                    else -> AppLog.e("Error releaseInterface()")
                }
            }
            // close() must be called after releaseInterface(); close() releases all resources
            // and aborts any in-flight bulkTransfer(), unblocking sendBlocking/recvBlocking.
            usbDeviceConnection?.close()
            usbDeviceConnection = null
            usbInterface = null
            usbDeviceConnected = null
            internalBufferPos = 0
            internalBufferAvailable = 0
        }
    }

    override val isConnected: Boolean
        get() = usbDeviceConnected != null

    override val isSingleMessage: Boolean
        get() = false

    // Read error tracking — only accessed by the poll thread; no lock needed.
    private var consecutiveReadErrors = 0
    private var firstErrorTimeMs = 0L
    // AAOS/car USB: longer patience for dongle WiFi recovery and slower enumeration
    private val maxErrorDurationBeforeDisconnect = if (isAaos) 90_000L else 60_000L
    private val errorsBeforeReset = if (isAaos) 5 else 3  // Less aggressive reset on car USB

    // Volatile reads capture the latest connection/endpoint references; bulkTransfer runs
    // entirely outside any lock. If disconnect() calls close() concurrently, bulkTransfer
    // returns -1 immediately — a safe, recoverable outcome.
    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        val connection = usbDeviceConnection ?: return -1
        val ep = endpointOut ?: return -1
        return try {
            connection.bulkTransfer(ep, buf, length, timeout)
        } catch (e: Exception) {
            AppLog.e("USB Write Error: ${e.message}")
            -1
        }
    }

    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
        val connection = usbDeviceConnection ?: return -1
        val ep = endpointIn ?: return -1

        return try {
            var totalReturned = 0

            while (totalReturned < length) {
                // 1. Serve from internal buffer if data is available
                if (internalBufferAvailable > 0) {
                    val toCopy = minOf(length - totalReturned, internalBufferAvailable)
                    System.arraycopy(internalBuffer, internalBufferPos, buf, totalReturned, toCopy)
                    internalBufferPos += toCopy
                    internalBufferAvailable -= toCopy
                    totalReturned += toCopy

                    if (totalReturned >= length || !readFully) break
                    continue
                }

                // 2. Internal buffer empty, read from USB. Retry transient -1 (timeout) up to 2x
                // before counting as error — car USB can have brief stalls.
                var read = -1
                for (attempt in 0..2) {
                    read = try {
                        connection.bulkTransfer(ep, internalBuffer, internalBuffer.size, timeout)
                    } catch (e: Exception) {
                        AppLog.e("USB Read Error: ${e.message}")
                        -1
                    }
                    if (read >= 0) break
                    if (attempt < 2) {
                        try { Thread.sleep(30) } catch (_: InterruptedException) {}
                    }
                }

                if (read < 0) {
                    consecutiveReadErrors++
                    if (consecutiveReadErrors == 1) {
                        firstErrorTimeMs = SystemClock.elapsedRealtime()
                    }
                    val errorDurationMs = SystemClock.elapsedRealtime() - firstErrorTimeMs
                    if (errorDurationMs > maxErrorDurationBeforeDisconnect) {
                        AppLog.e("USB read errors persisting for ${errorDurationMs / 1000}s — disconnecting")
                        disconnect()
                        return -1
                    }
                    if (consecutiveReadErrors % 10 == 0) {
                        AppLog.w("USB read errors ($consecutiveReadErrors) for ${errorDurationMs / 1000}s — waiting for recovery...")
                    }
                    // After N consecutive errors, try interface reset — recovers from USB stalls
                    if (consecutiveReadErrors == errorsBeforeReset) {
                        AppLog.i("USB: attempting interface reset after $errorsBeforeReset read errors")
                        resetInterface()
                        try { Thread.sleep(100) } catch (_: InterruptedException) {}
                    } else if (consecutiveReadErrors >= errorsBeforeReset + 2) {
                        try { Thread.sleep(200) } catch (_: InterruptedException) {}
                    }
                    return if (totalReturned > 0) totalReturned else -1
                }
                // If we reach here, read is 0 or positive, meaning no error.
                // Reset error counters if they were active.
                if (consecutiveReadErrors > 0) {
                    AppLog.i("USB reads recovered after $consecutiveReadErrors errors")
                    consecutiveReadErrors = 0
                    firstErrorTimeMs = 0
                }

                if (read == 0) {
                    return totalReturned
                }

                internalBufferPos = 0
                internalBufferAvailable = read
                // Loop will continue and serve from the new internalBuffer data
            }

            totalReturned

        } catch (e: Exception) {
            AppLog.e("USB Read Error: ${e.message}")
            -1
        }
    }

    private class UsbOpenException : Exception {
        constructor(message: String) : super(message)
        constructor(tr: Throwable) : super(tr)
    }

    companion object {
        // Held only during state mutations (connect / disconnect / reset).
        // Neither sendBlocking nor recvBlocking holds this lock during bulkTransfer.
        private val sStateLock = Any()
    }
}
