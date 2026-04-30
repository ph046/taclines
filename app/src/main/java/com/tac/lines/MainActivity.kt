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

    companion object {
        private const val REQ_CAPTURE = 1001
        private const val REQ_OVERLAY = 1002
        private const val REQ_ACCESSIBILITY = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        tvStatus = findViewById(R.id.tvStatus)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            checkStart()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, LineService::class.java))

            tvStatus.text = "Parado"
            tvStatus.setTextColor(0xFFAAAAAA.toInt())

            Toast.makeText(this, "TacLines parado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()

        val overlayOk = Settings.canDrawOverlays(this)
        val accessOk = isAutoAimAccessibilityEnabled()

        tvStatus.text = when {
            !overlayOk -> "Permita sobrepor a outros apps"
            !accessOk -> "Ative acessibilidade do TacLines"
            else -> "Pronto para iniciar"
        }

        tvStatus.setTextColor(
            if (overlayOk && accessOk) {
                0xFF00FF88.toInt()
            } else {
                0xFFFFCC00.toInt()
            }
        )
    }

    private fun checkStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Ative a permissão de sobrepor a outros apps",
                Toast.LENGTH_LONG
            ).show()

            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ),
                REQ_OVERLAY
            )

            return
        }

        if (!isAutoAimAccessibilityEnabled()) {
            Toast.makeText(
                this,
                "Ative o serviço TacLines nas configurações de acessibilidade",
                Toast.LENGTH_LONG
            ).show()

            try {
                startActivityForResult(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    REQ_ACCESSIBILITY
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }

            return
        }

        startActivityForResult(pm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    private fun isAutoAimAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val serviceFull = "$packageName/com.tac.lines.AutoAimAccessibilityService"
        val serviceShort = "$packageName/.AutoAimAccessibilityService"

        return enabledServices.split(":").any { service ->
            service.equals(serviceFull, ignoreCase = true) ||
                    service.equals(serviceShort, ignoreCase = true) ||
                    service.endsWith("/com.tac.lines.AutoAimAccessibilityService", ignoreCase = true) ||
                    service.endsWith("/.AutoAimAccessibilityService", ignoreCase = true)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQ_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    checkStart()
                } else {
                    tvStatus.text = "Overlay negado"
                    tvStatus.setTextColor(0xFFFF4444.toInt())
                }
            }

            REQ_ACCESSIBILITY -> {
                if (isAutoAimAccessibilityEnabled()) {
                    checkStart()
                } else {
                    tvStatus.text = "Acessibilidade não ativada"
                    tvStatus.setTextColor(0xFFFFCC00.toInt())
                }
            }

            REQ_CAPTURE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val i = Intent(this, LineService::class.java).apply {
                        putExtra("code", resultCode)
                        putExtra("data", data)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(i)
                    } else {
                        startService(i)
                    }

                    tvStatus.text = "Ativo! Abra o jogo."
                    tvStatus.setTextColor(0xFF00FF88.toInt())

                    Toast.makeText(
                        this,
                        "TacLines ativo! Abra o jogo.",
                        Toast.LENGTH_LONG
                    ).show()

                    moveTaskToBack(true)
                } else {
                    tvStatus.text = "Captura de tela negada"
                    tvStatus.setTextColor(0xFFFF4444.toInt())
                }
            }
        }
    }
}
