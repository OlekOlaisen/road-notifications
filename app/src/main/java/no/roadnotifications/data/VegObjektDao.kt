package no.roadnotifications.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.SkipQueryVerification

@Dao
interface VegObjektDao {
    /**
     * Objects whose alert coordinate is inside the travel query box.
     * Does not use the stretch bounding box, so long speed-limit
     * geometries cannot flood the cursor window.
     */
    @Query(
        """
        SELECT * FROM vegobjekt
        WHERE lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
        """,
    )
    suspend fun nearbyAlertPoints(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
    ): List<VegObjektEntity>

    /**
     * Stretch object ids that have a polyline segment near the query box.
     */
    @SkipQueryVerification
    @Query(
        """
        SELECT DISTINCT s.objektId AS id FROM vegobjekt_seg AS s
        INNER JOIN vegobjekt_seg_rtree AS r ON r.segId = s.segId
        WHERE r.minLat <= :maxLat AND r.maxLat >= :minLat
          AND r.minLon <= :maxLon AND r.maxLon >= :minLon
        LIMIT 192
        """,
    )
    suspend fun nearbyStretchIds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
    ): List<Long>

    @Query("SELECT * FROM vegobjekt WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<VegObjektEntity>

    @Query(
        """
        SELECT * FROM vegobjekt
        WHERE type = 'KOMMUNE'
          AND minLat <= :latitude AND maxLat >= :latitude
          AND minLon <= :longitude AND maxLon >= :longitude
        """,
    )
    suspend fun kommunerContaining(
        latitude: Double,
        longitude: Double,
    ): List<VegObjektEntity>
}
