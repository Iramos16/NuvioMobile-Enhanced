@file:OptIn(UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Applies the user's audio delay (positive = audio plays later) to ExoPlayer.
 *
 * The audio sink is ExoPlayer's master clock and video frames are shown when the clock reaches
 * their timestamp, so the A/V offset is `clock - heard audio position`. This sink reports
 * `delegate position + delay`:
 * - Positive delay: the clock runs ahead of the audio, video jumps forward, audio is heard later.
 * - Negative delay: the clock would fall behind the audio. Instead of freezing video for |delay|,
 *   the sink drops the next |delta| of audio (whole buffers only), then tells the delegate the
 *   stream is discontinuous so it re-anchors its own position to the new timestamps.
 *
 * Threading: [requestFlush] is the only call made from the main thread; it only sets a volatile
 * flag. Every AudioSink method runs on ExoPlayer's playback thread, which owns all other state.
 * The delegate is never flushed by this class; a delay change only changes the reported clock and,
 * for a decrease, which upcoming buffers reach the delegate.
 */
internal class DelayedAudioSink(
    delegate: AudioSink,
    private val delayUsProvider: () -> Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : ForwardingAudioSink(delegate) {

    @Volatile
    private var changeRequested = false

    // Playback-thread state.
    private var appliedDelayUs = 0L
    private var lastApplyMs = NEVER
    private var lastReportedUs = AudioSink.CURRENT_POSITION_NOT_SET
    private var dropShiftUs = 0L
    private var dropAnchorUs = AudioSink.CURRENT_POSITION_NOT_SET
    private var droppedSinceLastWrite = false
    private var delegateHoldsBuffer = false

    init {
        startNewRun()
    }

    /** Main thread: the provider's value changed; it is picked up on the playback thread. */
    fun requestFlush() {
        changeRequested = true
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        maybeApplyRequestedDelay()
        val reported = reportedPositionUs(
            super.getCurrentPositionUs(sourceEnded),
            appliedDelayUs,
            lastReportedUs,
        )
        if (reported != AudioSink.CURRENT_POSITION_NOT_SET) lastReportedUs = reported
        return reported
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        maybeApplyRequestedDelay()
        // The delegate requires the same buffer back while it holds a partial one, so drops and
        // re-anchoring only happen on buffer boundaries.
        if (!delegateHoldsBuffer) {
            if (dropShiftUs < 0L) {
                if (dropAnchorUs == AudioSink.CURRENT_POSITION_NOT_SET) dropAnchorUs = presentationTimeUs
                if (shouldDropBuffer(presentationTimeUs, dropShiftUs, dropAnchorUs)) {
                    buffer.position(buffer.limit())
                    droppedSinceLastWrite = true
                    return true
                }
                clearDrop()
            }
            if (droppedSinceLastWrite) {
                droppedSinceLastWrite = false
                super.handleDiscontinuity()
            }
        }
        val consumed = try {
            super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        } catch (error: Exception) {
            delegateHoldsBuffer = true
            throw error
        }
        delegateHoldsBuffer = !consumed
        return consumed
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        lastReportedUs = AudioSink.CURRENT_POSITION_NOT_SET
    }

    override fun flush() {
        super.flush()
        startNewRun()
    }

    override fun reset() {
        super.reset()
        startNewRun()
    }

    /** After a flush or reset the next buffer starts a new run with the current delay. */
    private fun startNewRun() {
        changeRequested = false
        appliedDelayUs = requestedDelayUs()
        lastApplyMs = NEVER
        lastReportedUs = AudioSink.CURRENT_POSITION_NOT_SET
        delegateHoldsBuffer = false
        droppedSinceLastWrite = false
        clearDrop()
        if (appliedDelayUs < 0L) dropShiftUs = appliedDelayUs
    }

    private fun maybeApplyRequestedDelay() {
        if (!changeRequested) return
        // Clear before reading so a request racing with this read is not lost.
        changeRequested = false
        val requested = requestedDelayUs()
        if (requested == appliedDelayUs) return
        val now = nowMs()
        val sinceMs = if (lastApplyMs == NEVER) Long.MAX_VALUE else now - lastApplyMs
        if (!shouldApplyFlush(appliedDelayUs, requested, sinceMs)) {
            changeRequested = true
            return
        }
        val deltaUs = requested - appliedDelayUs
        appliedDelayUs = requested
        lastApplyMs = now
        if (dropShiftUs < 0L) {
            dropShiftUs += deltaUs
            if (dropShiftUs >= 0L) clearDrop()
        } else if (deltaUs < 0L) {
            dropShiftUs = deltaUs
            dropAnchorUs = AudioSink.CURRENT_POSITION_NOT_SET
        }
    }

    private fun requestedDelayUs(): Long =
        delayUsProvider().coerceIn(AUDIO_DELAY_MIN_MS * 1_000L, AUDIO_DELAY_MAX_MS * 1_000L)

    private fun clearDrop() {
        dropShiftUs = 0L
        dropAnchorUs = AudioSink.CURRENT_POSITION_NOT_SET
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        const val FLUSH_DEBOUNCE_MS = 150L

        /**
         * The clock to report: the delegate's position plus the delay (positive = audio later, so
         * video runs ahead), never below [lastReportedUs] within one run.
         */
        fun reportedPositionUs(
            sinkPositionUs: Long,
            delayUs: Long,
            lastReportedUs: Long = AudioSink.CURRENT_POSITION_NOT_SET,
        ): Long {
            if (sinkPositionUs == AudioSink.CURRENT_POSITION_NOT_SET) return sinkPositionUs
            val shifted = sinkPositionUs + delayUs
            return if (lastReportedUs == AudioSink.CURRENT_POSITION_NOT_SET) shifted else maxOf(shifted, lastReportedUs)
        }

        /**
         * True when a buffer must be skipped so audio can jump ahead by -[delayUs]: its timestamp
         * shifted by the delay is still behind [clockPositionUs], the clock position the drop is
         * anchored to.
         */
        fun shouldDropBuffer(presentationTimeUs: Long, delayUs: Long, clockPositionUs: Long): Boolean =
            delayUs < 0L && presentationTimeUs + delayUs < clockPositionUs

        /** A changed delay is applied at most once per [FLUSH_DEBOUNCE_MS]. */
        fun shouldApplyFlush(lastAppliedDelayUs: Long, requestedDelayUs: Long, msSinceLastFlush: Long): Boolean =
            lastAppliedDelayUs != requestedDelayUs && msSinceLastFlush >= FLUSH_DEBOUNCE_MS
    }
}
