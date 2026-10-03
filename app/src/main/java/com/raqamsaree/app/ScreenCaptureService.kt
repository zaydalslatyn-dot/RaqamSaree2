package com.raqamsaree.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

class ScreenCaptureService : Service() {

    companion object {
        var instance: ScreenCaptureService? = null
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "raqam_capture"
        private const val NOTIF_ID = 1001
        private const val TIMEOUT_MS = 1500L
    }

    private var projection: MediaProjection? = null
    private val handler = Handler(Looper.getMainLooper())
    private val captureHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data != null && code != 0) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection?.stop()
            projection = mgr.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    projection = null
                }
            }, handler)
            instance = this
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "رقم سريع", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("رقم سريع")
            .setContentText("جاهز لقراءة الأرقام من الصور")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    fun isReady(): Boolean = projection != null

    fun captureOnce(callback: (Bitmap?) -> Unit) {
        val proj = projection
        if (proj == null) {
            callback(null)
            return
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var display: VirtualDisplay? = null
        var done = false

        fun finish(bmp: Bitmap?) {
            if (done) return
            done = true
            captureHandler.removeCallbacksAndMessages(null)
            runCatching { display?.release() }
            runCatching { reader.close() }
            callback(bmp)
        }

        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage()
            if (img != null) {
                val bmp = runCatching { imageToBitmap(img, w, h) }.getOrNull()
                img.close()
                finish(bmp)
            }
        }, handler)

        display = runCatching {
            proj.createVirtualDisplay(
                "raqam", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
        }.getOrNull()

        if (display == null) {
            finish(null)
            return
        }
        captureHandler.postDelayed({ finish(null) }, TIMEOUT_MS)
    }

    private fun imageToBitmap(img: Image, w: Int, h: Int): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w
        val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, w, h)
    }

    override fun onDestroy() {
        projection?.stop()
        projection = null
        instance = null
        super.onDestroy()
    }
}
