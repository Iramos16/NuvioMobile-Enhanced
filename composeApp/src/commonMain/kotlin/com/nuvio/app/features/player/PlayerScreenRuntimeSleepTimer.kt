package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import com.nuvio.app.core.logging.InAppLogger
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.sleep_timer_end_of_episode_short
import org.jetbrains.compose.resources.stringResource

internal const val SleepTimerTickIntervalMs = 1_000L

/** The prompt waits for full screen: not in system PiP and not in the in-app mini player. */
fun shouldShowSleepPrompt(pending: Boolean, inPip: Boolean, miniPlayerActive: Boolean): Boolean =
    pending && !inPip && !miniPlayerActive

fun formatSleepTimerCountdown(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1_000L
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}

internal fun PlayerScreenRuntime.setSleepTimer(mode: SleepTimerMode) {
    restoreSleepFadeVolume()
    sleepTimerMode = mode
    sleepTimerNowMs = sleepTimerClock()
    if (mode != SleepTimerMode.Off) {
        sleepTimerPromptPending = false
        sleepTimerSuppressAutoPlay = false
    }
}

/** Manual episode changes end an "end of this episode" timer; duration timers keep running. */
internal fun PlayerScreenRuntime.clearEndOfEpisodeSleepTimer() {
    if (sleepTimerMode == SleepTimerMode.EndOfEpisode) setSleepTimer(SleepTimerMode.Off)
    sleepTimerSuppressAutoPlay = false
}

internal fun PlayerScreenRuntime.onSleepTimerTick(nowMs: Long) {
    sleepTimerNowMs = nowMs
    val mode = sleepTimerMode
    if (mode == SleepTimerMode.Off) return
    val durationMs = playbackSnapshot.durationMs
    val positionMs = if (playbackSnapshot.isEnded) durationMs else playbackSnapshot.positionMs
    val thresholdMs = if (mode == SleepTimerMode.EndOfEpisode) sleepTimerNextEpisodeThresholdMs() else null

    if (mode.shouldFire(nowMs, positionMs, durationMs, thresholdMs)) {
        fireSleepTimer(mode)
        return
    }
    if (mode.fadeStartReached(nowMs, positionMs, durationMs, thresholdMs)) {
        val controller = playerController ?: return
        val startedAt = sleepFadeStartedAtMs ?: nowMs.also {
            sleepFadeStartedAtMs = it
            sleepFadeStartVolume = controller.currentPlayerVolume().fraction
        }
        controller.setPlayerVolume(fadeVolumeFraction(sleepFadeStartVolume, nowMs - startedAt))
    }
}

private fun PlayerScreenRuntime.sleepTimerNextEpisodeThresholdMs(): Long? {
    if (nextEpisodeInfo == null) return null
    return com.nuvio.app.features.player.skip.PlayerNextEpisodeRules.nextEpisodeThresholdPositionMs(
        durationMs = playbackSnapshot.durationMs,
        skipIntervals = skipIntervals,
        thresholdMode = playerSettingsUiState.nextEpisodeThresholdMode,
        thresholdPercent = playerSettingsUiState.nextEpisodeThresholdPercent,
        thresholdMinutesBeforeEnd = playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
    )
}

private fun PlayerScreenRuntime.fireSleepTimer(mode: SleepTimerMode) {
    InAppLogger.info("Player/SleepTimer", "fired mode=$mode positionMs=${playbackSnapshot.positionMs}")
    shouldPlay = false
    playerController?.pause()
    restoreSleepFadeVolume()
    if (mode == SleepTimerMode.EndOfEpisode) {
        cancelNextEpisodeAutoPlay()
        sleepTimerSuppressAutoPlay = true
    }
    sleepTimerMode = SleepTimerMode.Off
    sleepTimerPromptPending = true
}

private fun PlayerScreenRuntime.restoreSleepFadeVolume() {
    if (sleepFadeStartedAtMs == null) return
    sleepFadeStartedAtMs = null
    playerController?.setPlayerVolume(sleepFadeStartVolume)
}

internal fun PlayerScreenRuntime.extendSleepTimer() {
    setSleepTimer(startSleepTimer(SleepTimerExtendMinutes, sleepTimerClock()))
    sleepTimerPromptPending = false
    resumePlaybackAfterSleepPrompt()
}

internal fun PlayerScreenRuntime.dismissSleepPrompt(resume: Boolean) {
    sleepTimerPromptPending = false
    if (resume) {
        setSleepTimer(SleepTimerMode.Off)
        resumePlaybackAfterSleepPrompt()
    }
}

private fun PlayerScreenRuntime.resumePlaybackAfterSleepPrompt() {
    shouldPlay = true
    playerController?.play()
}

/** Header chip text: mm:ss for a duration timer, a short label for end of episode, null when off. */
@Composable
internal fun PlayerScreenRuntime.sleepTimerChipLabel(): String? = when (val mode = sleepTimerMode) {
    SleepTimerMode.Off -> null
    SleepTimerMode.EndOfEpisode -> stringResource(Res.string.sleep_timer_end_of_episode_short)
    is SleepTimerMode.Duration -> formatSleepTimerCountdown(mode.remainingMs(sleepTimerNowMs) ?: 0L)
}

@Composable
internal fun PlayerScreenRuntime.RenderSleepTimer() {
    SleepTimerSheet(
        visible = showSleepTimerSheet,
        currentMode = sleepTimerMode,
        showEndOfEpisode = !isMoviePlayback && !isLiveTvPlayback,
        onSelectMinutes = { minutes ->
            setSleepTimer(startSleepTimer(minutes, sleepTimerClock()))
            showSleepTimerSheet = false
        },
        onSelectMode = { mode ->
            setSleepTimer(mode)
            showSleepTimerSheet = false
        },
        onDismiss = { showSleepTimerSheet = false },
    )
    SleepTimerPromptHost(
        pending = sleepTimerPromptPending,
        inPip = rememberIsInPictureInPicture(),
        miniPlayerActive = MiniPlayerController.isActive,
        onExtend = { extendSleepTimer() },
        onResume = { dismissSleepPrompt(resume = true) },
        onDismiss = { dismissSleepPrompt(resume = false) },
    )
}
