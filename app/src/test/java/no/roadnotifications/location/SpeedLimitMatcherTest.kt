package no.roadnotifications.location

import no.roadnotifications.data.VegObjektEntity
import no.roadnotifications.data.VegObjektType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedLimitMatcherTest {
    @Test
    fun sideStreetAtRightAngleDoesNotMatchTravelHeading() {
        val matches = SpeedLimitMatcher.matchesSegmentHeading(
            travelHeadingDegrees = 0f,
            segmentHeadingDegrees = 90f,
            retning = null,
        )
        assertFalse(matches)
    }

    @Test
    fun sameRoadSegmentMatchesTravelHeading() {
        val matches = SpeedLimitMatcher.matchesSegmentHeading(
            travelHeadingDegrees = 335f,
            segmentHeadingDegrees = 330f,
            retning = "MED",
        )
        assertTrue(matches)
    }

    @Test
    fun oppositeDirectionMatchesWhenRetningIsMot() {
        val matches = SpeedLimitMatcher.matchesSegmentHeading(
            travelHeadingDegrees = 0f,
            segmentHeadingDegrees = 180f,
            retning = "MOT",
        )
        assertTrue(matches)
    }

    @Test
    fun oppositeDirectionDoesNotMatchWhenRetningIsMed() {
        val matches = SpeedLimitMatcher.matchesSegmentHeading(
            travelHeadingDegrees = 0f,
            segmentHeadingDegrees = 180f,
            retning = "MED",
        )
        assertFalse(matches)
    }

    @Test
    fun rampHeadingDoesNotMatchHighwayTravel() {
        val matches = SpeedLimitMatcher.matchesSegmentHeading(
            travelHeadingDegrees = 0f,
            segmentHeadingDegrees = 28f,
            retning = null,
        )
        assertFalse(matches)
    }

    @Test
    fun pickCurrentIgnoresCloserSideStreetWhenAlreadyOnForty() {
        val forty = aligned(id = 40L, verdi = "40", distanceMeters = 6f, headingDeltaDegrees = 4f)
        val thirty = aligned(id = 30L, verdi = "30", distanceMeters = 2f, headingDeltaDegrees = 8f)
        val current = SpeedLimitMatcher.pickCurrent(
            aligned = listOf(forty, thirty),
            previousVerdi = "40",
        )
        assertEquals(40L, current?.vegObjekt?.id)
    }

    @Test
    fun pickCurrentKeepsHighwayWhenRampIsCloserButWorseHeading() {
        val highwayEighty = aligned(
            id = 80L,
            verdi = "80",
            distanceMeters = 16f,
            headingDeltaDegrees = 4f,
        )
        val rampFifty = aligned(
            id = 50L,
            verdi = "50",
            distanceMeters = 10f,
            headingDeltaDegrees = 20f,
        )
        val current = SpeedLimitMatcher.pickCurrent(
            aligned = listOf(highwayEighty, rampFifty),
            previousVerdi = "80",
        )
        assertEquals(80L, current?.vegObjekt?.id)
    }

    @Test
    fun pickCurrentKeepsFiftyWhenParallelThirtyIsSlightlyCloser() {
        val fifty = aligned(id = 50L, verdi = "50", distanceMeters = 12f, headingDeltaDegrees = 4f)
        val thirty = aligned(id = 30L, verdi = "30", distanceMeters = 10f, headingDeltaDegrees = 6f)
        val current = SpeedLimitMatcher.pickCurrent(
            aligned = listOf(fifty, thirty),
            previousVerdi = "50",
        )
        assertEquals(50L, current?.vegObjekt?.id)
    }

    @Test
    fun pickCurrentKeepsFiftyWhenParallelThirtyIsMuchCloser() {
        val fifty = aligned(id = 50L, verdi = "50", distanceMeters = 8f, headingDeltaDegrees = 4f)
        val thirty = aligned(id = 30L, verdi = "30", distanceMeters = 1f, headingDeltaDegrees = 6f)
        val current = SpeedLimitMatcher.pickCurrent(
            aligned = listOf(fifty, thirty),
            previousVerdi = "50",
        )
        assertEquals(50L, current?.vegObjekt?.id)
    }

    @Test
    fun pickCurrentDoesNotAdoptDistantLimitWhenPreviousGone() {
        val forty = aligned(id = 40L, verdi = "40", distanceMeters = 20f, headingDeltaDegrees = 8f)
        val current = SpeedLimitMatcher.pickCurrent(
            aligned = listOf(forty),
            previousVerdi = "80",
        )
        assertNull(current)
    }

    @Test
    fun pickCurrentAdoptsNewLimitWhenPreviousGoneAndClearlyOnRoad() {
        val forty = aligned(id = 40L, verdi = "40", distanceMeters = 4f, headingDeltaDegrees = 5f)
        val current = SpeedLimitMatcher.pickCurrent(
            aligned = listOf(forty),
            previousVerdi = "80",
        )
        assertEquals(40L, current?.vegObjekt?.id)
    }

    @Test
    fun pickCurrentSwitchesWhenFortyIsNoLongerAligned() {
        val thirty = aligned(id = 30L, verdi = "30", distanceMeters = 4f, headingDeltaDegrees = 6f)
        val current = SpeedLimitMatcher.pickCurrent(
            aligned = listOf(thirty),
            previousVerdi = "40",
        )
        assertEquals(30L, current?.vegObjekt?.id)
    }

    @Test
    fun pickCurrentSwitchesOnSameRoadWhenNewLimitIsClearlyCloser() {
        val fifty = aligned(id = 50L, verdi = "50", distanceMeters = 22f, headingDeltaDegrees = 3f)
        val forty = aligned(id = 40L, verdi = "40", distanceMeters = 3f, headingDeltaDegrees = 2f)
        val current = SpeedLimitMatcher.pickCurrent(
            aligned = listOf(fifty, forty),
            previousVerdi = "50",
        )
        assertEquals(40L, current?.vegObjekt?.id)
    }

    @Test
    fun pickCurrentPrefersClosestWhenNoPreviousLimit() {
        val forty = aligned(id = 40L, verdi = "40", distanceMeters = 8f, headingDeltaDegrees = 2f)
        val thirty = aligned(id = 30L, verdi = "30", distanceMeters = 3f, headingDeltaDegrees = 4f)
        val current = SpeedLimitMatcher.pickCurrent(
            aligned = listOf(forty, thirty),
            previousVerdi = null,
        )
        assertEquals(30L, current?.vegObjekt?.id)
    }

    @Test
    fun pickCurrentReturnsNullWhenNothingIsAligned() {
        assertNull(
            SpeedLimitMatcher.pickCurrent(
                aligned = emptyList(),
                previousVerdi = "40",
            ),
        )
    }

    @Test
    fun shouldAlertWhenLimitChangesFromFortyToThirty() {
        assertTrue(SpeedLimitMatcher.shouldAlert(currentVerdi = "30", previousVerdi = "40"))
    }

    @Test
    fun shouldNotAlertWhenStillInForty() {
        assertFalse(SpeedLimitMatcher.shouldAlert(currentVerdi = "40", previousVerdi = "40"))
    }

    @Test
    fun shouldAlertOnFirstOnRoadLimit() {
        assertTrue(SpeedLimitMatcher.shouldAlert(currentVerdi = "30", previousVerdi = null))
    }

    @Test
    fun shouldAlertWhenReturningToFortyAfterFifty() {
        assertTrue(SpeedLimitMatcher.shouldAlert(currentVerdi = "40", previousVerdi = "50"))
    }

    @Test
    fun shouldNotAlertWhenLeavingAllSpeedLimits() {
        assertFalse(SpeedLimitMatcher.shouldAlert(currentVerdi = null, previousVerdi = "40"))
    }

    private fun aligned(
        id: Long,
        verdi: String,
        distanceMeters: Float,
        headingDeltaDegrees: Float,
    ): AlignedSpeedLimit {
        return AlignedSpeedLimit(
            vegObjekt = VegObjektEntity(
                id = id,
                type = VegObjektType.FART.name,
                verdi = verdi,
                lat = 59.9,
                lon = 10.8,
                minLat = 59.9,
                maxLat = 59.9,
                minLon = 10.8,
                maxLon = 10.8,
            ),
            distanceMeters = distanceMeters,
            headingDeltaDegrees = headingDeltaDegrees,
        )
    }
}
