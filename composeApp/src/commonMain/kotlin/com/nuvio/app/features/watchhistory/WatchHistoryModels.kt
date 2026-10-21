package com.nuvio.app.features.watchhistory

import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.serialization.Serializable

/** How trustworthy a source's watch timestamps are. */
enum class WatchHistoryDateConfidence { EXACT, APPROXIMATE }

/** Where a watch-history entry came from. Declaration order breaks ties in the merger. */
enum class WatchHistorySourceId(val dateConfidence: WatchHistoryDateConfidence) {
    NUVIO(WatchHistoryDateConfidence.EXACT),
    TRAKT(WatchHistoryDateConfidence.EXACT),
    SIMKL(WatchHistoryDateConfidence.EXACT),

    /** AniList only stores per-entry progress, so every episode carries the entry's update time. */
    ANILIST(WatchHistoryDateConfidence.APPROXIMATE),
    WETRAKR(WatchHistoryDateConfidence.EXACT),
    ;

    companion object {
        fun fromTrackingProvider(id: TrackingProviderId): WatchHistorySourceId? = when (id) {
            TrackingProviderId.TRAKT -> TRAKT
            TrackingProviderId.SIMKL -> SIMKL
            TrackingProviderId.ANILIST -> ANILIST
            TrackingProviderId.WETRAKR -> WETRAKR
            TrackingProviderId.MDBLIST -> null
        }
    }
}

data class WatchHistorySource(
    val id: WatchHistorySourceId,
    val items: List<WatchedItem>,
)

data class WatchHistoryEntry(
    val item: WatchedItem,
    val primarySource: WatchHistorySourceId,
    val sources: Set<WatchHistorySourceId>,
    val watchedAtEpochMs: Long,
    val dateConfidence: WatchHistoryDateConfidence,
)

data class WatchHistoryDaySection(
    val epochDay: Long,
    val entries: List<WatchHistoryEntry>,
)

/** Decides which tracker wins when several report the same watch. */
@Serializable
enum class WatchHistoryMergePolicy {
    /** For each title/episode, the tracker with the latest watch time wins. */
    MOST_RECENT,

    /** For each title, the tracker with the most watched entries wins; others fill its gaps. */
    MOST_COMPLETE,
    ;

    companion object {
        fun fromStorage(value: String?): WatchHistoryMergePolicy =
            entries.firstOrNull { it.name == value } ?: DEFAULT_WATCH_HISTORY_MERGE_POLICY
    }
}

val DEFAULT_WATCH_HISTORY_MERGE_POLICY = WatchHistoryMergePolicy.MOST_RECENT
