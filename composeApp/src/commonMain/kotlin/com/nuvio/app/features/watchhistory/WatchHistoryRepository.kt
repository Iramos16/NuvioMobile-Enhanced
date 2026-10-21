package com.nuvio.app.features.watchhistory

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.AppFileCache
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.core.tracking.ensureTrackingProvidersRegistered
import com.nuvio.app.features.anilist.AniListTracker
import com.nuvio.app.features.profiles.MAX_PROFILES
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.simkl.SimklSyncRepository
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.wetrakr.WeTrakrTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class WatchHistoryUiState(
    val sections: List<WatchHistoryDaySection> = emptyList(),
    val isLoaded: Boolean = false,
    val isRefreshing: Boolean = false,
    val lastUpdatedEpochMs: Long? = null,
    val failedSources: Set<WatchHistorySourceId> = emptySet(),
    val connectedSources: Set<WatchHistorySourceId> = emptySet(),
    val policy: WatchHistoryMergePolicy = DEFAULT_WATCH_HISTORY_MERGE_POLICY,
)

/** Last pulled items per source, keyed by [WatchHistorySourceId.name]. */
@Serializable
internal data class StoredWatchHistory(
    val sources: Map<String, List<WatchedItem>> = emptyMap(),
    val lastUpdatedEpochMs: Map<String, Long> = emptyMap(),
)

private val watchHistoryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun encodeStoredWatchHistory(stored: StoredWatchHistory): String =
    watchHistoryJson.encodeToString(stored)

internal fun decodeStoredWatchHistory(payload: String?): StoredWatchHistory {
    val trimmed = payload.orEmpty().trim()
    if (trimmed.isEmpty()) return StoredWatchHistory()
    return runCatching { watchHistoryJson.decodeFromString<StoredWatchHistory>(trimmed) }
        .getOrDefault(StoredWatchHistory())
}

/** Where history comes from: which sources are connected, and how to pull one. */
internal interface WatchHistorySourceGateway {
    fun connectedSources(): Flow<Set<WatchHistorySourceId>>
    suspend fun pull(id: WatchHistorySourceId, profileId: Int): List<WatchedItem>
}

internal interface WatchHistoryPayloadStore {
    fun load(profileId: Int): String?
    fun save(profileId: Int, payload: String)
    fun delete(profileId: Int)
}

/** An automatic refresh is skipped while every connected source was pulled more recently than this. */
internal const val WATCH_HISTORY_STALE_AFTER_MS = 5L * 60L * 1000L

/** One file per profile: histories can be large, so they stay out of preferences storage. */
private object FileWatchHistoryPayloadStore : WatchHistoryPayloadStore {
    private fun path(profileId: Int) = "watch_history/profile_$profileId.json"
    override fun load(profileId: Int): String? = AppFileCache.read(path(profileId))
    override fun save(profileId: Int, payload: String) = AppFileCache.write(path(profileId), payload)
    override fun delete(profileId: Int) = AppFileCache.delete(path(profileId))
}

/** Reads each connected tracker's watched projection plus local Nuvio items. Never writes. */
private object TrackingWatchHistorySourceGateway : WatchHistorySourceGateway {
    private const val PULL_PAGE_SIZE = 500

    override fun connectedSources(): Flow<Set<WatchHistorySourceId>> {
        ensureTrackingProvidersRegistered()
        TrackingProviderRegistry.ensureLoaded()
        return TrackingProviderRegistry.connectedProviderIds.map { providerIds ->
            buildSet {
                add(WatchHistorySourceId.NUVIO)
                providerIds
                    .filter { TrackingProviderRegistry.watchedProvider(it) != null }
                    .mapNotNullTo(this) { WatchHistorySourceId.fromTrackingProvider(it) }
            }
        }
    }

