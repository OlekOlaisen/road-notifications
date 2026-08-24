package no.roadnotifications.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.location.Location
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import no.roadnotifications.MainActivity
import no.roadnotifications.R
import no.roadnotifications.car.VegCarAlertStore
import no.roadnotifications.car.VegCarAppService
import no.roadnotifications.car.VegCarMessageActionService
import no.roadnotifications.data.VegObjektEntity
import no.roadnotifications.data.VegObjektType
import no.roadnotifications.location.LocationDistance
import no.roadnotifications.settings.AlertPreferences
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class AlertCandidate(
    val vegObjekt: VegObjektEntity,
    val alongTrackMeters: Float? = null,
)

class VegNotificationManager(private val context: Context) {
    private val carNotificationManager = CarNotificationManager.from(context)
    private val alertPreferences = AlertPreferences(context)

    fun notifyIfNeeded(candidates: List<AlertCandidate>, currentLocation: Location): Boolean {
        val toNotify = candidates
            .filter { candidate ->
                alertPreferences.isEnabled(
                    candidate.vegObjekt.type,
                    candidate.vegObjekt.verdi,
                )
            }
            .filter { candidate -> shouldNotify(candidate.vegObjekt, currentLocation) }
            .sortedBy { candidate -> messageOrder(candidate.vegObjekt.type) }
        if (toNotify.isEmpty()) {
            return false
        }
        postAlert(toNotify)
        toNotify.forEach { candidate ->
            rememberNotification(candidate.vegObjekt, currentLocation)
        }
        return true
    }

    fun postTestAlert(type: String, verdi: String?) {
        postAlert(
            listOf(
                AlertCandidate(
                    vegObjekt = testObjekt(type = type, verdi = verdi, id = TEST_ALERT_BASE_ID),
                    alongTrackMeters = sampleDistanceMeters(type),
                ),
            ),
        )
    }

    fun postTestCombinedAlert() {
        postAlert(
            listOf(
                AlertCandidate(
                    vegObjekt = testObjekt(
                        type = VegObjektType.FART.name,
                        verdi = "50",
                        id = TEST_ALERT_BASE_ID,
                    ),
                    alongTrackMeters = sampleDistanceMeters(VegObjektType.FART.name),
                ),
                AlertCandidate(
                    vegObjekt = testObjekt(
                        type = VegObjektType.FORKJOERSVEI.name,
                        verdi = null,
                        id = TEST_ALERT_BASE_ID + 1,
                    ),
                    alongTrackMeters = sampleDistanceMeters(VegObjektType.FORKJOERSVEI.name),
                ),
            ),
        )
    }

