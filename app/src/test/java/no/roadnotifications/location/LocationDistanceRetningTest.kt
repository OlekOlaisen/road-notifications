package no.roadnotifications.location

import no.roadnotifications.data.VegObjektType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationDistanceRetningTest {
    private val approachingOffset = TravelPathOffset(
        distanceMeters = 18f,
        alongTrackMeters = 15f,
        crossTrackMeters = 8f,
        headingDeltaDegrees = 8f,
        travelHeadingDegrees = 10f,
    )

    private val oppositeTravelOffset = approachingOffset.copy(
        travelHeadingDegrees = 190f,
    )

    private val directionalPointTypes = listOf(
        VegObjektType.FARLIG_VEGKRYSS.name,
        VegObjektType.FARLIG_SVING.name,
        VegObjektType.SMALERE_VEG.name,
        VegObjektType.TUNNEL.name,
        VegObjektType.STOPP.name,
        VegObjektType.VIKEPLIKT.name,
        VegObjektType.SLUTT_FORKJOERSVEI.name,
        VegObjektType.SLUTT_FART.name,
        VegObjektType.FOTOBOKS.name,
        VegObjektType.BOM.name,
        VegObjektType.VILTFARE.name,
        VegObjektType.JERNBANE.name,
        VegObjektType.FERJEKAI.name,
    )

    @Test
    fun medRetningMatchesTravelAlongTheRoad() {
        for (objektType in directionalPointTypes) {
            assertTrue(
                objektType,
                LocationDistance.matchesTravelPath(
                    offset = approachingOffset,
                    objektType = objektType,
                    retning = "MED",
                    vegRetningGrader = 10f,
                ),
            )
        }
    }

    @Test
    fun medRetningRejectsOppositeTravel() {
        for (objektType in directionalPointTypes) {
            assertFalse(
                objektType,
                LocationDistance.matchesTravelPath(
                    offset = oppositeTravelOffset,
                    objektType = objektType,
                    retning = "MED",
                    vegRetningGrader = 10f,
                ),
            )
        }
    }

    @Test
    fun motRetningMatchesOppositeMetrering() {
        assertTrue(
            LocationDistance.matchesTravelPath(
                offset = oppositeTravelOffset,
                objektType = VegObjektType.FARLIG_VEGKRYSS.name,
                retning = "MOT",
                vegRetningGrader = 10f,
            ),
        )
        assertFalse(
            LocationDistance.matchesTravelPath(
                offset = approachingOffset,
                objektType = VegObjektType.FARLIG_VEGKRYSS.name,
                retning = "MOT",
                vegRetningGrader = 10f,
            ),
        )
    }

    @Test
    fun missingHeadingAllowsBothDirectionsUntilImportFillsIt() {
        assertTrue(
            LocationDistance.matchesTravelPath(
                offset = oppositeTravelOffset,
                objektType = VegObjektType.FARLIG_VEGKRYSS.name,
                retning = "MED",
                vegRetningGrader = null,
            ),
        )
    }

    @Test
    fun endSpeedLimitWithoutHeadingDoesNotMatchEitherDirection() {
        assertFalse(
            LocationDistance.matchesTravelPath(
                offset = approachingOffset,
                objektType = VegObjektType.SLUTT_FART.name,
                retning = "MED",
                vegRetningGrader = null,
            ),
        )
        assertFalse(
            LocationDistance.matchesTravelPath(
                offset = approachingOffset,
                objektType = VegObjektType.SLUTT_FART.name,
                retning = null,
                vegRetningGrader = 10f,
            ),
        )
    }
}
