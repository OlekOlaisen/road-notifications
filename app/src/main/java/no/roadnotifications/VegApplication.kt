package no.roadnotifications

import android.app.Application
import no.roadnotifications.notification.VegNotificationManager

class VegApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        VegNotificationManager.createChannels(this)
    }
}
