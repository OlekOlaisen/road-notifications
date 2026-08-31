package no.roadnotifications.notification

import no.roadnotifications.data.VegObjektEntity
import no.roadnotifications.data.VegObjektType
import no.roadnotifications.location.TravelPathOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPriorityTest {
    @Test
    fun actionRequiredSignsAreHighImportance() {
        val highTypes = listOf(
            VegObjektType.STOPP.name,
            VegObjektType.VIKEPLIKT.name,
            VegObjektType.JERNBANE.name,
            VegObjektType.FOTOBOKS.name,
            VegObjektType.STREKNINGS_ATK.name,
        )
        for (objektType in highTypes) {
            assertEquals(objektType, AlertImportance.HIGH, AlertPriority.importance(objektType))
        }
    }

    @Test
    fun speedAndPriorityRoadAreMediumImportance() {
        assertEquals(AlertImportance.MEDIUM, AlertPriority.importance(VegObjektType.FART.name))
        assertEquals(
            AlertImportance.MEDIUM,
            AlertPriority.importance(VegObjektType.SLUTT_FART.name),
        )
        assertEquals(
            AlertImportance.MEDIUM,
            AlertPriority.importance(VegObjektType.FORKJOERSVEI.name),
        )
        assertEquals(AlertImportance.MEDIUM, AlertPriority.importance(VegObjektType.VILTFARE.name))
    }

    @Test
    fun advanceWarningsAreLowImportance() {
        val lowTypes = listOf(
            VegObjektType.FARLIG_SVING.name,
            VegObjektType.FARLIG_VEGKRYSS.name,
            VegObjektType.SMALERE_VEG.name,
            VegObjektType.TUNNEL.name,
            VegObjektType.BOM.name,
            VegObjektType.FERJEKAI.name,
            VegObjektType.SLUTT_FORKJOERSVEI.name,
            VegObjektType.KOMMUNE.name,
        )
        for (objektType in lowTypes) {
            assertEquals(objektType, AlertImportance.LOW, AlertPriority.importance(objektType))
        }
    }

    @Test
    fun yieldPlaysFirstThenSpeedThenJunction() {
        val selected = AlertPriority.selectToNotify(
            passingOncePerPass = listOf(
                candidate(id = 1L, type = VegObjektType.FARLIG_VEGKRYSS.name),
                candidate(id = 2L, type = VegObjektType.VIKEPLIKT.name),
                candidate(id = 3L, type = VegObjektType.FART.name, verdi = "40"),
            ),
            higherImportanceApproaching = false,
        )
        assertEquals(
            listOf(
                VegObjektType.VIKEPLIKT.name,
                VegObjektType.FART.name,
                VegObjektType.FARLIG_VEGKRYSS.name,
            ),
            selected.map { candidate -> candidate.vegObjekt.type },
        )
    }

    @Test
    fun curveWarningStillFiresWhenItIsTheOnlyMatch() {
        val curve = candidate(id = 4L, type = VegObjektType.FARLIG_SVING.name)
        val selected = AlertPriority.selectToNotify(
            passingOncePerPass = listOf(curve),
            higherImportanceApproaching = false,
        )
        assertEquals(listOf(curve), selected)
    }

    @Test
    fun yieldLookaheadStillHoldsJunctionUntilThePlateWindow() {
        val junction = candidate(id = 1L, type = VegObjektType.FARLIG_VEGKRYSS.name)
        val selected = AlertPriority.selectToNotify(
            passingOncePerPass = listOf(junction),
            higherImportanceApproaching = true,
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun lookaheadDoesNotDropSpeedOrPriorityRoad() {
        val selected = AlertPriority.selectToNotify(
            passingOncePerPass = listOf(
                candidate(id = 3L, type = VegObjektType.FART.name, verdi = "40"),
                candidate(id = 5L, type = VegObjektType.FORKJOERSVEI.name),
                candidate(id = 6L, type = VegObjektType.VILTFARE.name, verdi = "ELG"),
                candidate(id = 1L, type = VegObjektType.FARLIG_SVING.name),
            ),
            higherImportanceApproaching = true,
        )
        assertEquals(
            listOf(
                VegObjektType.FORKJOERSVEI.name,
                VegObjektType.FART.name,
                VegObjektType.VILTFARE.name,
            ),
            selected.map { candidate -> candidate.vegObjekt.type },
        )
    }

    @Test
    fun speedLimitDoesNotCountAsLookaheadSuppressor() {
        assertFalse(AlertPriority.canSuppressLowFromLookahead(VegObjektType.FART.name))
        assertTrue(AlertPriority.canSuppressLowFromLookahead(VegObjektType.VIKEPLIKT.name))
        assertTrue(AlertPriority.canSuppressLowFromLookahead(VegObjektType.FORKJOERSVEI.name))
    }

    @Test
    fun yieldAheadWithinLookaheadSuppressesLowAlerts() {
        val offsetTowardYield = TravelPathOffset(
            distanceMeters = 80f,
            alongTrackMeters = 75f,
            crossTrackMeters = 18f,
            headingDeltaDegrees = 20f,
            travelHeadingDegrees = 335f,
        )
        assertTrue(
            AlertPriority.isLookaheadSuppressor(
                objektType = VegObjektType.VIKEPLIKT.name,
                offset = offsetTowardYield,
            ),
        )
    }

    @Test
    fun yieldBehindTheCarDoesNotSuppressLowAlerts() {
        val offsetBehind = TravelPathOffset(
            distanceMeters = 20f,
            alongTrackMeters = -15f,
            crossTrackMeters = 4f,
            headingDeltaDegrees = 8f,
            travelHeadingDegrees = 335f,
        )
        assertFalse(
            AlertPriority.isLookaheadSuppressor(
                objektType = VegObjektType.VIKEPLIKT.name,
                offset = offsetBehind,
            ),
        )
    }

    @Test
    fun yieldTitleComesBeforeForkjoersveiAndSpeed() {
        assertTrue(
            AlertPriority.messageOrder(VegObjektType.VIKEPLIKT.name) <
                AlertPriority.messageOrder(VegObjektType.FORKJOERSVEI.name),
        )
        assertTrue(
            AlertPriority.messageOrder(VegObjektType.FOTOBOKS.name) <
                AlertPriority.messageOrder(VegObjektType.FART.name),
        )
        assertTrue(
            AlertPriority.iconPriority(VegObjektType.VIKEPLIKT.name) <
                AlertPriority.iconPriority(VegObjektType.FARLIG_VEGKRYSS.name),
        )
        assertTrue(
            AlertPriority.messageOrder(VegObjektType.FART.name) <
                AlertPriority.messageOrder(VegObjektType.SLUTT_FART.name),
        )
    }

    @Test
    fun endSpeedLimitTitlesNameThePlate() {
        assertEquals(
            "Slutt 70",
            VegNotificationManager.titleFor(
                candidate(id = 1L, type = VegObjektType.SLUTT_FART.name, verdi = "70").vegObjekt,
            ),
        )
        assertEquals(
            "Slutt på fartsgrensesone",
            VegNotificationManager.titleFor(
                candidate(id = 2L, type = VegObjektType.SLUTT_FART.name, verdi = "368").vegObjekt,
            ),
        )
        assertEquals(
            "Strekningsmåling slutt",
            VegNotificationManager.titleFor(
                candidate(
                    id = StrekningsAtkTripTracker.SYNTHETIC_SLUTT_ID,
                    type = VegObjektType.STREKNINGS_ATK.name,
                    verdi = "SLUTT:73",
                ).vegObjekt,
            ),
        )
        assertEquals(
            "73 km/t",
            VegNotificationManager.subtitleFor(
                candidate(
                    id = StrekningsAtkTripTracker.SYNTHETIC_SLUTT_ID,
                    type = VegObjektType.STREKNINGS_ATK.name,
                    verdi = "SLUTT:73",
                ).vegObjekt,
                alongTrackMeters = 0f,
            ),
        )
    }

    private fun candidate(
        id: Long,
        type: String,
        verdi: String? = null,
    ): AlertCandidate {
        return AlertCandidate(
            vegObjekt = VegObjektEntity(
                id = id,
                type = type,
                verdi = verdi,
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
