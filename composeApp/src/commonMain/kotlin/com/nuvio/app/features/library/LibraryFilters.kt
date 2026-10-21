package com.nuvio.app.features.library

import com.nuvio.app.features.anilist.aniListStatusDefinitions
import com.nuvio.app.features.simkl.simklLibraryStatusDefinitions
import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.wetrakr.weTrakrStatusDefinitions

enum class LibraryCategory {
    ALL,
    ANIME,
    MOVIES,
    SERIES,
}

internal data class LibraryWatchSnapshot(
    val watchedKeys: Set<String> = emptySet(),
    val fullyWatchedSeriesKeys: Set<String> = emptySet(),
    val startedContentIds: Set<String> = emptySet(),
) {
    companion object {
        val Empty = LibraryWatchSnapshot()
    }
}

internal data class LibraryFilterSelection(
    val category: LibraryCategory = LibraryCategory.ALL,
    val statuses: Set<TrackingListStatus> = emptySet(),
    val watch: LibraryWatchSnapshot = LibraryWatchSnapshot.Empty,
) {
    val isActive: Boolean
        get() = category != LibraryCategory.ALL || statuses.isNotEmpty()
}

internal fun buildLibraryWatchSnapshot(
    watchedItems: List<WatchedItem>,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    progressEntries: List<WatchProgressEntry>,
): LibraryWatchSnapshot =
    LibraryWatchSnapshot(
        watchedKeys = watchedKeys,
        fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
        startedContentIds = buildSet {
            watchedItems.forEach { item -> if (item.season != null || item.episode != null) add(item.id) }
            progressEntries.forEach { entry -> add(entry.parentMetaId) }
        },
    )

private val AnimeIdPrefixes = listOf("kitsu:", "mal:", "anilist:", "anidb:")
private val MovieTypes = setOf("movie", "film")

internal fun libraryItemCategory(item: LibraryItem): LibraryCategory {
    val id = item.id.trim().lowercase()
    val isAnime = item.mediaCategory.equals("anime", ignoreCase = true) ||
        AnimeIdPrefixes.any(id::startsWith) ||
        item.genres.any { genre -> genre.trim().equals("anime", ignoreCase = true) }
    return when {
        isAnime -> LibraryCategory.ANIME
        item.isLibraryMovie() -> LibraryCategory.MOVIES
        else -> LibraryCategory.SERIES
    }
}

// Lists without a shared status (AniList Rewatching, WeTrakr Waiting) are still in progress.
private val LibrarySectionStatuses: Map<String, TrackingListStatus> = buildMap {
    simklLibraryStatusDefinitions.forEach { put(it.key, it.trackingStatus) }
    aniListStatusDefinitions.forEach { put(it.key, it.trackingStatus ?: TrackingListStatus.WATCHING) }
    weTrakrStatusDefinitions.forEach { put(it.key, it.trackingStatus ?: TrackingListStatus.WATCHING) }
}

internal fun libraryStatusForSectionKey(sectionKey: String): TrackingListStatus? =
    LibrarySectionStatuses[sectionKey]

internal fun inferLibraryItemStatus(item: LibraryItem, watch: LibraryWatchSnapshot): TrackingListStatus {
    // Mirrors WatchingState.isPosterWatched so the status agrees with the poster's watched badge.
    val itemKeys = watchedItemKeys(item.type, item.id)
    val isCompleted = itemKeys.any(watch.watchedKeys::contains) ||
        (!item.isLibraryMovie() && itemKeys.any(watch.fullyWatchedSeriesKeys::contains))
    return when {
        isCompleted -> TrackingListStatus.COMPLETED
        item.id in watch.startedContentIds -> TrackingListStatus.WATCHING
        else -> TrackingListStatus.PLAN_TO_WATCH
    }
}

internal fun libraryItemStatus(
    item: LibraryItem,
    section: LibrarySection,
    watch: LibraryWatchSnapshot,
): TrackingListStatus =
    libraryStatusForSectionKey(section.type) ?: inferLibraryItemStatus(item, watch)

internal fun LibraryFilterSelection.matches(item: LibraryItem, section: LibrarySection): Boolean =
    (category == LibraryCategory.ALL || libraryItemCategory(item) == category) &&
        (statuses.isEmpty() || libraryItemStatus(item, section, watch) in statuses)

internal fun filterLibrarySections(
    sections: List<LibrarySection>,
    filters: LibraryFilterSelection,
): List<LibrarySection> {
    if (!filters.isActive) return sections
    return sections.mapNotNull { section ->
        section.copy(items = section.items.filter { item -> filters.matches(item, section) })
            .takeIf { it.items.isNotEmpty() }
    }
}

internal val LibrarySourceMode.hasNativeWatchStatus: Boolean
    get() = this == LibrarySourceMode.SIMKL ||
        this == LibrarySourceMode.ANILIST ||
        this == LibrarySourceMode.WETRAKR

private fun LibraryItem.isLibraryMovie(): Boolean = type.trim().lowercase() in MovieTypes
