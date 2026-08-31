package no.roadnotifications.alert

import no.roadnotifications.data.ForkjoersveiIds
import no.roadnotifications.data.VegObjektEntity
import no.roadnotifications.data.VegObjektStore
import no.roadnotifications.data.VegObjektType
import no.roadnotifications.location.AlignedSpeedLimit
import no.roadnotifications.location.BoundingBox
import no.roadnotifications.location.GpsFix
import no.roadnotifications.location.KommuneMatcher
import no.roadnotifications.location.LocationDistance
import no.roadnotifications.location.PackedPolyline
import no.roadnotifications.location.SpeedLimitMatcher
import no.roadnotifications.location.TravelPathOffset
import no.roadnotifications.notification.AlertCandidate
import no.roadnotifications.notification.AlertPriority
import no.roadnotifications.notification.EveryOtherSignStayTracker
import no.roadnotifications.notification.PriorityRoadGiveWay
import no.roadnotifications.notification.StrekningsAtkTripTracker

data class PathSkip(
    val vegObjekt: VegObjektEntity,
    val offset: TravelPathOffset?,
    val reason: String,
)

data class AlertTickResult(
    val queryLatitude: Double,
    val queryLongitude: Double,
    val travelHeadingDegrees: Float?,
    val nearby: List<VegObjektEntity>,
    val nearbyDurationMs: Long,
    val nearbyPathOffsets: List<Pair<VegObjektEntity, TravelPathOffset>>,
    val allPathMatches: List<Pair<VegObjektEntity, TravelPathOffset>>,
    val pathMatches: List<Pair<VegObjektEntity, TravelPathOffset>>,
    val onStretch: List<VegObjektEntity>,
    val enteringStretch: List<VegObjektEntity>,
    val currentSpeedLimit: VegObjektEntity?,
    val candidates: List<AlertCandidate>,
    val matchingObjektIds: Set<Long>,
    val higherImportanceApproaching: Boolean,
    val suppressedGiveWay: List<VegObjektEntity>,
    val pathSkips: List<PathSkip>,
)

