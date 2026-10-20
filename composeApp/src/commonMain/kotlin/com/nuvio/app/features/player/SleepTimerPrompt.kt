package com.nuvio.app.features.player

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.sleep_timer_extend
import nuvio.composeapp.generated.resources.sleep_timer_resume
import nuvio.composeapp.generated.resources.sleep_timer_still_watching
import org.jetbrains.compose.resources.stringResource

/** Shows "Still watching?" only in full screen; while hidden the timer stays paused and pending. */
@Composable
internal fun SleepTimerPromptHost(
    pending: Boolean,
    inPip: Boolean,
    miniPlayerActive: Boolean,
    onExtend: () -> Unit,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!shouldShowSleepPrompt(pending, inPip, miniPlayerActive)) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sleep_timer_still_watching)) },
        confirmButton = {
            TextButton(onClick = onExtend) { Text(stringResource(Res.string.sleep_timer_extend)) }
        },
        dismissButton = {
            TextButton(onClick = onResume) { Text(stringResource(Res.string.sleep_timer_resume)) }
        },
    )
}
