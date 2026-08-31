package no.roadnotifications.location

data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val elapsedRealtimeMs: Long,
    val speedMetersPerSecond: Float? = null,
    val headingDegrees: Float? = null,
    val accuracyMeters: Float? = null,
)