class AlertTickProcessor(
    private val onError: (String) -> Unit = {},
) {
    private var activeStretchIds: Set<Long> = emptySet()
    private var lastOnRoadFartVerdi: String? = null
    private var lastKommuneId: Long? = null
    private var pendingKommuneAlert: VegObjektEntity? = null
    private val forkjoersveiStayTracker = EveryOtherSignStayTracker()
    private val strekningsAtkStayTracker = StrekningsAtkTripTracker()

    suspend fun process(
        store: VegObjektStore,
        current: GpsFix,
        previous: GpsFix?,
        travelHeadingOverrideDegrees: Float? = null,
    ): AlertTickResult {
        val travelHeadingDegrees = travelHeadingOverrideDegrees
            ?: LocationDistance.headingDegrees(current, previous)
        val alertBox = LocationDistance.boundingBoxAround(
            current.latitude,
            current.longitude,
            LocationDistance.QUERY_RADIUS_METERS,
        )
        val stretchBox = LocationDistance.boundingBoxAround(
            current.latitude,
            current.longitude,
            LocationDistance.STRETCH_QUERY_RADIUS_METERS,
        )
        val nearbyStartedAtMs = System.nanoTime()
        val nearby = try {
            store.nearbyAlertPoints(alertBox)
        } catch (error: Throwable) {
            onError("ERROR nearby ${error.javaClass.simpleName}: ${error.message}")
            emptyList()
        }
        val stretchObjects = loadNearbyStretches(store, stretchBox, nearby)
        val nearbyDurationMs = (System.nanoTime() - nearbyStartedAtMs) / 1_000_000L
        val onStretch = stretchObjects.filter { vegObjekt ->
            isOnStretch(
                vegObjekt = vegObjekt,
                latitude = current.latitude,
                longitude = current.longitude,
                travelHeadingDegrees = travelHeadingDegrees,
            )
        }
        val enteringStretch = onStretch.filter { vegObjekt ->
            ForkjoersveiIds.stretchGroupId(vegObjekt) !in activeStretchIds
        }
        val onPriorityRoad = onStretch.any { vegObjekt ->
            vegObjekt.type == VegObjektType.FORKJOERSVEI.name
        }
        val onStrekningsAtk = onStretch.any { vegObjekt ->
            vegObjekt.type == VegObjektType.STREKNINGS_ATK.name
        }
        val wasOnStrekningsAtk = onStretch.any { vegObjekt ->
            vegObjekt.type == VegObjektType.STREKNINGS_ATK.name &&
                ForkjoersveiIds.stretchGroupId(vegObjekt) in activeStretchIds
        }
        activeStretchIds = onStretch.map { vegObjekt ->
            ForkjoersveiIds.stretchGroupId(vegObjekt)
        }.toSet()
        val currentSpeedLimit = SpeedLimitMatcher.pickCurrent(
            aligned = SpeedLimitMatcher.alignedLimits(
                onStretch = onStretch,
                latitude = current.latitude,
                longitude = current.longitude,
                travelHeadingDegrees = travelHeadingDegrees,
            ),
            previousVerdi = lastOnRoadFartVerdi,
        )
        val speedLimitChanged = SpeedLimitMatcher.shouldAlert(
            currentVerdi = currentSpeedLimit?.vegObjekt?.verdi,
            previousVerdi = lastOnRoadFartVerdi,
        )
        val nearbyPathOffsets = (nearby + stretchObjects)
            .distinctBy { vegObjekt -> vegObjekt.id }
            .mapNotNull { vegObjekt ->
                if (vegObjekt.type == VegObjektType.KOMMUNE.name) {
                    return@mapNotNull null
                }
                if (travelHeadingDegrees == null) {
                    return@mapNotNull null
                }
                val (targetLatitude, targetLongitude) = pathAlertTarget(
                    vegObjekt = vegObjekt,
                    latitude = current.latitude,
                    longitude = current.longitude,
                )
                val pathOffset = LocationDistance.travelPathOffset(
                    currentLatitude = current.latitude,
                    currentLongitude = current.longitude,
                    targetLatitude = targetLatitude,
                    targetLongitude = targetLongitude,
                    travelHeadingDegrees = travelHeadingDegrees,
                )
                vegObjekt to pathOffset
            }
        val travelSpeedMetersPerSecond = current.speedMetersPerSecond
        val pathSkips = mutableListOf<PathSkip>()
        val allPathMatches = nearbyPathOffsets
            .mapNotNull { (vegObjekt, pathOffset) ->
                if (vegObjekt.type == VegObjektType.FART.name &&
                    (currentSpeedLimit != null || lastOnRoadFartVerdi != null)
                ) {
                    pathSkips += PathSkip(
                        vegObjekt = vegObjekt,
                        offset = pathOffset,
                        reason = "FART dekkes av strekning",
                    )
                    return@mapNotNull null
                }
                val skipReason = LocationDistance.travelPathSkipReason(
                    offset = pathOffset,
                    objektType = vegObjekt.type,
                    retning = vegObjekt.retning,
                    vegRetningGrader = vegObjekt.vegRetningGrader,
                    speedMetersPerSecond = travelSpeedMetersPerSecond,
                )
                if (skipReason != null) {
                    pathSkips += PathSkip(
                        vegObjekt = vegObjekt,
                        offset = pathOffset,
                        reason = skipReason,
                    )
                    return@mapNotNull null
                }
                vegObjekt to pathOffset
            }
        val prioritySignInWindow = allPathMatches.any { (vegObjekt, _) ->
            vegObjekt.type == VegObjektType.FORKJOERSVEI.name
        }
        val sluttInWindow = allPathMatches.any { (vegObjekt, _) ->
            vegObjekt.type == VegObjektType.SLUTT_FORKJOERSVEI.name
        }
        val strekningsAtkSignInWindow = allPathMatches.any { (vegObjekt, _) ->
            vegObjekt.type == VegObjektType.STREKNINGS_ATK.name
        }
        val closestSectionStretch = pickClosestStretch(
            stretches = onStretch.filter { vegObjekt ->
                vegObjekt.type == VegObjektType.STREKNINGS_ATK.name &&
                    vegObjekt.points != null
            },
            latitude = current.latitude,
            longitude = current.longitude,
        )
        val nearSectionExit = closestSectionStretch != null &&
            isNearStretchExit(
                vegObjekt = closestSectionStretch,
                latitude = current.latitude,
                longitude = current.longitude,
                speedMetersPerSecond = travelSpeedMetersPerSecond,
            )
        forkjoersveiStayTracker.onTick(
            onPriorityRoad = onPriorityRoad,
            prioritySignInWindow = prioritySignInWindow,
            sluttInWindow = sluttInWindow,
            nowElapsedRealtimeMs = current.elapsedRealtimeMs,
        )
        strekningsAtkStayTracker.onTick(
            onSection = onStrekningsAtk,
            nearExit = nearSectionExit,
            signInWindow = strekningsAtkSignInWindow,
            latitude = current.latitude,
            longitude = current.longitude,
            nowElapsedRealtimeMs = current.elapsedRealtimeMs,
        )
        val suppressedGiveWay = allPathMatches.filter { (vegObjekt, _) ->
            PriorityRoadGiveWay.shouldSuppress(
                objektType = vegObjekt.type,
                onPriorityRoad = onPriorityRoad,
                stayActive = forkjoersveiStayTracker.stayActive,
                travelHeadingDegrees = travelHeadingDegrees,
                retning = vegObjekt.retning,
                vegRetningGrader = vegObjekt.vegRetningGrader,
            )
        }
        val higherImportanceApproaching = nearbyPathOffsets.any { (vegObjekt, pathOffset) ->
            if (
                PriorityRoadGiveWay.shouldSuppress(
                    objektType = vegObjekt.type,
                    onPriorityRoad = onPriorityRoad,
                    stayActive = forkjoersveiStayTracker.stayActive,
                    travelHeadingDegrees = travelHeadingDegrees,
                    retning = vegObjekt.retning,
                    vegRetningGrader = vegObjekt.vegRetningGrader,
                )
            ) {
                return@any false
            }
            AlertPriority.isLookaheadSuppressor(
                objektType = vegObjekt.type,
                offset = pathOffset,
                retning = vegObjekt.retning,
                vegRetningGrader = vegObjekt.vegRetningGrader,
            )
        }
        val closestForkjoersveiSign = pickClosestMatch(
            allPathMatches.filter { (vegObjekt, _) ->
                vegObjekt.type == VegObjektType.FORKJOERSVEI.name
            },
        )
        val forkjoersveiPathMatches = if (
            closestForkjoersveiSign != null &&
            forkjoersveiStayTracker.shouldAlertSign(closestForkjoersveiSign.first.id)
        ) {
            listOf(closestForkjoersveiSign)
        } else {
            emptyList()
        }
        val suppressedGiveWayIds = suppressedGiveWay.map { (vegObjekt, _) -> vegObjekt.id }.toSet()
        val pathMatches = allPathMatches.filter { (vegObjekt, _) ->
            when {
                vegObjekt.id in suppressedGiveWayIds -> {
                    pathSkips += PathSkip(
                        vegObjekt = vegObjekt,
                        offset = null,
                        reason = "undertrykt på forkjørsvei",
                    )
                    false
                }
                vegObjekt.type == VegObjektType.FORKJOERSVEI.name -> false
                vegObjekt.type == VegObjektType.VILTFARE.name -> vegObjekt.points == null
                vegObjekt.type == VegObjektType.STREKNINGS_ATK.name ->
                    !strekningsAtkStayTracker.suppressPathMatch(wasOnStrekningsAtk)
                vegObjekt.type == VegObjektType.FOTOBOKS.name -> {
                    if (onStrekningsAtk) {
                        pathSkips += PathSkip(
                            vegObjekt = vegObjekt,
                            offset = null,
                            reason = "fotoboks under streknings-ATK",
                        )
                        false
                    } else {
                        true
                    }
                }
                else -> true
            }
        } + forkjoersveiPathMatches
        val zeroPathOffset = TravelPathOffset(
            distanceMeters = 0f,
            alongTrackMeters = 0f,
            crossTrackMeters = 0f,
            headingDeltaDegrees = 0f,
            travelHeadingDegrees = travelHeadingDegrees ?: 0f,
        )
        val forkjoersveiEnterMatches = if (closestForkjoersveiSign == null) {
            val enteringPriority = enteringStretch.filter { vegObjekt ->
                vegObjekt.type == VegObjektType.FORKJOERSVEI.name
            }
            val closestEnter = pickClosestStretch(
                stretches = enteringPriority,
                latitude = current.latitude,
                longitude = current.longitude,
            )
            if (
                closestEnter != null &&
                forkjoersveiStayTracker.shouldAlertSign(
                    ForkjoersveiIds.stretchGroupId(closestEnter),
                )
            ) {
                listOf(closestEnter to zeroPathOffset)
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
        val enteringWildlife = enteringStretch.filter { vegObjekt ->
            vegObjekt.type == VegObjektType.VILTFARE.name
        }
        val closestWildlife = pickClosestStretch(
            stretches = enteringWildlife,
            latitude = current.latitude,
            longitude = current.longitude,
        )
        val wildlifeEnterMatches = if (closestWildlife != null) {
            listOf(closestWildlife to zeroPathOffset)
        } else {
            emptyList()
        }
        val stretchMatches = enteringStretch
            .filter { vegObjekt ->
                vegObjekt.type != VegObjektType.FART.name &&
                    vegObjekt.type != VegObjektType.FORKJOERSVEI.name &&
                    vegObjekt.type != VegObjektType.VILTFARE.name
            }
            .filter { vegObjekt ->
                when (vegObjekt.type) {
                    VegObjektType.STREKNINGS_ATK.name ->
                        !strekningsAtkStayTracker.suppressEnter()
                    else -> true
                }
            }
            .map { vegObjekt -> vegObjekt to zeroPathOffset } +
            forkjoersveiEnterMatches +
            wildlifeEnterMatches
        val speedLimitMatch = fartMatchesThisTick(
            speedLimitChanged = speedLimitChanged,
            currentSpeedLimit = currentSpeedLimit,
            travelHeadingDegrees = travelHeadingDegrees,
        )
        val sluttFromAtk = strekningsAtkStayTracker.consumeEndAlert()?.let { kmh ->
            listOf(
                syntheticSluttStrekningsAtk(current, kmh) to zeroPathOffset,
            )
        } ?: emptyList()
        val gpsAccuracyTooPoor = current.accuracyMeters != null &&
            current.accuracyMeters > LocationDistance.MAX_GPS_ACCURACY_METERS
        val currentKommune = if (gpsAccuracyTooPoor) {
            null
        } else {
            val kommuneCandidates = try {
                store.kommunerContaining(
                    latitude = current.latitude,
                    longitude = current.longitude,
                )
            } catch (error: Throwable) {
                onError("ERROR kommune ${error.javaClass.simpleName}: ${error.message}")
                emptyList()
            }
            KommuneMatcher.pickCurrent(
                candidates = kommuneCandidates,
                latitude = current.latitude,
                longitude = current.longitude,
            )
        }
        val enteredKommune = if (
            currentKommune != null &&
            lastKommuneId != null &&
            currentKommune.id != lastKommuneId
        ) {
            currentKommune
        } else {
            null
        }
        if (currentKommune != null) {
            lastKommuneId = currentKommune.id
        }
        if (enteredKommune != null) {
            pendingKommuneAlert = enteredKommune
        }
        if (pendingKommuneAlert != null &&
            currentKommune != null &&
            pendingKommuneAlert?.id != currentKommune.id
        ) {
            pendingKommuneAlert = null
        }
        val kommuneMatch = pendingKommuneAlert?.let { vegObjekt ->
            listOf(vegObjekt to zeroPathOffset)
        } ?: emptyList()
        val closestByType = (
            pathMatches + stretchMatches + speedLimitMatch + kommuneMatch + sluttFromAtk
            )
            .groupBy { (vegObjekt, _) -> vegObjekt.type }
            .map { (_, typedObjects) ->
                typedObjects.minWith(closestMatchComparator())
            }
        val candidates = closestByType.map { (vegObjekt, pathOffset) ->
            AlertCandidate(
                vegObjekt = vegObjekt,
                alongTrackMeters = pathOffset.alongTrackMeters,
            )
        }
        lastOnRoadFartVerdi = currentSpeedLimit?.vegObjekt?.verdi?.trim()
            ?: candidates
                .find { candidate -> candidate.vegObjekt.type == VegObjektType.FART.name }
                ?.vegObjekt
                ?.verdi
                ?.trim()
            ?: lastOnRoadFartVerdi
        val matchingObjektIds = (
            allPathMatches.map { (vegObjekt, _) -> vegObjekt.id } +
                stretchMatches.map { (vegObjekt, _) -> vegObjekt.id } +
                speedLimitMatch.map { (vegObjekt, _) -> vegObjekt.id } +
                sluttFromAtk.map { (vegObjekt, _) -> vegObjekt.id } +
                listOfNotNull(pendingKommuneAlert?.id)
            ).toSet()
        return AlertTickResult(
            queryLatitude = current.latitude,
            queryLongitude = current.longitude,
            travelHeadingDegrees = travelHeadingDegrees,
            nearby = nearby,
            nearbyDurationMs = nearbyDurationMs,
            nearbyPathOffsets = nearbyPathOffsets,
            allPathMatches = allPathMatches,
            pathMatches = pathMatches,
            onStretch = onStretch,
            enteringStretch = enteringStretch,
            currentSpeedLimit = currentSpeedLimit?.vegObjekt,
            candidates = candidates,
            matchingObjektIds = matchingObjektIds,
            higherImportanceApproaching = higherImportanceApproaching,
            suppressedGiveWay = suppressedGiveWay.map { (vegObjekt, _) -> vegObjekt },
            pathSkips = pathSkips,
        )
    }

    fun acknowledgeNotifications(notified: List<AlertCandidate>) {
        if (notified.any { candidate ->
                candidate.vegObjekt.type == VegObjektType.KOMMUNE.name
            }
        ) {
            pendingKommuneAlert = null
        }
        if (notified.any { candidate ->
                candidate.vegObjekt.type == VegObjektType.STREKNINGS_ATK.name &&
                    candidate.vegObjekt.id != StrekningsAtkTripTracker.SYNTHETIC_SLUTT_ID
            }
        ) {
            strekningsAtkStayTracker.markAlerted()
        }
    }

    private suspend fun loadNearbyStretches(
        store: VegObjektStore,
        stretchBox: BoundingBox,
        nearbyAlertPoints: List<VegObjektEntity>,
    ): List<VegObjektEntity> {
        val fromAlertPoints = nearbyAlertPoints.filter { vegObjekt ->
            LocationDistance.isStretchType(vegObjekt.type)
        }
        val stretchIds = try {
            store.nearbyStretchIds(stretchBox)
        } catch (error: Throwable) {
            onError("ERROR stretch ${error.javaClass.simpleName}: ${error.message}")
            return fromAlertPoints
        }
        val missingIds = stretchIds.filter { objektId ->
            fromAlertPoints.none { vegObjekt -> vegObjekt.id == objektId }
        }
        if (missingIds.isEmpty()) {
            return fromAlertPoints
        }
        val extraStretches = try {
            store.byIds(missingIds)
        } catch (error: Throwable) {
            onError("ERROR stretchIds ${error.javaClass.simpleName}: ${error.message}")
            return fromAlertPoints
        }
        return fromAlertPoints + extraStretches
    }

    private fun isOnStretch(
        vegObjekt: VegObjektEntity,
        latitude: Double,
        longitude: Double,
        travelHeadingDegrees: Float?,
    ): Boolean {
        if (!LocationDistance.isStretchType(vegObjekt.type)) {
            return false
        }
        val points = PackedPolyline.unpack(vegObjekt.points)
        val closest = PackedPolyline.closestSegment(
            latitude = latitude,
            longitude = longitude,
            points = points,
        ) ?: return false
        if (closest.distanceMeters > LocationDistance.STRETCH_ON_ROAD_METERS) {
            return false
        }
        return SpeedLimitMatcher.matchesSegmentHeading(
            travelHeadingDegrees = travelHeadingDegrees,
            segmentHeadingDegrees = closest.segmentHeadingDegrees,
            retning = vegObjekt.retning,
        )
    }

    private fun fartMatchesThisTick(
        speedLimitChanged: Boolean,
        currentSpeedLimit: AlignedSpeedLimit?,
        travelHeadingDegrees: Float?,
    ): List<Pair<VegObjektEntity, TravelPathOffset>> {
        val currentVerdi = currentSpeedLimit?.vegObjekt?.verdi?.trim().orEmpty()
        if (currentSpeedLimit == null || currentVerdi.isEmpty() || !speedLimitChanged) {
            return emptyList()
        }
        return listOf(
            currentSpeedLimit.vegObjekt to TravelPathOffset(
                distanceMeters = currentSpeedLimit.distanceMeters,
                alongTrackMeters = 0f,
                crossTrackMeters = currentSpeedLimit.distanceMeters,
                headingDeltaDegrees = currentSpeedLimit.headingDeltaDegrees,
                travelHeadingDegrees = travelHeadingDegrees ?: 0f,
            ),
        )
    }

    private fun pathAlertTarget(
        vegObjekt: VegObjektEntity,
        latitude: Double,
        longitude: Double,
    ): Pair<Double, Double> {
        if (!LocationDistance.usesClosestPolylinePoint(vegObjekt.type)) {
            return vegObjekt.lat to vegObjekt.lon
        }
        val closest = PackedPolyline.closestSegment(
            latitude = latitude,
            longitude = longitude,
            points = PackedPolyline.unpack(vegObjekt.points),
        ) ?: return vegObjekt.lat to vegObjekt.lon
        return closest.latitude to closest.longitude
    }

    private fun pickClosestMatch(
        matches: List<Pair<VegObjektEntity, TravelPathOffset>>,
    ): Pair<VegObjektEntity, TravelPathOffset>? {
        return matches.minWithOrNull(closestMatchComparator())
    }

    private fun pickClosestStretch(
        stretches: List<VegObjektEntity>,
        latitude: Double,
        longitude: Double,
    ): VegObjektEntity? {
        return stretches.minWithOrNull(
            compareBy { vegObjekt ->
                val points = PackedPolyline.unpack(vegObjekt.points)
                PackedPolyline.minDistanceMeters(
                    latitude = latitude,
                    longitude = longitude,
                    points = points,
                )
            },
        )
    }

    private fun closestMatchComparator(): Comparator<Pair<VegObjektEntity, TravelPathOffset>> {
        return compareBy(
            { (_, pathOffset) -> pathOffset.crossTrackMeters },
            { (_, pathOffset) -> pathOffset.headingDeltaDegrees },
            { (vegObjekt, _) ->
                if (vegObjekt.retning.isNullOrBlank() || vegObjekt.vegRetningGrader == null) {
                    1
                } else {
                    0
                }
            },
            { (_, pathOffset) -> pathOffset.alongTrackMeters },
        )
    }

    private fun syntheticSluttStrekningsAtk(
        current: GpsFix,
        averageSpeedKmh: Int,
    ): VegObjektEntity {
        return VegObjektEntity(
            id = StrekningsAtkTripTracker.SYNTHETIC_SLUTT_ID,
            type = VegObjektType.STREKNINGS_ATK.name,
            verdi = StrekningsAtkTripTracker.SLUTT_VERDI_PREFIX + averageSpeedKmh,
            lat = current.latitude,
            lon = current.longitude,
            minLat = current.latitude,
            maxLat = current.latitude,
            minLon = current.longitude,
            maxLon = current.longitude,
        )
    }

    private fun isNearStretchExit(
        vegObjekt: VegObjektEntity,
        latitude: Double,
        longitude: Double,
        speedMetersPerSecond: Float?,
    ): Boolean {
        val exit = stretchExitLatLon(vegObjekt) ?: return false
        val distanceMeters = LocationDistance.distanceMeters(
            latitude,
            longitude,
            exit.first,
            exit.second,
        )
        return distanceMeters <= LocationDistance.alertAlongTrackMeters(
            objektType = VegObjektType.STREKNINGS_ATK.name,
            speedMetersPerSecond = speedMetersPerSecond,
        )
    }

    private fun stretchExitLatLon(vegObjekt: VegObjektEntity): Pair<Double, Double>? {
        val points = PackedPolyline.unpack(vegObjekt.points)
        if (points.size < 2) {
            return null
        }
        val exit = if ((vegObjekt.retning ?: "").equals("MOT", ignoreCase = true)) {
            points.first()
        } else {
            points.last()
        }
        return exit.latitude to exit.longitude
    }
}
