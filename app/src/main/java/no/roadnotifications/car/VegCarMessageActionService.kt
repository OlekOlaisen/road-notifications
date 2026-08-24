package no.roadnotifications.car

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.car.app.notification.CarNotificationManager
import no.roadnotifications.notification.VegNotificationManager

class VegCarMessageActionService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REPLY,
            ACTION_MARK_AS_READ,
            -> {
                val carNotifications = CarNotificationManager.from(this)
                VegNotificationManager.ALERT_NOTIFICATION_IDS.forEach { notificationId ->
                    carNotifications.cancel(notificationId)
                }
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_REPLY = "no.roadnotifications.car.ACTION_REPLY"
        const val ACTION_MARK_AS_READ = "no.roadnotifications.car.ACTION_MARK_AS_READ"
        const val REMOTE_INPUT_RESULT_KEY = "veg_car_reply_input"
    }
}
