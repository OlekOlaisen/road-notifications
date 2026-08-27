package no.roadnotifications.location

import android.location.Location
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import no.roadnotifications.data.VegObjektType

data class BoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
)

data class TravelPathOffset(
    val distanceMeters: Float,
    val alongTrackMeters: Float,
    val crossTrackMeters: Float,
    val headingDeltaDegrees: Float,
    val travelHeadingDegrees: Float,
)

/**
 * Matching is "am I approaching this sign within its own alert distance?",
 * not "is anything within a shared radius?".
 */
object LocationDistance {
    const val MIN_MOVEMENT_METERS = 5f

    /**
     * Database query radius must cover the longest advance warning (fotoboks).
     */
    const val QUERY_RADIUS_METERS = 400.0

    /**
     * Above this horizontal accuracy, GPS is too noisy to snap onto a new road
     * (typical in tunnels and under bridges).
     */
    const val MAX_GPS_ACCURACY_METERS = 25f

    /**
     * How close the snapped position must be to a stretch polyline to count
     * as being on that speed-limit / priority-road zone. OSM and NVDB
     * centerlines often differ by more than 15 m.
     */
    const val STRETCH_ON_ROAD_METERS = 32f

    /**
     * Query radius for polyline-segment lookup. Slightly larger than
     * [STRETCH_ON_ROAD_METERS] so a hit is not lost to bbox rounding.
     */
    const val STRETCH_QUERY_RADIUS_METERS = 40.0

    /**
     * Prefer alerting ahead of / at the sign, but still allow a short distance
     * after passing so GPS lag does not drop the alert entirely.
     */
    const val MIN_ALONG_TRACK_METERS = -40f

    /**
     * Signs that should fire at the plate / boom, not as advance warning.
     * A few meters of lead covers GPS lag without becoming a 70–120 m preview.
     */
    const val AT_SIGN_ALONG_TRACK_METERS = 20f

    /**
     * Yield plates sit at the give-way line, often a few meters off the
     * centerline. Alert on the approach road before the turn, not only in
     * the last 20 m where heading already swings into the crossing.
     */
    const val YIELD_ALONG_TRACK_METERS = 50f

    private const val MIN_HEADING_SPEED_METERS_PER_SECOND = 1.5f
    private const val MIN_HEADING_MOVEMENT_METERS = 5f
    private const val METERS_PER_DEGREE_LATITUDE = 111_320.0
    private const val MAX_LOK_RETNING_HEADING_DELTA_DEGREES = 70f
    private const val CROSS_TRACK_ALONG_RATIO = 0.22f
    private const val MIN_DYNAMIC_CROSS_TRACK_METERS = 12f

    fun hasMovedEnough(previous: Location?, current: Location): Boolean {
        if (previous == null) {
            return true
        }
        return previous.distanceTo(current) >= MIN_MOVEMENT_METERS
    }

    fun boundingBoxAround(latitude: Double, longitude: Double, radiusMeters: Double): BoundingBox {
        val latitudeDelta = radiusMeters / METERS_PER_DEGREE_LATITUDE
        val longitudeScale = max(cos(Math.toRadians(latitude)), 0.01)
        val longitudeDelta = radiusMeters / (METERS_PER_DEGREE_LATITUDE * longitudeScale)
        return BoundingBox(
            minLat = latitude - latitudeDelta,
            maxLat = latitude + latitudeDelta,
            minLon = longitude - longitudeDelta,
            maxLon = longitude + longitudeDelta,
        )
    }

    fun distanceMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Float {
        val results = FloatArray(1)
        Location.distanceBetween(fromLatitude, fromLongitude, toLatitude, toLongitude, results)
        return results[0]
    }

