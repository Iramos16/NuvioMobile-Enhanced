package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem

internal const val MAX_FAILOVER_ALTERNATIVES = 3
internal const val FAILOVER_SOURCE_WAIT_MS = 15_000L
internal const val FAILOVER_SOURCE_POLL_MS = 500L
internal const val FAILOVER_HEALTHY_PLAYBACK_MS = 30_000L

/**
 * Failover progress for the current player session. [failedUrls] lasts until the player closes;
 * the retry flag and alternative budget reset once a source plays for [FAILOVER_HEALTHY_PLAYBACK_MS].
 */
internal data class StreamFailoverState(
    val retriedUrl: String? = null,
    val alternativesUsed: Int = 0,
    val failedUrls: Set<String> = emptySet(),
    val progressBaselineMs: Long? = null,
)

internal sealed interface StreamFailoverAction {
    data object Retry : StreamFailoverAction
    data object NextStream : StreamFailoverAction
    data object GiveUp : StreamFailoverAction
}

internal fun decideStreamFailover(
    state: StreamFailoverState,
    failedUrl: String,
    hasController: Boolean,
): StreamFailoverAction = when {
    state.retriedUrl != failedUrl && hasController -> StreamFailoverAction.Retry
    state.alternativesUsed < MAX_FAILOVER_ALTERNATIVES -> StreamFailoverAction.NextStream
    else -> StreamFailoverAction.GiveUp
}

/** The first playable stream after the failed one, or from the top when the failed one is not listed. */
internal fun selectFailoverCandidate(
    streams: List<StreamItem>,
    failedUrl: String,
    failedIdentityKey: String?,
    excludedUrls: Set<String>,
    isPlayable: (StreamItem) -> Boolean,
): StreamItem? {
    val failedIndex = streams.indexOfFirst { it.playableDirectUrl == failedUrl }
        .takeIf { it >= 0 }
        ?: failedIdentityKey?.let { key -> streams.indexOfFirst { it.playerSourceIdentityKey() == key } }
        ?: -1
    return streams
        .drop(failedIndex + 1)
        .firstOrNull { stream ->
            val url = stream.playableDirectUrl
            !url.isNullOrBlank() && url != failedUrl && url !in excludedUrls && isPlayable(stream)
        }
}

internal fun StreamFailoverState.afterProgress(positionMs: Long): StreamFailoverState {
    val baseline = progressBaselineMs ?: return this
    return if (positionMs - baseline >= FAILOVER_HEALTHY_PLAYBACK_MS) {
        StreamFailoverState(failedUrls = failedUrls)
    } else {
        this
    }
}
