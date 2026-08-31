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

private data class AlertWindowSpec(
    val seconds: Float,
    val minMeters: Float,
    val maxMeters: Float,
    val fallbackMeters: Float,
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

    fun hasMovedEnough(previous: GpsFix?, current: GpsFix): Boolean {
        if (previous == null) {
            return true
        }
        return GeoMath.distanceMeters(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude,
        ) >= MIN_MOVEMENT_METERS
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
        return GeoMath.distanceMeters(
            fromLatitude,
            fromLongitude,
            toLatitude,
            toLongitude,
        )
    }

    fun alertAlongTrackMeters(
        objektType: String,
        speedMetersPerSecond: Float? = null,
    ): Float {
        val spec = alertWindowSpec(objektType)
        if (speedMetersPerSecond == null || speedMetersPerSecond <= 0f) {
            return spec.fallbackMeters
        }
        return (speedMetersPerSecond * spec.seconds).coerceIn(
            spec.minMeters,
            spec.maxMeters,
        )
    }

    private fun alertWindowSpec(objektType: String): AlertWindowSpec {
        return when (objektType) {
            VegObjektType.FOTOBOKS.name ->
                AlertWindowSpec(seconds = 12f, minMeters = 200f, maxMeters = 400f, fallbackMeters = 350f)
            VegObjektType.STREKNINGS_ATK.name ->
                AlertWindowSpec(seconds = 3f, minMeters = 20f, maxMeters = 60f, fallbackMeters = AT_SIGN_ALONG_TRACK_METERS)
            VegObjektType.JERNBANE.name ->
                AlertWindowSpec(seconds = 8f, minMeters = 80f, maxMeters = 250f, fallbackMeters = 200f)
            VegObjektType.STOPP.name ->
                AlertWindowSpec(seconds = 5f, minMeters = 30f, maxMeters = 70f, fallbackMeters = 80f)
            VegObjektType.VIKEPLIKT.name,
            VegObjektType.FORKJOERSVEI.name,
            VegObjektType.SLUTT_FORKJOERSVEI.name,
            VegObjektType.SLUTT_FART.name ->
                AlertWindowSpec(seconds = 5f, minMeters = 30f, maxMeters = 70f, fallbackMeters = YIELD_ALONG_TRACK_METERS)
            VegObjektType.FARLIG_SVING.name,
            VegObjektType.FARLIG_VEGKRYSS.name ->
                AlertWindowSpec(seconds = 6f, minMeters = 50f, maxMeters = 120f, fallbackMeters = 80f)
            VegObjektType.SMALERE_VEG.name ->
                AlertWindowSpec(seconds = 6f, minMeters = 50f, maxMeters = 120f, fallbackMeters = 90f)
            VegObjektType.TUNNEL.name ->
                AlertWindowSpec(seconds = 8f, minMeters = 80f, maxMeters = 200f, fallbackMeters = 160f)
            VegObjektType.FERJEKAI.name ->
                AlertWindowSpec(seconds = 8f, minMeters = 80f, maxMeters = 200f, fallbackMeters = 150f)
            VegObjektType.BOM.name,
            VegObjektType.VILTFARE.name ->
                AlertWindowSpec(seconds = 2.5f, minMeters = 15f, maxMeters = 40f, fallbackMeters = AT_SIGN_ALONG_TRACK_METERS)
            VegObjektType.FART.name ->
                AlertWindowSpec(seconds = 6f, minMeters = 50f, maxMeters = 120f, fallbackMeters = 90f)
            else ->
                AlertWindowSpec(seconds = 5f, minMeters = 40f, maxMeters = 90f, fallbackMeters = 70f)
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
        return travelPathOffset(
            currentLatitude = currentLocation.latitude,
            currentLongitude = currentLocation.longitude,
            targetLatitude = targetLatitude,
            targetLongitude = targetLongitude,
            travelHeadingDegrees = headingDegrees,
        )
    }

    fun travelPathOffset(
        currentLatitude: Double,
        currentLongitude: Double,
        targetLatitude: Double,
        targetLongitude: Double,
        travelHeadingDegrees: Float,
    ): TravelPathOffset {
        val distanceMeters = GeoMath.distanceMeters(
            currentLatitude,
            currentLongitude,
            targetLatitude,
            targetLongitude,
        )
        val bearingToTargetDegrees = GeoMath.bearingDegrees(
            currentLatitude,
            currentLongitude,
            targetLatitude,
            targetLongitude,
        )
        val headingDeltaDegrees = abs(
            signedHeadingDeltaDegrees(travelHeadingDegrees, bearingToTargetDegrees),
        )
        val headingDeltaRadians = Math.toRadians(headingDeltaDegrees.toDouble())
        val alongTrackMeters = (distanceMeters * cos(headingDeltaRadians)).toFloat()
        val crossTrackMeters = abs((distanceMeters * sin(headingDeltaRadians)).toFloat())
        return TravelPathOffset(
            distanceMeters = distanceMeters,
            alongTrackMeters = alongTrackMeters,
            crossTrackMeters = crossTrackMeters,
            headingDeltaDegrees = headingDeltaDegrees,
            travelHeadingDegrees = travelHeadingDegrees,
        )
    }

    fun matchesTravelPath(
        offset: TravelPathOffset,
        objektType: String,
        retning: String? = null,
        vegRetningGrader: Float? = null,
        speedMetersPerSecond: Float? = null,
    ): Boolean {
        val maxAlongTrackMeters = alertAlongTrackMeters(
            objektType = objektType,
            speedMetersPerSecond = speedMetersPerSecond,
        )
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
        if (!matchesLokRetning(
                travelHeadingDegrees = offset.travelHeadingDegrees,
                retning = retning,
                vegRetningGrader = vegRetningGrader,
                objektType = objektType,
            )
        ) {
            return false
        }
        return true
    }

    fun travelPathSkipReason(
        offset: TravelPathOffset,
        objektType: String,
        retning: String? = null,
        vegRetningGrader: Float? = null,
        speedMetersPerSecond: Float? = null,
    ): String? {
        val maxAlongTrackMeters = alertAlongTrackMeters(
            objektType = objektType,
            speedMetersPerSecond = speedMetersPerSecond,
        )
        if (offset.alongTrackMeters < MIN_ALONG_TRACK_METERS) {
            return "for langt bak"
        }
        if (offset.alongTrackMeters > maxAlongTrackMeters) {
            return "for langt foran"
        }
        if (offset.distanceMeters > maxAlongTrackMeters + 10f) {
            return "for langt unna"
        }
        val maxCrossTrackMeters = effectiveMaxCrossTrackMeters(
            objektType = objektType,
            alongTrackMeters = offset.alongTrackMeters,
        )
        if (offset.crossTrackMeters > maxCrossTrackMeters) {
            return "for langt til siden"
        }
        if (offset.headingDeltaDegrees > maxHeadingDeltaDegrees(objektType)) {
            return "feil kurs"
        }
        if (!matchesLokRetning(
                travelHeadingDegrees = offset.travelHeadingDegrees,
                retning = retning,
                vegRetningGrader = vegRetningGrader,
                objektType = objektType,
            )
        ) {
            return "feil lok.retning"
        }
        return null
    }

    /**
     * [retning] MED/MOT is relative to road metrering. [vegRetningGrader] is the
     * compass bearing of the MED direction (from LINESTRING, or snapped onto a
     * nearby FART/FORKJOERSVEI segment at import).
     *
     * Applies to every NVDB object that has lok.retning: warning plates
     * (100/102/106/122/124), give-way (202/204/208), ATK, bom, wildlife,
     * railway, ferry, speed limits and priority-road stretches. Missing
     * retning or heading means "both directions".
     */
    fun matchesLokRetning(
        travelHeadingDegrees: Float,
        retning: String?,
        vegRetningGrader: Float?,
        objektType: String? = null,
    ): Boolean {
        if (retning.isNullOrBlank() || vegRetningGrader == null) {
            return objektType != VegObjektType.SLUTT_FART.name
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
            objektType == VegObjektType.SLUTT_FORKJOERSVEI.name ||
            objektType == VegObjektType.SLUTT_FART.name ||
            objektType == VegObjektType.STREKNINGS_ATK.name
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
            VegObjektType.VIKEPLIKT.name -> 24f
            VegObjektType.FARLIG_SVING.name -> 22f
            VegObjektType.FARLIG_VEGKRYSS.name -> 20f
            VegObjektType.SMALERE_VEG.name -> 18f
            VegObjektType.TUNNEL.name -> 35f
            VegObjektType.SLUTT_FORKJOERSVEI.name -> 35f
            VegObjektType.SLUTT_FART.name -> 35f
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
            VegObjektType.VIKEPLIKT.name -> 22f
            VegObjektType.FARLIG_SVING.name -> 20f
            VegObjektType.FARLIG_VEGKRYSS.name -> 20f
            VegObjektType.SMALERE_VEG.name -> 20f
            VegObjektType.TUNNEL.name -> 30f
            VegObjektType.SLUTT_FORKJOERSVEI.name -> 32f
            VegObjektType.SLUTT_FART.name -> 32f
            else -> 20f
        }
    }

    fun headingDeltaDegrees(fromDegrees: Float, toDegrees: Float): Float {
        return abs(signedHeadingDeltaDegrees(fromDegrees, toDegrees))
    }

    fun isStretchType(objektType: String): Boolean {
        return objektType == VegObjektType.FART.name ||
            objektType == VegObjektType.FORKJOERSVEI.name ||
            objektType == VegObjektType.STREKNINGS_ATK.name ||
            objektType == VegObjektType.VILTFARE.name ||
            objektType == VegObjektType.BOM.name ||
            objektType == VegObjektType.JERNBANE.name ||
            objektType == VegObjektType.FERJEKAI.name
    }

    fun usesClosestPolylinePoint(objektType: String): Boolean {
        return objektType == VegObjektType.BOM.name ||
            objektType == VegObjektType.JERNBANE.name ||
            objektType == VegObjektType.FERJEKAI.name
    }

    fun headingDegrees(current: GpsFix, previous: GpsFix?): Float? {
        val currentSpeed = current.speedMetersPerSecond ?: 0f
        if (current.headingDegrees != null &&
            currentSpeed >= MIN_HEADING_SPEED_METERS_PER_SECOND
        ) {
            return current.headingDegrees
        }
        if (previous != null) {
            val movedMeters = GeoMath.distanceMeters(
                previous.latitude,
                previous.longitude,
                current.latitude,
                current.longitude,
            )
            if (movedMeters >= MIN_HEADING_MOVEMENT_METERS) {
                return GeoMath.bearingDegrees(
                    previous.latitude,
                    previous.longitude,
                    current.latitude,
                    current.longitude,
                )
            }
        }
        return current.headingDegrees
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
