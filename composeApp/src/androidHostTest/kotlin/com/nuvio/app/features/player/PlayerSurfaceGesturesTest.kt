package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerSurfaceGesturesTest {
    @get:Rule
    val compose = createComposeRule()

    private val playbackReady = mutableStateOf(false)
    private val gestureFlags = mutableStateOf(
        PlayerGestureFlags(
            master = true,
            brightnessSwipe = true,
            volumeSwipe = true,
            holdToSpeed = true,
            doubleTapSeek = true,
            swipeToSeek = true,
            swapSides = false,
        ),
    )
    private var brightnessFeedback = 0
    private val movesConsumed = mutableListOf<Boolean>()
    private var volumeFeedback = 0
    private var taps = 0
    private var doubleTaps = 0
    private var speedBoosts = 0
    private var seekPreviews = 0
    private var seekCommits = 0
    private var sideAdjustments = 0
    private val seekStackDirection = mutableStateOf<PlayerSeekDirection?>(null)
    private var doubleTapStartsSeekStack = false
    private val gestureController = object : PlayerGestureController {
        override fun currentBrightness() = 0.5f
        override fun currentVolume() = PlayerAudioLevel(0.5f, false)
        override fun setBrightness(level: Float): Float {
            sideAdjustments++
            return level
        }
        override fun setVolume(level: Float): PlayerAudioLevel {
            sideAdjustments++
            return PlayerAudioLevel(level, false)
        }
    }

    private fun setContent() {
        compose.setContent {
            val unlocked = rememberUpdatedState(false)
            val noop = rememberUpdatedState<() -> Unit>({})
            Box(
                Modifier.size(320.dp, 180.dp)
                    .testTag("surface")
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                if (event.type == PointerEventType.Move) {
                                    event.changes.forEach { movesConsumed += it.isConsumed }
                                }
                            }
                        }
                    }
                    .playerSurfaceTapGestures(
                        layoutSize = IntSize(320, 180),
                        playbackGesturesEnabled = playbackReady.value,
                        gestureFlags = gestureFlags.value,
                        playerControlsLocked = false,
                        onSurfaceTap = rememberUpdatedState { _: Offset -> taps++ },
                        onSurfaceDoubleTap = rememberUpdatedState { offset: Offset ->
                            doubleTaps++
                            if (doubleTapStartsSeekStack && offset.x > 320f * PlayerRightGestureBoundary) {
                                seekStackDirection.value = PlayerSeekDirection.Forward
                            }
                        },
                        activeSeekStackDirectionState = seekStackDirection,
                        activateHoldToSpeedState = rememberUpdatedState { speedBoosts++ },
                        deactivateHoldToSpeedState = noop,
                        revealLockedOverlayState = noop,
                    )
                    .playerSurfaceDragGestures(
                        gestureController = gestureController,
                        playerController = null,
                        layoutSize = IntSize(320, 180),
                        playbackGesturesEnabled = playbackReady.value,
                        sideGestureSystemEdgeExclusionPx = 0f,
                        playerControlsLockedState = unlocked,
                        gestureFlagsState = gestureFlags,
                        isHoldToSpeedGestureActiveState = unlocked,
                        currentPositionMsState = rememberUpdatedState(30_000L),
                        currentDurationMsState = rememberUpdatedState(120_000L),
                        deactivateHoldToSpeedState = noop,
                        showHorizontalSeekPreviewState = rememberUpdatedState { _: Long, _: Long -> seekPreviews++ },
                        showBrightnessFeedbackState = rememberUpdatedState { _: Float -> brightnessFeedback++ },
                        showVolumeFeedbackState = rememberUpdatedState { _: PlayerAudioLevel -> volumeFeedback++ },
                        clearLiveGestureFeedbackState = noop,
                        revealLockedOverlayState = noop,
                        commitHorizontalSeekState = rememberUpdatedState { _: Long -> seekCommits++ },
                    ),
            )
        }
    }

    private fun performPlaybackGestures() {
        val surface = compose.onNodeWithTag("surface")
        surface.performTouchInput { doubleClick(Offset(width * 0.85f, centerY)) }
        surface.performTouchInput { swipe(center, Offset(width * 0.85f, centerY)) }
        surface.performTouchInput { longClick(center) }
        surface.performTouchInput { swipe(Offset(width * 0.1f, centerY), Offset(width * 0.1f, height * 0.1f)) }
        surface.performTouchInput { swipe(Offset(width * 0.9f, centerY), Offset(width * 0.9f, height * 0.1f)) }
    }

    @Test
    fun loadingIgnoresPlaybackGesturesButAllowsTappingControls() {
        setContent()
        compose.onNodeWithTag("surface").performTouchInput { click(center) }
        performPlaybackGestures()
        compose.runOnIdle {
            assertTrue(taps > 0)
            assertEquals(0, doubleTaps)
            assertEquals(0, speedBoosts)
            assertEquals(0, seekPreviews)
            assertEquals(0, seekCommits)
            assertEquals(0, sideAdjustments)
        }
    }

    @Test
    fun completingInitialLoadEnablesPlaybackGestures() {
        setContent()
        compose.runOnIdle { playbackReady.value = true }
        performPlaybackGestures()
        compose.runOnIdle {
            assertEquals(1, doubleTaps)
            assertEquals(1, speedBoosts)
            assertTrue(seekPreviews > 0)
            assertEquals(1, seekCommits)
            assertTrue(sideAdjustments > 0)
        }
    }

    @Test
    fun loadingAnotherSourceCancelsAnInProgressSeek() {
        playbackReady.value = true
        setContent()
        val surface = compose.onNodeWithTag("surface")
        surface.performTouchInput {
            down(center)
            moveTo(Offset(width * 0.85f, centerY))
        }
        compose.runOnIdle {
            assertTrue(seekPreviews > 0)
            playbackReady.value = false
        }
        surface.performTouchInput { up() }
        compose.runOnIdle { assertEquals(0, seekCommits) }
    }

    @Test
    fun disabledSideSwipesLeaveTheDragUnconsumedAndSilent() {
        playbackReady.value = true
        gestureFlags.value = gestureFlags.value.copy(brightnessSwipe = false, volumeSwipe = false)
        setContent()
        val surface = compose.onNodeWithTag("surface")
        surface.performTouchInput {
            down(Offset(width * 0.1f, centerY))
            moveTo(Offset(width * 0.1f, height * 0.1f))
        }
        compose.runOnIdle {
            assertEquals(0, sideAdjustments)
            assertEquals(0, brightnessFeedback)
            assertEquals(0, volumeFeedback)
            assertTrue(movesConsumed.isNotEmpty())
            assertTrue(movesConsumed.none { it }, "vertical drag must not be consumed")
        }
        surface.performTouchInput { up() }
    }

    @Test
    fun swappedSidesPutVolumeOnTheLeft() {
        playbackReady.value = true
        gestureFlags.value = gestureFlags.value.copy(swapSides = true, brightnessSwipe = false)
        setContent()
        val surface = compose.onNodeWithTag("surface")
        surface.performTouchInput {
            swipe(Offset(width * 0.9f, centerY), Offset(width * 0.9f, height * 0.1f))
        }
        compose.runOnIdle {
            assertEquals(0, volumeFeedback)
            assertEquals(0, brightnessFeedback)
        }
        surface.performTouchInput {
            swipe(Offset(width * 0.1f, centerY), Offset(width * 0.1f, height * 0.1f))
        }
        compose.runOnIdle {
            assertTrue(volumeFeedback > 0)
            assertEquals(0, brightnessFeedback)
        }
    }

    @Test
    fun doubleTapAndHoldAreIgnoredWhenTheirTogglesAreOff() {
        playbackReady.value = true
        gestureFlags.value = gestureFlags.value.copy(doubleTapSeek = false, holdToSpeed = false)
        setContent()
        val surface = compose.onNodeWithTag("surface")
        surface.performTouchInput { doubleClick(Offset(width * 0.85f, centerY)) }
        surface.performTouchInput { longClick(center) }
        compose.runOnIdle {
            assertEquals(0, doubleTaps)
            assertEquals(0, speedBoosts)
        }
    }

    @Test
    fun singleTapsDuringAStackingSeekEachAddAStepWithoutTogglingControls() {
        playbackReady.value = true
        doubleTapStartsSeekStack = true
        setContent()
        val surface = compose.onNodeWithTag("surface")
        surface.performTouchInput { doubleClick(Offset(width * 0.85f, centerY)) }
        compose.runOnIdle { assertEquals(1, doubleTaps) }
        surface.performTouchInput {
            advanceEventTime(150L)
            click(Offset(width * 0.85f, centerY))
        }
        compose.runOnIdle { assertEquals(2, doubleTaps, "third tap adds a step immediately") }
        surface.performTouchInput {
            advanceEventTime(150L)
            click(Offset(width * 0.85f, centerY))
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.runOnIdle {
            assertEquals(3, doubleTaps)
            assertEquals(0, taps, "stacking taps must not toggle the controls")
        }
    }
}
