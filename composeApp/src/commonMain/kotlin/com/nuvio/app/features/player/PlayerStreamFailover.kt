package com.nuvio.app.features.player

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_failover_retrying
import nuvio.composeapp.generated.resources.player_failover_trying_next
import org.jetbrains.compose.resources.getString

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

/**
 * Handles a playback error by retrying the current source once, then moving to the next stream in
 * the source list. Returns true when failover owns the error, so the caller must not show it.
 */
internal fun PlayerScreenRuntime.tryStreamFailoverAfterError(message: String): Boolean {
    if (!playerSettingsUiState.streamFailoverEnabled) return false
    if (streamFailoverJob?.isActive == true) return true
    val failedUrl = activeSourceUrl.takeIf { it.isNotBlank() } ?: return false

    return when (decideStreamFailover(streamFailoverState, failedUrl, playerController != null)) {
        StreamFailoverAction.Retry -> {
            streamFailoverState = streamFailoverState.copy(
                retriedUrl = failedUrl,
                progressBaselineMs = failoverResumePositionMs(),
            )
            InAppLogger.info("Player/Failover", "retry url=${InAppLogger.redactUrl(failedUrl)} error=$message")
            scope.launch { NuvioToastController.show(getString(Res.string.player_failover_retrying)) }
            playerController?.retry()
            loadFailoverSources()
            true
        }
        StreamFailoverAction.NextStream -> {
            streamFailoverState = streamFailoverState.copy(failedUrls = streamFailoverState.failedUrls + failedUrl)
            if (activeVideoId == null) return false
            removeFailedStreamFromCache()
            startFailoverToNextStream(failedUrl, message)
            true
        }
        StreamFailoverAction.GiveUp -> false
    }
}

internal fun PlayerScreenRuntime.noteStreamFailoverProgress(positionMs: Long) {
    val updated = streamFailoverState.afterProgress(positionMs)
    if (updated != streamFailoverState) streamFailoverState = updated
}

/** Where the next source should start: the live position, or the failed source's start if it never played. */
internal fun PlayerScreenRuntime.failoverResumePositionMs(): Long =
    playbackSnapshot.positionMs.takeIf { it > 0L } ?: activeInitialPositionMs.coerceAtLeast(0L)

private fun PlayerScreenRuntime.loadFailoverSources() {
    val videoId = activeVideoId ?: return
    PlayerStreamsRepository.loadSources(
        type = contentType ?: parentMetaType,
        videoId = videoId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
}

private fun PlayerScreenRuntime.startFailoverToNextStream(failedUrl: String, message: String) {
    val resumePositionMs = failoverResumePositionMs()
    val failedIdentityKey = activeSourceIdentityKey
    val attempt = streamFailoverState.alternativesUsed + 1
    errorMessage = null
    streamFailoverJob = scope.launch {
        try {
            loadFailoverSources()
            val started = TimeSource.Monotonic.markNow()
            while (
                PlayerStreamsRepository.sourceState.value.isAnyLoading &&
                started.elapsedNow().inWholeMilliseconds < FAILOVER_SOURCE_WAIT_MS
            ) {
                delay(FAILOVER_SOURCE_POLL_MS)
            }
            val candidate = selectFailoverCandidate(
                streams = PlayerStreamsRepository.sourceState.value.allStreams,
                failedUrl = failedUrl,
                failedIdentityKey = failedIdentityKey,
                excludedUrls = streamFailoverState.failedUrls,
                isPlayable = { isFailoverPlayable(it) },
            )
            if (candidate == null) {
                InAppLogger.warn("Player/Failover", "no candidate after url=${InAppLogger.redactUrl(failedUrl)}")
                errorMessage = message
                controlsVisible = !playerControlsLocked
                return@launch
            }
            streamFailoverState = streamFailoverState.copy(
                retriedUrl = null,
                alternativesUsed = attempt,
                progressBaselineMs = resumePositionMs,
            )
            InAppLogger.info(
                "Player/Failover",
                "next stream $attempt/$MAX_FAILOVER_ALTERNATIVES addon=${candidate.addonName} " +
                    "stream=${candidate.streamLabel} url=${InAppLogger.redactUrl(candidate.playableDirectUrl)}",
            )
            NuvioToastController.show(
                getString(
                    Res.string.player_failover_trying_next,
                    attempt,
                    MAX_FAILOVER_ALTERNATIVES,
                    "${candidate.addonName} · ${candidate.streamLabel}",
                ),
            )
            switchToSource(candidate, saveForReuse = false, resumePositionMs = resumePositionMs)
        } finally {
            PlayerStreamsRepository.stopSourcesLoading()
        }
    }
}

/** Only streams that switchToSource can play directly and synchronously. */
private fun PlayerScreenRuntime.isFailoverPlayable(stream: StreamItem): Boolean =
    !stream.needsServerPreparation &&
        stream.youTubeIdToResolve == null &&
        !DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream) &&
        !isP2pStream(stream) &&
        !stream.shouldOpenExternally
