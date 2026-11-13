package community.auroville.voiceengine

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** v16. Holds the microphone while a REPLY is recorded (Android lets a background app record only from a foreground service of type microphone). */
class ReplyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notification.Builder(this, "voice").setSmallIcon(R.drawable.ic_stat).setContentTitle("Recording your reply")
            .setOngoing(true).setShowWhen(false).build()
        try {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            VoiceService.instance?.reply?.failed("The microphone is not allowed yet: open Voice Engine and allow it.")
            stopSelf(); return START_NOT_STICKY
        }
        Thread {
            try { VoiceService.instance?.reply?.recordNow() } finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }.start()
        return START_NOT_STICKY
    }
}
