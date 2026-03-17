package com.adamate.aaforaaos.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.PermissionChecker
import com.adamate.aaforaaos.contract.LocationUpdateIntent
import com.adamate.aaforaaos.utils.AppLog

class GpsLocation constructor(private val context: Context): LocationListener {
    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var requested: Boolean = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (requested || locationManager == null) {
            if (locationManager == null) {
                AppLog.w("LocationManager unavailable — GPS location disabled")
            }
            return
        }
        AppLog.i("Request location updates")
        try {
            if (PermissionChecker.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PermissionChecker.PERMISSION_GRANTED
                    && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0.0f, this)
                val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                AppLog.i("Last known location:  ${location?.toString() ?: "Unknown"}")
                requested = true
            }
        } catch (e: Exception) {
            AppLog.w("Could not request location updates: ${e.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        context.sendBroadcast(LocationUpdateIntent(location))
    }

    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
        AppLog.i("$provider: $status")
    }

    override fun onProviderEnabled(provider: String) {
        AppLog.i(provider)
    }

    override fun onProviderDisabled(provider: String) {
        AppLog.i(provider)
    }

    fun stop() {
        AppLog.i("Remove location updates")
        requested = false
        locationManager?.removeUpdates(this)
    }
}
