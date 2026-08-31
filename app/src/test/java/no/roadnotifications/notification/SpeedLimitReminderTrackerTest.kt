package no.roadnotifications.notification

import no.roadnotifications.data.VegObjektEntity
import no.roadnotifications.data.VegObjektType
import no.roadnotifications.location.SpeedLimitMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedLimitReminderTrackerTest {
    @Test
    fun onlyAChangedLimitAlerts() {
        assertTrue(SpeedLimitMatcher.shouldAlert("40", null))
        assertTrue(SpeedLimitMatcher.shouldAlert("40", "80"))
        assertFalse(SpeedLimitMatcher.shouldAlert("40", "40"))
        assertFalse(SpeedLimitMatcher.shouldAlert(null, "40"))
    }
}

class AlertQueuePolicyTest {
    @Test
    fun capsQueueAtTwoAndHighEvictsLow() {
        val queued = listOf(
            candidate(1L, VegObjektType.BOM.name),
            candidate(2L, VegObjektType.FERJEKAI.name),
        )
        val incoming = listOf(
            candidate(3L, VegObjektType.FOTOBOKS.name),
        )
        val merged = AlertQueuePolicy.merge(queued, incoming)
        assertEquals(2, merged.size)
        assertEquals(VegObjektType.FOTOBOKS.name, merged[0].vegObjekt.type)
        assertEquals(VegObjektType.BOM.name, merged[1].vegObjekt.type)
    }

    @Test
    fun doesNotDuplicateTheSameId() {
        val existing = candidate(1L, VegObjektType.STOPP.name)
        val merged = AlertQueuePolicy.merge(
            queued = listOf(existing),
            incoming = listOf(candidate(1L, VegObjektType.STOPP.name)),
        )
        assertEquals(1, merged.size)
        assertEquals(1L, merged[0].vegObjekt.id)
    }

    private fun candidate(id: Long, type: String): AlertCandidate {
        return AlertCandidate(
            vegObjekt = VegObjektEntity(
                id = id,
                type = type,
                verdi = null,
                lat = 0.0,
                lon = 0.0,
                minLat = 0.0,
                maxLat = 0.0,
                minLon = 0.0,
                maxLon = 0.0,
            ),
        )
    }
}
