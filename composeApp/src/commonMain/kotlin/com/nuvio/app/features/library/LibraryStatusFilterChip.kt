package com.nuvio.app.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.tracking.TrackingListStatus
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_filter_status
import nuvio.composeapp.generated.resources.library_status_all
import nuvio.composeapp.generated.resources.library_status_completed
import nuvio.composeapp.generated.resources.library_status_count
import nuvio.composeapp.generated.resources.library_status_dropped
import nuvio.composeapp.generated.resources.library_status_on_hold
import nuvio.composeapp.generated.resources.library_status_plan_to_watch
import nuvio.composeapp.generated.resources.library_status_watching
import org.jetbrains.compose.resources.stringResource

/** Multi-select twin of NuvioDropdownChip: the sheet stays open while statuses are toggled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryStatusFilterChip(
    selected: Set<TrackingListStatus>,
    onSelectedChange: (Set<TrackingListStatus>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    var isSheetVisible by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val label = when (selected.size) {
        0 -> stringResource(Res.string.library_status_all)
        1 -> libraryStatusLabel(selected.first())
        else -> stringResource(Res.string.library_status_count, selected.size)
    }

    Row(
        modifier = modifier
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.surface)
            .clickable { isSheetVisible = true }
            .padding(horizontal = NuvioTokens.Space.s12, vertical = tokens.components.chipVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(NuvioTokens.Icon.sm + NuvioTokens.Space.s2),
            tint = tokens.colors.textMuted,
        )
    }

    if (isSheetVisible) {
        NuvioModalBottomSheet(
            onDismissRequest = {
                coroutineScope.launch {
                    dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = { isSheetVisible = false })
                }
            },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = nuvioSafeBottomPadding(tokens.spacing.screenHorizontal)),
            ) {
                Text(
                    text = stringResource(Res.string.library_filter_status),
                    modifier = Modifier.padding(
                        horizontal = tokens.spacing.screenHorizontal,
                        vertical = NuvioTokens.Space.s14,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                )
                NuvioBottomSheetDivider()
                TrackingListStatus.entries.forEach { status ->
                    val checked = status in selected
                    val toggle = { onSelectedChange(if (checked) selected - status else selected + status) }
                    NuvioBottomSheetActionRow(
                        title = libraryStatusLabel(status),
                        onClick = toggle,
                        selected = checked,
                        trailingContent = {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { toggle() },
                                colors = CheckboxDefaults.colors(checkedColor = tokens.colors.accent),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun libraryStatusLabel(status: TrackingListStatus): String =
    when (status) {
        TrackingListStatus.WATCHING -> stringResource(Res.string.library_status_watching)
        TrackingListStatus.PLAN_TO_WATCH -> stringResource(Res.string.library_status_plan_to_watch)
        TrackingListStatus.ON_HOLD -> stringResource(Res.string.library_status_on_hold)
        TrackingListStatus.COMPLETED -> stringResource(Res.string.library_status_completed)
        TrackingListStatus.DROPPED -> stringResource(Res.string.library_status_dropped)
    }