    /**
     * Adapters degrade to an empty or stale projection instead of throwing when offline or
     * unauthorized. Here that must count as a failure, or the cached slice would be replaced.
     */
    override suspend fun pull(id: WatchHistorySourceId, profileId: Int): List<WatchedItem> {
        if (id == WatchHistorySourceId.NUVIO) {
            WatchedRepository.ensureLoaded()
            return WatchedRepository.nuvioHistorySnapshot()
        }
        if (profileId != ProfileRepository.activeProfileId) error("${id.name}: profile changed")
        val provider = TrackingProviderRegistry.connectedWatchedProviders()
            .firstOrNull { WatchHistorySourceId.fromTrackingProvider(it.providerId) == id }
            ?: error("${id.name}: not connected")
        if (id == WatchHistorySourceId.TRAKT && TraktAuthRepository.authorizedHeaders(profileId) == null) {
            error("TRAKT: not authorized")
        }
        val items = provider.pull(profileId, PULL_PAGE_SIZE)
        val syncError = when (id) {
            WatchHistorySourceId.SIMKL -> SimklSyncRepository.state.value.errorMessage
            WatchHistorySourceId.ANILIST -> AniListTracker.sync.state.value.error?.toString()
            WatchHistorySourceId.WETRAKR -> WeTrakrTracker.sync.state.value.error?.toString()
            WatchHistorySourceId.NUVIO, WatchHistorySourceId.TRAKT -> null
        }
        if (syncError != null) error("${id.name}: $syncError")
        return items
    }
}

internal class WatchHistoryController(
    private val gateway: WatchHistorySourceGateway,
    private val store: WatchHistoryPayloadStore,
    private val policy: Flow<WatchHistoryMergePolicy>,
    private val profileIds: Flow<Int>,
    private val timeZone: () -> TimeZone,
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long,
) {
    /** Failures travel with the data they describe, so both are replaced together. */
    private class Cache(
        val profileId: Int,
        val stored: StoredWatchHistory,
        val failed: Set<WatchHistorySourceId> = emptySet(),
    )

    private val cache = MutableStateFlow<Cache?>(null)
    private val refreshing = MutableStateFlow(false)
    private val refreshQueued = MutableStateFlow(false)

    private val _uiState = MutableStateFlow(WatchHistoryUiState())
    val uiState: StateFlow<WatchHistoryUiState> = _uiState.asStateFlow()

    private val log = Logger.withTag("WatchHistory")
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            profileIds.distinctUntilChanged().collect { profileId ->
                val loaded = decodeStoredWatchHistory(store.load(profileId))
                // A refresh may have published fresher data for this profile while we were reading.
                cache.update { current -> current?.takeIf { it.profileId == profileId } ?: Cache(profileId, loaded) }
            }
        }
        scope.launch {
            combine(cache, gateway.connectedSources(), policy, refreshing) { cache, connected, policy, refreshing ->
                if (cache == null) return@combine WatchHistoryUiState(isRefreshing = refreshing)
                val sources = connected.map { id ->
                    WatchHistorySource(id, cache.stored.sources[id.name].orEmpty())
                }
                WatchHistoryUiState(
                    sections = groupWatchHistoryByDay(mergeWatchHistory(sources, policy), timeZone()),
                    isLoaded = true,
                    isRefreshing = refreshing,
                    // The oldest source decides, so one failing tracker cannot make the list look fresh.
                    lastUpdatedEpochMs = connected.mapNotNull { cache.stored.lastUpdatedEpochMs[it.name] }.minOrNull(),
                    failedSources = cache.failed intersect connected,
                    connectedSources = connected,
                    policy = policy,
                )
            }.collect { _uiState.value = it }
        }
    }

    /** Forgets every listed profile's history, on disk and in memory (account sign-out). */
    fun clear(profileIds: IntRange) {
        profileIds.forEach(store::delete)
        cache.update { current -> current?.let { Cache(it.profileId, StoredWatchHistory()) } }
    }

    /**
     * Pulls every connected source. With [force] off, nothing happens while all connected sources
     * are fresh. A call made during a running refresh queues exactly one follow-up run, so a
     * refresh requested after a profile switch is not lost.
     */
    suspend fun refresh(force: Boolean = true) {
        if (!force && !isStale()) return
        while (true) {
            if (!refreshing.compareAndSet(expect = false, update = true)) {
                refreshQueued.value = true
                if (refreshing.value) return else continue
            }
            try {
                do {
                    refreshQueued.value = false
                    refreshOnce()
                } while (refreshQueued.value)
            } finally {
                refreshing.value = false
            }
            if (!refreshQueued.value) return
        }
    }

    private suspend fun isStale(): Boolean {
        val profileId = profileIds.first()
        val stored = cache.value?.takeIf { it.profileId == profileId }?.stored
            ?: decodeStoredWatchHistory(store.load(profileId))
        val now = nowEpochMs()
        return gateway.connectedSources().first().any { id ->
            val updatedAt = stored.lastUpdatedEpochMs[id.name] ?: return@any true
            now - updatedAt >= WATCH_HISTORY_STALE_AFTER_MS
        }
    }

    private suspend fun refreshOnce() {
        val profileId = profileIds.first()
        val connected = gateway.connectedSources().first()
        val results = supervisorScope {
            connected.map { id ->
                async {
                    id to try {
                        Result.success(gateway.pull(id, profileId))
                    } catch (error: CancellationException) {
                        // Trackers signal "account changed" with a CancellationException; only a
                        // real cancellation of this refresh should abort the other sources.
                        currentCoroutineContext().ensureActive()
                        log.w(error) { "${id.name} refresh cancelled by source" }
                        Result.failure(error)
                    } catch (error: Throwable) {
                        log.w(error) { "${id.name} refresh failed" }
                        Result.failure(error)
                    }
                }
            }.awaitAll()
        }
        // Adapters read app-wide state for the active profile; results gathered across a
        // profile switch may belong to the new profile, so never store them under the old one.
        if (profileIds.first() != profileId) return
        val current = cache.value?.takeIf { it.profileId == profileId }?.stored
            ?: decodeStoredWatchHistory(store.load(profileId))
        val now = nowEpochMs()
        // Slices of sources not pulled now are kept: the connected set can be briefly
        // incomplete while auth state loads, and the UI already hides disconnected sources.
        val sources = current.sources.toMutableMap()
        val updated = current.lastUpdatedEpochMs.toMutableMap()
        results.forEach { (id, result) ->
            result.onSuccess { items ->
                sources[id.name] = items
                updated[id.name] = now
            }
        }
        val next = StoredWatchHistory(sources = sources, lastUpdatedEpochMs = updated)
        store.save(profileId, encodeStoredWatchHistory(next))
        cache.value = Cache(
            profileId = profileId,
            stored = next,
            failed = results.filter { it.second.isFailure }.mapTo(mutableSetOf()) { it.first },
        )
    }
}

object WatchHistoryRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val controllerDelegate = lazy {
        TrackingSettingsRepository.ensureLoaded()
        WatchHistoryController(
            gateway = TrackingWatchHistorySourceGateway,
            store = FileWatchHistoryPayloadStore,
            policy = TrackingSettingsRepository.uiState.map { it.watchHistoryMergePolicy },
            profileIds = ProfileRepository.state.map { ProfileRepository.activeProfileId },
            timeZone = { TimeZone.currentSystemDefault() },
            scope = scope,
            nowEpochMs = { EpisodeReleaseDatePlatform.nowEpochMs() },
        )
    }
    private val controller by controllerDelegate

    val uiState: StateFlow<WatchHistoryUiState>
        get() = controller.uiState

    fun ensureLoaded() {
        controller.start()
    }

    /** Refreshes now; with [force] off only when some connected source is stale. */
    fun refresh(force: Boolean = true) {
        ensureLoaded()
        scope.launch { controller.refresh(force) }
    }

    /** Removes every profile's history; called when the account's local data is wiped. */
    fun clearLocalState() {
        if (controllerDelegate.isInitialized()) {
            controller.clear(1..MAX_PROFILES)
        } else {
            (1..MAX_PROFILES).forEach(FileWatchHistoryPayloadStore::delete)
        }
    }
}
