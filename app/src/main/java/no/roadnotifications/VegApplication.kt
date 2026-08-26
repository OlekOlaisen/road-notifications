package no.roadnotifications

import android.app.Application
import no.roadnotifications.data.RtreeSqlite
import no.roadnotifications.log.TripLog
import no.roadnotifications.notification.VegNotificationManager

class VegApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TripLog.init(this)
        RtreeSqlite.load(this)
        VegNotificationManager.createChannels(this)
    }
}
