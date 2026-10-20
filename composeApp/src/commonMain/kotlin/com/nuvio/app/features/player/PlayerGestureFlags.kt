package com.nuvio.app.features.player

internal data class PlayerGestureFlags(
    val master: Boolean,
    val brightnessSwipe: Boolean,
    val volumeSwipe: Boolean,
    val holdToSpeed: Boolean,
    val doubleTapSeek: Boolean,
    val swipeToSeek: Boolean,
    val swapSides: Boolean,
)

internal val PlayerGestureFlags.doubleTapEnabled: Boolean
    get() = master && doubleTapSeek

internal val PlayerGestureFlags.holdToSpeedEnabled: Boolean
    get() = master && holdToSpeed

/** Returns the vertical-swipe gesture bound to the horizontal [xFraction] (0..1), or null if none applies. */
internal fun PlayerGestureFlags.sideGestureFor(xFraction: Float): PlayerSideGesture? {
    if (!master) return null
    val gesture = when {
        xFraction < PlayerLeftGestureBoundary ->
            if (swapSides) PlayerSideGesture.Volume else PlayerSideGesture.Brightness
        xFraction > PlayerRightGestureBoundary ->
            if (swapSides) PlayerSideGesture.Brightness else PlayerSideGesture.Volume
        else -> return null
    }
    return when (gesture) {
        PlayerSideGesture.Brightness -> gesture.takeIf { brightnessSwipe }
        PlayerSideGesture.Volume -> gesture.takeIf { volumeSwipe }
    }
}

internal fun PlayerSettingsUiState.toGestureFlags(): PlayerGestureFlags =
    PlayerGestureFlags(
        master = touchGesturesEnabled,
        brightnessSwipe = brightnessSwipeEnabled,
        volumeSwipe = volumeSwipeEnabled,
        holdToSpeed = holdToSpeedEnabled,
        doubleTapSeek = doubleTapSeekEnabled,
        swipeToSeek = swipeToSeekEnabled,
        swapSides = swapGestureSides,
    )
