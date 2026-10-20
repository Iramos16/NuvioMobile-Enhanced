package com.nuvio.app.features.library

import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryFiltersTest {

    @Test
    fun `category uses provider flag, anime id prefixes and Anime genre`() {
        assertEquals(LibraryCategory.ANIME, libraryItemCategory(item("tt1", type = "series", mediaCategory = "anime")))
        listOf("kitsu:1", "mal:2", "anilist:3", "anidb:4").forEach { id ->
            assertEquals(LibraryCategory.ANIME, libraryItemCategory(item(id, type = "series")), id)
        }
        assertEquals(LibraryCategory.ANIME, libraryItemCategory(item("tt2", type = "movie", genres = listOf("anime"))))
        assertEquals(LibraryCategory.MOVIES, libraryItemCategory(item("tt3", type = "movie", genres = listOf("Animation"))))
        assertEquals(LibraryCategory.MOVIES, libraryItemCategory(item("tt4", type = "movie")))
        assertEquals(LibraryCategory.MOVIES, libraryItemCategory(item("tt5", type = "film")))
        listOf("series", "tv", "show").forEach { type ->
            assertEquals(LibraryCategory.SERIES, libraryItemCategory(item("tt6", type = type)), type)
        }
    }

    @Test
    fun `section keys map to shared statuses`() {
        assertEquals(TrackingListStatus.ON_HOLD, libraryStatusForSectionKey("simkl:status:hold"))
        assertEquals(TrackingListStatus.ON_HOLD, libraryStatusForSectionKey("anilist:status:paused"))
        assertEquals(TrackingListStatus.WATCHING, libraryStatusForSectionKey("anilist:status:repeating"))
        assertEquals(TrackingListStatus.WATCHING, libraryStatusForSectionKey("wetrakr:status:waiting"))
        assertEquals(TrackingListStatus.COMPLETED, libraryStatusForSectionKey("wetrakr:status:watched"))
        assertNull(libraryStatusForSectionKey("trakt:watchlist"))
        assertNull(libraryStatusForSectionKey("local"))
    }

    @Test
    fun `status is inferred from watch history`() {
        val watch = LibraryWatchSnapshot(
            watchedKeys = watchedItemKeys("movie", "tt1"),
            fullyWatchedSeriesKeys = setOf(watchedItemKey("series", "tt2")),
            startedContentIds = setOf("tt3"),
        )
        assertEquals(TrackingListStatus.COMPLETED, inferLibraryItemStatus(item("tt1", type = "movie"), watch))
        assertEquals(TrackingListStatus.COMPLETED, inferLibraryItemStatus(item("tt2", type = "tv"), watch))
        assertEquals(TrackingListStatus.WATCHING, inferLibraryItemStatus(item("tt3", type = "series"), watch))
        assertEquals(TrackingListStatus.PLAN_TO_WATCH, inferLibraryItemStatus(item("tt4", type = "series"), watch))
    }

    @Test
    fun `section status wins over inference`() {
        val watch = LibraryWatchSnapshot(fullyWatchedSeriesKeys = setOf(watchedItemKey("series", "tt1")))
        val entry = item("tt1", type = "series")
        val section = LibrarySection("anilist:status:dropped", "Dropped", listOf(entry))
        assertEquals(TrackingListStatus.DROPPED, libraryItemStatus(entry, section, watch))
    }

    @Test
    fun `filters combine and empty status set means all`() {
        val animeWatching = item("kitsu:1", type = "series")
        val animePlanned = item("kitsu:2", type = "series")
        val movieWatching = item("tt3", type = "movie")
        val watching = LibrarySection("simkl:status:watching", "Watching", listOf(animeWatching, movieWatching))
        val planned = LibrarySection("simkl:status:plantowatch", "Plan", listOf(animePlanned))
        val sections = listOf(watching, planned)

        fun kept(filters: LibraryFilterSelection) =
            filterLibrarySections(sections, filters).flatMap { it.items }.map { it.id }

        assertEquals(
            listOf("kitsu:1", "kitsu:2"),
            kept(LibraryFilterSelection(LibraryCategory.ANIME, setOf(TrackingListStatus.WATCHING, TrackingListStatus.PLAN_TO_WATCH))),
        )
        assertEquals(listOf("kitsu:1", "tt3", "kitsu:2"), kept(LibraryFilterSelection(LibraryCategory.ALL, emptySet())))
        assertEquals(emptyList(), kept(LibraryFilterSelection(LibraryCategory.MOVIES, setOf(TrackingListStatus.DROPPED))))
    }

    @Test
    fun `filterLibrarySections drops rows left empty`() {
        val sections = listOf(
            LibrarySection("local", "Movies", listOf(item("tt1", type = "movie"))),
            LibrarySection("local:series", "Series", listOf(item("tt2", type = "series"))),
        )
        val filtered = filterLibrarySections(sections, LibraryFilterSelection(category = LibraryCategory.MOVIES))
        assertEquals(listOf("local"), filtered.map { it.type })
    }

    @Test
    fun `watch snapshot collects started ids from episodes and progress`() {
        val snapshot = buildLibraryWatchSnapshot(
            watchedItems = listOf(
                WatchedItem(id = "tt4", type = "movie", name = "Movie", markedAtEpochMs = 1L),
                WatchedItem(id = "tt5", type = "series", name = "Show", season = 1, episode = 2, markedAtEpochMs = 1L),
            ),
            watchedKeys = emptySet(),
            fullyWatchedSeriesKeys = emptySet(),
            progressEntries = listOf(
                WatchProgressEntry(
                    contentType = "series",
                    parentMetaId = "tt6",
                    parentMetaType = "series",
                    videoId = "tt6:1:1",
                    title = "Other",
                    lastPositionMs = 10L,
                    durationMs = 100L,
                    lastUpdatedEpochMs = 1L,
                ),
            ),
        )
        assertEquals(setOf("tt5", "tt6"), snapshot.startedContentIds)
    }

    private fun item(
        id: String,
        type: String = "movie",
        genres: List<String> = emptyList(),
        mediaCategory: String? = null,
    ): LibraryItem =
        LibraryItem(
            id = id,
            type = type,
            name = id,
            genres = genres,
            mediaCategory = mediaCategory,
            savedAtEpochMs = 0L,
        )
}
