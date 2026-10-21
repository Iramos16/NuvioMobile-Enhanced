package com.nuvio.app.features.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.core.logging.InAppLogger
import kotlin.math.abs
import kotlin.math.roundToLong

internal fun Modifier.playerSurfaceTapGestures(
    layoutSize: IntSize,
    playbackGesturesEnabled: Boolean,
    gestureFlags: PlayerGestureFlags,
    playerControlsLocked: Boolean,
    onSurfaceTap: State<(Offset) -> Unit>,
    onSurfaceDoubleTap: State<(Offset) -> Unit>,
    activeSeekStackDirectionState: State<PlayerSeekDirection?>,
    activateHoldToSpeedState: State<() -> Unit>,
    deactivateHoldToSpeedState: State<() -> Unit>,
    revealLockedOverlayState: State<() -> Unit>,
): Modifier =
    pointerInput(
        layoutSize,
        playbackGesturesEnabled,
        gestureFlags.doubleTapEnabled,
        gestureFlags.holdToSpeedEnabled,
        playerControlsLocked,
    ) {
        detectTapGestures(
            onPress = {
                tryAwaitRelease()
                deactivateHoldToSpeedState.value()
            },
            onTap = { offset -> onSurfaceTap.value(offset) },
            onDoubleTap = if (playbackGesturesEnabled && gestureFlags.doubleTapEnabled) {
                { offset -> onSurfaceDoubleTap.value(offset) }
            } else null,
            onLongPress = if (playbackGesturesEnabled && (gestureFlags.holdToSpeedEnabled || playerControlsLocked)) {
                {
                    if (playerControlsLocked) {
                        revealLockedOverlayState.value()
                    } else {
                        activateHoldToSpeedState.value()
                    }
                }
            } else null,
        )
    }.playerSurfaceSeekStackTaps(
        playbackGesturesEnabled = playbackGesturesEnabled,
        doubleTapEnabled = gestureFlags.doubleTapEnabled,
        playerControlsLocked = playerControlsLocked,
        onSurfaceDoubleTap = onSurfaceDoubleTap,
        activeSeekStackDirectionState = activeSeekStackDirectionState,
    )

/**
 * While a double-tap seek is still stacking, a single tap on the same side adds one more step at
 * once instead of waiting out the double-tap timeout (which would toggle the controls). It sits
 * inside the tap detector and consumes the tap in the main pass, so the detector never sees it,
 * while buttons drawn above the surface still get their taps first.
 */
private fun Modifier.playerSurfaceSeekStackTaps(
    playbackGesturesEnabled: Boolean,
    doubleTapEnabled: Boolean,
    playerControlsLocked: Boolean,
    onSurfaceDoubleTap: State<(Offset) -> Unit>,
    activeSeekStackDirectionState: State<PlayerSeekDirection?>,
): Modifier =
    pointerInput(playbackGesturesEnabled, doubleTapEnabled, playerControlsLocked) {
        if (!playbackGesturesEnabled || !doubleTapEnabled || playerControlsLocked) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Main)
            val direction = activeSeekStackDirectionState.value ?: return@awaitEachGesture
            val width = size.width.toFloat().takeIf { it > 0f } ?: return@awaitEachGesture
            val tapDirection = when {
                down.position.x < width * PlayerLeftGestureBoundary -> PlayerSeekDirection.Backward
                down.position.x > width * PlayerRightGestureBoundary -> PlayerSeekDirection.Forward
                else -> null
            }
            if (tapDirection != direction) return@awaitEachGesture
            down.consume()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) {
                    change.consume()
                    onSurfaceDoubleTap.value(down.position)
                    return@awaitEachGesture
                }
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    return@awaitEachGesture
                }
                change.consume()
            }
        }
    }

