package com.nuvio.app.features.player

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_failover_finding_next
import nuvio.composeapp.generated.resources.player_failover_retrying
import nuvio.composeapp.generated.resources.player_failover_trying_next
import org.jetbrains.compose.resources.getString

internal const val MAX_FAILOVER_ALTERNATIVES = 3
internal const val FAILOVER_SOURCE_WAIT_MS = 15_000L
internal const val FAILOVER_SOURCE_POLL_MS = 500L
internal const val FAILOVER_HEALTHY_PLAYBACK_MS = 30_000L

/** Position jumps larger than this between snapshots are seeks, not playback. */
private const val FAILOVER_MAX_PROGRESS_STEP_MS = 5_000L

/**
 * Failover progress for the current player session. [failedUrls] lasts until the player closes;
 * the retry flag and alternative budget reset once a source has played for
 * [FAILOVER_HEALTHY_PLAYBACK_MS], or when another video starts.
 */
internal data class StreamFailoverState(
    val retriedUrl: String? = null,
    val alternativesUsed: Int = 0,
    val failedUrls: Set<String> = emptySet(),
    val videoId: String? = null,
    /** Last reported position while waiting for healthy playback; null when nothing is pending. */
    val lastProgressPositionMs: Long? = null,
    val playedMs: Long = 0L,
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

/** Counts time actually played since the last failover step; seeks don't count. */
internal fun StreamFailoverState.afterProgress(positionMs: Long): StreamFailoverState {
    val lastPositionMs = lastProgressPositionMs ?: return this
    val stepMs = positionMs - lastPositionMs
    val played = playedMs + if (stepMs in 1..FAILOVER_MAX_PROGRESS_STEP_MS) stepMs else 0L
    return if (played >= FAILOVER_HEALTHY_PLAYBACK_MS) {
        StreamFailoverState(failedUrls = failedUrls, videoId = videoId)
    } else {
        copy(lastProgressPositionMs = positionMs, playedMs = played)
    }
}

/** A different video gets a fresh budget; links that already failed stay excluded. */
internal fun StreamFailoverState.forVideo(videoId: String?): StreamFailoverState =
    if (videoId == this.videoId) this else StreamFailoverState(failedUrls = failedUrls, videoId = videoId)

/** Source loading left over from a recovered failure is stopped unless the Sources panel or a failover uses it. */
internal fun shouldStopLeftoverSourceLoading(
    isLoading: Boolean,
    sourcesPanelOpen: Boolean,
    failoverRunning: Boolean,
): Boolean = isLoading && !sourcesPanelOpen && !failoverRunning

/**
 * Handles a playback error by retrying the current source once, then moving to the next stream in
 * the source list. Returns true when failover owns the error, so the caller must not show it.
 */
internal fun PlayerScreenRuntime.tryStreamFailoverAfterError(message: String): Boolean {
    if (!playerSettingsUiState.streamFailoverEnabled) return false
    if (streamFailoverJob?.isActive == true) return true
    val failedUrl = activeSourceUrl.takeIf { it.isNotBlank() } ?: return false
    streamFailoverState = streamFailoverState.forVideo(activeVideoId)

    return when (decideStreamFailover(streamFailoverState, failedUrl, playerController != null)) {
        StreamFailoverAction.Retry -> {
            val resumePositionMs = failoverResumePositionMs()
            streamFailoverState = streamFailoverState.copy(
                retriedUrl = failedUrl,
                lastProgressPositionMs = resumePositionMs,
                playedMs = 0L,
            )
            // libmpv reloads the file from the start on retry, so seek back once it is ready.
            activeInitialPositionMs = resumePositionMs
            activeInitialProgressFraction = null
            initialSeekApplied = resumePositionMs <= 0L
            InAppLogger.info("Player/Failover", "retry url=${InAppLogger.redactUrl(failedUrl)} error=$message")
            scope.launch { NuvioToastController.show(getString(Res.string.player_failover_retrying)) }
            playerController?.retry()
            loadFailoverSources()
            true
        }
        StreamFailoverAction.NextStream -> {
            streamFailoverState = streamFailoverState.copy(failedUrls = streamFailoverState.failedUrls + failedUrl)
            if (activeVideoId == null || isLiveTvPlayback) return false
            removeFailedStreamFromCache()
            startFailoverToNextStream(message)
            true
        }
        StreamFailoverAction.GiveUp -> false
    }
}

internal fun PlayerScreenRuntime.noteStreamFailoverProgress(positionMs: Long) {
    val current = streamFailoverState
    val updated = current.afterProgress(positionMs)
    if (updated == current) return
    streamFailoverState = updated
    val recovered = current.lastProgressPositionMs != null && updated.lastProgressPositionMs == null
    if (
        recovered &&
        shouldStopLeftoverSourceLoading(
            isLoading = PlayerStreamsRepository.sourceState.value.isAnyLoading,
            sourcesPanelOpen = showSourcesPanel,
            failoverRunning = streamFailoverJob?.isActive == true,
        )
    ) {
        PlayerStreamsRepository.stopSourcesLoading()
    }
}

/** Where the next source should start: the live position, or the failed source's start if it never played. */
internal fun PlayerScreenRuntime.failoverResumePositionMs(): Long =
    playbackSnapshot.positionMs.takeIf { it > 0L } ?: activeInitialPositionMs.coerceAtLeast(0L)

private fun PlayerScreenRuntime.loadFailoverSources() {
    if (isLiveTvPlayback) return
    val videoId = activeVideoId ?: return
    PlayerStreamsRepository.loadSources(
        type = contentType ?: parentMetaType,
        videoId = videoId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
}

/** What a next-stream attempt was started for, so a stale attempt can tell it has been overtaken. */
internal data class FailoverContext(
    val playbackKey: PlaybackKey,
    val failedUrl: String,
    val failedIdentityKey: String?,
    val resumePositionMs: Long,
    val attempt: Int,
    val message: String,
)

internal fun PlayerScreenRuntime.captureFailoverContext(message: String) = FailoverContext(
    playbackKey = activePlaybackKey,
    failedUrl = activeSourceUrl,
    failedIdentityKey = activeSourceIdentityKey,
    resumePositionMs = failoverResumePositionMs(),
    attempt = streamFailoverState.alternativesUsed + 1,
    message = message,
)

/** Shows [message] unless failover takes the error over. For error paths that give up on their own. */
internal fun PlayerScreenRuntime.showPlaybackErrorOrFailover(message: String?) {
    if (message != null && tryStreamFailoverAfterError(message)) return
    errorMessage = message
    controlsVisible = !playerControlsLocked
}

/** A finished source list is kept so the next failover sees the same streams and URLs. */
internal fun shouldStopFailoverSourceLoading(state: StreamsUiState): Boolean = state.isAnyLoading

private fun PlayerScreenRuntime.startFailoverToNextStream(message: String) {
    val context = captureFailoverContext(message)
    errorMessage = null
    scope.launch { NuvioToastController.show(getString(Res.string.player_failover_finding_next)) }
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
                failedUrl = context.failedUrl,
                failedIdentityKey = context.failedIdentityKey,
                excludedUrls = streamFailoverState.failedUrls,
                isPlayable = { isFailoverPlayable(it) },
            )
            finishFailover(context, candidate)
        } finally {
            if (shouldStopFailoverSourceLoading(PlayerStreamsRepository.sourceState.value)) {
                PlayerStreamsRepository.stopSourcesLoading()
            }
        }
    }
}

