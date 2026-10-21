package com.nuvio.app.features.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_history_open
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryWatchHistoryButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Rounded.History,
            contentDescription = stringResource(Res.string.watch_history_open),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
