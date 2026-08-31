package no.roadnotifications.data

import no.roadnotifications.location.BoundingBox

class DaoVegObjektStore(
    private val dao: VegObjektDao,
) : VegObjektStore {
    override suspend fun nearbyAlertPoints(box: BoundingBox): List<VegObjektEntity> {
        return dao.nearbyAlertPoints(
            minLat = box.minLat,
            maxLat = box.maxLat,
            minLon = box.minLon,
            maxLon = box.maxLon,
        )
    }

    override suspend fun nearbyStretchIds(box: BoundingBox): List<Long> {
        return dao.nearbyStretchIds(
            minLat = box.minLat,
            maxLat = box.maxLat,
            minLon = box.minLon,
            maxLon = box.maxLon,
        )
    }

    override suspend fun byIds(ids: List<Long>): List<VegObjektEntity> {
        return dao.byIds(ids)
    }

    override suspend fun kommunerContaining(
        latitude: Double,
        longitude: Double,
    ): List<VegObjektEntity> {
        return dao.kommunerContaining(latitude, longitude)
    }
}
