package no.roadnotifications.location

import no.roadnotifications.data.VegObjektType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationDistanceAlertRangeTest {
    @Test
    fun atSignTypesAlertAtThePlate() {
        val atSignTypes = listOf(
            VegObjektType.FORKJOERSVEI.name,
            VegObjektType.SLUTT_FORKJOERSVEI.name,
            VegObjektType.BOM.name,
            VegObjektType.VILTFARE.name,
            VegObjektType.STREKNINGS_ATK.name,
        )
        for (objektType in atSignTypes) {
            assertEquals(
                objektType,
                LocationDistance.AT_SIGN_ALONG_TRACK_METERS,
                LocationDistance.alertAlongTrackMeters(objektType),
                0.01f,
            )
        }
    }

    @Test
    fun yieldAlertsOnTheApproachBeforeTheGiveWayLine() {
        assertEquals(
            LocationDistance.YIELD_ALONG_TRACK_METERS,
            LocationDistance.alertAlongTrackMeters(VegObjektType.VIKEPLIKT.name),
            0.01f,
        )
    }

    @Test
    fun yieldMatchesAShoulderPlateOnTheApproachRoad() {
        val offsetTowardGiveWayLine = TravelPathOffset(
            distanceMeters = 45f,
            alongTrackMeters = 40f,
            crossTrackMeters = 20f,
            headingDeltaDegrees = 25f,
            travelHeadingDegrees = 335f,
        )
        assertTrue(
            LocationDistance.matchesTravelPath(
                offset = offsetTowardGiveWayLine,
                objektType = VegObjektType.VIKEPLIKT.name,
            ),
        )
    }

    @Test
    fun yieldDoesNotMatchBeyondTheApproachWindow() {
        val offsetTooFarAhead = TravelPathOffset(
            distanceMeters = 65f,
            alongTrackMeters = 60f,
            crossTrackMeters = 8f,
            headingDeltaDegrees = 8f,
            travelHeadingDegrees = 335f,
        )
        assertFalse(
            LocationDistance.matchesTravelPath(
                offset = offsetTooFarAhead,
                objektType = VegObjektType.VIKEPLIKT.name,
            ),
        )
    }

    @Test
    fun curveAndDangerousIntersectionAlertEightyMetersAhead() {
        assertEquals(
            80f,
            LocationDistance.alertAlongTrackMeters(VegObjektType.FARLIG_SVING.name),
            0.01f,
        )
        assertEquals(
            80f,
            LocationDistance.alertAlongTrackMeters(VegObjektType.FARLIG_VEGKRYSS.name),
            0.01f,
        )
    }

    @Test
    fun sectionControlIsAStretchType() {
        assertEquals(
            true,
            LocationDistance.isStretchType(VegObjektType.STREKNINGS_ATK.name),
        )
        assertEquals(
            LocationDistance.AT_SIGN_ALONG_TRACK_METERS,
            LocationDistance.alertAlongTrackMeters(VegObjektType.STREKNINGS_ATK.name),
            0.01f,
        )
    }
}
