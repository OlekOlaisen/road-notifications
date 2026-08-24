package no.roadnotifications.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Looper
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
import kotlinx.coroutines.withContext
import no.roadnotifications.data.VegDatabase
import no.roadnotifications.location.LocationDistance
import no.roadnotifications.notification.AlertCandidate
import no.roadnotifications.notification.VegNotificationManager

class VegTrackingService : LifecycleService() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var vegNotificationManager: VegNotificationManager
    private var lastCheckedLocation: Location? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            if (!LocationDistance.hasMovedEnough(lastCheckedLocation, location)) {
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startAsForeground()
        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
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
        lifecycleScope.launch {
            val boundingBox = LocationDistance.boundingBoxAround(
                location.latitude,
                location.longitude,
                LocationDistance.QUERY_RADIUS_METERS,
            )
            val nearby = withContext(Dispatchers.IO) {
                VegDatabase.getInstance(applicationContext)
                    .vegObjektDao()
                    .nearby(
                        minLat = boundingBox.minLat,
                        maxLat = boundingBox.maxLat,
                        minLon = boundingBox.minLon,
                        maxLon = boundingBox.maxLon,
                    )
            }
            val pathMatches = nearby
                .mapNotNull { vegObjekt ->
                    val pathOffset = LocationDistance.travelPathOffset(
                        currentLocation = location,
                        previousLocation = previousLocation,
                        targetLatitude = vegObjekt.lat,
                        targetLongitude = vegObjekt.lon,
                    ) ?: return@mapNotNull null
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
            val closestByType = pathMatches
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
            vegNotificationManager.notifyIfNeeded(
                closestByType.map { (vegObjekt, pathOffset) ->
                    AlertCandidate(
                        vegObjekt = vegObjekt,
                        alongTrackMeters = pathOffset.alongTrackMeters,
                    )
                },
                location,
            )
        }
    }

    companion object {
        private const val LOCATION_INTERVAL_MS = 1_000L
        private const val LOCATION_FASTEST_INTERVAL_MS = 500L
    }
}
