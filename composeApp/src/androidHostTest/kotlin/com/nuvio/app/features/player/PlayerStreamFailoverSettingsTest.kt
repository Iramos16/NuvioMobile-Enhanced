package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import com.nuvio.app.core.sync.decodeSyncBoolean
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerStreamFailoverSettingsTest {
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
    }

    @Test
    fun streamFailoverDefaultsOn() {
        assertTrue(PlayerSettingsUiState().streamFailoverEnabled)
        PlayerSettingsRepository.ensureLoaded()
        assertEquals(true, PlayerSettingsRepository.uiState.value.streamFailoverEnabled)
    }

    @Test
    fun streamFailoverPersists() {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.setStreamFailoverEnabled(false)
        PlayerSettingsRepository.clearLocalState()
        PlayerSettingsRepository.ensureLoaded()
        assertEquals(false, PlayerSettingsRepository.uiState.value.streamFailoverEnabled)
    }

    @Test
    fun streamFailoverSurvivesSyncRoundTrip() {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.setStreamFailoverEnabled(false)
        val payload = PlayerSettingsStorage.exportToSyncPayload()
        assertEquals(false, payload.decodeSyncBoolean("stream_failover_enabled"))
        PlayerSettingsStorage.saveStreamFailoverEnabled(true)
        PlayerSettingsStorage.replaceFromSyncPayload(payload)
        assertEquals(false, PlayerSettingsStorage.loadStreamFailoverEnabled())
    }
}
