package com.example.vivodex

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log

object HapticHelper {
    fun click(context: Context) {
        vibrate(context, VibrationEffect.EFFECT_CLICK)
    }

    fun tick(context: Context) {
        vibrate(context, VibrationEffect.EFFECT_TICK)
    }

    fun heavyClick(context: Context) {
        vibrate(context, VibrationEffect.EFFECT_HEAVY_CLICK)
    }

    private fun vibrate(context: Context, effectId: Int) {
        try {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(effectId))
        } catch (error: SecurityException) {
            Log.w(TAG, "Haptic feedback was denied", error)
        }
    }

    private const val TAG = "VivoDex"
}
