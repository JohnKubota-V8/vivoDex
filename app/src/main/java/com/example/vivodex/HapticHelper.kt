package com.example.vivodex

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

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

    fun doubleClick(context: Context) {
        vibrate(context, VibrationEffect.EFFECT_DOUBLE_CLICK)
    }

    private fun vibrate(context: Context, effectId: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(effectId))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createPredefined(effectId))
            }
        } catch (_: Exception) {
            // Gracefully ignore if vibrator is unavailable or permissions are restricted
        }
    }
}
