package com.nuvio.app.features.watching.domain

import kotlin.concurrent.Volatile

/**
 * The percentage at which an item counts as watched, everywhere: local progress, Continue Watching,
 * imported tracker playback and the stop scrobble sent to trackers. It is a per-profile playback
 * setting; [com.nuvio.app.features.player.PlayerSettingsRepository] keeps it current.
 *
 * Trakt, Simkl and MDBList mark an item watched on their side when a stop scrobble reaches 80 %,
 * so the setting cannot go below that.
 */
object WatchedThreshold {
    const val DEFAULT_PERCENT = 90
    const val MIN_PERCENT = 80
    const val MAX_PERCENT = 99

    @Volatile
    var percent: Int = DEFAULT_PERCENT
        set(value) {
            field = normalize(value)
        }

    fun normalize(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    fun isReached(progressPercent: Double): Boolean = progressPercent >= percent

    fun isReached(progressPercent: Float): Boolean = progressPercent >= percent
}
