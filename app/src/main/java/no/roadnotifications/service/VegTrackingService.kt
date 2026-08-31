package no.roadnotifications.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.roadnotifications.alert.AlertTickProcessor
import no.roadnotifications.data.DaoVegObjektStore
import no.roadnotifications.data.VegDatabase
import no.roadnotifications.data.VegObjektEntity
import no.roadnotifications.location.GpsFix
import no.roadnotifications.location.LocationDistance
import no.roadnotifications.location.RoadMatcher
import no.roadnotifications.location.TravelPathOffset
import no.roadnotifications.log.TripLog
import no.roadnotifications.notification.AlertCandidate
import no.roadnotifications.notification.VegNotificationManager
import java.util.Locale

class VegTrackingService : LifecycleService() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var vegNotificationManager: VegNotificationManager
    @Volatile
    private var roadMatcher: RoadMatcher? = null
    private var lastCheckedLocation: Location? = null
    private val alertTickProcessor = AlertTickProcessor { message ->
        TripLog.append(message)
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val gpsAccuracyTooPoor = location.hasAccuracy() &&
                location.accuracy > LocationDistance.MAX_GPS_ACCURACY_METERS
            if (!gpsAccuracyTooPoor &&
                !LocationDistance.hasMovedEnough(lastCheckedLocation, location)
            ) {
                return
            }
            val previousLocation = lastCheckedLocation
            lastCheckedLocation = location
            checkNearbyObjects(location, previousLocation)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        vegNotificationManager = VegNotificationManager(applicationContext)
        VegNotificationManager.createChannels(this)
        TripLog.append("SERVICE create")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                roadMatcher = RoadMatcher.open(applicationContext)
                TripLog.append(
                    if (roadMatcher == null) "ROADGRAPH mangler" else "ROADGRAPH lastet",
                )
            } catch (error: Throwable) {
                Log.e(TAG, "Klarte ikke å laste veinett", error)
                TripLog.append("ERROR roadgraph ${error.javaClass.simpleName}: ${error.message}")
                roadMatcher = null
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startAsForeground()
        startLocationUpdates()
        TripLog.append("SERVICE start")
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        TripLog.append("SERVICE task removed")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        roadMatcher?.close()
        roadMatcher = null
        vegNotificationManager.cancelQueuedAlerts()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        TripLog.append("SERVICE stop")
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = VegNotificationManager.trackingNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                VegNotificationManager.TRACKING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(VegNotificationManager.TRACKING_NOTIFICATION_ID, notification)
        }
    }

    private fun startLocationUpdates() {
        val hasFineLocation = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation && !hasCoarseLocation) {
            stopSelf()
            return
        }
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_INTERVAL_MS,
        )
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MS)
            .setMinUpdateDistanceMeters(LocationDistance.MIN_MOVEMENT_METERS)
            .setWaitForAccurateLocation(false)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun checkNearbyObjects(location: Location, previousLocation: Location?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                checkNearbyObjectsOrSkip(location, previousLocation)
            } catch (error: Throwable) {
                Log.e(TAG, "Varslingssjekk feilet", error)
                TripLog.append("ERROR check ${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    private suspend fun checkNearbyObjectsOrSkip(
        location: Location,
        previousLocation: Location?,
    ) {
            val match = roadMatcher?.match(location, previousLocation)
            val queryLocation = if (match == null) {
                location
            } else {
                match.toSnappedLocation(location)
            }
            val travelHeadingDegrees = match?.travelHeadingDegrees
            val currentFix = GpsFix(
                latitude = queryLocation.latitude,
                longitude = queryLocation.longitude,
                elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                speedMetersPerSecond = if (location.hasSpeed()) location.speed else null,
                headingDegrees = if (location.hasBearing()) location.bearing else null,
                accuracyMeters = if (queryLocation.hasAccuracy()) queryLocation.accuracy else null,
            )
            val previousFix = previousLocation?.let { previous ->
                GpsFix(
                    latitude = previous.latitude,
                    longitude = previous.longitude,
                    elapsedRealtimeMs = 0L,
                    speedMetersPerSecond = if (previous.hasSpeed()) previous.speed else null,
                    headingDegrees = if (previous.hasBearing()) previous.bearing else null,
                    accuracyMeters = if (previous.hasAccuracy()) previous.accuracy else null,
                )
            }
            val store = DaoVegObjektStore(
                VegDatabase.getInstance(applicationContext).vegObjektDao(),
            )
            val result = alertTickProcessor.process(
                store = store,
                current = currentFix,
                previous = previousFix,
                travelHeadingOverrideDegrees = travelHeadingDegrees,
            )
            if (result.suppressedGiveWay.isNotEmpty()) {
                TripLog.append(
                    "SKIP on-priority-road=" + result.suppressedGiveWay.joinToString(",") { vegObjekt ->
                        TripLog.formatObjekt(vegObjekt)
                    },
                )
            }
            val notified = vegNotificationManager.notifyIfNeeded(
                result.candidates,
                result.matchingObjektIds,
                higherImportanceApproaching = result.higherImportanceApproaching,
            )
            alertTickProcessor.acknowledgeNotifications(notified)
            TripLog.append(
                buildCheckLogLine(
                    location = location,
                    matchRoadName = match?.name,
                    matchEdgeId = match?.edgeId,
                    matchDistanceMeters = match?.distanceFromGpsMeters,
                    queryLocation = queryLocation,
                    travelHeadingDegrees = result.travelHeadingDegrees,
                    nearbyCount = result.nearby.size,
                    nearbyDurationMs = result.nearbyDurationMs,
                    nearbyTypeCounts = result.nearby.groupingBy { vegObjekt -> vegObjekt.type }.eachCount(),
                    pathMatches = result.pathMatches,
                    onStretch = result.onStretch,
                    enteringStretch = result.enteringStretch,
                    currentSpeedLimit = result.currentSpeedLimit,
                    candidates = result.candidates,
                    notified = notified.isNotEmpty(),
                ),
            )
    }
    private fun buildCheckLogLine(
        location: Location,
        matchRoadName: String?,
        matchEdgeId: Int?,
        matchDistanceMeters: Float?,
        queryLocation: Location,
        travelHeadingDegrees: Float?,
        nearbyCount: Int,
        nearbyDurationMs: Long,
        nearbyTypeCounts: Map<String, Int>,
        pathMatches: List<Pair<VegObjektEntity, TravelPathOffset>>,
        onStretch: List<VegObjektEntity>,
        enteringStretch: List<VegObjektEntity>,
        currentSpeedLimit: VegObjektEntity?,
        candidates: List<AlertCandidate>,
        notified: Boolean,
    ): String {
        val snapText = if (matchEdgeId == null) {
            "snap=none"
        } else {
            val roadName = matchRoadName?.ifBlank { null } ?: "#$matchEdgeId"
            val snapDistance = matchDistanceMeters?.let { distance ->
                String.format(Locale.US, "%.1f", distance)
            } ?: "-"
            String.format(
                Locale.US,
                "snap=%.6f,%.6f road=%s d=%sm",
                queryLocation.latitude,
                queryLocation.longitude,
                roadName.replace(' ', '_'),
                snapDistance,
            )
        }
        val headingText = travelHeadingDegrees?.let { heading ->
            String.format(Locale.US, "%.0f", heading)
        } ?: "-"
        val typeSummary = nearbyTypeCounts.entries
            .sortedByDescending { entry -> entry.value }
            .joinToString(",") { entry -> "${entry.key}:${entry.value}" }
            .ifBlank { "-" }
        val pathSummary = pathMatches.take(5).joinToString(",") { (vegObjekt, pathOffset) ->
            "${TripLog.formatObjekt(vegObjekt)}@${pathOffset.alongTrackMeters.toInt()}m/x${pathOffset.crossTrackMeters.toInt()}"
        }.ifBlank { "-" }
        val stretchSummary = onStretch.take(5).joinToString(",") { vegObjekt ->
            TripLog.formatObjekt(vegObjekt)
        }.ifBlank { "-" }
        val enterSummary = enteringStretch.take(5).joinToString(",") { vegObjekt ->
            TripLog.formatObjekt(vegObjekt)
        }.ifBlank { "-" }
        val limitSummary = currentSpeedLimit?.let { vegObjekt ->
            TripLog.formatObjekt(vegObjekt)
        } ?: "-"
        val candidateSummary = candidates.joinToString(",") { candidate ->
            TripLog.formatObjekt(candidate.vegObjekt)
        }.ifBlank { "-" }
        return "GPS ${TripLog.formatLocation(location)} $snapText hdg=$headingText " +
            "nearby=$nearbyCount($typeSummary) ${nearbyDurationMs}ms " +
            "path=$pathSummary stretch=$stretchSummary enter=$enterSummary " +
            "limit=$limitSummary cand=$candidateSummary alert=${if (notified) "yes" else "no"}"
    }
    companion object {
        private const val TAG = "VegTrackingService"
        private const val LOCATION_INTERVAL_MS = 1_000L
        private const val LOCATION_FASTEST_INTERVAL_MS = 500L
    }
}
