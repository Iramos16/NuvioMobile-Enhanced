package com.nuvio.app.features.player

internal const val DefaultSeekStepSeconds = 10

internal val PlayerSeekStepChoicesSeconds: List<Int> = listOf(5, 10, 15, 30, 60, 90)

/** Maps a stored or synced value onto a supported skip step; anything unknown falls back to 10 s. */
internal fun normalizeSeekStepSeconds(value: Int?): Int =
    value?.takeIf { it in PlayerSeekStepChoicesSeconds } ?: DefaultSeekStepSeconds

/** Total accumulated double-tap skip after one more tap: one step on the first tap, then one more per tap. */
internal fun stackDoubleTapSeek(currentAmountMs: Long?, stepMs: Long): Long =
    if (currentAmountMs == null) stepMs else currentAmountMs + stepMs
