package com.raqamsaree.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class RaqamAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RaqamAccessibilityService? = null
        const val LONG_PRESS_MS = 500L
        const val AUTO_EXIT_MS = 10000L
        private val NUMBER_REGEX = Regex("[0-9][0-9/\\-:.,،]*")
    }

    private lateinit var windowManager: WindowManager
    private var overlay: View? = null
    private var lastShot: Bitmap? = null
    private var askedForCapture = false
    private val handler = Handler(Looper.getMainLooper())
    private val autoExit = Runnable { exitSelectionMode() }
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        exitSelectionMode()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        exitSelectionMode()
        instance = null
        super.onDestroy()
    }

    fun toggleSelectionMode() {
        if (overlay != null) {
            exitSelectionMode()
            return
        }
        lastShot = null
        val cap = ScreenCaptureService.instance
        if (cap == null || !cap.isReady()) {
            if (!askedForCapture) {
                askedForCapture = true
                val i = Intent(this, CaptureActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                toast("اسمح بالتقاط الشاشة ثم اضغط بكسبي مرة ثانية")
                return
            }
            enterSelectionMode()
            return
        }
        handler.postDelayed({
            cap.captureOnce { bmp ->
                lastShot = bmp
                enterSelectionMode()
            }
        }, 250)
    }

    private fun enterSelectionMode() {
        if (overlay != null) return
        val v = View(this).apply {
            setBackgroundColor(Color.parseColor("#B3000000"))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        var downTime = 0L
        v.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val full = System.currentTimeMillis() - downTime >= LONG_PRESS_MS
                    handleTap(event.rawX.toInt(), event.rawY.toInt(), full)
                    true
                }
                else -> true
            }
        }
        val ok = runCatching { windowManager.addView(v, lp) }.isSuccess
        if (ok) {
            overlay = v
            handler.postDelayed(autoExit, AUTO_EXIT_MS)
        }
    }

    private fun exitSelectionMode() {
        handler.removeCallbacks(autoExit)
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
    }

    private fun handleTap(x: Int, y: Int, fullText: Boolean) {
        val hit = findNodeAt(x, y)
        exitSelectionMode()

        if (fullText) {
            if (hit == null) toast("ما لقيت نص بهالمكان") else copyToClipboard(hit.first.trim(), false)
            return
        }

        var number: String? = null
        if (hit != null) {
            val rawText = hit.first
            val bounds = hit.second
            val lines = rawText.split("\n").filter { it.isNotBlank() }
            val line = if (lines.size <= 1) {
                rawText
            } else {
                val lineHeight = bounds.height().toFloat() / lines.size
                val idx = (((y - bounds.top) / lineHeight).toInt()).coerceIn(0, lines.size - 1)
                lines[idx]
            }
            number = pickBest(cleanNumbers(line))
        }

        if (number != null) {
            deliver(number)
            return
        }
        if (lastShot != null) {
            ocrAt(x, y)
        } else {
            toast(if (hit == null) "ما لقيت نص بهالمكان" else "ما لقيت أرقام بهالسطر")
        }
    }

    private fun ocrAt(x: Int, y: Int) {
        val shot = lastShot ?: return
        val top = (y - 150).coerceAtLeast(0)
        val bottom = (y + 150).coerceAtMost(shot.height)
        if (bottom - top < 20) {
            toast("ما لقيت أرقام بالصورة")
            return
        }
        val strip = Bitmap.createBitmap(shot, 0, top, shot.width, bottom - top)
        val scaled = Bitmap.createScaledBitmap(strip, strip.width * 2, strip.height * 2, true)
        val targetY = (y - top) * 2

        recognizer.process(InputImage.fromBitmap(scaled, 0))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { it.lines }
                val nearest = lines.minByOrNull { l ->
                    val b = l.boundingBox
                    if (b == null) Int.MAX_VALUE else Math.abs(b.centerY() - targetY)
                }
                var number = nearest?.let { pickBest(cleanNumbers(it.text)) }
                if (number == null) {
                    number = pickBest(lines.flatMap { cleanNumbers(it.text) })
                }
                if (number != null) deliver(number) else toast("ما لقيت أرقام بالصورة")
            }
            .addOnFailureListener { toast("فشلت قراءة الصورة") }
    }

    private fun findNodeAt(x: Int, y: Int): Pair<String, Rect>? {
        val root = rootInActiveWindow ?: return null
        var bestText: String? = null
        var bestBounds: Rect? = null
        var bestArea = Long.MAX_VALUE
        val rect = Rect()

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.getBoundsInScreen(rect)
            if (rect.contains(x, y) && node.isVisibleToUser) {
                val t = node.text?.toString() ?: node.contentDescription?.toString()
                if (!t.isNullOrBlank()) {
                    val area = rect.width().toLong() * rect.height().toLong()
                    if (area < bestArea) {
                        bestArea = area
                        bestText = t
                        bestBounds = Rect(rect)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
            }
        }

        visit(root)
        val t = bestText ?: return null
        val b = bestBounds ?: return null
        return Pair(t, b)
    }

    private fun cleanNumbers(s: String): List<String> {
        return NUMBER_REGEX.findAll(toEnglishDigits(s))
            .map { it.value.trimEnd('/', '-', ':', '.', ',', '،') }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun pickBest(candidates: List<String>): String? {
        val valid = candidates.firstOrNull { it.length == 10 && it.all { c -> c.isDigit() } && luhnOk(it) }
        return valid ?: candidates.maxByOrNull { it.length }
    }

    private fun luhnOk(n: String): Boolean {
        var sum = 0
        for (i in n.indices) {
            var d = n[i] - '0'
            if (i % 2 == 0) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    private fun deliver(number: String) {
        val suspicious = number.length == 10 &&
            number.all { it.isDigit() } &&
            (number[0] == '1' || number[0] == '2') &&
            !luhnOk(number)
        copyToClipboard(number, suspicious)
    }

    private fun toEnglishDigits(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                in '\u0660'..'\u0669' -> sb.append('0' + (c - '\u0660'))
                in '\u06F0'..'\u06F9' -> sb.append('0' + (c - '\u06F0'))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun copyToClipboard(text: String, warn: Boolean) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("raqam", text))
        toast(if (warn) "⚠️ تأكد من الرقم: $text" else "تم النسخ: $text")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
