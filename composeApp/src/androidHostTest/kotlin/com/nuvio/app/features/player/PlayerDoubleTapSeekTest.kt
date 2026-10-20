package com.nuvio.app.features.player

import android.app.Application
import androidx.compose.ui.Modifier
import kotlinx.coroutines.test.TestScope
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerDoubleTapSeekTest {
    private val seeks = mutableListOf<Long>()
    private val runtime = PlayerScreenRuntime(testPlayerScreenArgs()).apply {
        scope = TestScope()
        playerController = RecordingController(seeks)
        playerSettingsUiState = PlayerSettingsUiState(seekForwardSeconds = 15, seekBackSeconds = 15)
    }

    private fun position(positionMs: Long) {
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            positionMs = positionMs,
            durationMs = 600_000L,
        )
    }

    @Test
    fun threeForwardTapsSeekOneConfiguredStepEachFromTheFirstBaseline() {
        position(60_000L)
        runtime.handleDoubleTapSeek(PlayerSeekDirection.Forward)
        // The player reports a stale position between taps; targets must not depend on it.
        runtime.handleDoubleTapSeek(PlayerSeekDirection.Forward)
        runtime.handleDoubleTapSeek(PlayerSeekDirection.Forward)
        assertEquals(listOf(75_000L, 90_000L, 105_000L), seeks)
    }

    @Test
    fun rewindingNearTheStartClampsAtZero() {
        position(20_000L)
        runtime.handleDoubleTapSeek(PlayerSeekDirection.Backward)
        runtime.handleDoubleTapSeek(PlayerSeekDirection.Backward)
        assertEquals(listOf(5_000L, 0L), seeks)
    }

    @Test
    fun targetHelperClampsToDuration() {
        assertEquals(
            100_000L,
            doubleTapSeekTarget(PlayerSeekDirection.Forward, 95_000L, 15_000L, 100_000L),
        )
        assertEquals(
            110_000L,
            doubleTapSeekTarget(PlayerSeekDirection.Forward, 95_000L, 15_000L, 0L),
        )
    }

    private class RecordingController(private val seeks: MutableList<Long>) : PlayerEngineController {
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) {
            seeks += positionMs
        }
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
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
