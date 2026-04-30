package com.tac.lines

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var pm: MediaProjectionManager
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        tvStatus = findViewById(R.id.tvStatus)
        findViewById<Button>(R.id.btnStart).setOnClickListener { checkStart() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, LineService::class.java))
            tvStatus.text = "Parado"
            tvStatus.setTextColor(0xFFAAAAAA.toInt())
        }
    }

    override fun onResume() {
        super.onResume()
        tvStatus.text = "Pronto para iniciar"
        tvStatus.setTextColor(0xFF00FF88.toInt())
    }

    private fun checkStart() {
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), 1002)
            return
        }
        startActivityForResult(pm.createScreenCaptureIntent(), 1001)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            1002 -> if (Settings.canDrawOverlays(this)) checkStart()
            1001 -> if (resultCode == Activity.RESULT_OK && data != null) {
                val i = Intent(this, LineService::class.java).apply {
                    putExtra("code", resultCode)
                    putExtra("data", data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(i)
                else startService(i)
                tvStatus.text = "Ativo! Abra o jogo."
                tvStatus.setTextColor(0xFF00FF88.toInt())
                Toast.makeText(this, "Linhas ativas! Abra o jogo.", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            }
        }
    }
}
