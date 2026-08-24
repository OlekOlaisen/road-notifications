package no.roadnotifications.data

import androidx.room.Dao
import androidx.room.Query

@Dao
interface VegObjektDao {
    @Query(
        """
        SELECT * FROM vegobjekt
        WHERE lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
        """,
    )
    suspend fun nearby(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
    ): List<VegObjektEntity>
}
