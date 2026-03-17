package com.adamate.aaforaaos.aap

import android.content.Context
import com.adamate.aaforaaos.connection.AccessoryConnection
import com.adamate.aaforaaos.decoder.MicRecorder
import com.adamate.aaforaaos.main.BackgroundNotification
import com.adamate.aaforaaos.utils.AppLog
import com.adamate.aaforaaos.utils.Settings

internal interface AapRead {
    fun read(): Int

    abstract class Base internal constructor(
            private val connection: AccessoryConnection?,
            internal val ssl: AapSsl,
            internal val handler: AapMessageHandler) : AapRead {

        override fun read(): Int {
            if (connection == null) {
                AppLog.e("No connection.")
                return -1
            }

            return doRead(connection)
        }

        protected abstract fun doRead(connection: AccessoryConnection): Int
    }

    object Factory {
        fun create(connection: AccessoryConnection, transport: AapTransport, recorder: MicRecorder, aapAudio: AapAudio, aapVideo: AapVideo, settings: Settings, notification: BackgroundNotification, context: Context): AapRead {
            val handler = AapMessageHandlerType(transport, recorder, aapAudio, aapVideo, settings, notification, context)

            return if (connection.isSingleMessage)
                AapReadSingleMessage(connection, transport.ssl, handler)
            else
                AapReadMultipleMessages(connection, transport.ssl, handler)
        }
    }
}
