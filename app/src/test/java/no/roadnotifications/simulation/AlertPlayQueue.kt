package no.roadnotifications.simulation

import no.roadnotifications.notification.AlertCandidate
import no.roadnotifications.notification.AlertQueuePolicy

data class QueueIngestResult(
    val played: List<AlertCandidate>,
    val dropped: List<AlertCandidate>,
)

class AlertPlayQueue(
    private val playDelayMs: Long = PLAY_DELAY_MS,
) {
    private val pending = ArrayDeque<AlertCandidate>()
    private var playing = false
    private var nextPlayElapsedMs = 0L

    fun ingest(
        nowElapsedMs: Long,
        incoming: List<AlertCandidate>,
    ): QueueIngestResult {
        val played = ArrayList<AlertCandidate>()
        played += drainDue(nowElapsedMs)
        val merged = AlertQueuePolicy.merge(
            queued = pending.toList(),
            incoming = incoming,
        )
        val keptIds = merged.map { candidate -> candidate.vegObjekt.id }.toSet()
        val dropped = (pending + incoming)
            .filter { candidate -> candidate.vegObjekt.id !in keptIds }
            .distinctBy { candidate -> candidate.vegObjekt.id }
        pending.clear()
        pending.addAll(merged)
        if (!playing && pending.isNotEmpty()) {
            playing = true
            nextPlayElapsedMs = nowElapsedMs
            played += drainDue(nowElapsedMs)
        }
        return QueueIngestResult(played = played, dropped = dropped)
    }

    fun drainDue(nowElapsedMs: Long): List<AlertCandidate> {
        val played = ArrayList<AlertCandidate>()
        while (playing && nowElapsedMs >= nextPlayElapsedMs) {
            val next = pending.removeFirstOrNull()
            if (next == null) {
                playing = false
                break
            }
            played += next
            nextPlayElapsedMs = nowElapsedMs + playDelayMs
        }
        if (pending.isEmpty()) {
            playing = false
        }
        return played
    }

    companion object {
        const val PLAY_DELAY_MS = 3_000L
    }
}
