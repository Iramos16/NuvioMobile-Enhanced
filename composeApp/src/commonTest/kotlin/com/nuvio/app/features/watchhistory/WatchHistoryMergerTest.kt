package com.nuvio.app.features.watchhistory

import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.normalizeWatchedMarkedAtEpochMs
import com.nuvio.app.features.watchhistory.WatchHistoryMergePolicy.MOST_COMPLETE
import com.nuvio.app.features.watchhistory.WatchHistoryMergePolicy.MOST_RECENT
import com.nuvio.app.features.watchhistory.WatchHistorySourceId.ANILIST
import com.nuvio.app.features.watchhistory.WatchHistorySourceId.SIMKL
import com.nuvio.app.features.watchhistory.WatchHistorySourceId.TRAKT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchHistoryMergerTest {

    private fun item(
        id: String,
        type: String = "series",
        s: Int? = 1,
        e: Int? = 1,
        at: Long,
    ) = WatchedItem(id = id, type = type, name = id, season = s, episode = e, markedAtEpochMs = at)

    private fun source(id: WatchHistorySourceId, vararg items: WatchedItem) =
        WatchHistorySource(id, items.toList())

    @Test
    fun noSources_returnsEmpty() {
        assertTrue(mergeWatchHistory(emptyList(), MOST_RECENT).isEmpty())
    }

    @Test
    fun singleSource_sortedNewestFirst() {
        val merged = mergeWatchHistory(
            listOf(source(TRAKT, item("tt1", e = 1, at = 100), item("tt1", e = 2, at = 300), item("tt1", e = 3, at = 200))),
            MOST_RECENT,
        )
        assertEquals(listOf(300L, 200L, 100L), merged.map { it.watchedAtEpochMs })
    }

    @Test
    fun typeAliasesAndIdCase_dedupToOneEntry() {
        val merged = mergeWatchHistory(
            listOf(
                source(TRAKT, item("tt1", type = "series", at = 100)),
                source(SIMKL, item(" TT1 ", type = "show", at = 50)),
            ),
            MOST_RECENT,
        )
        assertEquals(1, merged.size)
        assertEquals(setOf(TRAKT, SIMKL), merged.single().sources)
    }

    @Test
    fun movieTypeAliases_dedupToOneEntry() {
        val merged = mergeWatchHistory(
            listOf(
                source(TRAKT, item("tt9", type = "movie", s = null, e = null, at = 100)),
                source(SIMKL, item("tt9", type = "film", s = null, e = null, at = 100)),
            ),
            MOST_RECENT,
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun mostRecent_picksLatestExact() {
        val merged = mergeWatchHistory(
            listOf(source(TRAKT, item("tt1", at = 100)), source(SIMKL, item("tt1", at = 200))),
            MOST_RECENT,
        )
        assertEquals(SIMKL, merged.single().primarySource)
        assertEquals(200L, merged.single().watchedAtEpochMs)
    }

    @Test
    fun mostRecent_exactBeatsNewerApproximate() {
        val merged = mergeWatchHistory(
            listOf(source(TRAKT, item("tt1", at = 100)), source(ANILIST, item("tt1", at = 900))),
            MOST_RECENT,
        ).single()
        assertEquals(TRAKT, merged.primarySource)
        assertEquals(100L, merged.watchedAtEpochMs)
        assertEquals(WatchHistoryDateConfidence.EXACT, merged.dateConfidence)
        assertTrue(ANILIST in merged.sources)
    }

    @Test
    fun mostRecent_approximateOnlyEpisodeStillIncluded() {
        val merged = mergeWatchHistory(
            listOf(source(TRAKT, item("tt1", e = 1, at = 100)), source(ANILIST, item("tt1", e = 1, at = 900), item("tt1", e = 2, at = 900))),
            MOST_RECENT,
        )
        val e2 = merged.single { it.item.episode == 2 }
        assertEquals(ANILIST, e2.primarySource)
        assertEquals(WatchHistoryDateConfidence.APPROXIMATE, e2.dateConfidence)
    }

    @Test
    fun mostComplete_perTitlePrimaryWithGapFill() {
        val merged = mergeWatchHistory(
            listOf(
                source(
                    SIMKL,
                    item("a", e = 1, at = 10), item("a", e = 2, at = 20), item("a", e = 3, at = 30),
                    item("b", e = 1, at = 40),
                ),
                source(
                    TRAKT,
                    item("a", e = 1, at = 500), item("a", e = 4, at = 600),
                    item("b", e = 1, at = 41), item("b", e = 2, at = 42),
                ),
            ),
            MOST_COMPLETE,
        )
        fun find(id: String, e: Int) = merged.single { it.item.id == id && it.item.episode == e }
        assertEquals(SIMKL to 10L, find("a", 1).let { it.primarySource to it.watchedAtEpochMs })
        assertEquals(SIMKL, find("a", 2).primarySource)
        assertEquals(SIMKL, find("a", 3).primarySource)
        assertEquals(TRAKT to 600L, find("a", 4).let { it.primarySource to it.watchedAtEpochMs })
        assertEquals(TRAKT, find("b", 1).primarySource)
        assertEquals(setOf(SIMKL, TRAKT), find("a", 1).sources)
    }

    @Test
    fun mostComplete_tieBrokenByNewestEntry() {
        val merged = mergeWatchHistory(
            listOf(source(TRAKT, item("a", at = 100)), source(SIMKL, item("a", at = 200))),
            MOST_COMPLETE,
        )
        assertEquals(SIMKL, merged.single().primarySource)
    }

    @Test
    fun mostComplete_differsFromMostRecent() {
        val sources = listOf(
            source(SIMKL, item("a", e = 1, at = 10), item("a", e = 2, at = 20)),
            source(TRAKT, item("a", e = 1, at = 500)),
        )
        assertEquals(TRAKT, mergeWatchHistory(sources, MOST_RECENT).single { it.item.episode == 1 }.primarySource)
        assertEquals(SIMKL, mergeWatchHistory(sources, MOST_COMPLETE).single { it.item.episode == 1 }.primarySource)
    }

    @Test
    fun mostComplete_approximatePrimaryEntryYieldsToExact() {
        val merged = mergeWatchHistory(
            listOf(
                source(ANILIST, item("a", e = 1, at = 900), item("a", e = 2, at = 900)),
                source(TRAKT, item("a", e = 1, at = 100)),
            ),
            MOST_COMPLETE,
        )
        val e1 = merged.single { it.item.episode == 1 }
        assertEquals(TRAKT to 100L, e1.primarySource to e1.watchedAtEpochMs)
        assertEquals(ANILIST, merged.single { it.item.episode == 2 }.primarySource)
    }

    @Test
    fun seriesLevelItem_isSeparateWatch() {
        val merged = mergeWatchHistory(
            listOf(source(SIMKL, item("a", s = null, e = null, at = 100), item("a", at = 50))),
            MOST_RECENT,
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun withinSourceDuplicates_keepLatest() {
        val merged = mergeWatchHistory(
            listOf(source(TRAKT, item("a", at = 100), item("a", at = 300))),
            MOST_RECENT,
        )
        assertEquals(300L, merged.single().watchedAtEpochMs)
    }

    @Test
    fun nonPositiveTimestamps_dropped() {
        assertTrue(mergeWatchHistory(listOf(source(TRAKT, item("a", at = 0))), MOST_RECENT).isEmpty())
    }

    @Test
    fun compactTimestamps_normalized() {
        val merged = mergeWatchHistory(listOf(source(TRAKT, item("a", at = 20240102030405L))), MOST_RECENT).single()
        assertEquals(normalizeWatchedMarkedAtEpochMs(20240102030405L), merged.watchedAtEpochMs)
        assertEquals(merged.watchedAtEpochMs, merged.item.markedAtEpochMs)
    }

    @Test
    fun equalTimestamps_deterministicOrder() {
        val a = mergeWatchHistory(listOf(source(TRAKT, item("b", at = 100), item("a", at = 100))), MOST_RECENT)
        val b = mergeWatchHistory(listOf(source(TRAKT, item("a", at = 100), item("b", at = 100))), MOST_RECENT)
        assertEquals(listOf("a", "b"), a.map { it.item.id })
        assertEquals(a, b)
    }
}
