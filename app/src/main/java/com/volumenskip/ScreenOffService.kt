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
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent

/**
 * Funciona con la PANTALLA APAGADA.
 *
 * Al apagar la pantalla (y si hay música sonando) la app crea una sesión
 * multimedia con su propio "control de volumen". Android le entrega entonces
 * los botones de volumen a esta sesión, aunque la pantalla esté apagada.
 *
 *  - Pulsación rápida doble  o  mantener  Vol +  -> siguiente canción
 *  - Pulsación rápida doble  o  mantener  Vol −  -> canción anterior
 *  - Una sola pulsación                            -> volumen normal
 *
 * Al encender la pantalla se desactiva y vuelve a mandar VolumeKeyService.
 */
class ScreenOffService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var power: PowerManager
    private var session: MediaSession? = null
    private var sessionOn = false

    // Detección de gesto
    private var pendingDir = 0
    private var firstPressAt = 0L
    private var lockUntil = 0L

    private val singlePress = Runnable {
        // Solo hubo una pulsación: cambiar volumen normal
        if (pendingDir != 0) {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (pendingDir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0
            )
        }
        pendingDir = 0
    }

    private val watcher = object : Runnable {
        override fun run() {
            updateSession()
            if (!power.isInteractive) handler.postDelayed(this, 3000)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(watcher)
                    handler.post(watcher)
                }
                Intent.ACTION_SCREEN_ON -> {
                    handler.removeCallbacks(watcher)
                    setSessionActive(false)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        power = getSystemService(Context.POWER_SERVICE) as PowerManager
        startAsForeground()
        createSession()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        if (!power.isInteractive) handler.post(watcher)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        session?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Sesión multimedia con volumen propio ----------

    private fun createSession() {
        val s = MediaSession(this, "VolumenSkip")
        s.setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                if (direction != 0) onVolumeKey(direction)
            }
        })
        session = s
    }

    private fun updateSession() {
        // Activo solo si la pantalla está apagada y hay música sonando
        setSessionActive(!power.isInteractive && audio.isMusicActive)
    }

    private fun setSessionActive(on: Boolean) {
        val s = session ?: return
        if (on == sessionOn) return
        sessionOn = on
        if (on) {
            s.isActive = true
            setState(PlaybackState.STATE_PLAYING)
        } else {
            setState(PlaybackState.STATE_PAUSED)
            s.isActive = false
        }
    }

    private fun setState(state: Int) {
        session?.setPlaybackState(
            PlaybackState.Builder().setState(state, 0, 1f).build()
        )
    }

    /** Tras cambiar de canción la app de música puede "quitarnos" el volumen; lo recuperamos. */
    private fun reclaimVolume() {
        if (!sessionOn) return
        setState(PlaybackState.STATE_PAUSED)
        setState(PlaybackState.STATE_PLAYING)
    }

    // ---------- Gestos ----------

    private fun onVolumeKey(dir: Int) {
        val now = SystemClock.uptimeMillis()
        if (now < lockUntil) {          // ignorar repeticiones tras un salto
            lockUntil = now + LOCK_MS
            return
        }
        if (pendingDir == dir && now - firstPressAt < GESTURE_MS) {
            // Segunda pulsación rápida o botón mantenido -> cambiar canción
            handler.removeCallbacks(singlePress)
            pendingDir = 0
            lockUntil = now + LOCK_MS
            skip(dir)
            return
        }
        // Primera pulsación: esperamos a ver si llega otra
        handler.removeCallbacks(singlePress)
        if (pendingDir != 0 && pendingDir != dir) singlePress.run()
        pendingDir = dir
        firstPressAt = now
        handler.postDelayed(singlePress, GESTURE_MS)
    }

    private fun skip(dir: Int) {
        val code = if (dir > 0) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        vibrate()
        handler.postDelayed({ reclaimVolume() }, 1500)
        handler.postDelayed({ reclaimVolume() }, 4000)
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
        const val GESTURE_MS = 450L
        const val LOCK_MS = 700L

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ScreenOffService::class.java))
            } catch (_: Exception) { }
        }
    }
}
