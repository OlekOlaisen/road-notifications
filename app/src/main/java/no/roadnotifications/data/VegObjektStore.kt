package no.roadnotifications.data

import no.roadnotifications.location.BoundingBox

interface VegObjektStore {
    suspend fun nearbyAlertPoints(box: BoundingBox): List<VegObjektEntity>

    suspend fun nearbyStretchIds(box: BoundingBox): List<Long>

    suspend fun byIds(ids: List<Long>): List<VegObjektEntity>

    suspend fun kommunerContaining(
        latitude: Double,
        longitude: Double,
    ): List<VegObjektEntity>
}
