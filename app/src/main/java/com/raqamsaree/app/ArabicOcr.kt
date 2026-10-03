package com.raqamsaree.app

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

object ArabicOcr {

    private var api: TessBaseAPI? = null

    private val WHITELIST =
        (0x0660..0x0669).map { it.toChar() }.joinToString("") +
            (0x06F0..0x06F9).map { it.toChar() }.joinToString("") +
            "0123456789"

    @Synchronized
    private fun ensureReady(context: Context): TessBaseAPI? {
        api?.let { return it }
        return try {
            val dir = File(context.filesDir, "tessdata")
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, "ara.traineddata")
            if (!target.exists() || target.length() == 0L) {
                context.assets.open("tessdata/ara.traineddata").use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                }
            }
            val t = TessBaseAPI()
            val ok = t.init(context.filesDir.absolutePath, "ara")
            if (!ok) {
                t.recycle()
                null
            } else {
                t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
                api = t
                t
            }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun recognizeDigits(context: Context, bmp: Bitmap): String? {
        val t = ensureReady(context) ?: return null
        return try {
            t.setVariable("tessedit_char_whitelist", WHITELIST)
            t.setImage(bmp)
            var text = t.getUTF8Text()
            t.clear()
            if (text.isNullOrBlank() || text.none { it.isDigit() }) {
                t.setVariable("tessedit_char_whitelist", "")
                t.setImage(bmp)
                text = t.getUTF8Text()
                t.clear()
            }
            text
        } catch (e: Exception) {
            null
        }
    }
}
