package com.nuvio.app.features.catalog

import com.nuvio.app.features.library.LibraryCategory
import com.nuvio.app.features.library.LibraryFilterSelection
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryWatchSnapshot
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.LibrarySortOption
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.LibraryUiState
import com.nuvio.app.features.library.LibraryProviderOrders
import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.watched.watchedItemKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryCatalogStateTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `view all uses provider added orders and follows order and membership updates`() = runTest {
        val section = "mdblist:list:7"
        val alpha = item("alpha").copy(savedAtEpochMs = 0)
        val zulu = item("zulu").copy(savedAtEpochMs = 0)
        val library = MutableStateFlow(state(listOf(alpha, zulu), section).copy(sourceMode = LibrarySourceMode.MDBLIST))
        val orders = MutableStateFlow(LibraryProviderOrders(mapOf(section to mapOf("movie:zulu" to 0, "movie:alpha" to 1))))
        val seen = mutableListOf<CatalogUiState>()
        backgroundScope.launch {
            library.libraryCatalogStates(target(section).copy(sortOption = LibrarySortOption.ADDED_DESC), orders)
                .collect { seen += it }
        }
        runCurrent()
        assertEquals(listOf("zulu", "alpha"), seen.last().items.map { it.id })
        orders.value = LibraryProviderOrders(mapOf(section to mapOf("movie:alpha" to 0, "movie:zulu" to 1)))
        runCurrent()
        assertEquals(listOf("alpha", "zulu"), seen.last().items.map { it.id })
        library.value = state(listOf(zulu), section).copy(sourceMode = LibrarySourceMode.MDBLIST)
        runCurrent()
        assertEquals(listOf("zulu"), seen.last().items.map { it.id })
    }

    @Test
    fun `open catalog updates after removal including the last item`() = runBlocking {
        val first = item("first")
        val second = item("second")
        val library = MutableStateFlow(state(listOf(first, second)))
        val emissions = mutableListOf<CatalogUiState>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            library.libraryCatalogStates(target()).collect { emissions.add(it) }
        }
        try {
            assertEquals(listOf("first", "second"), emissions.last().items.map { it.id })

            library.value = state(listOf(second))
            yield()
            assertEquals(listOf("second"), emissions.last().items.map { it.id })

            library.value = LibraryUiState(isLoaded = true)
            yield()
            assertTrue(emissions.last().items.isEmpty())
            assertFalse(emissions.last().isLoading)
            assertFalse(emissions.last().canLoadMore)
            assertEquals(3, emissions.size)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `live tracking list updates keep section identity and selected sort`() = runBlocking {
        val alpha = item("alpha")
        val zulu = item("zulu")
        val sectionKey = "trakt:watchlist"
        val otherSection = LibrarySection("trakt:collection", "Collection", listOf(zulu))
        val library = MutableStateFlow(
            state(listOf(alpha, zulu, alpha), sectionKey).copy(
                sourceMode = LibrarySourceMode.TRAKT,
            ).let { it.copy(sections = it.sections + otherSection) },
        )
        val emissions = mutableListOf<CatalogUiState>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            library.libraryCatalogStates(
                target(sectionKey).copy(sortOption = LibrarySortOption.TITLE_DESC),
            ).collect { emissions.add(it) }
        }
        try {
            assertEquals(listOf("zulu", "alpha"), emissions.last().items.map { it.id })

            library.value = library.value.copy(
                sections = listOf(
                    LibrarySection(sectionKey, "Watchlist", listOf(alpha, item("bravo"))),
                    otherSection,
                ),
            )
            yield()
            assertEquals(listOf("bravo", "alpha"), emissions.last().items.map { it.id })
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `mdblist catalog follows the selected list ranks during live updates`() = runBlocking {
        val sectionKey = "mdblist:list:1"
        val otherSectionKey = "mdblist:list:2"
        val alpha = item("alpha").copy(listRanks = mapOf(sectionKey to 2, otherSectionKey to 1))
        val zulu = item("zulu").copy(listRanks = mapOf(sectionKey to 1, otherSectionKey to 2))
        val library = MutableStateFlow(
            state(listOf(alpha, zulu), sectionKey).copy(sourceMode = LibrarySourceMode.MDBLIST),
        )
        val emissions = mutableListOf<CatalogUiState>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            library.libraryCatalogStates(
                target(sectionKey).copy(sortOption = LibrarySortOption.DEFAULT),
            ).collect { emissions.add(it) }
        }
        try {
            assertEquals(listOf("zulu", "alpha"), emissions.last().items.map { it.id })

            library.value = state(
                listOf(
                    alpha.copy(listRanks = alpha.listRanks + (sectionKey to 1)),
                    zulu.copy(listRanks = zulu.listRanks + (sectionKey to 2)),
                ),
                sectionKey,
            ).copy(sourceMode = LibrarySourceMode.MDBLIST)
            yield()

            assertEquals(listOf("alpha", "zulu"), emissions.last().items.map { it.id })
            assertEquals(2, emissions.size)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `catalog follows library loading and recovery without reopening`() = runBlocking {
        val library = MutableStateFlow(LibraryUiState(isLoading = true))
        val emissions = mutableListOf<CatalogUiState>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            library.libraryCatalogStates(target()).collect { emissions.add(it) }
        }
        try {
            assertTrue(emissions.last().isLoading)

            library.value = LibraryUiState(errorMessage = "Unable to load library")
            yield()
            assertFalse(emissions.last().isLoading)
            assertEquals("Unable to load library", emissions.last().errorMessage)

            library.value = state(listOf(item("recovered")))
            yield()
            assertEquals(listOf("recovered"), emissions.last().items.map { it.id })
            assertNull(emissions.last().errorMessage)
        } finally {
            job.cancelAndJoin()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `view all applies the active category filter and follows filter changes`() = runTest {
        val sectionKey = "trakt:watchlist"
        val frieren = item("frieren").copy(type = "series", genres = listOf("Anime"))
        val bebop = item("kitsu:1").copy(type = "series")
        val dune = item("dune")
        val library = MutableStateFlow(
            state(listOf(frieren, dune, bebop), sectionKey).copy(sourceMode = LibrarySourceMode.TRAKT),
        )
        val filters = MutableStateFlow(LibraryFilterSelection(category = LibraryCategory.ANIME))
        val seen = mutableListOf<CatalogUiState>()
        backgroundScope.launch {
            library.libraryCatalogStates(target(sectionKey), filters = filters).collect { seen += it }
        }
        runCurrent()
        assertEquals(listOf("frieren", "kitsu:1"), seen.last().items.map { it.id })

        filters.value = LibraryFilterSelection(category = LibraryCategory.MOVIES)
        runCurrent()
        assertEquals(listOf("dune"), seen.last().items.map { it.id })

        filters.value = LibraryFilterSelection()
        runCurrent()
        assertEquals(listOf("dune", "frieren", "kitsu:1"), seen.last().items.map { it.id })
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `view all applies the inferred watch status filter`() = runTest {
        val sectionKey = "trakt:watchlist"
        val watched = item("watched")
        val started = item("started")
        val planned = item("planned")
        val library = MutableStateFlow(
            state(listOf(planned, started, watched), sectionKey).copy(sourceMode = LibrarySourceMode.TRAKT),
        )
        val filters = MutableStateFlow(
            LibraryFilterSelection(
                statuses = setOf(TrackingListStatus.PLAN_TO_WATCH, TrackingListStatus.WATCHING),
                watch = LibraryWatchSnapshot(
                    watchedKeys = setOf(watchedItemKey("movie", "watched")),
                    startedContentIds = setOf("started"),
                ),
            ),
        )
        val seen = mutableListOf<CatalogUiState>()
        backgroundScope.launch {
            library.libraryCatalogStates(target(sectionKey), filters = filters).collect { seen += it }
        }
        runCurrent()
        assertEquals(listOf("planned", "started"), seen.last().items.map { it.id })

        filters.value = filters.value.copy(statuses = setOf(TrackingListStatus.COMPLETED))
        runCurrent()
        assertEquals(listOf("watched"), seen.last().items.map { it.id })
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `view all is empty when the filters exclude the whole section`() = runTest {
        val library = MutableStateFlow(state(listOf(item("dune"))))
        val filters = MutableStateFlow(LibraryFilterSelection(category = LibraryCategory.ANIME))
        val seen = mutableListOf<CatalogUiState>()
        backgroundScope.launch {
            library.libraryCatalogStates(target(), filters = filters).collect { seen += it }
        }
        runCurrent()
        assertTrue(seen.last().items.isEmpty())
        assertFalse(seen.last().isLoading)
    }

    private fun target(sectionType: String = "movie") = CatalogTarget.Library(
        contentType = "movie",
        sectionType = sectionType,
        sortOption = LibrarySortOption.TITLE_ASC,
    )

    private fun item(id: String) = LibraryItem(
        id = id,
        type = "movie",
        name = id,
        savedAtEpochMs = 1L,
    )

    private fun state(items: List<LibraryItem>, sectionType: String = "movie") = LibraryUiState(
        items = items,
        sections = listOf(LibrarySection(sectionType, "Library", items)),
        isLoaded = true,
    )
}