    fun alertAlongTrackMeters(objektType: String): Float {
        return when (objektType) {
            VegObjektType.FART.name -> 90f
            VegObjektType.FORKJOERSVEI.name -> AT_SIGN_ALONG_TRACK_METERS
            VegObjektType.BOM.name -> AT_SIGN_ALONG_TRACK_METERS
            VegObjektType.VILTFARE.name -> AT_SIGN_ALONG_TRACK_METERS
            VegObjektType.FOTOBOKS.name -> 350f
            VegObjektType.STREKNINGS_ATK.name -> AT_SIGN_ALONG_TRACK_METERS
            VegObjektType.JERNBANE.name -> 200f
            VegObjektType.FERJEKAI.name -> 150f
            VegObjektType.STOPP.name -> 80f
            VegObjektType.VIKEPLIKT.name -> YIELD_ALONG_TRACK_METERS
            VegObjektType.FARLIG_SVING.name -> 80f
            VegObjektType.FARLIG_VEGKRYSS.name -> 80f
            VegObjektType.SMALERE_VEG.name -> 90f
            VegObjektType.TUNNEL.name -> 160f
            VegObjektType.SLUTT_FORKJOERSVEI.name -> AT_SIGN_ALONG_TRACK_METERS
            else -> 70f
        }
    }

    fun travelPathOffset(
        currentLocation: Location,
        previousLocation: Location?,
        targetLatitude: Double,
        targetLongitude: Double,
        travelHeadingOverrideDegrees: Float? = null,
    ): TravelPathOffset? {
        val headingDegrees = travelHeadingOverrideDegrees
            ?: headingDegrees(currentLocation, previousLocation)
            ?: return null
        val distanceAndBearing = FloatArray(2)
        Location.distanceBetween(
            currentLocation.latitude,
            currentLocation.longitude,
            targetLatitude,
            targetLongitude,
            distanceAndBearing,
        )
        val distanceMeters = distanceAndBearing[0]
        val bearingToTargetDegrees = distanceAndBearing[1]
        val headingDeltaDegrees = abs(
            signedHeadingDeltaDegrees(headingDegrees, bearingToTargetDegrees),
        )
        val headingDeltaRadians = Math.toRadians(headingDeltaDegrees.toDouble())
        val alongTrackMeters = (distanceMeters * cos(headingDeltaRadians)).toFloat()
        val crossTrackMeters = abs((distanceMeters * sin(headingDeltaRadians)).toFloat())
        return TravelPathOffset(
            distanceMeters = distanceMeters,
            alongTrackMeters = alongTrackMeters,
            crossTrackMeters = crossTrackMeters,
            headingDeltaDegrees = headingDeltaDegrees,
            travelHeadingDegrees = headingDegrees,
        )
    }

    fun matchesTravelPath(
        offset: TravelPathOffset,
        objektType: String,
        retning: String? = null,
        vegRetningGrader: Float? = null,
    ): Boolean {
        val maxAlongTrackMeters = alertAlongTrackMeters(objektType)
        if (offset.alongTrackMeters < MIN_ALONG_TRACK_METERS) {
            return false
        }
        if (offset.alongTrackMeters > maxAlongTrackMeters) {
            return false
        }
        if (offset.distanceMeters > maxAlongTrackMeters + 10f) {
            return false
        }
        val maxCrossTrackMeters = effectiveMaxCrossTrackMeters(
            objektType = objektType,
            alongTrackMeters = offset.alongTrackMeters,
        )
        if (offset.crossTrackMeters > maxCrossTrackMeters) {
            return false
        }
        if (offset.headingDeltaDegrees > maxHeadingDeltaDegrees(objektType)) {
            return false
        }
        if (!matchesLokRetning(offset.travelHeadingDegrees, retning, vegRetningGrader)) {
            return false
        }
        return true
    }

    /**
     * [retning] MED/MOT is relative to road metrering. [vegRetningGrader] is the compass
     * bearing of the MED direction when known from LINESTRING geometry.
     */
    fun matchesLokRetning(
        travelHeadingDegrees: Float,
        retning: String?,
        vegRetningGrader: Float?,
    ): Boolean {
        if (retning.isNullOrBlank() || vegRetningGrader == null) {
            return true
        }
        val expectedHeadingDegrees = when (retning.trim().uppercase()) {
            "MED" -> vegRetningGrader
            "MOT" -> (vegRetningGrader + 180f) % 360f
            else -> return true
        }
        val headingErrorDegrees = abs(
            signedHeadingDeltaDegrees(travelHeadingDegrees, expectedHeadingDegrees),
        )
        return headingErrorDegrees <= MAX_LOK_RETNING_HEADING_DELTA_DEGREES
    }

