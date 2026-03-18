package com.adamate.aaforaaos.connection

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.adamate.aaforaaos.aap.Utils
import com.adamate.aaforaaos.utils.AppLog
import com.adamate.aaforaaos.utils.AutomotiveUtils

class UsbAccessoryMode(private val usbMgr: UsbManager, private val context: Context? = null) {

    private val isAaos: Boolean get() = context?.let { AutomotiveUtils.isAutomotiveOs(it) } ?: false

    fun connectAndSwitch(device: UsbDevice): Boolean {
        var connection: UsbDeviceConnection? = null
        var lastError: Throwable? = null
        for (attempt in 1..3) {
            try {
                connection = usbMgr.openDevice(device)
                if (connection != null) break
            } catch (e: Throwable) {
                lastError = e
                AppLog.w("connectAndSwitch attempt $attempt: ${e.message}")
            }
            if (attempt < 3) try { Thread.sleep(400L * attempt) } catch (_: Exception) {}
        }

        if (connection == null) {
            lastError?.let { AppLog.e("Cannot open device after 3 attempts", it) }
                ?: AppLog.e("Cannot open device after 3 attempts")
            return false
        }

        return try {
            val result = switch(connection)
            AppLog.i("connectAndSwitch result: $result")
            result
        } finally {
            connection.close()
        }
    }

    private fun switch(connection: UsbDeviceConnection): Boolean {
        // Do accessory negotiation and attempt to switch to accessory mode. Called only by usb_connect()
        val buffer = ByteArray(2)
        var len = connection.controlTransfer(UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR, ACC_REQ_GET_PROTOCOL, 0, 0, buffer, 2, usbTimeoutMs)
        if (len != 2) {
            AppLog.e("Error controlTransfer len: $len")
            return false
        }
        val acc_ver = Utils.getAccVersion(buffer)
        // Get OAP / ACC protocol version
        AppLog.i("Success controlTransfer len: $len  acc_ver: $acc_ver")
        if (acc_ver < 1) {
            // If error or version too low...
            AppLog.e("No support acc")
            return false
        }
        AppLog.i("acc_ver: $acc_ver")

        // Send all accessory identification strings. Abort if any transfer fails — a partial
        // identification (e.g. manufacturer sent but model missing) can cause the phone to
        // ignore the ACC_REQ_START or fail to switch into accessory mode.
        if (!initStringControlTransfer(connection, ACC_IDX_MAN, MANUFACTURER) ||
            !initStringControlTransfer(connection, ACC_IDX_MOD, MODEL) ||
            !initStringControlTransfer(connection, ACC_IDX_DES, DESCRIPTION) ||
            !initStringControlTransfer(connection, ACC_IDX_VER, VERSION) ||
            !initStringControlTransfer(connection, ACC_IDX_URI, URI) ||
            !initStringControlTransfer(connection, ACC_IDX_SER, SERIAL)) {
            return false
        }

        AppLog.i("Sending acc start")
        // Send accessory start request. Device should re-enumerate as an accessory.
        len = connection.controlTransfer(UsbConstants.USB_TYPE_VENDOR, ACC_REQ_START, 0, 0, byteArrayOf(), 0, usbTimeoutMs)

        // len == 0: clean ACK before re-enumeration (expected path).
        // len < 0: phone disconnected before the ACK because it started re-enumerating
        //          immediately upon receipt — the command was still received. Treat as success.
        AppLog.i("Acc start sent (len=$len). Waiting for re-enumeration...")
        val reenumDelayMs = if (isAaos) 800 else 500
        try { Thread.sleep(reenumDelayMs.toLong()) } catch (e: Exception) {}
        return true
    }

    private fun initStringControlTransfer(conn: UsbDeviceConnection, index: Int, string: String): Boolean {
        val len = conn.controlTransfer(UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, index, string.toByteArray(), string.length, usbTimeoutMs)
        return if (len < 0) {
            // Negative means the USB transfer itself failed (e.g. device disconnected or
            // timed out). Abort the switch — ACC_REQ_START would be pointless.
            AppLog.e("Error controlTransfer len: $len  index: $index  string: \"$string\"")
            false
        } else {
            // len == string.length is the ideal ACK. Some phones return 0 for a successful
            // OUT control transfer (they accept the data but report 0 bytes in the data
            // stage). Treat any non-negative return as success; log a warning if unexpected.
            if (len != string.length) {
                AppLog.w("Unexpected controlTransfer len: $len (expected ${string.length})  index: $index  string: \"$string\"")
            } else {
                AppLog.i("Success controlTransfer len: $len  index: $index  string: \"$string\"")
            }
            true
        }
    }

    private val usbTimeoutMs: Int get() = if (isAaos) 900 else 500

    companion object {
        private const val MANUFACTURER = "Android"
        private const val MODEL = "Android Auto"
        private const val DESCRIPTION = "Android Auto"//"Android Open Automotive Protocol"
        private const val VERSION = "2.0.1"
        private const val URI = "https://developer.android.com/auto/index.html"
        private const val SERIAL = "HU-AAAAAA001"

        // Indexes for strings sent by the host via ACC_REQ_SEND_STRING:
        private const val ACC_IDX_MAN = 0
        private const val ACC_IDX_MOD = 1
        private const val ACC_IDX_DES = 2
        private const val ACC_IDX_VER = 3
        private const val ACC_IDX_URI = 4
        private const val ACC_IDX_SER = 5

        // OAP Control requests:
        private const val ACC_REQ_GET_PROTOCOL = 51
        private const val ACC_REQ_SEND_STRING = 52
        private const val ACC_REQ_START = 53
    }
}
