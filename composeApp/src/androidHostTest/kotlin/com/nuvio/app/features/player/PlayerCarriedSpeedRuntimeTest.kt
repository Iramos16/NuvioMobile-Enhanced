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
class PlayerCarriedSpeedRuntimeTest {
    private val runtime = PlayerScreenRuntime(testPlayerScreenArgs()).apply {
        scope = TestScope()
        sessionPlaybackSpeed = 1.5f
    }
    private val loaded = PlayerPlaybackSnapshot(
        isLoading = false,
        isPlaying = true,
        positionMs = 10_000L,
        durationMs = 600_000L,
    )

    @Test
    fun carriedSpeedIsAppliedOncePerEngineUnderTheSameKey() {
        val first = SpeedController()
        runtime.playerController = first
        runtime.updatePlaybackSnapshot(loaded)
        runtime.updatePlaybackSnapshot(loaded)
        assertEquals(listOf(1.5f), first.speeds)

        // Engine rebuild or fallback for the same stream: the new engine starts at 1x.
        val rebuilt = SpeedController()
        runtime.playerController = rebuilt
        runtime.updatePlaybackSnapshot(loaded)
        runtime.updatePlaybackSnapshot(loaded)
        assertEquals(listOf(1.5f), rebuilt.speeds)
    }

    private class SpeedController : PlayerEngineController {
        val speeds = mutableListOf<Float>()
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) {
            speeds += speed
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
