package com.adamate.aaforaaos

import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import com.adamate.aaforaaos.connection.CommManager
import com.adamate.aaforaaos.decoder.AudioDecoder
import com.adamate.aaforaaos.decoder.VideoDecoder
import com.adamate.aaforaaos.utils.Settings

class AppComponent(private val app: App) {

    val settings = Settings(app)
    val videoDecoder = VideoDecoder(settings)
    val audioDecoder = AudioDecoder()

    val notificationManager: NotificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val wifiManager: WifiManager
        get() = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val commManager = CommManager(app, settings, audioDecoder, videoDecoder)
}