    private fun postAlert(toNotify: List<AlertCandidate>) {
        val orderedAlerts = toNotify.sortedBy { candidate -> messageOrder(candidate.vegObjekt.type) }
        val (titleText, subtitleText) = titleAndSubtitleFor(orderedAlerts)
        val primaryObjekt = orderedAlerts
            .minBy { candidate -> iconPriority(candidate.vegObjekt.type) }
            .vegObjekt
        val isWildlife = orderedAlerts.any { candidate ->
            candidate.vegObjekt.type == VegObjektType.VILTFARE.name
        }
        val channelId = if (isWildlife) CHANNEL_WILDLIFE else CHANNEL_ALERTS
        val signBitmap = SignIconFactory.largeIconBitmap(context, primaryObjekt)
        val customView = alertRemoteViews(titleText, subtitleText, signBitmap)
        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(titleText)
            .setContentText(subtitleText.ifBlank { null })
            .setStyle(messagingStyleFor(titleText, subtitleText, signBitmap))
            .setLargeIcon(signBitmap)
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .setCustomHeadsUpContentView(customView)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(phoneContentIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(replyAction())
            .addAction(markAsReadAction())
            .extend(carAppExtender(titleText, subtitleText, signBitmap))
        carNotificationManager.notify(ALERT_NOTIFICATION_ID, notificationBuilder)
        val logText = if (subtitleText.isBlank()) {
            titleText
        } else {
            "$titleText — $subtitleText"
        }
        VegCarAlertStore.recordAlert(logText)
    }

    private fun titleAndSubtitleFor(orderedAlerts: List<AlertCandidate>): Pair<String, String> {
        val forkjoersveiAlert = orderedAlerts.find { candidate ->
            candidate.vegObjekt.type == VegObjektType.FORKJOERSVEI.name
        }
        val fartAlert = orderedAlerts.find { candidate ->
            candidate.vegObjekt.type == VegObjektType.FART.name
        }
        if (forkjoersveiAlert != null && fartAlert != null) {
            val remainingAlerts = orderedAlerts.filter { candidate ->
                candidate.vegObjekt.type != VegObjektType.FORKJOERSVEI.name &&
                    candidate.vegObjekt.type != VegObjektType.FART.name
            }
            val subtitleParts = mutableListOf(titleFor(fartAlert.vegObjekt))
            remainingAlerts.forEach { candidate ->
                val part = subtitleFor(candidate.vegObjekt, candidate.alongTrackMeters)
                if (part.isNotBlank()) {
                    subtitleParts += part
                }
            }
            return titleFor(forkjoersveiAlert.vegObjekt) to subtitleParts.joinToString(" · ")
        }
        val titleText = orderedAlerts.joinToString(" - ") { candidate ->
            titleFor(candidate.vegObjekt)
        }
        val subtitleText = orderedAlerts
            .map { candidate -> subtitleFor(candidate.vegObjekt, candidate.alongTrackMeters) }
            .filter { part -> part.isNotBlank() }
            .joinToString(" · ")
        return titleText to subtitleText
    }

    private fun testObjekt(type: String, verdi: String?, id: Long): VegObjektEntity {
        return VegObjektEntity(
            id = id,
            type = type,
            verdi = verdi,
            lat = 0.0,
            lon = 0.0,
            minLat = 0.0,
            maxLat = 0.0,
            minLon = 0.0,
            maxLon = 0.0,
        )
    }

    private fun alertRemoteViews(
        titleText: String,
        subtitleText: String,
        signBitmap: Bitmap,
    ): RemoteViews {
        return RemoteViews(context.packageName, R.layout.notification_alert).apply {
            setImageViewBitmap(R.id.notification_sign, signBitmap)
            setTextViewText(R.id.notification_title, titleText)
            if (subtitleText.isBlank()) {
                setViewVisibility(R.id.notification_subtitle, android.view.View.GONE)
            } else {
                setViewVisibility(R.id.notification_subtitle, android.view.View.VISIBLE)
                setTextViewText(R.id.notification_subtitle, subtitleText)
            }
        }
    }

    private fun messagingStyleFor(
        titleText: String,
        subtitleText: String,
        signBitmap: Bitmap,
    ): NotificationCompat.MessagingStyle {
        val signIcon = IconCompat.createWithBitmap(signBitmap)
        val driver = Person.Builder()
            .setName(context.getString(R.string.app_name))
            .setKey("driver")
            .build()
        val hasSubtitle = subtitleText.isNotBlank()
        val sender = Person.Builder()
            .setName(if (hasSubtitle) context.getString(R.string.app_name) else titleText)
            .setKey("vegassistent")
            .setIcon(signIcon)
            .setImportant(true)
            .build()
        val style = NotificationCompat.MessagingStyle(driver)
            .setGroupConversation(false)
        if (hasSubtitle) {
            style.setConversationTitle(titleText)
        }
        style.addMessage(
            NotificationCompat.MessagingStyle.Message(
                if (hasSubtitle) subtitleText else "\u200B",
                System.currentTimeMillis(),
                sender,
            ),
        )
        return style
    }

    private fun phoneContentIntent(): PendingIntent {
        return PendingIntent.getActivity(
            context,
            PHONE_CONTENT_REQUEST_CODE,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun carAppContentIntent(): PendingIntent {
        val carIntent = Intent(Intent.ACTION_VIEW).setComponent(
            ComponentName(context, VegCarAppService::class.java),
        )
        return CarPendingIntent.getCarApp(
            context,
            CAR_CONTENT_REQUEST_CODE,
            carIntent,
            PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun carAppExtender(
        titleText: String,
        subtitleText: String,
        signBitmap: Bitmap,
    ): CarAppExtender {
        return CarAppExtender.Builder()
            .setContentTitle(titleText)
            .setContentText(subtitleText)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(signBitmap)
            .setImportance(NotificationManager.IMPORTANCE_HIGH)
            .setContentIntent(carAppContentIntent())
            .build()
    }

    /**
     * Android Auto only shows heads-up over other apps for messaging notifications
     * that include reply and mark-as-read actions.
     */
    private fun replyAction(): NotificationCompat.Action {
        val replyIntent = Intent(context, VegCarMessageActionService::class.java).apply {
            action = VegCarMessageActionService.ACTION_REPLY
        }
        val replyPendingIntent = PendingIntent.getService(
            context,
            REPLY_REQUEST_CODE,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyInput = RemoteInput.Builder(VegCarMessageActionService.REMOTE_INPUT_RESULT_KEY)
            .setLabel(context.getString(R.string.car_notification_reply))
            .build()
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.car_notification_reply),
            replyPendingIntent,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .addRemoteInput(replyInput)
            .build()
    }

    private fun markAsReadAction(): NotificationCompat.Action {
        val markAsReadIntent = Intent(context, VegCarMessageActionService::class.java).apply {
            action = VegCarMessageActionService.ACTION_MARK_AS_READ
        }
        val markAsReadPendingIntent = PendingIntent.getService(
            context,
            MARK_AS_READ_REQUEST_CODE,
            markAsReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.car_notification_mark_as_read),
            markAsReadPendingIntent,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    companion object {
        const val CHANNEL_TRACKING = "veg_tracking"
        const val CHANNEL_ALERTS = "veg_alerts"
        const val CHANNEL_WILDLIFE = "veg_wildlife"
        const val TRACKING_NOTIFICATION_ID = 1
        const val ALERT_NOTIFICATION_ID = 2
        val ALERT_NOTIFICATION_IDS = listOf(2, 3, 4, 5, 6)
        private const val TEST_ALERT_BASE_ID = -1000L

        fun alertLabelFor(vegObjekt: VegObjektEntity): String {
            return titleFor(vegObjekt)
        }

        fun titleFor(vegObjekt: VegObjektEntity): String {
            val verdi = vegObjekt.verdi?.trim().orEmpty()
            return when (vegObjekt.type) {
                VegObjektType.FART.name ->
                    if (verdi.isBlank()) "Fartsgrense" else "Fartsgrense $verdi"
                VegObjektType.FOTOBOKS.name -> "Fotoboks"
                VegObjektType.BOM.name -> "Bomstasjon"
                VegObjektType.FORKJOERSVEI.name -> "Forkjørsvei"
                VegObjektType.VILTFARE.name -> wildlifeTitle(verdi)
                VegObjektType.JERNBANE.name -> "Jernbaneovergang"
                VegObjektType.FERJEKAI.name -> "Ferjekai"
                VegObjektType.STOPP.name -> "Stopp"
                VegObjektType.FARLIG_SVING.name -> farligSvingTitle(verdi)
                VegObjektType.SMALERE_VEG.name -> smalereVegTitle(verdi)
                VegObjektType.TUNNEL.name -> "Tunnel"
                VegObjektType.SLUTT_FORKJOERSVEI.name -> "Slutt på forkjørsvei"
                else -> "Vegobjekt i nærheten"
            }
        }

        fun subtitleFor(vegObjekt: VegObjektEntity, alongTrackMeters: Float?): String {
            val verdi = vegObjekt.verdi?.trim().orEmpty()
            return when (vegObjekt.type) {
                VegObjektType.FOTOBOKS.name ->
                    distanceSubtitle(alongTrackMeters) ?: "Fotoboks foran"
                VegObjektType.BOM.name -> bomSubtitle(verdi)
                VegObjektType.FART.name -> ""
                VegObjektType.FORKJOERSVEI.name -> "Forkjørsvei foran"
                VegObjektType.VILTFARE.name -> "Viltfare foran"
                VegObjektType.JERNBANE.name ->
                    if (verdi.isBlank()) "Planovergang foran" else verdi
                VegObjektType.FERJEKAI.name ->
                    if (verdi.isBlank()) "Ferjekai foran" else verdi
                VegObjektType.STOPP.name -> "Stopplikt foran"
                VegObjektType.FARLIG_SVING.name -> "Reduser farten"
                VegObjektType.SMALERE_VEG.name -> "Vegen smalner"
                VegObjektType.TUNNEL.name -> "Tunnel foran"
                VegObjektType.SLUTT_FORKJOERSVEI.name -> "Forkjørsvei opphører"
                else -> "Objekt foran"
            }
        }

        private fun farligSvingTitle(verdi: String): String {
            return when (verdi) {
                "100.1" -> "Farlig sving — høyre"
                "100.2" -> "Farlig sving — venstre"
                "102.1" -> "Farlige svinger — høyre"
                "102.2" -> "Farlige svinger — venstre"
                else -> "Farlig sving"
            }
        }

        private fun smalereVegTitle(verdi: String): String {
            return when (verdi) {
                "106.1" -> "Smalere veg — begge sider"
                "106.2" -> "Smalere veg — høyre"
                "106.3" -> "Smalere veg — venstre"
                else -> "Smalere veg"
            }
        }

        private fun bomSubtitle(verdi: String): String {
            return if (verdi.isBlank()) {
                "Bomstasjon foran"
            } else {
                "Pris bomstasjon: $verdi kr"
            }
        }

        private fun wildlifeTitle(verdi: String): String {
            val art = wildlifeArtLabel(verdi)
            return if (art == null) "Viltfare" else "Viltfare — $art"
        }

        private fun wildlifeArtLabel(verdi: String): String? {
            return when (verdi.uppercase()) {
                "ELG" -> "elg"
                "HJORT" -> "hjort"
                "REIN" -> "rein"
                "RADYR" -> "rådyr"
                else -> null
            }
        }

        private fun distanceSubtitle(alongTrackMeters: Float?): String? {
            if (alongTrackMeters == null) {
                return null
            }
            val roundedMeters = alongTrackMeters.coerceAtLeast(0f).toInt()
            return "om $roundedMeters meter"
        }

        private fun sampleDistanceMeters(objektType: String): Float? {
            return when (objektType) {
                VegObjektType.FOTOBOKS.name -> 250f
                VegObjektType.JERNBANE.name -> 180f
                VegObjektType.FERJEKAI.name -> 120f
                VegObjektType.FARLIG_SVING.name -> 100f
                VegObjektType.TUNNEL.name -> 140f
                VegObjektType.SMALERE_VEG.name -> 70f
                VegObjektType.STOPP.name -> 50f
                else -> null
            }
        }

        private fun messageOrder(type: String): Int {
            return when (type) {
                VegObjektType.JERNBANE.name -> 0
                VegObjektType.STOPP.name -> 1
                VegObjektType.FARLIG_SVING.name -> 2
                VegObjektType.FORKJOERSVEI.name -> 3
                VegObjektType.SLUTT_FORKJOERSVEI.name -> 4
                VegObjektType.FART.name -> 5
                VegObjektType.TUNNEL.name -> 6
                VegObjektType.SMALERE_VEG.name -> 7
                VegObjektType.FOTOBOKS.name -> 8
                VegObjektType.BOM.name -> 9
                VegObjektType.FERJEKAI.name -> 10
                VegObjektType.VILTFARE.name -> 11
                else -> 12
            }
        }

        private fun iconPriority(type: String): Int {
            return when (type) {
                VegObjektType.JERNBANE.name -> 0
                VegObjektType.STOPP.name -> 1
                VegObjektType.FARLIG_SVING.name -> 2
                VegObjektType.VILTFARE.name -> 3
                VegObjektType.FOTOBOKS.name -> 4
                VegObjektType.TUNNEL.name -> 5
                VegObjektType.FORKJOERSVEI.name -> 6
                VegObjektType.SLUTT_FORKJOERSVEI.name -> 7
                VegObjektType.SMALERE_VEG.name -> 8
                VegObjektType.BOM.name -> 9
                VegObjektType.FERJEKAI.name -> 10
                VegObjektType.FART.name -> 11
                else -> 12
            }
        }
        private const val PHONE_CONTENT_REQUEST_CODE = 0
        private const val CAR_CONTENT_REQUEST_CODE = 10
        private const val REPLY_REQUEST_CODE = 11
        private const val MARK_AS_READ_REQUEST_CODE = 12

        private val COOLDOWN_MS_DEFAULT = TimeUnit.MINUTES.toMillis(2)
        private const val COOLDOWN_DISTANCE_METERS_DEFAULT = 350f

        private data class NotificationMemory(
            val elapsedRealtimeMs: Long,
            val latitude: Double,
            val longitude: Double,
        )

        @Volatile
        private var lastNotifiedObjektId: Long? = null

        private val lastNotificationByObjektId = ConcurrentHashMap<Long, NotificationMemory>()
        private val lastNotificationByTypeKey = ConcurrentHashMap<String, NotificationMemory>()

        fun createChannels(context: Context) {
            val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
                ?: return
            val tracking = NotificationChannel(
                CHANNEL_TRACKING,
                context.getString(R.string.channel_tracking_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_tracking_description)
                setShowBadge(false)
            }
            val alerts = NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.channel_alerts_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_alerts_description)
                enableVibration(true)
            }
            val wildlife = NotificationChannel(
                CHANNEL_WILDLIFE,
                context.getString(R.string.channel_wildlife_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_wildlife_description)
                enableVibration(true)
            }
            manager.createNotificationChannels(listOf(tracking, alerts, wildlife))
        }

        fun trackingNotification(context: Context): Notification {
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_TRACKING)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.tracking_notification_title))
                .setContentText(context.getString(R.string.tracking_notification_text))
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(contentIntent)
                .build()
        }

        private fun shouldNotify(vegObjekt: VegObjektEntity, currentLocation: Location): Boolean {
            if (vegObjekt.id == lastNotifiedObjektId) {
                return false
            }
            val previousForObjekt = lastNotificationByObjektId[vegObjekt.id]
            if (previousForObjekt != null &&
                !cooldownExpired(previousForObjekt, currentLocation, vegObjekt.type)
            ) {
                return false
            }
            val typeKey = typeKeyFor(vegObjekt)
            val previousForType = lastNotificationByTypeKey[typeKey]
            if (previousForType != null &&
                !cooldownExpired(previousForType, currentLocation, vegObjekt.type)
            ) {
                return false
            }
            return true
        }

        private fun cooldownExpired(
            previous: NotificationMemory,
            currentLocation: Location,
            objektType: String,
        ): Boolean {
            val elapsedEnough =
                SystemClock.elapsedRealtime() - previous.elapsedRealtimeMs >=
                    cooldownDurationMs(objektType)
            val movedEnough = LocationDistance.distanceMeters(
                previous.latitude,
                previous.longitude,
                currentLocation.latitude,
                currentLocation.longitude,
            ) >= cooldownDistanceMeters(objektType)
            return elapsedEnough || movedEnough
        }

        private fun cooldownDurationMs(objektType: String): Long {
            return when (objektType) {
                VegObjektType.FOTOBOKS.name -> TimeUnit.MINUTES.toMillis(3)
                VegObjektType.FART.name -> TimeUnit.SECONDS.toMillis(90)
                VegObjektType.FARLIG_SVING.name -> TimeUnit.SECONDS.toMillis(90)
                VegObjektType.STOPP.name -> TimeUnit.SECONDS.toMillis(90)
                else -> COOLDOWN_MS_DEFAULT
            }
        }

        private fun cooldownDistanceMeters(objektType: String): Float {
            return when (objektType) {
                VegObjektType.FOTOBOKS.name -> 800f
                VegObjektType.FART.name -> 250f
                VegObjektType.VILTFARE.name -> 500f
                VegObjektType.FARLIG_SVING.name -> 400f
                VegObjektType.TUNNEL.name -> 600f
                else -> COOLDOWN_DISTANCE_METERS_DEFAULT
            }
        }

        private fun rememberNotification(vegObjekt: VegObjektEntity, currentLocation: Location) {
            val memory = NotificationMemory(
                elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                latitude = currentLocation.latitude,
                longitude = currentLocation.longitude,
            )
            lastNotifiedObjektId = vegObjekt.id
            lastNotificationByObjektId[vegObjekt.id] = memory
            lastNotificationByTypeKey[typeKeyFor(vegObjekt)] = memory
        }

        private fun typeKeyFor(vegObjekt: VegObjektEntity): String {
            val verdi = vegObjekt.verdi?.trim().orEmpty()
            return when (vegObjekt.type) {
                VegObjektType.FART.name,
                VegObjektType.VILTFARE.name,
                VegObjektType.FARLIG_SVING.name,
                VegObjektType.SMALERE_VEG.name,
                -> "${vegObjekt.type}:$verdi"
                else -> vegObjekt.type
            }
        }
    }
}
