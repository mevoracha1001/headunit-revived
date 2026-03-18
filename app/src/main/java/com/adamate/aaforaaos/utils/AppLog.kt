package com.adamate.aaforaaos.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.adamate.aaforaaos.App
import com.adamate.aaforaaos.R
import java.util.IllegalFormatException
import java.util.Locale

object AppLog {

    const val ERROR_CHANNEL_ID = "headunit_errors"
    private const val ERROR_NOTIFICATION_ID = 0xE11E

    interface Logger {
        fun println(priority: Int, tag: String, msg: String)

        class Android : Logger {
            override fun println(priority: Int, tag: String, msg: String) {
                Log.println(priority, TAG, msg)
            }
        }

        class StdOut : Logger {
            override fun println(priority: Int, tag: String, msg: String) {
                println("[$tag:$priority] $msg")
            }
        }
    }

    private var appContext: Context? = null
    private var settings: Settings? = null

    fun init(context: Context, settings: Settings) {
        this.appContext = context.applicationContext
        this.settings = settings
    }

    var LOGGER: Logger = Logger.Android()
    private val LOG_LEVEL get() = settings?.logLevel ?: Log.INFO

    const val TAG = "HUREV"
    // LOG_LEVEL constants should not longer be needed because we check the setting directly.
    val LOG_VERBOSE get() = LOG_LEVEL <= Log.VERBOSE
    val LOG_DEBUG get() = LOG_LEVEL <= Log.DEBUG

    fun i(msg: String) {
        log(Log.INFO, format(msg))
    }

    fun i(msg: String, vararg params: Any) {
        log(Log.INFO, format(msg, *params))
    }

    fun e(msg: String?) {
        loge(format(msg ?: "Unknown error"), null)
    }

    fun e(msg: String, tr: Throwable) {
        loge(format(msg), tr)
    }

    fun e(tr: Throwable) {
        loge(tr.message ?: "Unknown error", tr)
    }


    fun e(msg: String?, vararg params: Any) {
        loge(format(msg ?: "Unknown error", *params), null)
    }

    fun v(msg: String, vararg params: Any) {
        log(Log.VERBOSE, format(msg, *params))
    }

    fun d(msg: String, vararg params: Any) {
        log(Log.DEBUG, format(msg, *params))
    }

    fun d(msg: String) {
        log(Log.DEBUG, format(msg))
    }

    fun w(msg: String) {
        log(Log.WARN, format(msg))
    }

    fun w(msg: String, vararg params: Any) {
        log(Log.WARN, format(msg, *params))
    }

    private fun log(priority: Int, msg: String) {
        if (priority >= LOG_LEVEL) {
            LOGGER.println(priority, TAG, msg)
        }
    }

    private fun loge(message: String, tr: Throwable?) {
        val trace = if (LOGGER is Logger.Android) Log.getStackTraceString(tr) else ""
        val fullMsg = message + if (trace.isNotEmpty()) "\n$trace" else ""
        LOGGER.println(Log.ERROR, TAG, fullMsg)

        if (settings?.showErrorNotifications == true) {
            showErrorNotification(message, tr)
        }
    }

    private fun showErrorNotification(message: String, tr: Throwable?) {
        val ctx = appContext ?: return
        val displayMsg = buildString {
            append(message.take(200))
            if (message.length > 200) append("…")
            tr?.message?.let { append("\n").append(it.take(100)) }
        }
        try {
            val notification = NotificationCompat.Builder(ctx, ERROR_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_aa)
                .setContentTitle(ctx.getString(R.string.error_notification_title))
                .setContentText(displayMsg)
                .setStyle(NotificationCompat.BigTextStyle().bigText(displayMsg))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            App.provide(ctx).notificationManager.notify(ERROR_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show error notification", e)
        }
    }


    private fun format(msg: String, vararg array: Any): String {
        var formatted: String
        if (array.isEmpty()) {
            formatted = msg
        } else try {
            formatted = String.format(Locale.US, msg, *array)
        } catch (ex: IllegalFormatException) {
            e("IllegalFormatException: formatString='%s' numArgs=%d", msg, array.size)
            formatted = "$msg (An error occurred while formatting the message.)"
        }
        val stackTrace = Throwable().fillInStackTrace().stackTrace
        var string = "<unknown>"
        for (i in 2 until stackTrace.size) {
            val className = stackTrace[i].className
            if (className != AppLog::class.java.name) {
                val substring = className.substring(1 + className.indexOfLast { a -> a == 46.toChar() })
                string = substring.substring(1 + substring.indexOfLast { a -> a == 36.toChar() }) + "." + stackTrace[i].methodName
                break
            }
        }
        return String.format(Locale.US, "[%d] %s | %s", Thread.currentThread().id, string, formatted)
    }

    fun i(intent: Intent) {
        i(intent.toString())
        val ex = intent.extras
        if (ex != null) {
            i(ex.toString())
        }
    }
}

