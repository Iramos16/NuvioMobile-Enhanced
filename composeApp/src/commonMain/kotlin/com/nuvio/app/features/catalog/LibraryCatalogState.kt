package com.nuvio.app.features.catalog

import com.nuvio.app.features.library.LibraryDisplaySettingsRepository
import com.nuvio.app.features.library.LibraryFilterSelection
import com.nuvio.app.features.library.LibraryUiState
import com.nuvio.app.features.library.LibraryWatchSnapshot
import com.nuvio.app.features.library.LibraryProviderOrders
import com.nuvio.app.features.library.LibrarySortOption
import com.nuvio.app.features.library.buildLibraryWatchSnapshot
import com.nuvio.app.features.library.filterLibrarySections
import com.nuvio.app.features.library.librarySorter
import com.nuvio.app.features.library.observeLibraryProviderOrders
import com.nuvio.app.features.library.sortLibraryItems
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_error_sort_failed
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal fun Flow<LibraryUiState>.libraryCatalogStates(
    target: CatalogTarget.Library,
    providerOrders: Flow<LibraryProviderOrders>? = null,
    filters: Flow<LibraryFilterSelection>? = null,
): Flow<CatalogUiState> {
    val inputs: Flow<Pair<LibraryProviderOrders, LibraryFilterSelection>>? = when {
        providerOrders != null && filters != null -> combine(providerOrders, filters, ::Pair)
        providerOrders != null -> providerOrders.map { it to LibraryFilterSelection() }
        filters != null -> filters.map { LibraryProviderOrders() to it }
        else -> null
    }
    return if (inputs == null) {
        map { it.libraryCatalogState(target, LibraryProviderOrders(), LibraryFilterSelection()) }
    } else {
        combine(inputs) { libraryState, (orders, selection) -> libraryState.libraryCatalogState(target, orders, selection) }
    }.distinctUntilChanged()
}

// Same Category/Status selection the Library rows apply, so View all lists what the row shows.
internal fun libraryCatalogFilters(): Flow<LibraryFilterSelection> {
    LibraryDisplaySettingsRepository.ensureLoaded()
    WatchedRepository.ensureLoaded()
    WatchProgressRepository.ensureLoaded()
    return combine(
        LibraryDisplaySettingsRepository.uiState,
        WatchedRepository.uiState,
        WatchedRepository.fullyWatchedSeriesKeys,
        WatchProgressRepository.uiState,
    ) { settings, watched, fullyWatchedSeriesKeys, progress ->
        LibraryFilterSelection(
            category = settings.category,
            statuses = settings.statusFilter,
            watch = if (settings.statusFilter.isEmpty()) {
                LibraryWatchSnapshot.Empty
            } else {
                buildLibraryWatchSnapshot(
                    watchedItems = watched.items,
                    watchedKeys = watched.watchedKeys,
                    fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                    progressEntries = progress.entries,
                )
            },
        )
    }.distinctUntilChanged()
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun Flow<LibraryUiState>.libraryCatalogOrders(target: CatalogTarget.Library): Flow<LibraryProviderOrders> =
    combine(ProfileRepository.state) { library, profile ->
        library.sourceMode to (profile.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId)
    }.distinctUntilChanged().flatMapLatest { (source, _) ->
        observeLibraryProviderOrders(source.librarySorter(), listOf(target.sectionType), target.sortOption)
    }

private suspend fun LibraryUiState.libraryCatalogState(
    target: CatalogTarget.Library,
    orders: LibraryProviderOrders,
    filters: LibraryFilterSelection,
): CatalogUiState {
    val section = sections.firstOrNull { it.type == target.sectionType }
    val items = section?.let { filterLibrarySections(listOf(it), filters).firstOrNull()?.items }.orEmpty()
    return CatalogUiState(
        items = sortLibraryItems(
            items = items,
            selected = if (orders.failed) LibrarySortOption.DEFAULT else target.sortOption,
            sourceMode = sourceMode,
            listKey = target.sectionType,
            providerOrder = orders.ranks[target.sectionType],
        ).map { it.toMetaPreview() }.let(::dedupeCatalogItems),
        isLoading = isLoading,
        errorMessage = if (orders.failed) getString(Res.string.library_error_sort_failed) else errorMessage,
    )
}
