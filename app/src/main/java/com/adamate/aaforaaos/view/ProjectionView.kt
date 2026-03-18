package com.adamate.aaforaaos.view

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.adamate.aaforaaos.App
import com.adamate.aaforaaos.decoder.VideoDecoder
import com.adamate.aaforaaos.utils.AppLog
import com.adamate.aaforaaos.utils.AutomotiveUtils

class ProjectionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), IProjectionView, SurfaceHolder.Callback {

    private val callbacks = mutableListOf<IProjectionView.Callbacks>()
    private var videoDecoder: VideoDecoder? = null
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        videoDecoder = App.provide(context).videoDecoder
        // On AAOS (GM cars), explicit format improves MediaCodec compatibility on restricted devices
        if (AutomotiveUtils.isAutomotiveOs(context)) {
            holder.setFormat(PixelFormat.OPAQUE)
        }
        holder.addCallback(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        videoDecoder?.stop("onDetachedFromWindow")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        AppLog.i("ProjectionView surfaceCreated: $holder")
        callbacks.forEach { c ->
            try {
                c.onSurfaceCreated(holder.surface)
            } catch (e: Exception) {
                AppLog.e("ProjectionView surfaceCreated callback error", e)
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        AppLog.i("ProjectionView surfaceChanged: format=$format width=$width height=$height")
        callbacks.forEach { c ->
            try {
                c.onSurfaceChanged(holder.surface, width, height)
            } catch (e: Exception) {
                AppLog.e("ProjectionView surfaceChanged callback error", e)
            }
        }
        try {
            ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
        } catch (e: Exception) {
            AppLog.e("ProjectionView updateScale error", e)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AppLog.i("ProjectionView surfaceDestroyed: $holder")
        try {
            videoDecoder?.stop("surfaceDestroyed")
        } catch (e: Exception) {
            AppLog.e("ProjectionView stop decoder error", e)
        }
        callbacks.forEach { c ->
            try {
                c.onSurfaceDestroyed(holder.surface)
            } catch (e: Exception) {
                AppLog.e("ProjectionView surfaceDestroyed callback error", e)
            }
        }
    }

    override fun addCallback(callback: IProjectionView.Callbacks) {
        callbacks.add(callback)
        if (holder.surface.isValid) {
            callback.onSurfaceCreated(holder.surface)
            callback.onSurfaceChanged(holder.surface, width, height)
        }
    }

    override fun removeCallback(callback: IProjectionView.Callbacks) {
        callbacks.remove(callback)
    }

    override fun setVideoSize(width: Int, height: Int) {
        if (videoWidth == width && videoHeight == height) return
        AppLog.i("ProjectionView", "Video size set to ${width}x$height")
        videoWidth = width
        videoHeight = height
        ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
    }

    override fun setVideoScale(scaleX: Float, scaleY: Float) {
        this.scaleX = scaleX
        this.scaleY = scaleY
    }
}