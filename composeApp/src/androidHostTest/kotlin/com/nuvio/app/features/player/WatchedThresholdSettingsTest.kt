package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import com.nuvio.app.core.sync.decodeSyncInt
import com.nuvio.app.features.watching.domain.WatchedThreshold
import kotlinx.serialization.json.buildJsonObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WatchedThresholdSettingsTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerSettingsStorage.initialize(context)
        PlayerSettingsRepository.clearLocalState()
    }

    @AfterTest
    fun clearState() {
        PlayerSettingsRepository.clearLocalState()
        WatchedThreshold.percent = WatchedThreshold.DEFAULT_PERCENT
    }

    @Test
    fun defaultsToNinetyPercentAndKeepsTheSavedValueAfterReload() {
        PlayerSettingsRepository.ensureLoaded()
        assertEquals(90, PlayerSettingsRepository.uiState.value.watchedThresholdPercent)

        PlayerSettingsRepository.setWatchedThresholdPercent(95)
        assertEquals(95, WatchedThreshold.percent)

        WatchedThreshold.percent = WatchedThreshold.DEFAULT_PERCENT
        PlayerSettingsRepository.onProfileChanged()

        assertEquals(95, PlayerSettingsRepository.uiState.value.watchedThresholdPercent)
        assertEquals(95, PlayerSettingsStorage.loadWatchedThresholdPercent())
        assertEquals(95, WatchedThreshold.percent)
    }

    @Test
    fun clampsValuesOutsideTheSupportedRange() {
        PlayerSettingsRepository.setWatchedThresholdPercent(50)
        assertEquals(80, PlayerSettingsRepository.uiState.value.watchedThresholdPercent)

        PlayerSettingsRepository.setWatchedThresholdPercent(100)
        assertEquals(99, PlayerSettingsRepository.uiState.value.watchedThresholdPercent)
    }

    @Test
    fun roundTripsThroughSyncAndOlderPayloadsRestoreTheDefault() {
        PlayerSettingsRepository.setWatchedThresholdPercent(85)
        val payload = PlayerSettingsStorage.exportToSyncPayload()
        assertEquals(85, payload.decodeSyncInt("watched_threshold_percent"))

        PlayerSettingsRepository.setWatchedThresholdPercent(97)
        PlayerSettingsStorage.replaceFromSyncPayload(payload)
        PlayerSettingsRepository.onProfileChanged()
        assertEquals(85, PlayerSettingsRepository.uiState.value.watchedThresholdPercent)

        PlayerSettingsStorage.replaceFromSyncPayload(buildJsonObject {})
        PlayerSettingsRepository.onProfileChanged()
        assertEquals(90, PlayerSettingsRepository.uiState.value.watchedThresholdPercent)
        assertEquals(90, WatchedThreshold.percent)
    }
}
