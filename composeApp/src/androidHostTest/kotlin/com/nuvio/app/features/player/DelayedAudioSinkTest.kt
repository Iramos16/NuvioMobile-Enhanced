package com.nuvio.app.features.player

import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DelayedAudioSinkTest {
    // Positive delay = audio plays later, so the clock (what video follows) runs ahead of the audio.
    @Test
    fun positiveDelayMovesReportedClockAheadOfAudio() {
        assertEquals(5_250_000L, DelayedAudioSink.reportedPositionUs(5_000_000L, 250_000L))
    }

    @Test
    fun negativeDelayMovesReportedClockBehindAudio() {
        assertEquals(4_750_000L, DelayedAudioSink.reportedPositionUs(5_000_000L, -250_000L))
    }

    @Test
    fun reportedPositionIsMonotonicAcrossDelayDecrease() {
        val before = DelayedAudioSink.reportedPositionUs(5_000_000L, 250_000L)
        val after = DelayedAudioSink.reportedPositionUs(5_010_000L, 0L, lastReportedUs = before)
        assertEquals(before, after)
        val later = DelayedAudioSink.reportedPositionUs(5_300_000L, 0L, lastReportedUs = after)
        assertEquals(5_300_000L, later)
    }

    @Test
    fun positionNotSetPassesThrough() {
        assertEquals(
            AudioSink.CURRENT_POSITION_NOT_SET,
            DelayedAudioSink.reportedPositionUs(AudioSink.CURRENT_POSITION_NOT_SET, 250_000L, 9_000_000L),
        )
    }

    @Test
    fun dropsOnlyBuffersBehindNegativeDelayTarget() {
        assertTrue(DelayedAudioSink.shouldDropBuffer(1_000_000L, -500_000L, 800_000L))
        assertFalse(DelayedAudioSink.shouldDropBuffer(1_000_000L, -500_000L, 400_000L))
        assertFalse(DelayedAudioSink.shouldDropBuffer(1_000_000L, 0L, 5_000_000L))
        assertFalse(DelayedAudioSink.shouldDropBuffer(1_000_000L, 500_000L, 5_000_000L))
    }

    @Test
    fun flushIsDebouncedAndOnlyForChanges() {
        assertFalse(DelayedAudioSink.shouldApplyFlush(0L, 100_000L, 50L))
        assertTrue(DelayedAudioSink.shouldApplyFlush(0L, 100_000L, 200L))
        assertFalse(DelayedAudioSink.shouldApplyFlush(100_000L, 100_000L, 999L))
    }

    @Test
    fun wrapperReportsShiftedPositionForPositiveDelay() {
        val fake = FakeSink(positionUs = 5_000_000L)
        val sink = DelayedAudioSink(fake.proxy, delayUsProvider = { 250_000L }, nowMs = { 10_000L })
        sink.flush()
        assertEquals(5_250_000L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun wrapperDropsAudioUntilNegativeDelayTargetThenResyncs() {
        val fake = FakeSink(positionUs = AudioSink.CURRENT_POSITION_NOT_SET)
        val sink = DelayedAudioSink(fake.proxy, delayUsProvider = { -500_000L }, nowMs = { 10_000L })
        sink.flush()
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(4), 0L, 1))
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(4), 400_000L, 1))
        assertEquals(emptyList(), fake.written)
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(4), 500_000L, 1))
        assertEquals(listOf(500_000L), fake.written)
        assertEquals(1, fake.discontinuities)
        fake.positionUs = 600_000L
        assertEquals(100_000L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun wrapperNeverDropsWhileDelegateHoldsAPartialBuffer() {
        var delayUs = 0L
        var now = 10_000L
        val fake = FakeSink(positionUs = 1_000_000L, accept = false)
        val sink = DelayedAudioSink(fake.proxy, delayUsProvider = { delayUs }, nowMs = { now })
        sink.flush()
        val buffer = ByteBuffer.allocate(4)
        assertFalse(sink.handleBuffer(buffer, 1_000_000L, 1))
        delayUs = -500_000L
        now += 1_000L
        sink.requestFlush()
        assertFalse(sink.handleBuffer(buffer, 1_000_000L, 1))
        assertEquals(listOf(1_000_000L, 1_000_000L), fake.written)
    }

    @Test
    fun zeroDelayPassesBackwardDelegatePositionStraightThrough() {
        val fake = FakeSink(positionUs = 5_000_000L)
        val sink = DelayedAudioSink(fake.proxy, delayUsProvider = { 0L }, nowMs = { 10_000L })
        sink.flush()
        assertEquals(5_000_000L, sink.getCurrentPositionUs(false))
        fake.positionUs = 2_000_000L
        assertEquals(2_000_000L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun delegateSignalledDiscontinuityResetsTheClamp() {
        var delayUs = 0L
        var now = 10_000L
        val fake = FakeSink(positionUs = 5_000_000L)
        val sink = DelayedAudioSink(fake.proxy, delayUsProvider = { delayUs }, nowMs = { now })
        var forwarded = 0
        sink.setListener(listenerProxy { if (it == "onPositionDiscontinuity") forwarded++ })
        sink.flush()
        assertEquals(5_000_000L, sink.getCurrentPositionUs(false))
        delayUs = -500_000L
        now += 1_000L
        sink.requestFlush()
        fake.positionUs = 5_010_000L
        assertEquals(5_000_000L, sink.getCurrentPositionUs(false))
        fake.listener!!.onPositionDiscontinuity()
        assertEquals(1, forwarded)
        fake.positionUs = 3_000_000L
        assertEquals(2_500_000L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun midRunChangeIsDebouncedThenAppliedOnALaterBuffer() {
        var delayUs = 0L
        var now = 10_000L
        val fake = FakeSink(positionUs = 1_000_000L)
        val sink = DelayedAudioSink(fake.proxy, delayUsProvider = { delayUs }, nowMs = { now })
        sink.flush()
        delayUs = 200_000L
        sink.requestFlush()
        sink.handleBuffer(ByteBuffer.allocate(4), 2_000_000L, 1)
        assertEquals(1_200_000L, sink.getCurrentPositionUs(false))
        delayUs = 300_000L
        now += 50L
        sink.requestFlush()
        sink.handleBuffer(ByteBuffer.allocate(4), 2_010_000L, 1)
        assertEquals(1_200_000L, sink.getCurrentPositionUs(false))
        now += 150L
        sink.handleBuffer(ByteBuffer.allocate(4), 2_020_000L, 1)
        assertEquals(1_300_000L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun flushResetsTheBaseline() {
        var delayUs = 0L
        var now = 10_000L
        val fake = FakeSink(positionUs = 5_000_000L)
        val sink = DelayedAudioSink(fake.proxy, delayUsProvider = { delayUs }, nowMs = { now })
        sink.flush()
        assertEquals(5_000_000L, sink.getCurrentPositionUs(false))
        delayUs = -500_000L
        now += 1_000L
        sink.requestFlush()
        assertEquals(5_000_000L, sink.getCurrentPositionUs(false))
        sink.flush()
        fake.positionUs = 1_000_000L
        assertEquals(500_000L, sink.getCurrentPositionUs(false))
    }

    private fun listenerProxy(onCall: (String) -> Unit): AudioSink.Listener = Proxy.newProxyInstance(
        AudioSink.Listener::class.java.classLoader,
        arrayOf(AudioSink.Listener::class.java),
    ) { _, method, _ ->
        onCall(method.name)
        null
    } as AudioSink.Listener

    private class FakeSink(var positionUs: Long, var accept: Boolean = true) {
        val written = mutableListOf<Long>()
        var discontinuities = 0
        var listener: AudioSink.Listener? = null
        val proxy: AudioSink = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getCurrentPositionUs" -> positionUs
                "handleBuffer" -> {
                    written += args[1] as Long
                    accept
                }
                "setListener" -> {
                    listener = args[0] as AudioSink.Listener
                    null
                }
                "handleDiscontinuity" -> {
                    discontinuities++
                    null
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Long.TYPE -> 0L
                    Integer.TYPE -> 0
                    else -> null
                }
            }
        } as AudioSink
    }
}
