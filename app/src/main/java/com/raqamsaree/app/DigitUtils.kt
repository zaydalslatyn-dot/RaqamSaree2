package com.raqamsaree.app

/**
 * نفس منطق التحويل المستخدم بمعاينة التصميم (HTML)، منقول بالضبط لكوتلن.
 * جُرّب بعدة حالات: أرقام عربية (٠-٩)، فارسية (۰-۹)، نص مختلط، ونص بدون أي أرقام.
 */
object DigitUtils {

    private val arabicIndic = '\u0660'..'\u0669'   // ٠-٩
    private val persian = '\u06F0'..'\u06F9'        // ۰-۹

    /** يحوّل أي أرقام عربية/فارسية داخل النص إلى أرقام إنجليزية، ويسيب باقي النص كما هو. */
    fun convertDigitsToEnglish(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch in arabicIndic -> sb.append((ch - arabicIndic.first))
                ch in persian -> sb.append((ch - persian.first))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** يطلّع الأرقام فقط من النص (عربي/فارسي/إنجليزي مختلط)، محوّلة كلها لإنجليزي. */
    fun extractDigitsOnly(text: String): String {
        val digitsOnly = text.filter { it.isDigit() || it in arabicIndic || it in persian }
        return convertDigitsToEnglish(digitsOnly)
    }
}
