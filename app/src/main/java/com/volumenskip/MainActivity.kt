package com.volumenskip

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.ScrollView
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Pantalla simple: estado del servicio, botón para activarlo y botones de prueba. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var power: Button
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.parseColor("#111418"))
        }

        root.addView(TextView(this).apply {
            text = "VolumenSkip"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })

        root.addView(TextView(this).apply {
            text = "\nCon música sonando:\n\n" +
                "PANTALLA ENCENDIDA\n" +
                "• Mantén  VOLUMEN +  → siguiente ⏭\n" +
                "• Mantén  VOLUMEN −  → anterior ⏮\n\n" +
                "PANTALLA APAGADA\n" +
                "• Mantén  VOLUMEN +  → siguiente ⏭\n" +
                "• Mantén  VOLUMEN −  → anterior ⏮\n\n" +
                "Un toque corto siempre cambia el volumen normal.\n"
            textSize = 16f
            setTextColor(Color.parseColor("#C9D1D9"))
        })

        // Interruptor general: ENCENDER / APAGAR la app
        power = Button(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(pad, pad / 2, pad, pad / 2)
            setOnClickListener {
                AppSwitch.setOn(this@MainActivity, !AppSwitch.isOn(this@MainActivity))
                refreshPower()
            }
        }
        root.addView(power, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        status = TextView(this).apply {
            textSize = 18f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Activar en Accesibilidad"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        root.addView(Button(this).apply {
            text = "Quitar ahorro de batería (recomendado)"
            setOnClickListener {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        })

        root.addView(TextView(this).apply {
            text = "\nProbar sin botones físicos:"
            setTextColor(Color.parseColor("#8B949E"))
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "⏮ Anterior"
            setOnClickListener {
                VolumeKeyService.sendFromApp(this@MainActivity, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
        })
        row.addView(Button(this).apply {
            text = "Siguiente ⏭"
            setOnClickListener {
                VolumeKeyService.sendFromApp(this@MainActivity, KeyEvent.KEYCODE_MEDIA_NEXT)
            }
        })
        root.addView(row)

        root.addView(TextView(this).apply {
            text = "\nRegistro con pantalla apagada (para revisar si falla):"
            setTextColor(Color.parseColor("#8B949E"))
        })
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#C9D1D9"))
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#111418"))
            addView(root)
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ScreenOffService.start(this)
    }

    override fun onResume() {
        super.onResume()
        refreshPower()
        logView.text = ScreenOffService.readLog(this)
        val on = isServiceEnabled()
        status.text = if (on) "✅ Servicio ACTIVO" else "⚠️ Servicio desactivado"
        status.setTextColor(Color.parseColor(if (on) "#3FB950" else "#F0883E"))
    }

    private fun refreshPower() {
        val on = AppSwitch.isOn(this)
        power.text = if (on) "🟢  ENCENDIDA  (tocar para apagar)" else "🔴  APAGADA  (tocar para encender)"
        power.setBackgroundColor(Color.parseColor(if (on) "#238636" else "#6E2B2B"))
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, VolumeKeyService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
