package com.raqamsaree.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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

class RaqamAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RaqamAccessibilityService? = null
        const val LONG_PRESS_MS = 500L
        const val AUTO_EXIT_MS = 10000L
        private val NUMBER_REGEX = Regex("[0-9][0-9/\\-:.,،]*")
    }

    private lateinit var windowManager: WindowManager
    private var overlay: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoExit = Runnable { exitSelectionMode() }

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
        if (overlay == null) enterSelectionMode() else exitSelectionMode()
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
        if (hit == null) {
            toast("ما لقيت نص بهالمكان")
            return
        }
        val rawText = hit.first
        val bounds = hit.second

        if (fullText) {
            copyToClipboard(rawText.trim())
            return
        }

        val lines = rawText.split("\n").filter { it.isNotBlank() }
        val line = if (lines.size <= 1) {
            rawText
        } else {
            val lineHeight = bounds.height().toFloat() / lines.size
            val idx = (((y - bounds.top) / lineHeight).toInt()).coerceIn(0, lines.size - 1)
            lines[idx]
        }

        val converted = toEnglishDigits(line)
        val best = NUMBER_REGEX.findAll(converted)
            .map { it.value.trimEnd('/', '-', ':', '.', ',', '،') }
            .maxByOrNull { it.length }

        if (best.isNullOrEmpty()) {
            toast("ما لقيت أرقام بهالسطر")
        } else {
            copyToClipboard(best)
        }
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

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("raqam", text))
        toast("تم النسخ: $text")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
