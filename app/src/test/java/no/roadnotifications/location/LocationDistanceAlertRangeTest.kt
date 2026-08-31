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
    fun priorityRoadPlatesUseTheYieldApproachWindow() {
        assertEquals(
            LocationDistance.YIELD_ALONG_TRACK_METERS,
            LocationDistance.alertAlongTrackMeters(VegObjektType.FORKJOERSVEI.name),
            0.01f,
        )
        assertEquals(
            LocationDistance.YIELD_ALONG_TRACK_METERS,
            LocationDistance.alertAlongTrackMeters(VegObjektType.SLUTT_FORKJOERSVEI.name),
            0.01f,
        )
        assertEquals(
            LocationDistance.YIELD_ALONG_TRACK_METERS,
            LocationDistance.alertAlongTrackMeters(VegObjektType.SLUTT_FART.name),
            0.01f,
        )
    }

    @Test
    fun yieldMatchesAShoulderPlateOnTheApproachRoad() {
        val offsetTowardGiveWayLine = TravelPathOffset(
            distanceMeters = 42f,
            alongTrackMeters = 40f,
            crossTrackMeters = 10f,
            headingDeltaDegrees = 12f,
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
    fun yieldDoesNotMatchASideStreetPlate() {
        val offsetTowardSideStreet = TravelPathOffset(
            distanceMeters = 45f,
            alongTrackMeters = 40f,
            crossTrackMeters = 20f,
            headingDeltaDegrees = 25f,
            travelHeadingDegrees = 335f,
        )
        assertFalse(
            LocationDistance.matchesTravelPath(
                offset = offsetTowardSideStreet,
                objektType = VegObjektType.VIKEPLIKT.name,
            ),
        )
    }

    @Test
    fun endOfPriorityRoadMatchesTheSameShoulderWindowAsTheEntrance() {
        val offsetTowardPlate = TravelPathOffset(
            distanceMeters = 45f,
            alongTrackMeters = 40f,
            crossTrackMeters = 20f,
            headingDeltaDegrees = 25f,
            travelHeadingDegrees = 335f,
        )
        assertTrue(
            LocationDistance.matchesTravelPath(
                offset = offsetTowardPlate,
                objektType = VegObjektType.FORKJOERSVEI.name,
            ),
        )
        assertTrue(
            LocationDistance.matchesTravelPath(
                offset = offsetTowardPlate,
                objektType = VegObjektType.SLUTT_FORKJOERSVEI.name,
            ),
        )
        assertTrue(
            LocationDistance.matchesTravelPath(
                offset = offsetTowardPlate,
                objektType = VegObjektType.SLUTT_FART.name,
                retning = "MED",
                vegRetningGrader = 335f,
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
        assertEquals(
            350f,
            LocationDistance.alertAlongTrackMeters(VegObjektType.FOTOBOKS.name),
            0.01f,
        )
        val highwaySpeedMetersPerSecond = 80f / 3.6f
        val fotoboksAtHighway = LocationDistance.alertAlongTrackMeters(
            VegObjektType.FOTOBOKS.name,
            highwaySpeedMetersPerSecond,
        )
        assertTrue(fotoboksAtHighway in 200f..400f)
        assertTrue(
            fotoboksAtHighway <
                LocationDistance.alertAlongTrackMeters(VegObjektType.FOTOBOKS.name),
        )
        val sectionAtHighway = LocationDistance.alertAlongTrackMeters(
            VegObjektType.STREKNINGS_ATK.name,
            highwaySpeedMetersPerSecond,
        )
        assertTrue(sectionAtHighway in 20f..60f)
    }

    @Test
    fun wildlifeIsAStretchType() {
        assertEquals(
            true,
            LocationDistance.isStretchType(VegObjektType.VILTFARE.name),
        )
    }

    @Test
    fun tollRailwayAndFerryArePolylineStretches() {
        val types = listOf(
            VegObjektType.BOM.name,
            VegObjektType.JERNBANE.name,
            VegObjektType.FERJEKAI.name,
        )
        for (objektType in types) {
            assertEquals(objektType, true, LocationDistance.isStretchType(objektType))
            assertEquals(
                objektType,
                true,
                LocationDistance.usesClosestPolylinePoint(objektType),
            )
        }
        assertEquals(false, LocationDistance.usesClosestPolylinePoint(VegObjektType.VILTFARE.name))
        assertEquals(false, LocationDistance.usesClosestPolylinePoint(VegObjektType.FART.name))
    }
}