    private fun effectiveMaxCrossTrackMeters(objektType: String, alongTrackMeters: Float): Float {
        val absoluteMaxCrossTrackMeters = maxCrossTrackMeters(objektType)
        if (objektType == VegObjektType.FART.name ||
            objektType == VegObjektType.FORKJOERSVEI.name ||
            objektType == VegObjektType.STREKNINGS_ATK.name ||
            objektType == VegObjektType.VIKEPLIKT.name
        ) {
            return absoluteMaxCrossTrackMeters
        }
        val alongTrackForRatio = abs(alongTrackMeters)
        val dynamicMaxCrossTrackMeters = max(
            MIN_DYNAMIC_CROSS_TRACK_METERS,
            alongTrackForRatio * CROSS_TRACK_ALONG_RATIO,
        )
        return minOf(absoluteMaxCrossTrackMeters, dynamicMaxCrossTrackMeters)
    }

    private fun maxCrossTrackMeters(objektType: String): Float {
        return when (objektType) {
            VegObjektType.FART.name -> 24f
            VegObjektType.FORKJOERSVEI.name -> 35f
            VegObjektType.BOM.name -> 18f
            VegObjektType.FOTOBOKS.name -> 28f
            VegObjektType.STREKNINGS_ATK.name -> 28f
            VegObjektType.VILTFARE.name -> 22f
            VegObjektType.JERNBANE.name -> 30f
            VegObjektType.FERJEKAI.name -> 40f
            VegObjektType.STOPP.name -> 22f
            VegObjektType.VIKEPLIKT.name -> 35f
            VegObjektType.FARLIG_SVING.name -> 28f
            VegObjektType.FARLIG_VEGKRYSS.name -> 26f
            VegObjektType.SMALERE_VEG.name -> 22f
            VegObjektType.TUNNEL.name -> 35f
            VegObjektType.SLUTT_FORKJOERSVEI.name -> 16f
            else -> 20f
        }
    }

    private fun maxHeadingDeltaDegrees(objektType: String): Float {
        return when (objektType) {
            VegObjektType.FART.name -> 25f
            VegObjektType.FORKJOERSVEI.name -> 32f
            VegObjektType.BOM.name -> 18f
            VegObjektType.FOTOBOKS.name -> 20f
            VegObjektType.STREKNINGS_ATK.name -> 20f
            VegObjektType.VILTFARE.name -> 22f
            VegObjektType.JERNBANE.name -> 25f
            VegObjektType.FERJEKAI.name -> 35f
            VegObjektType.STOPP.name -> 22f
            VegObjektType.VIKEPLIKT.name -> 32f
            VegObjektType.FARLIG_SVING.name -> 25f
            VegObjektType.FARLIG_VEGKRYSS.name -> 24f
            VegObjektType.SMALERE_VEG.name -> 22f
            VegObjektType.TUNNEL.name -> 30f
            VegObjektType.SLUTT_FORKJOERSVEI.name -> 20f
            else -> 20f
        }
    }

    fun headingDeltaDegrees(fromDegrees: Float, toDegrees: Float): Float {
        return abs(signedHeadingDeltaDegrees(fromDegrees, toDegrees))
    }

    fun isStretchType(objektType: String): Boolean {
        return objektType == VegObjektType.FART.name ||
            objektType == VegObjektType.FORKJOERSVEI.name ||
            objektType == VegObjektType.STREKNINGS_ATK.name
    }

    fun headingDegrees(currentLocation: Location, previousLocation: Location?): Float? {
        if (currentLocation.hasBearing() &&
            currentLocation.speed >= MIN_HEADING_SPEED_METERS_PER_SECOND
        ) {
            return currentLocation.bearing
        }
        if (previousLocation != null &&
            currentLocation.distanceTo(previousLocation) >= MIN_HEADING_MOVEMENT_METERS
        ) {
            return previousLocation.bearingTo(currentLocation)
        }
        if (currentLocation.hasBearing()) {
            return currentLocation.bearing
        }
        return null
    }

    private fun signedHeadingDeltaDegrees(fromDegrees: Float, toDegrees: Float): Float {
        var delta = toDegrees - fromDegrees
        while (delta > 180f) {
            delta -= 360f
        }
        while (delta < -180f) {
            delta += 360f
        }
        return delta
    }
}
