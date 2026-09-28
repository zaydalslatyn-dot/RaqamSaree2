package com.raqamsaree.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val projectionRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START
                putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } else {
            Toast.makeText(this, "لازم توافق على إذن مشاركة الشاشة عشان تشتغل الأداة", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlePress()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePress()
    }

    private fun handlePress() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "خطوة أولى: فعّل صلاحية \"الظهور فوق التطبيقات\" ثم اضغط الزر من جديد",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            finish()
            return
        }

        if (!OverlayService.isRunning) {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            projectionRequest.launch(mgr.createScreenCaptureIntent())
            return
        }

        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_TOGGLE
        }
        startService(intent)
        finish()
    }
}
