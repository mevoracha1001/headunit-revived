package com.adamate.aaforaaos.utils

import android.content.Context
import android.content.pm.PackageManager

/**
 * Utilities for Android Automotive OS (AAOS) compatibility.
 *
 * On AAOS, the device is already in car mode and may not have USB host support.
 * This class helps adapt behavior for head-unit-only deployments.
 */
object AutomotiveUtils {

    /**
     * Returns true if the app is running on Android Automotive OS (embedded head unit).
     * AAOS devices have [PackageManager.FEATURE_AUTOMOTIVE] and typically lack USB host.
     */
    fun isAutomotiveOs(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    }

    /**
     * Returns true if USB host/accessory mode is available.
     * On AAOS, USB is often unavailable (no phone connection via cable).
     */
    fun hasUsbHost(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
    }
}
