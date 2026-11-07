package community.auroville.voiceengine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The engine is there after a restart of the phone and after every update, without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try { VoiceService.start(context) } catch (e: RuntimeException) { }
        }
    }
}
