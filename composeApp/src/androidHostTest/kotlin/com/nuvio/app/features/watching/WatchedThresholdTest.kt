package com.nuvio.app.features.watching

import com.nuvio.app.features.anilist.AniListTrackingWrites
import com.nuvio.app.features.mdblist.MDBLIST_TEST_TIME
import com.nuvio.app.features.mdblist.MdbListProgressProjection
import com.nuvio.app.features.mdblist.MdbListScrobbleService
import com.nuvio.app.features.mdblist.MdbListSyncSnapshot
import com.nuvio.app.features.mdblist.MdbListSyncTestHarness
import com.nuvio.app.features.mdblist.mdbListResponseElement
import com.nuvio.app.features.mdblist.mdbListTestPlayback
import com.nuvio.app.features.mdblist.objectValue
import com.nuvio.app.features.mdblist.text
import com.nuvio.app.features.player.shouldSendStopScrobble
import com.nuvio.app.features.player.shouldUpdateTrackingScrobbleAfterSeek
import com.nuvio.app.features.simkl.SimklMedia
import com.nuvio.app.features.simkl.SimklPlaybackSession
import com.nuvio.app.features.simkl.toWatchProgressEntry
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingScrobbler
import com.nuvio.app.features.tracking.dispatchTrackingScrobble
import com.nuvio.app.features.trakt.TraktTrackingProgressProvider
import com.nuvio.app.features.watching.domain.WatchedThreshold
import com.nuvio.app.features.watching.domain.isProgressComplete
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktPlayback
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchedThresholdTest {
    @AfterTest
    fun resetThreshold() {
        WatchedThreshold.percent = WatchedThreshold.DEFAULT_PERCENT
    }

    @Test
    fun defaultsToNinetyPercent() {
        assertEquals(90, WatchedThreshold.DEFAULT_PERCENT)
        assertEquals(90, WatchedThreshold.percent)
    }

    @Test
    fun clampsToTheSupportedRange() {
        assertEquals(80, WatchedThreshold.normalize(50))
        assertEquals(99, WatchedThreshold.normalize(100))
        assertEquals(93, WatchedThreshold.normalize(93))
    }

    @Test
    fun localPlaybackCompletesAtTheConfiguredThreshold() {
        WatchedThreshold.percent = 95
        assertFalse(isProgressComplete(positionMs = 94 * 60_000L, durationMs = 100 * 60_000L, isEnded = false))
        assertTrue(isProgressComplete(positionMs = 95 * 60_000L, durationMs = 100 * 60_000L, isEnded = false))

        WatchedThreshold.percent = 85
        assertTrue(isProgressComplete(positionMs = 86 * 60_000L, durationMs = 100 * 60_000L, isEnded = false))
    }

    @Test
    fun explicitProgressPercentHonoursTheConfiguredThreshold() {
        WatchedThreshold.percent = 95
        assertFalse(entry(progressPercent = 92f).isEffectivelyCompleted)
        assertTrue(entry(progressPercent = 95f).isEffectivelyCompleted)
    }

    @Test
    fun playerStopScrobbleWaitsForTheConfiguredThreshold() {
        WatchedThreshold.percent = 95
        assertFalse(shouldSendStopScrobble(hasActiveScrobble = false, progressPercent = 90f))
        assertTrue(shouldSendStopScrobble(hasActiveScrobble = false, progressPercent = 95f))
    }

    @Test
    fun seekScrobbleUpdatesContinueUntilTheConfiguredThreshold() {
        WatchedThreshold.percent = 95
        assertTrue(shouldUpdateTrackingScrobbleAfterSeek(hasActiveScrobble = true, progressPercent = 90f))
        assertFalse(shouldUpdateTrackingScrobbleAfterSeek(hasActiveScrobble = true, progressPercent = 95f))
    }

    @Test
    fun stopBelowTheThresholdIsSentToTrackersAsPause() = runBlocking {
        WatchedThreshold.percent = 95
        val scrobbler = RecordingScrobbler()

        dispatchTrackingScrobble(listOf(scrobbler), profileId = 1, action = TrackingScrobbleAction.STOP, event = event(85.0))
        dispatchTrackingScrobble(listOf(scrobbler), profileId = 1, action = TrackingScrobbleAction.STOP, event = event(96.0))
        dispatchTrackingScrobble(listOf(scrobbler), profileId = 1, action = TrackingScrobbleAction.STOP, event = event(40.0))

        assertEquals(
            listOf(TrackingScrobbleAction.PAUSE, TrackingScrobbleAction.STOP, TrackingScrobbleAction.STOP),
            scrobbler.actions,
        )
    }

    @Test
    fun aniListCompletionUsesTheConfiguredThreshold() {
        WatchedThreshold.percent = 95
        assertFalse(AniListTrackingWrites.isCompletionProgress(90.0))
        assertTrue(AniListTrackingWrites.isCompletionProgress(95.0))
    }

    @Test
    fun simklPlaybackStaysInProgressBelowTheConfiguredThreshold() {
        WatchedThreshold.percent = 95
        val media = SimklMedia(title = "Movie", ids = buildJsonObject { put("imdb", "tt0111161") })
        val entry = SimklPlaybackSession(id = 1, progress = 85.0, type = "movie", movie = media).toWatchProgressEntry()!!
        assertFalse(entry.isCompleted)
        assertFalse(entry.isEffectivelyCompleted)
    }

    @Test
    fun mdbListPlaybackStaysInProgressBelowTheConfiguredThreshold() {
        WatchedThreshold.percent = 95
        val projection = MdbListProgressProjection(MdbListSyncSnapshot(42, playback = listOf(mdbListTestPlayback(85f))))
        assertFalse(projection.progress.single().isEffectivelyCompleted)
        assertTrue(
            MdbListProgressProjection(MdbListSyncSnapshot(42, playback = listOf(mdbListTestPlayback(95f)))).progress.isEmpty(),
        )
    }

    @Test
    fun mdbListScrobblesBelowTheConfiguredThresholdStayUnderItsWatchedCutoff() = runTest {
        WatchedThreshold.percent = 95
        for ((action, progress, sent) in listOf(
            Triple(TrackingScrobbleAction.PAUSE, 85.0, 79.99),
            Triple(TrackingScrobbleAction.PAUSE, 60.0, 60.0),
            Triple(TrackingScrobbleAction.STOP, 96.0, 96.0),
        )) {
            val harness = MdbListSyncTestHarness(backgroundScope)
            harness.http.reply(body = """{"id":7,"action":"pause","progress":$sent,"paused_at":"$MDBLIST_TEST_TIME"}""")

            MdbListScrobbleService(harness.http.api, harness.repository)
                .scrobble(harness.repository.currentScope(), action, mdbListEvent(progress))

            val body = mdbListResponseElement(harness.http.engine.requests.last().body).objectValue()
            assertEquals(sent, body.text("progress")!!.toDouble(), 0.0)
        }
    }

    @Test
    fun traktPlaybackIsNotSeededAsNextUpBeforeTheConfiguredThreshold() {
        WatchedThreshold.percent = 99
        val now = 1_000_000L
        val traktEntry = entry(progressPercent = 96f).copy(
            source = WatchProgressSourceTraktPlayback,
            lastUpdatedEpochMs = now,
        )
        assertFalse(TraktTrackingProgressProvider.shouldUseAsNextUpSeed(traktEntry, now))
    }

    private fun entry(progressPercent: Float) = WatchProgressEntry(
        contentType = "series",
        parentMetaId = "tt1",
        parentMetaType = "series",
        videoId = "tt1:1:1",
        title = "Show",
        seasonNumber = 1,
        episodeNumber = 1,
        lastPositionMs = 0L,
        durationMs = 0L,
        lastUpdatedEpochMs = 0L,
        progressPercent = progressPercent,
    )

    private fun mdbListEvent(progress: Double) = TrackingScrobbleEvent(
        TrackingMediaReference(TrackingMediaKind.MOVIE, "Movie", ids = TrackingExternalIds(imdb = "tt1")),
        progress,
    )

    private fun event(percent: Double) = TrackingScrobbleEvent(
        media = TrackingMediaReference(kind = TrackingMediaKind.MOVIE, ids = TrackingExternalIds(imdb = "tt0111161")),
        progressPercent = percent,
    )

    private class RecordingScrobbler : TrackingScrobbler {
        override val providerId: TrackingProviderId = TrackingProviderId.TRAKT
        val actions = mutableListOf<TrackingScrobbleAction>()

        override suspend fun scrobble(profileId: Int, action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
            actions += action
        }
    }
}
