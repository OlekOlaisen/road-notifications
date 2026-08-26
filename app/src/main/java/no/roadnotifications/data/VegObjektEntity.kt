package no.roadnotifications.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vegobjekt",
    indices = [Index(value = ["lat", "lon"])],
)
data class VegObjektEntity(
    @PrimaryKey val id: Long,
    val type: String,
    val verdi: String?,
    val lat: Double,
    val lon: Double,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    /**
     * NVDB [LOK.RETNING]: MED / MOT relative to road metrering, or null if unknown.
     */
    val retning: String? = null,
    /**
     * Compass bearing (degrees) of metrering direction (MED), derived from LINESTRING when available.
     */
    val vegRetningGrader: Float? = null,
    /**
     * Packed polyline for stretch objects (speed limit, priority road,
     * section-control ATK). Used to detect that the vehicle has entered
     * the stretch, not only the start coordinate.
     */
    val points: ByteArray? = null,
)
