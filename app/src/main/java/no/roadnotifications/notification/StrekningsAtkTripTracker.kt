package no.roadnotifications.notification

import kotlin.math.roundToInt

/**
 * One start alert per stay on a 775 stretch, then one end alert at the exit
 * (or shortly after leaving) with average speed over the trip.
 *
 * Short GPS drops off the polyline must not look like a finished section.
 */
class StrekningsAtkTripTracker {
    var alertedThisStay: Boolean = false
        private set

    private var lastActiveElapsedRealtimeMs: Long = Long.MIN_VALUE / 2
    private var lastOnStretchElapsedRealtimeMs: Long = Long.MIN_VALUE / 2
    private var tripStartedAtMs: Long = 0L
    private var accumulatedMeters: Float = 0f
    private var lastLatitude: Double = Double.NaN
    private var lastLongitude: Double = Double.NaN
    private var pendingEndAlert: Boolean = false
    private var endAlertedThisStay: Boolean = false

    fun onTick(
        onSection: Boolean,
        nearExit: Boolean,
        signInWindow: Boolean,
        latitude: Double,
        longitude: Double,
        nowElapsedRealtimeMs: Long,
    ) {
        if (onSection || signInWindow) {
            lastActiveElapsedRealtimeMs = nowElapsedRealtimeMs
        }
        if (onSection) {
            lastOnStretchElapsedRealtimeMs = nowElapsedRealtimeMs
            if (tripStartedAtMs == 0L) {
                tripStartedAtMs = nowElapsedRealtimeMs
            }
            if (!lastLatitude.isNaN()) {
                accumulatedMeters += planarDistanceMeters(
                    lastLatitude,
                    lastLongitude,
                    latitude,
                    longitude,
                )
            }
            lastLatitude = latitude
            lastLongitude = longitude
            if (nearExit && canFireEnd(nowElapsedRealtimeMs)) {
                pendingEndAlert = true
            }
            return
        }
        lastLatitude = Double.NaN
        lastLongitude = Double.NaN
        if (tripStartedAtMs != 0L &&
            !endAlertedThisStay &&
            lastOnStretchElapsedRealtimeMs != Long.MIN_VALUE / 2
        ) {
            val offStretchMs = nowElapsedRealtimeMs - lastOnStretchElapsedRealtimeMs
            if (offStretchMs >= END_AFTER_LEAVE_MS && canFireEnd(nowElapsedRealtimeMs)) {
                pendingEndAlert = true
            }
        }
        if (tripStartedAtMs == 0L && !alertedThisStay) {
            return
        }
        val inactiveForMs = nowElapsedRealtimeMs - lastActiveElapsedRealtimeMs
        if (inactiveForMs >= GRACE_AFTER_LEAVE_MS) {
            resetStay()
        }
    }

    fun markAlerted() {
        alertedThisStay = true
    }

    fun suppressPathMatch(wasOnSection: Boolean): Boolean {
        return wasOnSection || alertedThisStay
    }

    fun suppressEnter(): Boolean {
        return alertedThisStay
    }

    fun consumeEndAlert(): Int? {
        if (!pendingEndAlert || endAlertedThisStay) {
            return null
        }
        pendingEndAlert = false
        endAlertedThisStay = true
        return averageSpeedKmh()
    }

    private fun canFireEnd(nowElapsedRealtimeMs: Long): Boolean {
        if (endAlertedThisStay || tripStartedAtMs == 0L) {
            return false
        }
        val stretchEndMs = if (lastOnStretchElapsedRealtimeMs == Long.MIN_VALUE / 2) {
            nowElapsedRealtimeMs
        } else {
            lastOnStretchElapsedRealtimeMs
        }
        val elapsedMs = stretchEndMs - tripStartedAtMs
        return elapsedMs >= MIN_TRIP_MS || accumulatedMeters >= MIN_TRIP_METERS
    }

    private fun averageSpeedKmh(): Int {
        val elapsedMs = lastOnStretchElapsedRealtimeMs - tripStartedAtMs
        if (elapsedMs <= 0L || accumulatedMeters <= 0f) {
            return 0
        }
        val metersPerSecond = accumulatedMeters / (elapsedMs / 1000f)
        return (metersPerSecond * 3.6f).roundToInt().coerceAtLeast(0)
    }

    private fun resetStay() {
        alertedThisStay = false
        pendingEndAlert = false
        endAlertedThisStay = false
        tripStartedAtMs = 0L
        accumulatedMeters = 0f
        lastLatitude = Double.NaN
        lastLongitude = Double.NaN
        lastOnStretchElapsedRealtimeMs = Long.MIN_VALUE / 2
    }

    companion object {
        const val GRACE_AFTER_LEAVE_MS = 90_000L
        const val END_AFTER_LEAVE_MS = 12_000L
        const val MIN_TRIP_MS = 15_000L
        const val MIN_TRIP_METERS = 200f
        const val SYNTHETIC_SLUTT_ID = -5562L
        const val SLUTT_VERDI_PREFIX = "SLUTT:"
        private const val METERS_PER_DEGREE_LATITUDE = 111_320.0

        private fun planarDistanceMeters(
            fromLatitude: Double,
            fromLongitude: Double,
            toLatitude: Double,
            toLongitude: Double,
        ): Float {
            val meanLatitude = Math.toRadians((fromLatitude + toLatitude) / 2.0)
            val northing = (toLatitude - fromLatitude) * METERS_PER_DEGREE_LATITUDE
            val easting = (toLongitude - fromLongitude) *
                METERS_PER_DEGREE_LATITUDE *
                kotlin.math.cos(meanLatitude)
            return kotlin.math.hypot(easting, northing).toFloat()
        }
    }
}
