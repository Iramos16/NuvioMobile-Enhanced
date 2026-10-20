package com.nuvio.app.features.player

internal const val DefaultSeekStepSeconds = 10

internal val PlayerSeekStepChoicesSeconds: List<Int> = listOf(5, 10, 15, 30, 60, 90)

/** Maps a stored or synced value onto a supported skip step; anything unknown falls back to 10 s. */
internal fun normalizeSeekStepSeconds(value: Int?): Int =
    value?.takeIf { it in PlayerSeekStepChoicesSeconds } ?: DefaultSeekStepSeconds

/** Total accumulated double-tap skip after one more tap: one step on the first tap, then one more per tap. */
internal fun stackDoubleTapSeek(currentAmountMs: Long?, stepMs: Long): Long =
    if (currentAmountMs == null) stepMs else currentAmountMs + stepMs

/** Absolute target of a stacked double-tap: baseline +/- accumulated amount, clamped to 0..duration (when known). */
internal fun doubleTapSeekTarget(
    direction: PlayerSeekDirection,
    baselinePositionMs: Long,
    amountMs: Long,
    durationMs: Long,
): Long = when (direction) {
    PlayerSeekDirection.Backward -> (baselinePositionMs - amountMs).coerceAtLeast(0L)
    PlayerSeekDirection.Forward -> {
        val unclamped = baselinePositionMs + amountMs
        durationMs.takeIf { it > 0L }?.let { unclamped.coerceAtMost(it) } ?: unclamped
    }
}
