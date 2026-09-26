package com.raqamsaree.app

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.raqamsaree.app.START"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "raqam_saree_channel"
        var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // حالة الأداة — نفس أسماء المتغيرات بالمعاينة عشان يسهل تتبعها
    private var ready = false
    private var fullText = false
    private var stickyCopy = false
    private var indicatorShown = false

    // الفأرة (زر عائم دائم)
    private var bubbleView: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var textIndicator: TextView? = null
    private var stayIndicator: TextView? = null

    // الطبقة الشفافة اللي تظهر بس وقت "جاهز" لالتقاط أي نقطة بالشاشة
    private var catcherView: View? = null

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            startForegroundNotification()
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (resultData != null) setupProjection(resultCode, resultData)
            setupWindowManagerAndMetrics()
            addBubble()
            isRunning = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeCatcher()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        isRunning = false
    }

    /* ---------------- إشعار الخدمة الأمامية (إلزامي لالتقاط الشاشة) ---------------- */
    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "رقم سريع", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompatBuilder(this).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun setupProjection(resultCode: Int, resultData: Intent) {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mgr.getMediaProjection(resultCode, resultData)
    }

    private fun setupWindowManagerAndMetrics() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "RaqamSareeCapture", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    /* ---------------- الفأرة: زر عائم قابل للسحب ---------------- */
    private fun addBubble() {
        val ctx = this
        val root = FrameLayout(ctx)

        val bubble = TextView(ctx).apply {
            text = "✦"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.circle_button_blue)
        }
        val bubbleSize = dp(58)
        root.addView(bubble, FrameLayout.LayoutParams(bubbleSize, bubbleSize))

        val indicatorSize = dp(48)
        val text = TextView(ctx).apply {
            text = "نص"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = getDrawable(R.drawable.indicator_bg)
            visibility = View.GONE
        }
        val textLp = FrameLayout.LayoutParams(indicatorSize, indicatorSize)
        textLp.gravity = Gravity.CENTER_HORIZONTAL
        textLp.topMargin = -dp(58)
        root.addView(text, textLp)
        textIndicator = text

        val stay = TextView(ctx).apply {
            text = "قفل"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = getDrawable(R.drawable.indicator_bg)
            visibility = View.GONE
        }
        val stayLp = FrameLayout.LayoutParams(indicatorSize, indicatorSize)
        stayLp.gravity = Gravity.CENTER_HORIZONTAL
        stayLp.topMargin = -dp(116)
        root.addView(stay, stayLp)
        stayIndicator = stay

        val lp = WindowManager.LayoutParams(
            bubbleSize, bubbleSize + dp(140),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = screenWidth - bubbleSize - dp(24)
        lp.y = (screenHeight * 0.55).toInt()
        bubbleParams = lp
        bubbleView = root

        attachDrag(bubble, root, lp)
        text.setOnClickListener { toggleFullText() }
        stay.setOnClickListener { toggleSticky() }

        windowManager.addView(root, lp)
    }

    private fun toggleFullText() {
        fullText = !fullText
        indicatorShown = false
        if (fullText) ready = true
        applyUiState()
        toast(if (fullText) "خيار النص مفعّل — تنسخ نص وأرقام معاً" else "خيار النص متوقف — تنسخ أرقام فقط")
    }

    private fun toggleSticky() {
        stickyCopy = !stickyCopy
        indicatorShown = false
        if (stickyCopy) ready = true
        applyUiState()
        toast(if (stickyCopy) "النسخ المستمر مفعّل — تقدر تنسخ عدة أرقام بدون إعادة التفعيل" else "النسخ المستمر متوقف")
    }

    private fun applyUiState() {
        val bubble = bubbleView?.getChildAt(0) as? TextView
        bubble?.background = getDrawable(if (ready) R.drawable.circle_button_green else R.drawable.circle_button_blue)
        textIndicator?.visibility = if (indicatorShown) View.VISIBLE else View.GONE
        textIndicator?.background = getDrawable(if (fullText) R.drawable.indicator_bg_on else R.drawable.indicator_bg)
        stayIndicator?.visibility = if (indicatorShown) View.VISIBLE else View.GONE
        stayIndicator?.background = getDrawable(if (stickyCopy) R.drawable.indicator_bg_on else R.drawable.indicator_bg)

        if (ready) addCatcher() else removeCatcher()
    }

    /* ---------------- سحب الفأرة + تمييز ضغطة عادية عن ضغطة طويلة ---------------- */
    private fun attachDrag(handle: View, root: View, lp: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        var downTime = 0L
        val longPressMs = 260L

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX)
                    val dy = (event.rawY - touchY)
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        windowManager.updateViewLayout(root, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        val duration = System.currentTimeMillis() - downTime
                        if (duration >= longPressMs) {
                            indicatorShown = !indicatorShown
                            applyUiState()
                        } else {
                            ready = !ready
                            applyUiState()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /* ---------------- الطبقة الشفافة اللي تلتقط أول ضغطة بأي مكان بالشاشة وهي بوضع "جاهز" ---------------- */
    private fun addCatcher() {
        if (catcherView != null) return
        val v = View(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        v.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                captureAndCopy(event.rawX.toInt(), event.rawY.toInt())
            }
            false // نسيب اللمسة تكمل لتحت عشان ما نعطّل التطبيق الثاني
        }
        catcherView = v
        runCatching { windowManager.addView(v, lp) }
    }

    private fun removeCatcher() {
        catcherView?.let { runCatching { windowManager.removeView(it) } }
        catcherView = null
    }

    /* ---------------- التقاط جزء من الشاشة حوالين نقطة الضغط + قراءة الرقم منه (OCR) ---------------- */
    private fun captureAndCopy(x: Int, y: Int) {
        val reader = imageReader ?: return
        val image: Image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: run { toast("تعذر التقاط الشاشة، حاول مرة ثانية"); return }

        val bitmap = imageToBitmap(image)
        image.close()

        // نقص منطقة صغيرة حوالين نقطة الضغط (تقريباً عرض السطر) عشان دقة القراءة تكون أفضل
        val cropW = (screenWidth * 0.85).toInt()
        val cropH = dp(90)
        val left = (x - cropW / 2).coerceIn(0, screenWidth - cropW)
        val top = (y - cropH / 2).coerceIn(0, screenHeight - cropH)
        val cropped = Bitmap.createBitmap(bitmap, left, top, cropW.coerceAtMost(bitmap.width - left), cropH.coerceAtMost(bitmap.height - top))

        val input = InputImage.fromBitmap(cropped, 0)
        recognizer.process(input)
            .addOnSuccessListener { visionText ->
                val raw = visionText.text
                val result = if (fullText) DigitUtils.convertDigitsToEnglish(raw) else DigitUtils.extractDigitsOnly(raw)
                if (result.isBlank()) {
                    toast("ما لقيت رقم بهالمكان، جرّب تضغط بالضبط على الرقم")
                } else {
                    copyToClipboard(result)
                    if (!stickyCopy) {
                        ready = false
                        mainHandler.post { applyUiState() }
                    }
                }
            }
            .addOnFailureListener {
                toast("تعذرت قراءة الرقم، جرّب مرة ثانية")
            }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("رقم سريع", text))
        toast("تم النسخ: $text")
    }

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

/** بناء إشعار بسيط بدون الاعتماد على AndroidX Core مباشرة، لتفادي تعارضات نسخة قديمة */
private class NotificationCompatBuilder(private val ctx: Context) {
    fun build(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(ctx, OverlayService.CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(ctx)
        builder.setContentTitle("رقم سريع شغّال")
            .setContentText("اضغط لإيقاف الأداة من التطبيق")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
        return builder.build()
    }
}
