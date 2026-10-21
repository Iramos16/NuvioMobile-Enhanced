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
import kotlinx.coroutines.launch
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
        override fun delete(profileId: Int) {
            payloads.remove(profileId)
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
        now: Long = 5_000L,
    ) = WatchHistoryController(
        gateway = gateway,
        store = store,
        policy = policy,
        profileIds = profile,
        timeZone = { TimeZone.UTC },
        scope = scope,
        nowEpochMs = { now },
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
        override fun delete(profileId: Int) {
            saved = null
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

    @Test
    fun refreshWhileRefreshing_runsQueuedFollowUpForNewProfile() {
        val profile = MutableStateFlow(1)
        val firstPullStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseFirstPull = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val gateway = object : WatchHistorySourceGateway {
            override fun connectedSources(): Flow<Set<WatchHistorySourceId>> = MutableStateFlow(setOf(NUVIO))
            override suspend fun pull(id: WatchHistorySourceId, profileId: Int): List<WatchedItem> {
                calls += 1
                if (calls == 1) {
                    firstPullStarted.complete(Unit)
                    releaseFirstPull.await()
                }
                return listOf(item("profile-$profileId", 100))
            }
        }
        val store = FakeStore()
        val controller = WatchHistoryController(
            gateway = gateway,
            store = store,
            policy = MutableStateFlow(WatchHistoryMergePolicy.MOST_RECENT),
            profileIds = profile,
            timeZone = { TimeZone.UTC },
            scope = scope,
            nowEpochMs = { 5_000L },
        ).also { it.start() }
        runBlocking {
            val first = scope.launch { controller.refresh() }
            firstPullStarted.await()
            profile.value = 2
            controller.refresh()
            releaseFirstPull.complete(Unit)
            first.join()
        }
        assertEquals(
            listOf("profile-2"),
            decodeStoredWatchHistory(store.payloads[2]).sources["NUVIO"].orEmpty().map { it.id },
        )
    }

    @Test
    fun accountChangedCancellation_failsOnlyThatSource() {
        val gateway = FakeGateway(setOf(NUVIO, SIMKL)).apply {
            results[NUVIO] = { listOf(item("n", 100)) }
            results[SIMKL] = { throw kotlinx.coroutines.CancellationException("account changed") }
        }
        val store = FakeStore()
        val controller = controller(gateway, store)
        runBlocking { controller.refresh() }
        val state = controller.awaitState { it.ids() == listOf("n") && it.failedSources.isNotEmpty() }
        assertEquals(setOf(SIMKL), state.failedSources)
    }

    @Test
    fun refreshIfStale_skipsWhenEveryConnectedSourceIsFresh() {
        var pulls = 0
        val gateway = FakeGateway(setOf(NUVIO, TRAKT)).apply {
            results[NUVIO] = { pulls += 1; emptyList() }
            results[TRAKT] = { pulls += 1; emptyList() }
        }
        val store = FakeStore().apply { payloads[1] = cached(NUVIO to emptyList(), TRAKT to emptyList()) }
        val controller = controller(gateway, store, now = 1L + WATCH_HISTORY_STALE_AFTER_MS - 1)
        runBlocking { controller.refresh(force = false) }
        assertEquals(0, pulls)
    }

    @Test
    fun refreshIfStale_runsWhenAConnectedSourceIsOld() {
        var pulls = 0
        val gateway = FakeGateway(setOf(NUVIO, TRAKT)).apply {
            results[NUVIO] = { pulls += 1; emptyList() }
            results[TRAKT] = { pulls += 1; emptyList() }
        }
        val store = FakeStore().apply { payloads[1] = cached(NUVIO to emptyList(), TRAKT to emptyList()) }
        val controller = controller(gateway, store, now = 1L + WATCH_HISTORY_STALE_AFTER_MS + 1)
        runBlocking { controller.refresh(force = false) }
        assertEquals(2, pulls)
    }

    @Test
    fun refreshIfStale_runsWhenAConnectedSourceWasNeverPulled() {
        var pulls = 0
        val gateway = FakeGateway(setOf(NUVIO, TRAKT)).apply {
            results[NUVIO] = { pulls += 1; emptyList() }
            results[TRAKT] = { pulls += 1; emptyList() }
        }
        val store = FakeStore().apply { payloads[1] = cached(NUVIO to emptyList()) }
        val controller = controller(gateway, store, now = 2L)
        runBlocking { controller.refresh(force = false) }
        assertEquals(2, pulls)
    }

    @Test
    fun lastUpdated_isOldestConnectedSource() {
        val store = FakeStore().apply {
            payloads[1] = encodeStoredWatchHistory(
                StoredWatchHistory(
                    sources = mapOf("NUVIO" to emptyList(), "TRAKT" to emptyList(), "SIMKL" to emptyList()),
                    lastUpdatedEpochMs = mapOf("NUVIO" to 300L, "TRAKT" to 100L, "SIMKL" to 50L),
                ),
            )
        }
        val controller = controller(FakeGateway(setOf(NUVIO, TRAKT)), store)
        assertEquals(100L, controller.awaitState { it.isLoaded }.lastUpdatedEpochMs)
    }

    @Test
    fun clear_deletesEveryProfilesHistoryAndResetsState() {
        val store = FakeStore().apply {
            payloads[1] = cached(NUVIO to listOf(item("one", 100)))
            payloads[2] = cached(NUVIO to listOf(item("two", 100)))
        }
        val controller = controller(FakeGateway(setOf(NUVIO)), store)
        controller.awaitState { it.ids() == listOf("one") }
        controller.clear(profileIds = 1..2)
        assertTrue(store.payloads.isEmpty())
        assertTrue(controller.awaitState { it.sections.isEmpty() }.sections.isEmpty())
    }
}
