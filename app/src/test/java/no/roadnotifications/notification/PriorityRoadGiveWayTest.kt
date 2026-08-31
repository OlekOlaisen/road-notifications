package no.roadnotifications.notification

import no.roadnotifications.data.VegObjektType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityRoadGiveWayTest {
    @Test
    fun stopAndYieldAreGiveWayTypes() {
        assertTrue(PriorityRoadGiveWay.isGiveWayType(VegObjektType.STOPP.name))
        assertTrue(PriorityRoadGiveWay.isGiveWayType(VegObjektType.VIKEPLIKT.name))
        assertFalse(PriorityRoadGiveWay.isGiveWayType(VegObjektType.FART.name))
        assertFalse(PriorityRoadGiveWay.isGiveWayType(VegObjektType.FORKJOERSVEI.name))
    }

    @Test
    fun stopOnPriorityRoadIsSuppressed() {
        assertTrue(
            PriorityRoadGiveWay.shouldSuppress(
                objektType = VegObjektType.STOPP.name,
                onPriorityRoad = true,
                stayActive = true,
                travelHeadingDegrees = 160f,
                retning = null,
                vegRetningGrader = null,
            ),
        )
    }

    @Test
    fun yieldOnPriorityRoadIsSuppressedEvenWhenPlateFacesTravel() {
        assertTrue(
            PriorityRoadGiveWay.shouldSuppress(
                objektType = VegObjektType.VIKEPLIKT.name,
                onPriorityRoad = true,
                stayActive = true,
                travelHeadingDegrees = 160f,
                retning = "MED",
                vegRetningGrader = 160f,
            ),
        )
    }

    @Test
    fun sideRoadStopDuringPriorityStayIsSuppressed() {
        assertTrue(
            PriorityRoadGiveWay.shouldSuppress(
                objektType = VegObjektType.STOPP.name,
                onPriorityRoad = false,
                stayActive = true,
                travelHeadingDegrees = 160f,
                retning = "MED",
                vegRetningGrader = 70f,
            ),
        )
    }

    @Test
    fun stopWithUnknownFacingIsSuppressedWhileStayIsActive() {
        assertTrue(
            PriorityRoadGiveWay.shouldSuppress(
                objektType = VegObjektType.STOPP.name,
                onPriorityRoad = false,
                stayActive = true,
                travelHeadingDegrees = 160f,
                retning = null,
                vegRetningGrader = null,
            ),
        )
    }

    @Test
    fun turningOntoSideRoadAllowsStopThatFacesTravel() {
        assertFalse(
            PriorityRoadGiveWay.shouldSuppress(
                objektType = VegObjektType.STOPP.name,
                onPriorityRoad = false,
                stayActive = true,
                travelHeadingDegrees = 70f,
                retning = "MED",
                vegRetningGrader = 70f,
            ),
        )
    }

    @Test
    fun giveWayAlertsWhenNotOnPriorityRoadAndStayHasEnded() {
        assertFalse(
            PriorityRoadGiveWay.shouldSuppress(
                objektType = VegObjektType.VIKEPLIKT.name,
                onPriorityRoad = false,
                stayActive = false,
                travelHeadingDegrees = 160f,
                retning = null,
                vegRetningGrader = null,
            ),
        )
    }

    @Test
    fun otherTypesAreNeverSuppressedByThisRule() {
        assertFalse(
            PriorityRoadGiveWay.shouldSuppress(
                objektType = VegObjektType.FARLIG_SVING.name,
                onPriorityRoad = true,
                stayActive = true,
                travelHeadingDegrees = 160f,
                retning = null,
                vegRetningGrader = null,
            ),
        )
    }
}
