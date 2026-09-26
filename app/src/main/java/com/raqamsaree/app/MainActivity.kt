package com.raqamsaree.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var mainButton: Button

    private val projectionRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // عندنا إذن التقاط الشاشة — نبدأ الخدمة ونمررها له
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START
                putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            updateStatus(true)
        } else {
            Toast.makeText(this, "لازم توافق على إذن مشاركة الشاشة عشان تشتغل الأداة", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        mainButton = findViewById(R.id.mainButton)

        mainButton.setOnClickListener {
            if (OverlayService.isRunning) {
                stopService(Intent(this, OverlayService::class.java))
                updateStatus(false)
            } else {
                startFlow()
            }
        }

        updateStatus(OverlayService.isRunning)
    }

    override fun onResume() {
        super.onResume()
        updateStatus(OverlayService.isRunning)
    }

    private fun startFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "خطوة أولى: فعّل صلاحية \"الظهور فوق التطبيقات\"", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projectionRequest.launch(mgr.createScreenCaptureIntent())
    }

    private fun updateStatus(on: Boolean) {
        statusText.text = if (on) "مفعل" else "غير مفعل"
        mainButton.text = if (on) "إيقاف" else "تشغيل"
    }
}