internal fun Modifier.playerSurfaceDragGestures(
    gestureController: PlayerGestureController?,
    playerController: PlayerEngineController?,
    layoutSize: IntSize,
    playbackGesturesEnabled: Boolean,
    sideGestureSystemEdgeExclusionPx: Float,
    playerControlsLockedState: State<Boolean>,
    gestureFlagsState: State<PlayerGestureFlags>,
    isHoldToSpeedGestureActiveState: State<Boolean>,
    currentPositionMsState: State<Long>,
    currentDurationMsState: State<Long>,
    deactivateHoldToSpeedState: State<() -> Unit>,
    showHorizontalSeekPreviewState: State<(Long, Long) -> Unit>,
    showBrightnessFeedbackState: State<(Float) -> Unit>,
    showVolumeFeedbackState: State<(PlayerAudioLevel) -> Unit>,
    clearLiveGestureFeedbackState: State<() -> Unit>,
    revealLockedOverlayState: State<() -> Unit>,
    commitHorizontalSeekState: State<(Long) -> Unit>,
): Modifier =
    pointerInput(
        gestureController,
        playerController,
        layoutSize,
        sideGestureSystemEdgeExclusionPx,
        playbackGesturesEnabled,
    ) {
        if (!playbackGesturesEnabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown()
            if (playerControlsLockedState.value) {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                }
                return@awaitEachGesture
            }
            val gestureFlags = gestureFlagsState.value
            if (!gestureFlags.master) {
                return@awaitEachGesture
            }
            val controller = gestureController
            val width = size.width.toFloat().takeIf { it > 0f } ?: return@awaitEachGesture
            val height = size.height.toFloat().takeIf { it > 0f } ?: return@awaitEachGesture
            val sideGestureEdgeExclusionPx = sideGestureSystemEdgeExclusionPx
                .coerceAtMost(height * 0.25f)
            val isInSideGestureSystemEdge =
                down.position.y <= sideGestureEdgeExclusionPx ||
                    down.position.y >= height - sideGestureEdgeExclusionPx
            val region = if (isInSideGestureSystemEdge) {
                null
            } else {
                gestureFlags.sideGestureFor(down.position.x / width)
            }

            val initialBrightness = if (region == PlayerSideGesture.Brightness) {
                controller?.currentBrightness()
            } else {
                null
            }
            val initialVolume = if (region == PlayerSideGesture.Volume) {
                currentCombinedVolumeLevel(
                    systemVolume = controller?.currentVolume(),
                    softwareVolume = playerController?.currentPlayerVolume(),
                )
            } else {
                null
            }

            var totalDx = 0f
            var totalDy = 0f
            var gestureMode: PlayerGestureMode? = null
            var verticalGestureActivationDy = 0f
            val horizontalSeekBaselineMs = currentPositionMsState.value
            var horizontalSeekPreviewMs = horizontalSeekBaselineMs
            var lastVolumeTarget: Float? = null
            var interruptedByPinch = false

            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) {
                    interruptedByPinch = true
                    break
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

                val delta = change.position - change.previousPosition
                totalDx += delta.x
                totalDy += delta.y

                if (gestureMode == null) {
                    val holdToSpeedActive = isHoldToSpeedGestureActiveState.value
                    val verticalGestureActivationSlop = maxOf(
                        viewConfiguration.touchSlop * PlayerVerticalGestureTouchSlopMultiplier,
                        height * PlayerVerticalGestureMinHeightFraction,
                    )
                    val horizontalGestureActivationSlop =
                        viewConfiguration.touchSlop * PlayerHorizontalGestureTouchSlopMultiplier
                    val horizontalDominant =
                        !holdToSpeedActive &&
                            gestureFlags.swipeToSeek &&
                            abs(totalDx) > horizontalGestureActivationSlop &&
                            abs(totalDx) > abs(totalDy) * PlayerHorizontalGestureDominanceRatio
                    val verticalDominant =
                        !holdToSpeedActive &&
                            abs(totalDy) > verticalGestureActivationSlop &&
                            abs(totalDy) > abs(totalDx) * PlayerVerticalGestureDominanceRatio

                    gestureMode = when {
                        horizontalDominant -> {
                            deactivateHoldToSpeedState.value()
                            PlayerGestureMode.HorizontalSeek
                        }

                        verticalDominant && region == PlayerSideGesture.Brightness && initialBrightness != null -> {
                            verticalGestureActivationDy = totalDy
                            PlayerGestureMode.Brightness
                        }

                        verticalDominant && region == PlayerSideGesture.Volume && initialVolume != null -> {
                            verticalGestureActivationDy = totalDy
                            PlayerGestureMode.Volume
                        }

                        else -> null
                    }

                    if (gestureMode == null) {
                        continue
                    }
                }

                when (gestureMode) {
                    PlayerGestureMode.HorizontalSeek -> {
                        val sensitivitySeconds = when {
                            currentDurationMsState.value >= 3_600_000L -> 120f
                            currentDurationMsState.value >= 1_800_000L -> 90f
                            else -> 60f
                        }
                        val previewOffsetMs =
                            ((totalDx / width) * sensitivitySeconds * 1000f).roundToLong()
                        val unclampedPreviewMs = horizontalSeekBaselineMs + previewOffsetMs
                        horizontalSeekPreviewMs = currentDurationMsState.value
                            .takeIf { it > 0L }
                            ?.let { durationMs ->
                                unclampedPreviewMs.coerceIn(0L, durationMs)
                            }
                            ?: unclampedPreviewMs.coerceAtLeast(0L)
                        showHorizontalSeekPreviewState.value(
                            horizontalSeekPreviewMs,
                            horizontalSeekBaselineMs,
                        )
                    }

                    PlayerGestureMode.Brightness -> {
                        val activeTotalDy = totalDy - verticalGestureActivationDy
                        val gestureDeltaFraction =
                            (-activeTotalDy / height) * PlayerVerticalGestureSensitivity
                        controller?.setBrightness((initialBrightness ?: 0f) + gestureDeltaFraction)
                            ?.let(showBrightnessFeedbackState.value)
                    }

                    PlayerGestureMode.Volume -> {
                        val activeTotalDy = totalDy - verticalGestureActivationDy
                        val gestureDeltaFraction =
                            (-activeTotalDy / height) * PlayerVerticalGestureSensitivity
                        val target = ((initialVolume?.fraction ?: 0f) + gestureDeltaFraction)
                            .coerceIn(0f, PlayerMaxVolumeBoost)
                        lastVolumeTarget = target
                        setCombinedVolumeLevel(
                            target = target,
                            systemController = controller,
                            engineController = playerController,
                        )?.let(showVolumeFeedbackState.value)
                    }
                }
                change.consume()
            }

            lastVolumeTarget?.let { target ->
                val system = target.coerceAtMost(PlayerNormalVolumeCeiling)
                val software = target.coerceAtLeast(PlayerNormalVolumeCeiling)
                InAppLogger.debug(
                    "Player/Gesture",
                    "volume gesture end target=${formatVolumeFraction(target)} " +
                        "system=${formatVolumeFraction(system)} software=${formatVolumeFraction(software)}",
                )
            }

            if (interruptedByPinch) {
                if (gestureMode == PlayerGestureMode.HorizontalSeek) clearLiveGestureFeedbackState.value()
                return@awaitEachGesture
            }

            if (gestureMode == PlayerGestureMode.HorizontalSeek && !isHoldToSpeedGestureActiveState.value) {
                commitHorizontalSeekState.value(horizontalSeekPreviewMs)
                clearLiveGestureFeedbackState.value()
            }
        }
    }

internal fun Modifier.playerSurfacePinchGestures(
    playbackGesturesEnabled: Boolean,
    playerControlsLockedState: State<Boolean>,
    gestureFlagsState: State<PlayerGestureFlags>,
    onPinchZoomState: State<(zoomFactor: Float) -> Unit>,
): Modifier =
    pointerInput(playbackGesturesEnabled) {
        if (!playbackGesturesEnabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val multiTouch = event.changes.count { it.pressed } > 1
                if (multiTouch && !playerControlsLockedState.value && gestureFlagsState.value.master) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) onPinchZoomState.value(zoom)
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }

private fun formatVolumeFraction(value: Float): String {
    val hundredths = (value * 100f).roundToLong()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
