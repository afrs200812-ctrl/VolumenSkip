package com.volumenskip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.KeyEvent

/**
 * Funciona con la PANTALLA APAGADA.
 *
 * Con la pantalla apagada Android no deja a ninguna app leer los botones,
 * pero los botones SÍ cambian el volumen de la música. Este servicio vigila
 * esos cambios:
 *
 *  - 2 cambios seguidos y rápidos hacia arriba (doble toque o mantener Vol +) -> siguiente
 *  - 2 cambios seguidos y rápidos hacia abajo  (doble toque o mantener Vol −) -> anterior
 *  - 1 solo cambio -> no hace nada (volumen normal)
 *
 * Después de cambiar de canción devuelve el volumen al que había antes.
 */
class ScreenOffService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var power: PowerManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private var lastVol = -1
    private var stepDir = 0
    private var stepCount = 0
    private var firstStepAt = 0L
    private var baseVol = -1
    private var ignoreUntil = 0L

    // Fuente 1: aviso del sistema cada vez que cambia el volumen
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { checkVolume() }
    }

    // Fuente 2 (respaldo): cambios en los ajustes del sistema
    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { checkVolume() }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
            resetGesture()
            lastVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VolumenSkip:vol")
        startAsForeground()
        lastVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

        val volFilter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        val scrFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(volumeReceiver, volFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(screenReceiver, scrFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(volumeReceiver, volFilter)
            registerReceiver(screenReceiver, scrFilter)
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { contentResolver.unregisterContentObserver(settingsObserver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Si el volumen está al máximo, Vol + no produce cambio: lo bajamos un punto. */
    private fun onScreenOff() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (audio.isMusicActive && audio.getStreamVolume(AudioManager.STREAM_MUSIC) >= max) {
            ignoreUntil = SystemClock.elapsedRealtime() + 300
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, max - 1, 0)
        }
    }

    private fun resetGesture() {
        stepDir = 0
        stepCount = 0
    }

    private fun checkVolume() {
        val v = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val prev = lastVol
        lastVol = v
        if (prev < 0 || v == prev) return

        val now = SystemClock.elapsedRealtime()
        if (now < ignoreUntil) return
        // Con pantalla encendida se encarga el servicio de Accesibilidad.
        if (power.isInteractive && VolumeKeyService.isRunning) return
        if (!audio.isMusicActive) return
        if (!wakeLock.isHeld) wakeLock.acquire(3000)

        val dir = if (v > prev) 1 else -1
        if (dir != stepDir || now - firstStepAt > GESTURE_MS) {
            stepDir = dir
            stepCount = 0
            firstStepAt = now
            baseVol = prev
        }
        stepCount++
        if (stepCount >= 2) skip(dir, now)
    }

    private fun skip(dir: Int, now: Long) {
        resetGesture()
        val code = if (dir > 0) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        vibrate()

        // Devolver el volumen como estaba (si el dedo sigue presionando, se repite al final)
        val restore = baseVol.coerceIn(0, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) - 1)
        ignoreUntil = now + LOCK_MS
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
        handler.postDelayed({
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
            lastVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        }, LOCK_MS - 200)
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ---------- Notificación obligatoria ----------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "VolumenSkip activo", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_next)
            .setContentTitle("VolumenSkip activo")
            .setContentText("Cambia canciones con el volumen, incluso con la pantalla apagada")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
    }

    companion object {
        private const val CHANNEL = "volumenskip"
        const val GESTURE_MS = 700L
        const val LOCK_MS = 1500L

        @Volatile var isRunning = false

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ScreenOffService::class.java))
            } catch (_: Exception) { }
        }
    }
}
