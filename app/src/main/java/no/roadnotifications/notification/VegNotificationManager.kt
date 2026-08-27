package no.roadnotifications.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import no.roadnotifications.log.TripLog
import no.roadnotifications.settings.AlertPreferences

data class AlertCandidate(
    val vegObjekt: VegObjektEntity,
    val alongTrackMeters: Float? = null,
)

class VegNotificationManager(private val context: Context) {
    private val carNotificationManager = CarNotificationManager.from(context)
    private val alertPreferences = AlertPreferences(context)
    private val alertPassTracker = AlertPassTracker()

    fun notifyIfNeeded(
        candidates: List<AlertCandidate>,
        matchingObjektIds: Set<Long>,
        higherImportanceApproaching: Boolean = false,
    ): List<AlertCandidate> {
        alertPassTracker.prepareTick(matchingObjektIds)
        val enabled = candidates.filter { candidate ->
            alertPreferences.isEnabled(
                candidate.vegObjekt.type,
                candidate.vegObjekt.verdi,
            )
        }
        val disabled = candidates.filter { candidate -> candidate !in enabled }
        if (disabled.isNotEmpty()) {
            TripLog.append(
                "SKIP disabled=" + disabled.joinToString(",") { candidate ->
                    TripLog.formatObjekt(candidate.vegObjekt)
                },
            )
        }
        val passingOncePerPass = enabled.filter { candidate ->
            alertPassTracker.shouldNotify(candidate.vegObjekt.id)
        }
        val alreadyAlertedThisPass = enabled.filter { candidate ->
            candidate !in passingOncePerPass
        }
        if (alreadyAlertedThisPass.isNotEmpty()) {
            TripLog.append(
                "SKIP already-alerted=" + alreadyAlertedThisPass.joinToString(",") { candidate ->
                    TripLog.formatObjekt(candidate.vegObjekt)
                },
            )
        }
        val selected = AlertPriority.selectToNotify(
            passingOncePerPass = passingOncePerPass,
            candidatesInWindow = enabled,
            higherImportanceApproaching = higherImportanceApproaching,
        )
        val suppressedByPriority = passingOncePerPass.filter { candidate ->
            candidate !in selected
        }
        if (suppressedByPriority.isNotEmpty()) {
            TripLog.append(
                "SKIP lower-priority=" + suppressedByPriority.joinToString(",") { candidate ->
                    TripLog.formatObjekt(candidate.vegObjekt)
                },
            )
        }
        passingOncePerPass.forEach { candidate ->
            val suppressedKommune = candidate in suppressedByPriority &&
                candidate.vegObjekt.type == VegObjektType.KOMMUNE.name
            if (!suppressedKommune) {
                alertPassTracker.remember(candidate.vegObjekt.id)
            }
        }
        val toNotify = selected.sortedBy { candidate ->
            AlertPriority.messageOrder(candidate.vegObjekt.type)
        }
        if (toNotify.isEmpty()) {
            return emptyList()
        }
        postAlert(toNotify)
        TripLog.append(
            "ALERT " + toNotify.joinToString(",") { candidate ->
                TripLog.formatObjekt(candidate.vegObjekt)
            },
        )
        return toNotify
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
        val orderedAlerts = toNotify.sortedBy { candidate ->
            AlertPriority.messageOrder(candidate.vegObjekt.type)
        }
        val (titleText, subtitleText) = titleAndSubtitleFor(orderedAlerts)
        val primaryObjekt = orderedAlerts
            .minBy { candidate -> AlertPriority.iconPriority(candidate.vegObjekt.type) }
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
        val hasHighImportance = orderedAlerts.any { candidate ->
            AlertPriority.importance(candidate.vegObjekt.type) == AlertImportance.HIGH
        }
        if (forkjoersveiAlert != null && fartAlert != null && !hasHighImportance) {
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
        private const val SKYTTELPASS_RABATT = 0.20

        fun alertLabelFor(vegObjekt: VegObjektEntity): String {
            return titleFor(vegObjekt)
        }

        fun titleFor(vegObjekt: VegObjektEntity): String {
            val verdi = vegObjekt.verdi?.trim().orEmpty()
            return when (vegObjekt.type) {
                VegObjektType.FART.name ->
                    if (verdi.isBlank()) "Fartsgrense" else "Fartsgrense $verdi"
                VegObjektType.FOTOBOKS.name -> "Fotoboks"
                VegObjektType.STREKNINGS_ATK.name -> "Strekningsmåling"
                VegObjektType.BOM.name -> "Bomstasjon"
                VegObjektType.FORKJOERSVEI.name -> "Forkjørsvei"
                VegObjektType.VILTFARE.name -> wildlifeTitle(verdi)
                VegObjektType.JERNBANE.name -> "Jernbaneovergang"
                VegObjektType.FERJEKAI.name -> "Ferjekai"
                VegObjektType.STOPP.name -> "Stopp"
                VegObjektType.VIKEPLIKT.name -> "Vikeplikt"
                VegObjektType.FARLIG_SVING.name -> farligSvingTitle(verdi)
                VegObjektType.FARLIG_VEGKRYSS.name -> "Farlig vegkryss"
                VegObjektType.SMALERE_VEG.name -> smalereVegTitle(verdi)
                VegObjektType.TUNNEL.name -> "Tunnel"
                VegObjektType.SLUTT_FORKJOERSVEI.name -> "Slutt på forkjørsvei"
                VegObjektType.KOMMUNE.name ->
                    if (verdi.isBlank()) "Ny kommune" else verdi
                else -> "Vegobjekt i nærheten"
            }
        }

        fun subtitleFor(vegObjekt: VegObjektEntity, alongTrackMeters: Float?): String {
            val verdi = vegObjekt.verdi?.trim().orEmpty()
            return when (vegObjekt.type) {
                VegObjektType.FOTOBOKS.name ->
                    distanceSubtitle(alongTrackMeters) ?: "Fotoboks foran"
                VegObjektType.STREKNINGS_ATK.name ->
                    if (verdi.isBlank()) "Gjennomsnittsfart foran" else verdi
                VegObjektType.BOM.name -> bomSubtitle(verdi)
                VegObjektType.FART.name -> ""
                VegObjektType.FORKJOERSVEI.name -> "Forkjørsvei foran"
                VegObjektType.VILTFARE.name -> "Viltfare foran"
                VegObjektType.JERNBANE.name ->
                    if (verdi.isBlank()) "Planovergang foran" else verdi
                VegObjektType.FERJEKAI.name ->
                    if (verdi.isBlank()) "Ferjekai foran" else verdi
                VegObjektType.STOPP.name -> "Stopplikt foran"
                VegObjektType.VIKEPLIKT.name -> "Vikeplikt foran"
                VegObjektType.FARLIG_SVING.name -> "Reduser farten"
                VegObjektType.FARLIG_VEGKRYSS.name -> "Farlig kryss foran"
                VegObjektType.SMALERE_VEG.name -> "Vegen smalner"
                VegObjektType.TUNNEL.name -> "Tunnel foran"
                VegObjektType.SLUTT_FORKJOERSVEI.name -> "Forkjørsvei opphører"
                VegObjektType.KOMMUNE.name -> "Ny kommune"
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
            if (verdi.isBlank()) {
                return "Bomstasjon foran"
            }
            val displayedPrice = skyttelpassPris(verdi) ?: verdi
            return "Pris bomstasjon: $displayedPrice kr"
        }

        private fun skyttelpassPris(verdi: String): String? {
            val fullPrice = verdi.replace(',', '.').toDoubleOrNull() ?: return null
            val discounted = fullPrice * (1.0 - SKYTTELPASS_RABATT)
            return formatKroner(discounted)
        }

        private fun formatKroner(amount: Double): String {
            val ore = kotlin.math.round(amount * 100.0).toLong()
            val kroner = ore / 100
            val remainder = (ore % 100).toInt()
            return if (remainder == 0) {
                kroner.toString()
            } else {
                "%d,%02d".format(kroner, remainder)
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
                VegObjektType.STREKNINGS_ATK.name -> LocationDistance.AT_SIGN_ALONG_TRACK_METERS
                VegObjektType.JERNBANE.name -> 180f
                VegObjektType.FERJEKAI.name -> 120f
                VegObjektType.FARLIG_SVING.name -> 80f
                VegObjektType.FARLIG_VEGKRYSS.name -> 80f
                VegObjektType.TUNNEL.name -> 140f
                VegObjektType.SMALERE_VEG.name -> 70f
                VegObjektType.STOPP.name -> 50f
                VegObjektType.VIKEPLIKT.name -> LocationDistance.YIELD_ALONG_TRACK_METERS
                VegObjektType.BOM.name -> LocationDistance.AT_SIGN_ALONG_TRACK_METERS
                VegObjektType.VILTFARE.name -> LocationDistance.AT_SIGN_ALONG_TRACK_METERS
                VegObjektType.FORKJOERSVEI.name -> LocationDistance.AT_SIGN_ALONG_TRACK_METERS
                VegObjektType.SLUTT_FORKJOERSVEI.name -> LocationDistance.AT_SIGN_ALONG_TRACK_METERS
                else -> null
            }
        }

        private const val PHONE_CONTENT_REQUEST_CODE = 0
        private const val CAR_CONTENT_REQUEST_CODE = 10
        private const val REPLY_REQUEST_CODE = 11
        private const val MARK_AS_READ_REQUEST_CODE = 12

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
    }
}
