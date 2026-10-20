package com.nuvio.app.features.watchhistory

import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.normalizeWatchedMarkedAtEpochMs
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Groups ids across trackers: `movie:<id>` or `series:<id>`, case- and alias-insensitive. */
internal fun watchHistoryTitleKey(item: WatchedItem): String {
    val type = when (val raw = item.type.trim().lowercase()) {
        "movie", "film" -> "movie"
        "series", "show", "tv", "tvshow", "anime" -> "series"
        else -> raw
    }
    return "$type:${item.id.trim().lowercase()}"
}

/** One watch = one title + season/episode. A series-level item (no episode) is its own watch. */
internal fun watchHistoryWatchKey(item: WatchedItem): String =
    "${watchHistoryTitleKey(item)}:${item.season ?: -1}:${item.episode ?: -1}"

private class Candidate(
    val source: WatchHistorySourceId,
    val item: WatchedItem,
    val watchKey: String,
    val titleKey: String,
) {
    val at: Long get() = item.markedAtEpochMs
    val isExact: Boolean get() = source.dateConfidence == WatchHistoryDateConfidence.EXACT
}

private val candidatePreference = compareBy<Candidate>({ !it.isExact }, { -it.at }, { it.source.ordinal })

/** Latest exact-dated candidate; approximate dates only win when nothing exact exists. */
private fun List<Candidate>.best(): Candidate = minWith(candidatePreference)

internal fun mergeWatchHistory(
    sources: List<WatchHistorySource>,
    policy: WatchHistoryMergePolicy,
): List<WatchHistoryEntry> {
    val candidates = sources.flatMap { source ->
        source.items
            .map { it.copy(markedAtEpochMs = normalizeWatchedMarkedAtEpochMs(it.markedAtEpochMs)) }
            .filter { it.markedAtEpochMs > 0L }
            .map { Candidate(source.id, it, watchHistoryWatchKey(it), watchHistoryTitleKey(it)) }
            .groupBy { it.watchKey }
            .values
            .map { duplicates -> duplicates.maxBy { it.at } }
    }

    val byWatch = candidates.groupBy { it.watchKey }
    val primaryByTitle: Map<String, WatchHistorySourceId> = when (policy) {
        WatchHistoryMergePolicy.MOST_RECENT -> emptyMap()
        WatchHistoryMergePolicy.MOST_COMPLETE -> candidates
            .groupBy { it.titleKey }
            .mapValues { (_, titleCandidates) ->
                titleCandidates
                    .groupBy { it.source }
                    .minWith(
                        compareBy<Map.Entry<WatchHistorySourceId, List<Candidate>>>(
                            { -it.value.size },
                            { -it.value.maxOf { candidate -> candidate.at } },
                            { it.key.ordinal },
                        ),
                    )
                    .key
            }
    }

    return byWatch.values
        .map { watchCandidates ->
            val primary = primaryByTitle[watchCandidates.first().titleKey]
            val fromPrimary = watchCandidates.firstOrNull { it.source == primary }
            val winner = if (fromPrimary != null && (fromPrimary.isExact || watchCandidates.none { it.isExact })) {
                fromPrimary
            } else {
                watchCandidates.best()
            }
            // Some trackers (e.g. Trakt) carry no artwork; borrow it from another source.
            val item = if (winner.item.poster.isNullOrBlank()) {
                watchCandidates.firstNotNullOfOrNull { it.item.poster?.takeIf(String::isNotBlank) }
                    ?.let { winner.item.copy(poster = it) } ?: winner.item
            } else {
                winner.item
            }
            WatchHistoryEntry(
                item = item,
                primarySource = winner.source,
                sources = watchCandidates.mapTo(linkedSetOf()) { it.source },
                watchedAtEpochMs = winner.at,
                dateConfidence = winner.source.dateConfidence,
            )
        }
        .sortedWith(compareBy({ -it.watchedAtEpochMs }, { watchHistoryWatchKey(it.item) }))
}

/** Splits an already-sorted list into local-day sections, preserving order. */
internal fun groupWatchHistoryByDay(
    entries: List<WatchHistoryEntry>,
    timeZone: TimeZone,
): List<WatchHistoryDaySection> {
    val sections = mutableListOf<WatchHistoryDaySection>()
    var currentDay: Long? = null
    var current = mutableListOf<WatchHistoryEntry>()
    entries.forEach { entry ->
        val day = Instant.fromEpochMilliseconds(entry.watchedAtEpochMs)
            .toLocalDateTime(timeZone)
            .date
            .toEpochDays()
        if (day != currentDay) {
            currentDay?.let { sections += WatchHistoryDaySection(it, current) }
            currentDay = day
            current = mutableListOf()
        }
        current += entry
    }
    currentDay?.let { sections += WatchHistoryDaySection(it, current) }
    return sections
}
