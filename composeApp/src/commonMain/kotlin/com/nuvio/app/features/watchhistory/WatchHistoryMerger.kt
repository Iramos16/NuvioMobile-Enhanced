package com.nuvio.app.features.watchhistory

import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.normalizeWatchedMarkedAtEpochMs

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
            WatchHistoryEntry(
                item = winner.item,
                primarySource = winner.source,
                sources = watchCandidates.mapTo(linkedSetOf()) { it.source },
                watchedAtEpochMs = winner.at,
                dateConfidence = winner.source.dateConfidence,
            )
        }
        .sortedWith(compareBy({ -it.watchedAtEpochMs }, { watchHistoryWatchKey(it.item) }))
}
