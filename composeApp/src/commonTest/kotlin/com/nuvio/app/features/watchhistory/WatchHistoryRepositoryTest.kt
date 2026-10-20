package com.nuvio.app.features.watchhistory

import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchhistory.WatchHistorySourceId.NUVIO
import com.nuvio.app.features.watchhistory.WatchHistorySourceId.SIMKL
import com.nuvio.app.features.watchhistory.WatchHistorySourceId.TRAKT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchHistoryRepositoryTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun item(id: String, at: Long, e: Int = 1) =
        WatchedItem(id = id, type = "series", name = id, season = 1, episode = e, markedAtEpochMs = at)

    private class FakeGateway(connected: Set<WatchHistorySourceId>) : WatchHistorySourceGateway {
        val connected = MutableStateFlow(connected)
        val results = mutableMapOf<WatchHistorySourceId, () -> List<WatchedItem>>()
        override fun connectedSources(): Flow<Set<WatchHistorySourceId>> = connected
        override suspend fun pull(id: WatchHistorySourceId, profileId: Int): List<WatchedItem> =
            results.getValue(id).invoke()
    }

    private class FakeStore : WatchHistoryPayloadStore {
        val payloads = mutableMapOf<Int, String>()
        override fun load(profileId: Int): String? = payloads[profileId]
        override fun save(profileId: Int, payload: String) {
            payloads[profileId] = payload
        }
    }

    private fun cached(vararg slices: Pair<WatchHistorySourceId, List<WatchedItem>>): String =
        encodeStoredWatchHistory(
            StoredWatchHistory(
                sources = slices.associate { (id, items) -> id.name to items },
                lastUpdatedEpochMs = slices.associate { (id, _) -> id.name to 1L },
            ),
        )

    private fun controller(
        gateway: FakeGateway,
        store: FakeStore,
        policy: MutableStateFlow<WatchHistoryMergePolicy> = MutableStateFlow(WatchHistoryMergePolicy.MOST_RECENT),
        profile: MutableStateFlow<Int> = MutableStateFlow(1),
    ) = WatchHistoryController(
        gateway = gateway,
        store = store,
        policy = policy,
        profileIds = profile,
        timeZone = { TimeZone.UTC },
        scope = scope,
        nowEpochMs = { 5_000L },
    ).also { it.start() }

    private fun WatchHistoryController.awaitState(predicate: (WatchHistoryUiState) -> Boolean): WatchHistoryUiState =
        runBlocking { withTimeout(5_000) { uiState.first(predicate) } }

    private fun WatchHistoryUiState.ids(): List<String> = sections.flatMap { it.entries }.map { it.item.id }

    @Test
    fun start_publishesCachedHistoryBeforeRefresh() {
        val store = FakeStore().apply { payloads[1] = cached(TRAKT to listOf(item("a", 100))) }
        val controller = controller(FakeGateway(setOf(NUVIO, TRAKT)), store)
        val state = controller.awaitState { it.isLoaded }
        assertEquals(listOf("a"), state.ids())
    }

    @Test
    fun refresh_mergesAllConnectedSources() {
        val gateway = FakeGateway(setOf(NUVIO, TRAKT, SIMKL)).apply {
            results[NUVIO] = { listOf(item("n", 300)) }
            results[TRAKT] = { listOf(item("t", 200)) }
            results[SIMKL] = { listOf(item("s", 100)) }
        }
        val store = FakeStore()
        val controller = controller(gateway, store)
        runBlocking { controller.refresh() }
        val state = controller.awaitState { it.ids().size == 3 }
        assertEquals(listOf("n", "t", "s"), state.ids())
        assertEquals(5_000L, state.lastUpdatedEpochMs)
        assertTrue(store.payloads[1].orEmpty().contains("\"t\""))
    }

    @Test
    fun refreshFailure_keepsCachedSliceAndReportsSource() {
        val gateway = FakeGateway(setOf(NUVIO, SIMKL)).apply {
            results[NUVIO] = { emptyList() }
            results[SIMKL] = { error("offline") }
        }
        val store = FakeStore().apply { payloads[1] = cached(SIMKL to listOf(item("s", 100))) }
        val controller = controller(gateway, store)
        runBlocking { controller.refresh() }
        val state = controller.awaitState { !it.isRefreshing && it.failedSources.isNotEmpty() }
        assertEquals(setOf(SIMKL), state.failedSources)
        assertEquals(listOf("s"), state.ids())
    }

    @Test
    fun disconnectedSource_excludedImmediately() {
        val gateway = FakeGateway(setOf(NUVIO, TRAKT))
        val store = FakeStore().apply {
            payloads[1] = cached(TRAKT to listOf(item("t", 100)), NUVIO to listOf(item("n", 50)))
        }
        val controller = controller(gateway, store)
        controller.awaitState { it.ids().size == 2 }
        gateway.connected.value = setOf(NUVIO)
        assertEquals(listOf("n"), controller.awaitState { it.ids() == listOf("n") }.ids())
    }

    @Test
    fun policyChange_remerges() {
        val store = FakeStore().apply {
            payloads[1] = cached(
                SIMKL to listOf(item("a", 10, e = 1), item("a", 20, e = 2)),
                TRAKT to listOf(item("a", 500, e = 1)),
            )
        }
        val policy = MutableStateFlow(WatchHistoryMergePolicy.MOST_RECENT)
        val controller = controller(FakeGateway(setOf(NUVIO, TRAKT, SIMKL)), store, policy)
        fun WatchHistoryUiState.e1Source() = sections.flatMap { it.entries }.firstOrNull { it.item.episode == 1 }?.primarySource
        controller.awaitState { it.e1Source() == TRAKT }
        policy.value = WatchHistoryMergePolicy.MOST_COMPLETE
        val state = controller.awaitState { it.e1Source() == SIMKL }
        assertEquals(WatchHistoryMergePolicy.MOST_COMPLETE, state.policy)
    }

    @Test
    fun profileChange_loadsOtherProfilesCache() {
        val store = FakeStore().apply {
            payloads[1] = cached(NUVIO to listOf(item("one", 100)))
            payloads[2] = cached(NUVIO to listOf(item("two", 100)))
        }
        val profile = MutableStateFlow(1)
        val controller = controller(FakeGateway(setOf(NUVIO)), store, profile = profile)
        controller.awaitState { it.ids() == listOf("one") }
        profile.value = 2
        assertEquals(listOf("two"), controller.awaitState { it.ids() == listOf("two") }.ids())
    }

    @Test
    fun noTrackersConnected_showsNuvioOnly() {
        val gateway = FakeGateway(setOf(NUVIO)).apply { results[NUVIO] = { listOf(item("n", 100)) } }
        val store = FakeStore().apply { payloads[1] = cached(TRAKT to listOf(item("t", 900))) }
        val controller = controller(gateway, store)
        runBlocking { controller.refresh() }
        val state = controller.awaitState { it.ids() == listOf("n") }
        assertTrue(state.failedSources.isEmpty())
    }

    @Test
    fun refreshWithTransientConnectedSet_keepsUnpulledSlices() {
        val gateway = FakeGateway(setOf(NUVIO)).apply { results[NUVIO] = { listOf(item("n", 100)) } }
        val store = FakeStore().apply { payloads[1] = cached(TRAKT to listOf(item("t", 900))) }
        val controller = controller(gateway, store)
        runBlocking { controller.refresh() }
        assertEquals(listOf("t"), decodeStoredWatchHistory(store.payloads[1]).sources["TRAKT"].orEmpty().map { it.id })
    }

    @Test
    fun profileSwitchDuringRefresh_doesNotSaveIntoOldProfile() {
        val profile = MutableStateFlow(1)
        val gateway = FakeGateway(setOf(NUVIO)).apply {
            results[NUVIO] = {
                profile.value = 2
                listOf(item("from-profile-two", 100))
            }
        }
        val store = FakeStore().apply { payloads[1] = cached(NUVIO to listOf(item("one", 100))) }
        val controller = controller(gateway, store, profile = profile)
        runBlocking { controller.refresh() }
        assertEquals(listOf("one"), decodeStoredWatchHistory(store.payloads[1]).sources["NUVIO"].orEmpty().map { it.id })
    }

    @Test
    fun refreshBeforeCacheLoaded_failingSourceKeepsStoredSlice() {
        val gateway = FakeGateway(setOf(NUVIO, SIMKL)).apply {
            results[NUVIO] = { emptyList() }
            results[SIMKL] = { error("offline") }
        }
        val store = FakeStore().apply { payloads[1] = cached(SIMKL to listOf(item("s", 100))) }
        val notStarted = WatchHistoryController(
            gateway = gateway,
            store = store,
            policy = MutableStateFlow(WatchHistoryMergePolicy.MOST_RECENT),
            profileIds = MutableStateFlow(1),
            timeZone = { TimeZone.UTC },
            scope = scope,
            nowEpochMs = { 5_000L },
        )
        runBlocking { notStarted.refresh() }
        assertTrue(decodeStoredWatchHistory(store.payloads[1]).sources["SIMKL"].orEmpty().isNotEmpty())
    }

    /** First load blocks until released, so the start-up cache read can land after a refresh. */
    private class GatedStore(private val initial: String) : WatchHistoryPayloadStore {
        @Volatile var loads = 0
        @Volatile var released = false
        @Volatile var firstLoadDone = false
        @Volatile var saved: String? = null
        override fun load(profileId: Int): String? {
            val call = ++loads
            if (call == 1) {
                while (!released) Unit
                return initial.also { firstLoadDone = true }
            }
            return saved ?: initial
        }
        override fun save(profileId: Int, payload: String) {
            saved = payload
        }
    }

    @Test
    fun lateStartupCacheRead_doesNotOverwriteFresherRefresh() {
        val gateway = FakeGateway(setOf(NUVIO)).apply { results[NUVIO] = { listOf(item("fresh", 100)) } }
        val store = GatedStore(initial = cached(NUVIO to listOf(item("stale", 50))))
        val controller = WatchHistoryController(
            gateway = gateway,
            store = store,
            policy = MutableStateFlow(WatchHistoryMergePolicy.MOST_RECENT),
            profileIds = MutableStateFlow(1),
            timeZone = { TimeZone.UTC },
            scope = scope,
            nowEpochMs = { 5_000L },
        )
        controller.start()
        runBlocking {
            withTimeout(5_000) { while (store.loads < 1) kotlinx.coroutines.yield() }
            controller.refresh()
            store.released = true
            withTimeout(5_000) { while (!store.firstLoadDone) kotlinx.coroutines.yield() }
        }
        assertEquals(listOf("fresh"), controller.awaitState { it.ids() == listOf("fresh") }.ids())
    }
}
