package com.volumenskip

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Prototipo:
 *  - Mantener Volumen +  (≈0.6 s)  -> siguiente canción
 *  - Mantener Volumen −  (≈0.6 s)  -> canción anterior
 *  - Pulsación corta               -> sube/baja volumen como siempre
 *
 * Funciona con cualquier app de música (Spotify, YouTube Music, etc.)
 * porque envía teclas multimedia estándar del sistema.
 */
class VolumeKeyService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager

    private var pressedKey: Int? = null
    private var longPressFired = false

    private val longPressRunnable = Runnable {
        val key = pressedKey ?: return@Runnable
        longPressFired = true
        if (key == KeyEvent.KEYCODE_VOLUME_UP) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        } else {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
        vibrate()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        isRunning = true
        ScreenOffService.start(this)
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        // Solo actuamos si hay música sonando; si no, el volumen funciona normal.
        if (!audio.isMusicActive && pressedKey == null) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    pressedKey = code
                    longPressFired = false
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                return true // consumimos; decidimos al soltar
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (!longPressFired) {
                    // Pulsación corta: ajustar volumen normalmente
                    val dir = if (code == KeyEvent.KEYCODE_VOLUME_UP)
                        AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI
                    )
                }
                pressedKey = null
                longPressFired = false
                return true
            }
        }
        return false
    }

    private fun sendMediaKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @Volatile var isRunning = false
        const val LONG_PRESS_MS = 600L

        /** Usado por la pantalla principal para los botones de prueba. */
        fun sendFromApp(context: Context, keyCode: Int) {
            val a = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val now = SystemClock.uptimeMillis()
            a.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            a.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }
}
