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
import no.roadnotifications.data.ForkjoersveiIds
import no.roadnotifications.data.VegDatabase
import no.roadnotifications.data.VegObjektDao
import no.roadnotifications.data.VegObjektEntity
import no.roadnotifications.data.VegObjektType
import no.roadnotifications.location.BoundingBox
import no.roadnotifications.location.KommuneMatcher
import no.roadnotifications.location.LocationDistance
import no.roadnotifications.location.PackedPolyline
import no.roadnotifications.location.RoadMatcher
import no.roadnotifications.location.SpeedLimitMatcher
import no.roadnotifications.location.TravelPathOffset
import no.roadnotifications.log.TripLog
import no.roadnotifications.notification.AlertCandidate
import no.roadnotifications.notification.AlertPriority
import no.roadnotifications.notification.ForkjoersveiStayTracker
import no.roadnotifications.notification.VegNotificationManager
import java.util.Locale

class VegTrackingService : LifecycleService() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var vegNotificationManager: VegNotificationManager
    @Volatile
    private var roadMatcher: RoadMatcher? = null
    private var lastCheckedLocation: Location? = null
    private var activeStretchIds: Set<Long> = emptySet()
    private var lastOnRoadFartVerdi: String? = null
    private var lastKommuneId: Long? = null
    private var pendingKommuneAlert: VegObjektEntity? = null
    private val forkjoersveiStayTracker = ForkjoersveiStayTracker()
    private val strekningsAtkStayTracker = ForkjoersveiStayTracker()

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
            val alertBox = LocationDistance.boundingBoxAround(
                queryLocation.latitude,
                queryLocation.longitude,
                LocationDistance.QUERY_RADIUS_METERS,
            )
            val stretchBox = LocationDistance.boundingBoxAround(
                queryLocation.latitude,
                queryLocation.longitude,
                LocationDistance.STRETCH_QUERY_RADIUS_METERS,
            )
            val nearbyStartedAtMs = SystemClock.elapsedRealtime()
            val dao = VegDatabase.getInstance(applicationContext).vegObjektDao()
            val nearby = try {
                dao.nearbyAlertPoints(
                    minLat = alertBox.minLat,
                    maxLat = alertBox.maxLat,
                    minLon = alertBox.minLon,
                    maxLon = alertBox.maxLon,
                )
            } catch (error: Throwable) {
                TripLog.append(
                    "ERROR nearby ${error.javaClass.simpleName}: ${error.message}",
                )
                emptyList()
            }
            val stretchObjects = loadNearbyStretches(dao, stretchBox, nearby)
            val nearbyDurationMs = SystemClock.elapsedRealtime() - nearbyStartedAtMs
            val onStretch = stretchObjects.filter { vegObjekt ->
                isOnStretch(
                    vegObjekt = vegObjekt,
                    queryLocation = queryLocation,
                    travelHeadingDegrees = travelHeadingDegrees,
                )
            }
            val enteringStretch = onStretch.filter { vegObjekt ->
                ForkjoersveiIds.stretchGroupId(vegObjekt) !in activeStretchIds
            }
            val onPriorityRoad = onStretch.any { vegObjekt ->
                vegObjekt.type == VegObjektType.FORKJOERSVEI.name
            }
            val wasOnPriorityRoad = onStretch.any { vegObjekt ->
                vegObjekt.type == VegObjektType.FORKJOERSVEI.name &&
                    ForkjoersveiIds.stretchGroupId(vegObjekt) in activeStretchIds
            }
            val onStrekningsAtk = onStretch.any { vegObjekt ->
                vegObjekt.type == VegObjektType.STREKNINGS_ATK.name
            }
            val wasOnStrekningsAtk = onStretch.any { vegObjekt ->
                vegObjekt.type == VegObjektType.STREKNINGS_ATK.name &&
                    ForkjoersveiIds.stretchGroupId(vegObjekt) in activeStretchIds
            }
            activeStretchIds = onStretch.map { vegObjekt ->
                ForkjoersveiIds.stretchGroupId(vegObjekt)
            }.toSet()
            val currentSpeedLimit = SpeedLimitMatcher.pickCurrent(
                aligned = SpeedLimitMatcher.alignedLimits(
                    onStretch = onStretch,
                    latitude = queryLocation.latitude,
                    longitude = queryLocation.longitude,
                    travelHeadingDegrees = travelHeadingDegrees,
                ),
                previousVerdi = lastOnRoadFartVerdi,
            )
            val speedLimitChanged = SpeedLimitMatcher.shouldAlert(
                currentVerdi = currentSpeedLimit?.vegObjekt?.verdi,
                previousVerdi = lastOnRoadFartVerdi,
            )
            val nearbyPathOffsets = nearby
                .mapNotNull { vegObjekt ->
                    if (vegObjekt.type == VegObjektType.KOMMUNE.name) {
                        return@mapNotNull null
                    }
                    val pathOffset = LocationDistance.travelPathOffset(
                        currentLocation = queryLocation,
                        previousLocation = previousLocation,
                        targetLatitude = vegObjekt.lat,
                        targetLongitude = vegObjekt.lon,
                        travelHeadingOverrideDegrees = travelHeadingDegrees,
                    ) ?: return@mapNotNull null
                    vegObjekt to pathOffset
                }
            val allPathMatches = nearbyPathOffsets
                .mapNotNull { (vegObjekt, pathOffset) ->
                    if (vegObjekt.type == VegObjektType.FART.name && currentSpeedLimit != null) {
                        return@mapNotNull null
                    }
                    if (!LocationDistance.matchesTravelPath(
                            offset = pathOffset,
                            objektType = vegObjekt.type,
                            retning = vegObjekt.retning,
                            vegRetningGrader = vegObjekt.vegRetningGrader,
                        )
                    ) {
                        return@mapNotNull null
                    }
                    vegObjekt to pathOffset
                }
            val higherImportanceApproaching = nearbyPathOffsets.any { (vegObjekt, pathOffset) ->
                AlertPriority.isLookaheadSuppressor(
                    objektType = vegObjekt.type,
                    offset = pathOffset,
                    retning = vegObjekt.retning,
                    vegRetningGrader = vegObjekt.vegRetningGrader,
                )
            }
            val prioritySignInWindow = allPathMatches.any { (vegObjekt, _) ->
                vegObjekt.type == VegObjektType.FORKJOERSVEI.name
            }
            val strekningsAtkSignInWindow = allPathMatches.any { (vegObjekt, _) ->
                vegObjekt.type == VegObjektType.STREKNINGS_ATK.name
            }
            val nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
            forkjoersveiStayTracker.onTick(
                onPriorityRoad = onPriorityRoad,
                prioritySignInWindow = prioritySignInWindow,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            )
            strekningsAtkStayTracker.onTick(
                onPriorityRoad = onStrekningsAtk,
                prioritySignInWindow = strekningsAtkSignInWindow,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            )
            val pathMatches = allPathMatches.filter { (vegObjekt, _) ->
                when (vegObjekt.type) {
                    VegObjektType.FORKJOERSVEI.name ->
                        !forkjoersveiStayTracker.suppressPathMatch(wasOnPriorityRoad)
                    VegObjektType.STREKNINGS_ATK.name ->
                        !strekningsAtkStayTracker.suppressPathMatch(wasOnStrekningsAtk)
                    VegObjektType.FOTOBOKS.name -> !onStrekningsAtk
                    else -> true
                }
            }
            val stretchMatches = enteringStretch
                .filter { vegObjekt -> vegObjekt.type != VegObjektType.FART.name }
                .filter { vegObjekt ->
                    when (vegObjekt.type) {
                        VegObjektType.FORKJOERSVEI.name ->
                            !forkjoersveiStayTracker.suppressEnter()
                        VegObjektType.STREKNINGS_ATK.name ->
                            !strekningsAtkStayTracker.suppressEnter()
                        else -> true
                    }
                }
                .map { vegObjekt ->
                    vegObjekt to TravelPathOffset(
                        distanceMeters = 0f,
                        alongTrackMeters = 0f,
                        crossTrackMeters = 0f,
                        headingDeltaDegrees = 0f,
                        travelHeadingDegrees = travelHeadingDegrees ?: 0f,
                    )
                }
            val speedLimitMatch = if (speedLimitChanged && currentSpeedLimit != null) {
                listOf(
                    currentSpeedLimit.vegObjekt to TravelPathOffset(
                        distanceMeters = currentSpeedLimit.distanceMeters,
                        alongTrackMeters = 0f,
                        crossTrackMeters = currentSpeedLimit.distanceMeters,
                        headingDeltaDegrees = currentSpeedLimit.headingDeltaDegrees,
                        travelHeadingDegrees = travelHeadingDegrees ?: 0f,
                    ),
                )
            } else {
                emptyList()
            }
            val gpsAccuracyTooPoor = queryLocation.hasAccuracy() &&
                queryLocation.accuracy > LocationDistance.MAX_GPS_ACCURACY_METERS
            val currentKommune = if (gpsAccuracyTooPoor) {
                null
            } else {
                val kommuneCandidates = try {
                    dao.kommunerContaining(
                        latitude = queryLocation.latitude,
                        longitude = queryLocation.longitude,
                    )
                } catch (error: Throwable) {
                    TripLog.append(
                        "ERROR kommune ${error.javaClass.simpleName}: ${error.message}",
                    )
                    emptyList()
                }
                KommuneMatcher.pickCurrent(
                    candidates = kommuneCandidates,
                    latitude = queryLocation.latitude,
                    longitude = queryLocation.longitude,
                )
            }
            val enteredKommune = if (
                currentKommune != null &&
                lastKommuneId != null &&
                currentKommune.id != lastKommuneId
            ) {
                currentKommune
            } else {
                null
            }
            if (currentKommune != null) {
                lastKommuneId = currentKommune.id
            }
            if (enteredKommune != null) {
                pendingKommuneAlert = enteredKommune
            }
            if (pendingKommuneAlert != null &&
                currentKommune != null &&
                pendingKommuneAlert?.id != currentKommune.id
            ) {
                pendingKommuneAlert = null
            }
            val kommuneMatch = pendingKommuneAlert?.let { vegObjekt ->
                listOf(
                    vegObjekt to TravelPathOffset(
                        distanceMeters = 0f,
                        alongTrackMeters = 0f,
                        crossTrackMeters = 0f,
                        headingDeltaDegrees = 0f,
                        travelHeadingDegrees = travelHeadingDegrees ?: 0f,
                    ),
                )
            } ?: emptyList()
            val closestByType = (pathMatches + stretchMatches + speedLimitMatch + kommuneMatch)
                .groupBy { (vegObjekt, _) -> vegObjekt.type }
                .map { (_, typedObjects) ->
                    typedObjects.minWith(
                        compareBy(
                            { (_, pathOffset) -> pathOffset.crossTrackMeters },
                            { (_, pathOffset) -> pathOffset.headingDeltaDegrees },
                            { (vegObjekt, _) ->
                                if (vegObjekt.retning.isNullOrBlank() ||
                                    vegObjekt.vegRetningGrader == null
                                ) {
                                    1
                                } else {
                                    0
                                }
                            },
                            { (_, pathOffset) -> pathOffset.alongTrackMeters },
                        ),
                    )
                }
            val candidates = closestByType.map { (vegObjekt, pathOffset) ->
                AlertCandidate(
                    vegObjekt = vegObjekt,
                    alongTrackMeters = pathOffset.alongTrackMeters,
                )
            }
            lastOnRoadFartVerdi = currentSpeedLimit?.vegObjekt?.verdi?.trim()
                ?: candidates
                    .find { candidate -> candidate.vegObjekt.type == VegObjektType.FART.name }
                    ?.vegObjekt
                    ?.verdi
                    ?.trim()
                ?: lastOnRoadFartVerdi
            val matchingObjektIds = (
                allPathMatches.map { (vegObjekt, _) -> vegObjekt.id } +
                    stretchMatches.map { (vegObjekt, _) -> vegObjekt.id } +
                    speedLimitMatch.map { (vegObjekt, _) -> vegObjekt.id } +
                    listOfNotNull(pendingKommuneAlert?.id)
                ).toSet()
            val notified = vegNotificationManager.notifyIfNeeded(
                candidates,
                matchingObjektIds,
                higherImportanceApproaching = higherImportanceApproaching,
            )
            if (notified.any { candidate ->
                    candidate.vegObjekt.type == VegObjektType.KOMMUNE.name
                }
            ) {
                pendingKommuneAlert = null
            }
            if (notified.any { candidate ->
                    candidate.vegObjekt.type == VegObjektType.FORKJOERSVEI.name
                }
            ) {
                forkjoersveiStayTracker.markAlerted()
            }
            if (notified.any { candidate ->
                    candidate.vegObjekt.type == VegObjektType.STREKNINGS_ATK.name
                }
            ) {
                strekningsAtkStayTracker.markAlerted()
            }
            TripLog.append(
                buildCheckLogLine(
                    location = location,
                    matchRoadName = match?.name,
                    matchEdgeId = match?.edgeId,
                    matchDistanceMeters = match?.distanceFromGpsMeters,
                    queryLocation = queryLocation,
                    travelHeadingDegrees = travelHeadingDegrees,
                    nearbyCount = nearby.size,
                    nearbyDurationMs = nearbyDurationMs,
                    nearbyTypeCounts = nearby.groupingBy { vegObjekt -> vegObjekt.type }.eachCount(),
                    pathMatches = pathMatches,
                    onStretch = onStretch,
                    enteringStretch = enteringStretch,
                    currentSpeedLimit = currentSpeedLimit?.vegObjekt,
                    candidates = candidates,
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

    private suspend fun loadNearbyStretches(
        dao: VegObjektDao,
        stretchBox: BoundingBox,
        nearbyAlertPoints: List<VegObjektEntity>,
    ): List<VegObjektEntity> {
        val fromAlertPoints = nearbyAlertPoints.filter { vegObjekt ->
            LocationDistance.isStretchType(vegObjekt.type)
        }
        val stretchIds = try {
            dao.nearbyStretchIds(
                minLat = stretchBox.minLat,
                maxLat = stretchBox.maxLat,
                minLon = stretchBox.minLon,
                maxLon = stretchBox.maxLon,
            )
        } catch (error: Throwable) {
            TripLog.append(
                "ERROR stretch ${error.javaClass.simpleName}: ${error.message}",
            )
            return fromAlertPoints
        }
        val missingIds = stretchIds.filter { objektId ->
            fromAlertPoints.none { vegObjekt -> vegObjekt.id == objektId }
        }
        if (missingIds.isEmpty()) {
            return fromAlertPoints
        }
        val extraStretches = try {
            dao.byIds(missingIds)
        } catch (error: Throwable) {
            TripLog.append(
                "ERROR stretchIds ${error.javaClass.simpleName}: ${error.message}",
            )
            return fromAlertPoints
        }
        return fromAlertPoints + extraStretches
    }

    private fun isOnStretch(
        vegObjekt: VegObjektEntity,
        queryLocation: Location,
        travelHeadingDegrees: Float?,
    ): Boolean {
        if (!LocationDistance.isStretchType(vegObjekt.type)) {
            return false
        }
        if (travelHeadingDegrees != null &&
            !LocationDistance.matchesLokRetning(
                travelHeadingDegrees = travelHeadingDegrees,
                retning = vegObjekt.retning,
                vegRetningGrader = vegObjekt.vegRetningGrader,
            )
        ) {
            return false
        }
        val points = PackedPolyline.unpack(vegObjekt.points)
        if (points.size < 2) {
            return false
        }
        val distanceMeters = PackedPolyline.minDistanceMeters(
            latitude = queryLocation.latitude,
            longitude = queryLocation.longitude,
            points = points,
        )
        return distanceMeters <= LocationDistance.STRETCH_ON_ROAD_METERS
    }

    companion object {
        private const val TAG = "VegTrackingService"
        private const val LOCATION_INTERVAL_MS = 1_000L
        private const val LOCATION_FASTEST_INTERVAL_MS = 500L
    }
}
