package com.nuvio.app.features.player

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.skip.NextEpisodeThresholdMode
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerSleepTimerRuntimeTest {
    @get:Rule
    val compose = createComposeRule()

    private val controller = FakeController()
    private val runtime = PlayerScreenRuntime(testPlayerScreenArgs()).apply {
        scope = TestScope()
        playerController = controller
        playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            positionMs = 60_000L,
            durationMs = 600_000L,
        )
    }

    @Test
    fun tickingPastTheEndFadesThenPausesOnceAndRestoresVolume() {
        controller.volume = 0.8f
        runtime.setSleepTimer(SleepTimerMode.Duration(endsAtMs = 100_000L))

        runtime.onSleepTimerTick(nowMs = 95_000L)
        runtime.onSleepTimerTick(nowMs = 97_000L)
        assertEquals(0, controller.pauses)
        assertTrue(controller.volumeSets.last() < 0.8f, "fading")

        runtime.onSleepTimerTick(nowMs = 100_000L)
        runtime.onSleepTimerTick(nowMs = 101_000L)
        assertEquals(1, controller.pauses)
        assertEquals(0.8f, controller.volumeSets.last())
        assertEquals(SleepTimerMode.Off, runtime.sleepTimerMode)
        assertTrue(runtime.sleepTimerPromptPending)
        assertFalse(runtime.shouldPlay)
    }

    @Test
    fun fadeIsMeasuredFromWhenItActuallyStarted() {
        controller.volume = 1f
        runtime.setSleepTimer(SleepTimerMode.Duration(endsAtMs = 100_000L))
        // The first tick that sees the fade window is 4 s late; the fade still starts at full volume.
        runtime.onSleepTimerTick(nowMs = 94_000L)
        assertEquals(1f, controller.volumeSets.last())
        runtime.onSleepTimerTick(nowMs = 99_000L)
        assertEquals(0.5f, controller.volumeSets.last())
    }

    @Test
    fun turningTheTimerOffMidFadeRestoresVolume() {
        controller.volume = 0.6f
        runtime.setSleepTimer(SleepTimerMode.Duration(endsAtMs = 100_000L))
        runtime.onSleepTimerTick(nowMs = 97_000L)
        runtime.setSleepTimer(SleepTimerMode.Off)
        assertEquals(0.6f, controller.volumeSets.last())
        runtime.onSleepTimerTick(nowMs = 120_000L)
        assertEquals(0, controller.pauses)
    }

    @Test
    fun promptStaysHiddenInPictureInPictureAndAppearsOnReturn() {
        var inPip by mutableStateOf(true)
        compose.setContent {
            NuvioTheme {
                SleepTimerPromptHost(
                    pending = runtime.sleepTimerPromptPending,
                    inPip = inPip,
                    miniPlayerActive = false,
                    onExtend = {},
                    onResume = {},
                    onDismiss = {},
                )
            }
        }
        runtime.setSleepTimer(SleepTimerMode.Duration(endsAtMs = 1_000L))
        compose.runOnIdle { runtime.onSleepTimerTick(nowMs = 1_000L) }
        compose.waitForIdle()

        assertEquals(1, controller.pauses)
        assertTrue(runtime.sleepTimerPromptPending)
        compose.onNodeWithText("Still watching?").assertDoesNotExistCompat()

        compose.runOnIdle { inPip = false }
        compose.onNodeWithText("Still watching?").assertIsDisplayed()
    }

    @Test
    fun partyLockedFireSkipsFadeAndPauseButStillPrompts() {
        runtime.sleepTimerPartyLocked = { true }
        controller.volume = 0.8f
        runtime.nextEpisodeAutoPlayCountdown = 2
        runtime.setSleepTimer(SleepTimerMode.Duration(endsAtMs = 100_000L))

        runtime.onSleepTimerTick(nowMs = 95_000L)
        runtime.onSleepTimerTick(nowMs = 97_000L)
        runtime.onSleepTimerTick(nowMs = 100_000L)

        assertEquals(0, controller.pauses)
        assertEquals(emptyList(), controller.volumeSets)
        assertEquals(SleepTimerMode.Off, runtime.sleepTimerMode)
        assertTrue(runtime.sleepTimerPromptPending)
        runtime.dismissSleepPrompt(resume = true)
        assertEquals(0, controller.plays)
    }

    @Test
    fun engineFailuresInATickAreSwallowed() {
        controller.failVolume = true
        runtime.setSleepTimer(SleepTimerMode.Duration(endsAtMs = 100_000L))
        runtime.onSleepTimerTick(nowMs = 95_000L)
        runtime.onSleepTimerTick(nowMs = 100_000L)
        assertEquals(1, controller.pauses)
        assertTrue(runtime.sleepTimerPromptPending)
    }

    @Test
    fun leavingTheFadeWindowRestoresVolume() {
        controller.volume = 0.7f
        runtime.playbackSnapshot = runtime.playbackSnapshot.copy(positionMs = 599_000L - 5_000L)
        runtime.setSleepTimer(SleepTimerMode.EndOfEpisode)
        runtime.onSleepTimerTick(nowMs = 1_000L)
        runtime.onSleepTimerTick(nowMs = 3_000L)
        assertTrue(controller.volumeSets.last() < 0.7f)

        runtime.playbackSnapshot = runtime.playbackSnapshot.copy(positionMs = 100_000L)
        runtime.onSleepTimerTick(nowMs = 4_000L)
        assertEquals(0.7f, controller.volumeSets.last())
        assertNull(runtime.sleepFadeStartedAtMs)
    }

    @Test
    fun releasingTheTimerOnExitRestoresVolume() {
        controller.volume = 0.9f
        runtime.setSleepTimer(SleepTimerMode.Duration(endsAtMs = 100_000L))
        runtime.onSleepTimerTick(nowMs = 95_000L)
        runtime.onSleepTimerTick(nowMs = 98_000L)
        runtime.restoreSleepFadeVolume()
        assertEquals(0.9f, controller.volumeSets.last())
        assertNull(runtime.sleepFadeStartedAtMs)
    }

    @Test
    fun durationFireCancelsAutoPlayAndResumeClearsSuppression() {
        runtime.nextEpisodeAutoPlayCountdown = 3
        runtime.setSleepTimer(SleepTimerMode.Duration(endsAtMs = 1_000L))
        runtime.onSleepTimerTick(nowMs = 1_000L)
        assertNull(runtime.nextEpisodeAutoPlayCountdown)
        assertTrue(runtime.sleepTimerSuppressAutoPlay)

        runtime.dismissSleepPrompt(resume = true)
        assertFalse(runtime.sleepTimerSuppressAutoPlay)
    }

    @Test
    fun playingAfterADismissedFireClearsAutoPlaySuppression() {
        runtime.setSleepTimer(SleepTimerMode.Duration(endsAtMs = 1_000L))
        runtime.onSleepTimerTick(nowMs = 1_000L)
        runtime.playbackSnapshot = runtime.playbackSnapshot.copy(isPlaying = false)
        assertTrue(runtime.sleepTimerSuppressAutoPlay)

        runtime.dismissSleepPrompt(resume = false)
        assertTrue(runtime.sleepTimerSuppressAutoPlay, "dismissing alone keeps the stop")

        runtime.togglePlayback()
        assertEquals(1, controller.plays)
        assertFalse(runtime.sleepTimerSuppressAutoPlay, "autoplay is no longer blocked")
    }

    @Test
    fun playWhileThePromptIsPendingKeepsSuppression() {
        runtime.setSleepTimer(SleepTimerMode.Duration(endsAtMs = 1_000L))
        runtime.onSleepTimerTick(nowMs = 1_000L)
        runtime.setPlaybackPlaying(true)
        assertTrue(runtime.sleepTimerSuppressAutoPlay)
    }

    @Test
    fun partyLockArrivingMidFadeStillRestoresVolumeOnFire() {
        controller.volume = 0.8f
        runtime.setSleepTimer(SleepTimerMode.Duration(endsAtMs = 100_000L))
        runtime.onSleepTimerTick(nowMs = 95_000L)
        runtime.onSleepTimerTick(nowMs = 97_000L)
        assertTrue(controller.volumeSets.last() < 0.8f, "fading")

        runtime.sleepTimerPartyLocked = { true }
        runtime.onSleepTimerTick(nowMs = 100_000L)

        assertEquals(0, controller.pauses)
        assertEquals(0.8f, controller.volumeSets.last())
        assertNull(runtime.sleepFadeStartedAtMs)
        assertTrue(runtime.sleepTimerPromptPending)
    }

    @Test
    fun promptGateHidesWhileMiniPlayerIsActive() {
        assertFalse(shouldShowSleepPrompt(pending = true, inPip = false, miniPlayerActive = true))
        assertFalse(shouldShowSleepPrompt(pending = true, inPip = true, miniPlayerActive = false))
        assertFalse(shouldShowSleepPrompt(pending = false, inPip = false, miniPlayerActive = false))
        assertTrue(shouldShowSleepPrompt(pending = true, inPip = false, miniPlayerActive = false))
    }

    @Test
    fun endOfEpisodeFireCancelsNextEpisodeAutoPlayAndSuppressesIt() {
        runtime.nextEpisodeAutoPlayCountdown = 2
        runtime.nextEpisodeAutoPlaySearching = true
        runtime.setSleepTimer(SleepTimerMode.EndOfEpisode)

        runtime.playbackSnapshot = runtime.playbackSnapshot.copy(positionMs = 599_000L)
        runtime.onSleepTimerTick(nowMs = 1_000L)

        assertEquals(1, controller.pauses)
        assertNull(runtime.nextEpisodeAutoPlayCountdown)
        assertFalse(runtime.nextEpisodeAutoPlaySearching)
        assertTrue(runtime.sleepTimerSuppressAutoPlay)
        assertEquals(SleepTimerMode.Off, runtime.sleepTimerMode)
    }

    @Test
    fun extendStartsAFifteenMinuteTimerAndResumesPlayback() {
        runtime.sleepTimerClock = { 5_000L }
        runtime.sleepTimerPromptPending = true
        runtime.shouldPlay = false

        runtime.extendSleepTimer()

        assertEquals(SleepTimerMode.Duration(endsAtMs = 5_000L + 900_000L), runtime.sleepTimerMode)
        assertEquals(1, controller.plays)
        assertFalse(runtime.sleepTimerPromptPending)
        assertTrue(runtime.shouldPlay)
    }

    @Test
    fun resumeTurnsTheTimerOffAndPlaysWhileDismissStaysPaused() {
        runtime.sleepTimerPromptPending = true
        runtime.shouldPlay = false
        runtime.dismissSleepPrompt(resume = true)
        assertEquals(1, controller.plays)
        assertEquals(SleepTimerMode.Off, runtime.sleepTimerMode)
        assertFalse(runtime.sleepTimerPromptPending)

        runtime.sleepTimerPromptPending = true
        runtime.dismissSleepPrompt(resume = false)
        assertEquals(1, controller.plays)
        assertFalse(runtime.sleepTimerPromptPending)
    }

    @Test
    fun manualEpisodeSwitchClearsEndOfEpisode() {
        runtime.setSleepTimer(SleepTimerMode.EndOfEpisode)
        runtime.clearEndOfEpisodeSleepTimer()
        assertEquals(SleepTimerMode.Off, runtime.sleepTimerMode)

        runtime.setSleepTimer(SleepTimerMode.Duration(9_000L))
        runtime.clearEndOfEpisodeSleepTimer()
        assertEquals(SleepTimerMode.Duration(9_000L), runtime.sleepTimerMode)
    }

    @Test
    fun thresholdPositionMatchesTheNextEpisodeCardRule() {
        val position = PlayerNextEpisodeRules.nextEpisodeThresholdPositionMs(
            durationMs = 600_000L,
            skipIntervals = emptyList(),
            thresholdMode = NextEpisodeThresholdMode.MINUTES_BEFORE_END,
            thresholdPercent = 99f,
            thresholdMinutesBeforeEnd = 1f,
        )
        assertEquals(540_000L, position)
    }

    @Test
    fun countdownLabelFormatsMinutesAndSeconds() {
        assertEquals("01:05", formatSleepTimerCountdown(65_000L))
        assertEquals("00:00", formatSleepTimerCountdown(0L))
        assertEquals("15:00", formatSleepTimerCountdown(900_000L))
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() =
        assertDoesNotExist()

    private class FakeController : PlayerEngineController {
        var pauses = 0
        var plays = 0
        var volume = 1f
        var failVolume = false
        val volumeSets = mutableListOf<Float>()
        override fun play() { plays++ }
        override fun pause() { pauses++ }
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun currentPlayerVolume() = if (failVolume) error("boom") else PlayerAudioLevel(fraction = volume, isMuted = false)
        override fun setPlayerVolume(level: Float): PlayerAudioLevel {
            if (failVolume) error("boom")
            volumeSets += level
            return PlayerAudioLevel(fraction = level, isMuted = level <= 0.001f)
        }
        override fun getAudioTracks(): List<AudioTrack> = emptyList()
        override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun selectSubtitleTrack(index: Int) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }

    private fun testPlayerScreenArgs() = PlayerScreenArgs(
        profileId = 1,
        title = "Title",
        sourceUrl = "https://example.com/video.mp4",
        sourceAudioUrl = null,
        sourceHeaders = emptyMap(),
        sourceResponseHeaders = emptyMap(),
        streamType = null,
        providerName = "Provider",
        streamTitle = "Source",
        streamSubtitle = null,
        initialBingeGroup = null,
        pauseDescription = null,
        onBack = {},
        onOpenInExternalPlayer = null,
        onOpenExternalUrl = null,
        modifier = Modifier,
        logo = null,
        poster = null,
        background = null,
        seasonNumber = null,
        episodeNumber = null,
        episodeTitle = null,
        episodeThumbnail = null,
        contentType = "movie",
        videoId = "tt1234567",
        parentMetaId = "tt1234567",
        parentMetaType = "movie",
        providerAddonId = null,
        torrentInfoHash = null,
        torrentFileIdx = null,
        torrentFilename = null,
        torrentTrackers = emptyList(),
        initialPositionMs = 0L,
        initialProgressFraction = null,
    )
}
