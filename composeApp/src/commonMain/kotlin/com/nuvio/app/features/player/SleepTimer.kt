package com.nuvio.app.features.player

sealed interface SleepTimerMode {
    data object Off : SleepTimerMode
    data class Duration(val endsAtMs: Long) : SleepTimerMode
    data object EndOfEpisode : SleepTimerMode
}

val SleepTimerPresetsMinutes = listOf(15, 30, 45, 60, 90)
const val SleepTimerFadeMs = 10_000L
const val SleepTimerExtendMinutes = 15

private const val EndOfEpisodeFallbackLeadMs = 1_000L

/** Remaining time for a [SleepTimerMode.Duration] timer, clamped at 0. Null for other modes. */
fun SleepTimerMode.remainingMs(nowMs: Long): Long? = when (this) {
    is SleepTimerMode.Duration -> (endsAtMs - nowMs).coerceAtLeast(0L)
    SleepTimerMode.Off, SleepTimerMode.EndOfEpisode -> null
}

private fun SleepTimerMode.reached(
    nowMs: Long,
    positionMs: Long,
    durationMs: Long,
    nextEpisodeThresholdMs: Long?,
    leadMs: Long,
): Boolean = when (this) {
    SleepTimerMode.Off -> false
    is SleepTimerMode.Duration -> nowMs >= endsAtMs - leadMs
    SleepTimerMode.EndOfEpisode -> {
        if (durationMs <= 0L) {
            false
        } else {
            val firePoint = nextEpisodeThresholdMs ?: (durationMs - EndOfEpisodeFallbackLeadMs)
            positionMs >= firePoint - leadMs
        }
    }
}

fun SleepTimerMode.fadeStartReached(
    nowMs: Long,
    positionMs: Long,
    durationMs: Long,
    nextEpisodeThresholdMs: Long?,
): Boolean = reached(nowMs, positionMs, durationMs, nextEpisodeThresholdMs, SleepTimerFadeMs)

fun SleepTimerMode.shouldFire(
    nowMs: Long,
    positionMs: Long,
    durationMs: Long,
    nextEpisodeThresholdMs: Long?,
): Boolean = reached(nowMs, positionMs, durationMs, nextEpisodeThresholdMs, 0L)

/** Linear fade from [startVolume] to 0 over [SleepTimerFadeMs]. */
fun fadeVolumeFraction(startVolume: Float, elapsedFadeMs: Long): Float {
    val elapsed = elapsedFadeMs.coerceIn(0L, SleepTimerFadeMs)
    return startVolume * (1f - elapsed.toFloat() / SleepTimerFadeMs.toFloat())
}

fun startSleepTimer(minutes: Int, nowMs: Long): SleepTimerMode.Duration =
    SleepTimerMode.Duration(endsAtMs = nowMs + minutes * 60_000L)
