package com.nuvio.app.features.player

import kotlin.math.abs

const val AUDIO_DELAY_MIN_MS = -60_000
const val AUDIO_DELAY_MAX_MS = 60_000
const val AUDIO_DELAY_STEP_MS = 50

/** Audio delay for the stream currently playing. Positive means audio plays later. Never persisted. */
data class AudioDelayState(val streamUrl: String?, val delayMs: Int)

fun clampAudioDelayMs(ms: Int): Int = ms.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)

/** Keeps the delay while the same stream reloads; any other stream starts at 0. */
fun audioDelayForStream(current: AudioDelayState, streamUrl: String): AudioDelayState =
    if (current.streamUrl == streamUrl) current else AudioDelayState(streamUrl, 0)

/** Formats as signed seconds with two decimals: "+0.25 s", "-1.50 s", "0.00 s". */
fun formatAudioDelay(ms: Int): String {
    val centis = (abs(ms.toLong()) + 5) / 10
    val sign = when {
        centis == 0L -> ""
        ms > 0 -> "+"
        else -> "-"
    }
    val whole = centis / 100
    val fraction = (centis % 100).toString().padStart(2, '0')
    return "$sign$whole.$fraction s"
}

/** Step for a held − / + button: 50 ms for the first second, 250 ms up to 3 s, then 1 s. */
fun holdRepeatStepMs(heldForMs: Long): Int = when {
    heldForMs < 1_000L -> AUDIO_DELAY_STEP_MS
    heldForMs < 3_000L -> 250
    else -> 1_000
}
