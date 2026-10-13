package com.volumenskip

import android.content.Context

/** Interruptor general de la app (encendida / apagada). Se recuerda aunque reinicies. */
object AppSwitch {
    private const val PREFS = "volumenskip"
    private const val KEY_ON = "app_on"

    fun isOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ON, on).apply()
        if (on) ScreenOffService.start(context) else ScreenOffService.stop(context)
    }
}
