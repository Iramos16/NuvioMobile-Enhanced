package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_audio_delay
import nuvio.composeapp.generated.resources.compose_player_audio_delay_decrease
import nuvio.composeapp.generated.resources.compose_player_audio_delay_increase
import nuvio.composeapp.generated.resources.compose_player_audio_delay_unavailable
import nuvio.composeapp.generated.resources.compose_player_audio_tracks
import nuvio.composeapp.generated.resources.compose_player_no_audio_tracks_available
import nuvio.composeapp.generated.resources.compose_player_reset
import org.jetbrains.compose.resources.stringResource

@Composable
fun AudioTrackModal(
    visible: Boolean,
    audioTracks: List<AudioTrack>,
    selectedIndex: Int,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    audioDelayMs: Int = 0,
    supportsAudioDelay: Boolean = false,
    onAudioDelayChanged: (Int) -> Unit = {},
) {
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 64.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val railWidth = minOf(maxWidth, 444.dp)
            val railMaxHeight = (maxHeight - 64.dp).coerceAtLeast(120.dp).coerceAtMost(620.dp)

            Column(
                modifier = Modifier
                    .width(railWidth)
                    .fillMaxHeight()
                    .align(Alignment.BottomStart),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = stringResource(Res.string.compose_player_audio_tracks),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                if (audioTracks.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.compose_player_no_audio_tracks_available),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .heightIn(max = railMaxHeight),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(audioTracks, key = { "${it.index}:${it.id}" }) { track ->
                            AudioTrackRow(
                                track = track,
                                isSelected = track.index == selectedIndex,
                                onClick = { onTrackSelected(track.index) },
                            )
                        }
                    }
                }

                AudioDelayRow(
                    delayMs = audioDelayMs,
                    enabled = supportsAudioDelay,
                    onDelayChanged = onAudioDelayChanged,
                )
            }
        }
    }
}

@Composable
private fun AudioDelayRow(
    delayMs: Int,
    enabled: Boolean,
    onDelayChanged: (Int) -> Unit,
) {
    val currentDelay by rememberUpdatedState(delayMs)
    val contentAlpha = if (enabled) 1f else 0.4f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(Res.string.compose_player_audio_delay),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AudioDelayStepButton(
                icon = Icons.Rounded.Remove,
                contentDescription = stringResource(Res.string.compose_player_audio_delay_decrease),
                enabled = enabled,
                onStep = { step -> onDelayChanged(clampAudioDelayMs(currentDelay - step)) },
            )
            Text(
                text = formatAudioDelay(delayMs),
                color = Color.White.copy(alpha = contentAlpha),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(96.dp),
                textAlign = TextAlign.Center,
            )
            AudioDelayStepButton(
                icon = Icons.Rounded.Add,
                contentDescription = stringResource(Res.string.compose_player_audio_delay_increase),
                enabled = enabled,
                onStep = { step -> onDelayChanged(clampAudioDelayMs(currentDelay + step)) },
            )
            Text(
                text = stringResource(Res.string.compose_player_reset),
                color = Color.White.copy(alpha = contentAlpha),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable(enabled = enabled) { onDelayChanged(0) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        if (!enabled) {
            Text(
                text = stringResource(Res.string.compose_player_audio_delay_unavailable),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val HOLD_REPEAT_INITIAL_DELAY_MS = 400L
private const val HOLD_REPEAT_INTERVAL_MS = 100L

/**
 * A − / + button: a tap steps once by [AUDIO_DELAY_STEP_MS]; holding repeats, with the step
 * growing per [holdRepeatStepMs] the longer it is held.
 */
@Composable
private fun AudioDelayStepButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onStep: (Int) -> Unit,
) {
    val currentOnStep by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
                if (enabled) {
                    onClick { currentOnStep(AUDIO_DELAY_STEP_MS); true }
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled) {
                // Consume presses so they never reach the overlay's tap-to-dismiss background.
                awaitEachGesture {
                    awaitFirstDown().consume()
                    if (!enabled) {
                        waitForUpOrCancellation()?.consume()
                        return@awaitEachGesture
                    }
                    val pressedAt = TimeSource.Monotonic.markNow()
                    currentOnStep(AUDIO_DELAY_STEP_MS)
                    val repeatJob = scope.launch {
                        delay(HOLD_REPEAT_INITIAL_DELAY_MS)
                        while (true) {
                            currentOnStep(holdRepeatStepMs(pressedAt.elapsedNow().inWholeMilliseconds))
                            delay(HOLD_REPEAT_INTERVAL_MS)
                        }
                    }
                    try {
                        waitForUpOrCancellation()?.consume()
                    } finally {
                        repeatJob.cancel()
                    }
                }
            }
            .padding(10.dp)
            .size(20.dp),
    )
}

@Composable
private fun AudioTrackRow(
    track: AudioTrack,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val primaryColor = if (isSelected) tokens.colors.onAccent else Color.White
    val secondaryColor = if (isSelected) {
        tokens.colors.onAccent.copy(alpha = 0.82f)
    } else {
        Color.White.copy(alpha = 0.72f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) tokens.colors.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = localizedTrackDisplayName(track.label, track.language, track.index),
                color = primaryColor,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            track.language?.takeIf { it.isNotBlank() && it != "und" }?.let { language ->
                Text(
                    text = languageLabelForCode(language),
                    color = secondaryColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = tokens.colors.onAccent,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(20.dp),
            )
        }
    }
}
