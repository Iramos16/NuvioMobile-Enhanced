package com.nuvio.app.features.watchhistory

import com.nuvio.app.features.watched.WatchedItem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class WatchHistoryDayGroupingTest {

    private val saoPaulo = TimeZone.of("America/Sao_Paulo")

    private fun entry(iso: String): WatchHistoryEntry {
        val at = Instant.parse(iso).toEpochMilliseconds()
        return WatchHistoryEntry(
            item = WatchedItem(id = iso, type = "movie", name = iso, markedAtEpochMs = at),
            primarySource = WatchHistorySourceId.TRAKT,
            sources = setOf(WatchHistorySourceId.TRAKT),
            watchedAtEpochMs = at,
            dateConfidence = WatchHistoryDateConfidence.EXACT,
        )
    }

    @Test
    fun entriesOnSameLocalDay_grouped() {
        val sections = groupWatchHistoryByDay(
            listOf(entry("2023-11-14T23:30:00-03:00"), entry("2023-11-14T01:00:00-03:00")),
            saoPaulo,
        )
        assertEquals(1, sections.size)
        assertEquals(2, sections.single().entries.size)
        assertEquals(LocalDate(2023, 11, 14).toEpochDays().toLong(), sections.single().epochDay)
    }

    @Test
    fun utcMidnightCrossing_splitsByLocalDay() {
        val sections = groupWatchHistoryByDay(
            listOf(entry("2023-11-15T04:00:00Z"), entry("2023-11-15T01:00:00Z")),
            saoPaulo,
        )
        assertEquals(
            listOf(LocalDate(2023, 11, 15), LocalDate(2023, 11, 14)).map { it.toEpochDays().toLong() },
            sections.map { it.epochDay },
        )
    }

    @Test
    fun empty_returnsEmpty() {
        assertTrue(groupWatchHistoryByDay(emptyList(), saoPaulo).isEmpty())
    }
}
