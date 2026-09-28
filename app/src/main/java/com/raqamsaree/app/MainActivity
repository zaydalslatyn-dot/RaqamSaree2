package com.raqamsaree.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * بلا واجهة: يستقبل ضغطة بيكسبي، ويبدّل وضع التحديد بالخدمة.
 * إذا الخدمة مو مفعّلة، يفتح لك إعدادات إمكانية الوصول.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlePress()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePress()
    }

    private fun handlePress() {
        val service = RaqamAccessibilityService.instance
        if (service == null) {
            Toast.makeText(
                this,
                "فعّل خدمة \"رقم سريع\" من الخدمات المثبتة، ثم اضغط بيكسبي من جديد",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            service.toggleSelectionMode()
        }
        finish()
    }
}
