package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import androidx.compose.ui.Modifier
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerStreamFailoverRuntimeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sourceUrl = "https://example.com/movie.mp4"

    @Before
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        listOf("nuvio_player_track_preferences", "nuvio_watch_progress", "nuvio_player_settings").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        PlayerTrackPreferenceStorage.initialize(context)
        PlayerSettingsStorage.initialize(context)
        WatchProgressStorage.initialize(context)
        WatchProgressRepository.clearLocalState()
    }

    @After
    fun cleanup() {
        scope.cancel()
        WatchProgressRepository.clearLocalState()
    }

    @Test
    fun disabledSettingLeavesErrorHandlingUnchanged() {
        val controller = RetryCountingController()
        val runtime = runtime(controller, failoverEnabled = false)

        assertFalse(runtime.tryStreamFailoverAfterError("boom"))
        assertEquals(0, controller.retries)
    }

    @Test
    fun firstErrorRetriesCurrentSource() {
        val controller = RetryCountingController()
        val runtime = runtime(controller)

        assertTrue(runtime.tryStreamFailoverAfterError("boom"))
        assertEquals(1, controller.retries)
        assertNull(runtime.errorMessage)
        assertEquals(sourceUrl, runtime.streamFailoverState.retriedUrl)
    }

    @Test
    fun retryStepHoldsNoJobSoSecondErrorAdvances() {
        val controller = RetryCountingController()
        val runtime = runtime(controller)

        runtime.tryStreamFailoverAfterError("boom")
        assertNull(runtime.streamFailoverJob)

        // No videoId means no source list, so the next-stream step gives up straight away.
        assertFalse(runtime.tryStreamFailoverAfterError("boom"))
        assertEquals(1, controller.retries)
        assertTrue(sourceUrl in runtime.streamFailoverState.failedUrls)
    }

    @Test
    fun failoverResumePositionFallsBackToInitialPosition() {
        val runtime = runtime(RetryCountingController(), initialPositionMs = 1_200_000L)

        assertEquals(1_200_000L, runtime.failoverResumePositionMs())
        runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(positionMs = 5_000L))
        assertEquals(5_000L, runtime.failoverResumePositionMs())
    }

    private fun runtime(
        controller: PlayerEngineController,
        failoverEnabled: Boolean = true,
        initialPositionMs: Long = 0L,
    ) = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Movie",
            sourceUrl = sourceUrl,
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Test",
            streamTitle = "Movie 1080p",
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
            videoId = null,
            parentMetaId = "tt2026",
            parentMetaType = "movie",
            providerAddonId = "test",
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = initialPositionMs,
            initialProgressFraction = null,
        ),
    ).apply {
        scope = this@PlayerStreamFailoverRuntimeTest.scope
        playerSettingsUiState = PlayerSettingsUiState(streamFailoverEnabled = failoverEnabled)
        playerController = controller
    }

    private class RetryCountingController : PlayerEngineController {
        var retries = 0

        override fun retry() {
            retries++
        }

        override fun getAudioTracks() = emptyList<AudioTrack>()
        override fun getSubtitleTracks() = emptyList<SubtitleTrack>()
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun selectSubtitleTrack(index: Int) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }
}