/** Switches to [candidate], or shows the error when there is none. Does nothing if playback moved on meanwhile. */
internal fun PlayerScreenRuntime.finishFailover(context: FailoverContext, candidate: StreamItem?) {
    if (context.playbackKey != activePlaybackKey || context.failedUrl != activeSourceUrl) {
        InAppLogger.info("Player/Failover", "dropped: playback changed while finding a stream")
        return
    }
    if (candidate == null) {
        InAppLogger.warn("Player/Failover", "no candidate after url=${InAppLogger.redactUrl(context.failedUrl)}")
        errorMessage = context.message
        controlsVisible = !playerControlsLocked
        return
    }
    streamFailoverState = streamFailoverState.copy(
        retriedUrl = null,
        alternativesUsed = context.attempt,
        lastProgressPositionMs = context.resumePositionMs,
        playedMs = 0L,
    )
    InAppLogger.info(
        "Player/Failover",
        "next stream ${context.attempt}/$MAX_FAILOVER_ALTERNATIVES addon=${candidate.addonName} " +
            "stream=${candidate.streamLabel} url=${InAppLogger.redactUrl(candidate.playableDirectUrl)}",
    )
    scope.launch {
        NuvioToastController.show(
            getString(
                Res.string.player_failover_trying_next,
                context.attempt,
                MAX_FAILOVER_ALTERNATIVES,
                "${candidate.addonName} · ${candidate.streamLabel}",
            ),
        )
    }
    switchToSource(candidate, saveForReuse = false, resumePositionMs = context.resumePositionMs)
}

/** Only streams that switchToSource can play directly and synchronously. */
private fun PlayerScreenRuntime.isFailoverPlayable(stream: StreamItem): Boolean =
    !stream.needsServerPreparation &&
        stream.youTubeIdToResolve == null &&
        !DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream) &&
        !isP2pStream(stream) &&
        !stream.shouldOpenExternally
