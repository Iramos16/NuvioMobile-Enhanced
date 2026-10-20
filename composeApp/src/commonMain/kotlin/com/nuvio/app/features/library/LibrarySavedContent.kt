package com.nuvio.app.features.library

import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.poster.withCustomPosterUrl
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.PosterGridRow
import com.nuvio.app.features.home.components.PosterGridSkeletonRow
import com.nuvio.app.features.tracking.TrackingListStatus
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_category_all
import nuvio.composeapp.generated.resources.library_category_anime
import nuvio.composeapp.generated.resources.library_category_movies
import nuvio.composeapp.generated.resources.library_category_series
import nuvio.composeapp.generated.resources.library_filter_category
import nuvio.composeapp.generated.resources.library_filter_list
import nuvio.composeapp.generated.resources.library_filter_sort
import nuvio.composeapp.generated.resources.library_sort_added_asc
import nuvio.composeapp.generated.resources.library_sort_added_desc
import nuvio.composeapp.generated.resources.library_sort_title_asc
import nuvio.composeapp.generated.resources.library_sort_title_desc
import nuvio.composeapp.generated.resources.library_sort_provider_order
import nuvio.composeapp.generated.resources.library_sort_released_asc
import nuvio.composeapp.generated.resources.library_sort_released_desc
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibrarySavedControls(
    layoutMode: LibraryLayoutMode,
    sourceMode: LibrarySourceMode,
    sortOption: LibrarySortOption,
    filters: LibraryFilterSelection,
    verticalProjection: LibraryVerticalProjection,
    onSectionSelected: (String) -> Unit,
    onCategorySelected: (LibraryCategory) -> Unit,
    onStatusesSelected: (Set<TrackingListStatus>) -> Unit,
    onSortSelected: (LibrarySortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sortOptions = availableLibrarySortOptions(sourceMode)

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (layoutMode == LibraryLayoutMode.VERTICAL && sourceMode.isRemoteTrackingSource && !sourceMode.hasNativeWatchStatus) {
            val selectedSection = verticalProjection.availableSections
                .firstOrNull { section -> section.type == verticalProjection.selectedSectionKey }
            NuvioDropdownChip(
                title = stringResource(Res.string.library_filter_list),
                label = selectedSection?.let { section -> localizedLibrarySectionTitle(section.type, section.displayTitle) }.orEmpty(),
                selectedKey = verticalProjection.selectedSectionKey,
                options = verticalProjection.availableSections.map { section ->
                    NuvioDropdownOption(key = section.type, label = localizedLibrarySectionTitle(section.type, section.displayTitle))
                },
                enabled = verticalProjection.availableSections.size > 1,
                onSelected = { option -> onSectionSelected(option.key) },
            )
        }

        NuvioDropdownChip(
            title = stringResource(Res.string.library_filter_category),
            label = libraryCategoryLabel(filters.category),
            selectedKey = filters.category.name,
            options = LibraryCategory.entries.map { category ->
                NuvioDropdownOption(key = category.name, label = libraryCategoryLabel(category))
            },
            onSelected = { option ->
                LibraryCategory.entries
                    .firstOrNull { it.name == option.key }
                    ?.let(onCategorySelected)
            },
        )

        LibraryStatusFilterChip(
            selected = filters.statuses,
            onSelectedChange = onStatusesSelected,
        )

        NuvioDropdownChip(
            title = stringResource(Res.string.library_filter_sort),
            label = librarySortOptionLabel(sortOption),
            selectedKey = sortOption.name,
            options = sortOptions.map { option ->
                NuvioDropdownOption(key = option.name, label = librarySortOptionLabel(option))
            },
            enabled = sortOptions.size > 1,
            onSelected = { option ->
                LibrarySortOption.entries
                    .firstOrNull { it.name == option.key }
                    ?.let(onSortSelected)
            },
        )
    }
}

internal fun LazyListScope.libraryVerticalContent(
    projection: LibraryVerticalProjection,
    columns: Int,
    releaseInfoFor: (LibraryItem) -> String,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    posterPattern: String,
    onPosterClick: ((LibraryItem) -> Unit)?,
    onPosterLongClick: ((LibraryItem, LibrarySection) -> Unit)?,
) {
    items(
        items = projection.entries.chunked(columns),
        key = { rowEntries ->
            val firstEntry = rowEntries.first()
            "library-vertical:${firstEntry.item.type}:${firstEntry.item.id}"
        },
    ) { rowEntries ->
        val resolvedItems = remember(rowEntries, posterPattern, releaseInfoFor) {
            rowEntries.map { entry ->
                entry.item.toMetaPreview()
                    .copy(releaseInfo = releaseInfoFor(entry.item))
                    .let { if (posterPattern.isNotBlank()) it.withCustomPosterUrl(posterPattern) else it }
            }
        }
        PosterGridRow(
            items = resolvedItems,
            columns = columns,
            modifier = libraryContentTransitionModifier()
                .padding(horizontal = 16.dp),
            watchedKeys = watchedKeys,
            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            onPosterClick = onPosterClick?.let { callback ->
                { preview -> rowEntries.findEntry(preview)?.item?.let(callback) }
            },
            onPosterLongClick = onPosterLongClick?.let { callback ->
                { preview ->
                    rowEntries.findEntry(preview)?.let { entry -> callback(entry.item, entry.section) }
                }
            },
        )
    }
}

internal fun LazyListScope.libraryVerticalSkeletonItems(columns: Int) {
    items(
        count = 2,
        key = { index -> "library-vertical-skeleton:$index" },
    ) {
        PosterGridSkeletonRow(
            columns = columns,
            modifier = libraryContentTransitionModifier()
                .padding(horizontal = 16.dp),
        )
    }
}

internal fun LazyItemScope.libraryContentTransitionModifier(): Modifier =
    Modifier.animateItem(
        fadeInSpec = tween(durationMillis = 160),
        placementSpec = tween(durationMillis = 180),
        fadeOutSpec = tween(durationMillis = 90),
    )

@Composable
private fun libraryCategoryLabel(category: LibraryCategory): String =
    when (category) {
        LibraryCategory.ALL -> stringResource(Res.string.library_category_all)
        LibraryCategory.ANIME -> stringResource(Res.string.library_category_anime)
        LibraryCategory.MOVIES -> stringResource(Res.string.library_category_movies)
        LibraryCategory.SERIES -> stringResource(Res.string.library_category_series)
    }

@Composable
private fun librarySortOptionLabel(option: LibrarySortOption): String =
    when (option) {
        LibrarySortOption.DEFAULT -> stringResource(Res.string.library_sort_provider_order)
        LibrarySortOption.ADDED_DESC -> stringResource(Res.string.library_sort_added_desc)
        LibrarySortOption.ADDED_ASC -> stringResource(Res.string.library_sort_added_asc)
        LibrarySortOption.RELEASED_DESC -> stringResource(Res.string.library_sort_released_desc)
        LibrarySortOption.RELEASED_ASC -> stringResource(Res.string.library_sort_released_asc)
        LibrarySortOption.TITLE_ASC -> stringResource(Res.string.library_sort_title_asc)
        LibrarySortOption.TITLE_DESC -> stringResource(Res.string.library_sort_title_desc)
    }

private fun List<LibraryVerticalEntry>.findEntry(preview: MetaPreview): LibraryVerticalEntry? =
    firstOrNull { entry -> entry.item.id == preview.id && entry.item.type == preview.type }
