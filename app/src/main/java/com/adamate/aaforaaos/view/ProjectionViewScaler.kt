package com.adamate.aaforaaos.view

import android.view.View
import com.adamate.aaforaaos.App
import com.adamate.aaforaaos.utils.AppLog
import com.adamate.aaforaaos.utils.HeadUnitScreenConfig

object ProjectionViewScaler {

    fun updateScale(view: View, videoWidth: Int, videoHeight: Int) {
        if (videoWidth == 0 || videoHeight == 0 || view.width == 0 || view.height == 0) {
            return
        }

        // The dimensions of the content area we want to display
        val displayMetrics = view.resources.displayMetrics
        val contentWidth = displayMetrics.widthPixels
        val contentHeight = displayMetrics.heightPixels

        HeadUnitScreenConfig.init(view.context, displayMetrics, App.provide(view.context).settings)

        // The dimensions of the content area we want to display
        val finalScaleX = HeadUnitScreenConfig.getScaleX()
        val finalScaleY = HeadUnitScreenConfig.getScaleY()

        view.scaleX = finalScaleX
        view.scaleY = finalScaleY
        AppLog.i("ProjectionViewScaler: Dimensions: Video: ${videoWidth}x$videoHeight, Content: ${contentWidth}x$contentHeight, View: ${view.width}x${view.height}")
        AppLog.i("ProjectionViewScaler: Scale updated for view ${view.javaClass.simpleName}. scaleX: $finalScaleX, scaleY: $finalScaleY")
    }
}
