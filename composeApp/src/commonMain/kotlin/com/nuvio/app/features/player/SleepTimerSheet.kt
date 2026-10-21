package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.sleep_timer_end_of_episode
import nuvio.composeapp.generated.resources.sleep_timer_minutes
import nuvio.composeapp.generated.resources.sleep_timer_off
import nuvio.composeapp.generated.resources.sleep_timer_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SleepTimerSheet(
    visible: Boolean,
    currentMode: SleepTimerMode,
    showEndOfEpisode: Boolean,
    onSelectMinutes: (Int) -> Unit,
    onSelectMode: (SleepTimerMode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 64.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(minOf(maxWidth, 444.dp))
                    .fillMaxHeight()
                    .align(Alignment.BottomStart),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = stringResource(Res.string.sleep_timer_title),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SleepTimerPresetsMinutes.forEach { minutes ->
                        SleepTimerOptionRow(
                            label = stringResource(Res.string.sleep_timer_minutes, minutes),
                            selected = false,
                            onClick = { onSelectMinutes(minutes) },
                        )
                    }
                    if (showEndOfEpisode) {
                        SleepTimerOptionRow(
                            label = stringResource(Res.string.sleep_timer_end_of_episode),
                            selected = currentMode == SleepTimerMode.EndOfEpisode,
                            onClick = { onSelectMode(SleepTimerMode.EndOfEpisode) },
                        )
                    }
                    SleepTimerOptionRow(
                        label = stringResource(Res.string.sleep_timer_off),
                        selected = currentMode == SleepTimerMode.Off,
                        onClick = { onSelectMode(SleepTimerMode.Off) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepTimerOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = label,
        color = if (selected) tokens.colors.onAccent else Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) tokens.colors.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    )
}
