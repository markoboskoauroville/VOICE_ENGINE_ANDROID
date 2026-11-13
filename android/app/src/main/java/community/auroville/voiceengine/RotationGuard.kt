package community.auroville.voiceengine

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * v8, 13.11.2026. Marko: "And still this auto rotate option is selecting by itself automatically. What's going on? Auto
 * rotation is annoying me. I already told you and fixed this." Something keeps switching Auto-rotate on (Android's own
 * record only says "android"), so the engine, which is always running, watches the setting and puts it back to off
 * within a moment, every time, and remembers when it did (for /status, to find the cause). It needs "Modify system
 * settings" (granted once: the window's button, or over adb `appops set community.auroville.voiceengine WRITE_SETTINGS allow`).
 */
class RotationGuard(private val ctx: Context) {
    @Volatile var caught = 0
        private set
    @Volatile var lastAt = 0L
        private set

    fun allowed() = Settings.System.canWrite(ctx)

    private val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = check()
    }

    fun start() {
        ctx.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, obs)
        check()
    }

    fun stop() = runCatching { ctx.contentResolver.unregisterContentObserver(obs) }

    private fun check() {
        val on = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        if (!on || !allowed()) return
        runCatching {
            Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            caught++; lastAt = System.currentTimeMillis()
        }
    }
}
