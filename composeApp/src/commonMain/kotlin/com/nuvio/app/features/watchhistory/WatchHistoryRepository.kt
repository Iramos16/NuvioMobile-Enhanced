package com.nuvio.app.features.watchhistory

import co.touchlab.kermit.Logger
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.core.tracking.ensureTrackingProvidersRegistered
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
}

private object PlatformWatchHistoryPayloadStore : WatchHistoryPayloadStore {
    override fun load(profileId: Int): String? = WatchHistoryStorage.loadPayload(profileId)
    override fun save(profileId: Int, payload: String) = WatchHistoryStorage.savePayload(profileId, payload)
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

    override suspend fun pull(id: WatchHistorySourceId, profileId: Int): List<WatchedItem> {
        if (id == WatchHistorySourceId.NUVIO) {
            WatchedRepository.ensureLoaded()
            return WatchedRepository.nuvioHistorySnapshot()
        }
        val provider = TrackingProviderRegistry.connectedWatchedProviders()
            .firstOrNull { WatchHistorySourceId.fromTrackingProvider(it.providerId) == id }
            ?: return emptyList()
        return provider.pull(profileId, PULL_PAGE_SIZE)
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
    private class Cache(val profileId: Int, val stored: StoredWatchHistory)

    private val cache = MutableStateFlow<Cache?>(null)
    private val refreshing = MutableStateFlow(false)
    private val failed = MutableStateFlow<Set<WatchHistorySourceId>>(emptySet())

    private val _uiState = MutableStateFlow(WatchHistoryUiState())
    val uiState: StateFlow<WatchHistoryUiState> = _uiState.asStateFlow()

    private val log = Logger.withTag("WatchHistory")
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            profileIds.distinctUntilChanged().collect { profileId ->
                failed.value = emptySet()
                cache.value = Cache(profileId, decodeStoredWatchHistory(store.load(profileId)))
            }
        }
        scope.launch {
            combine(cache, gateway.connectedSources(), policy, refreshing, failed) { cache, connected, policy, refreshing, failed ->
                if (cache == null) return@combine WatchHistoryUiState(isRefreshing = refreshing)
                val sources = connected.map { id ->
                    WatchHistorySource(id, cache.stored.sources[id.name].orEmpty())
                }
                WatchHistoryUiState(
                    sections = groupWatchHistoryByDay(mergeWatchHistory(sources, policy), timeZone()),
                    isLoaded = true,
                    isRefreshing = refreshing,
                    lastUpdatedEpochMs = connected.mapNotNull { cache.stored.lastUpdatedEpochMs[it.name] }.maxOrNull(),
                    failedSources = failed intersect connected,
                    connectedSources = connected,
                    policy = policy,
                )
            }.collect { _uiState.value = it }
        }
    }

    suspend fun refresh() {
        if (!refreshing.compareAndSet(expect = false, update = true)) return
        try {
            val profileId = profileIds.first()
            val connected = gateway.connectedSources().first()
            val results = supervisorScope {
                connected.map { id ->
                    async {
                        id to try {
                            Result.success(gateway.pull(id, profileId))
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            log.w(error) { "${id.name} refresh failed" }
                            Result.failure(error)
                        }
                    }
                }.awaitAll()
            }
            val current = cache.value?.takeIf { it.profileId == profileId }?.stored
                ?: decodeStoredWatchHistory(store.load(profileId))
            val now = nowEpochMs()
            val connectedNames = connected.mapTo(mutableSetOf()) { it.name }
            val sources = current.sources.filterKeys { it in connectedNames }.toMutableMap()
            val updated = current.lastUpdatedEpochMs.filterKeys { it in connectedNames }.toMutableMap()
            results.forEach { (id, result) ->
                result.onSuccess { items ->
                    sources[id.name] = items
                    updated[id.name] = now
                }
            }
            val next = StoredWatchHistory(sources = sources, lastUpdatedEpochMs = updated)
            store.save(profileId, encodeStoredWatchHistory(next))
            if (profileIds.first() == profileId) {
                cache.value = Cache(profileId, next)
                failed.value = results.filter { it.second.isFailure }.mapTo(mutableSetOf()) { it.first }
            }
        } finally {
            refreshing.value = false
        }
    }
}

object WatchHistoryRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val controller by lazy {
        TrackingSettingsRepository.ensureLoaded()
        WatchHistoryController(
            gateway = TrackingWatchHistorySourceGateway,
            store = PlatformWatchHistoryPayloadStore,
            policy = TrackingSettingsRepository.uiState.map { it.watchHistoryMergePolicy },
            profileIds = ProfileRepository.state.map { ProfileRepository.activeProfileId },
            timeZone = { TimeZone.currentSystemDefault() },
            scope = scope,
            nowEpochMs = { EpisodeReleaseDatePlatform.nowEpochMs() },
        )
    }

    val uiState: StateFlow<WatchHistoryUiState>
        get() = controller.uiState

    fun ensureLoaded() {
        controller.start()
    }

    fun refresh() {
        ensureLoaded()
        scope.launch { controller.refresh() }
    }
}
