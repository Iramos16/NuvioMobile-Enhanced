package com.nuvio.app.features.watchhistory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.i18n.localizedShortMonthName
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.watchHistoryMergePolicyLabel
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_history_approximate_date
import nuvio.composeapp.generated.resources.watch_history_empty
import nuvio.composeapp.generated.resources.watch_history_episode_label
import nuvio.composeapp.generated.resources.watch_history_last_updated
import nuvio.composeapp.generated.resources.watch_history_policy_title
import nuvio.composeapp.generated.resources.watch_history_refresh
import nuvio.composeapp.generated.resources.watch_history_refresh_failed
import nuvio.composeapp.generated.resources.watch_history_title
import nuvio.composeapp.generated.resources.watch_history_today
import nuvio.composeapp.generated.resources.watch_history_yesterday
import org.jetbrains.compose.resources.stringResource

@Composable
fun WatchHistoryScreen(
    onBack: () -> Unit,
    onOpenItem: (WatchedItem) -> Unit,
) {
    val uiState by remember {
        WatchHistoryRepository.ensureLoaded()
        WatchHistoryRepository.uiState
    }.collectAsStateWithLifecycle()
    // Coming back from Details re-runs this; only refresh when something is actually stale.
    LaunchedEffect(Unit) { WatchHistoryRepository.refresh(force = false) }

    val tokens = MaterialTheme.nuvio
    val timeZone = remember { TimeZone.currentSystemDefault() }
    val todayEpochDay by produceState(initialValue = currentEpochDay(timeZone)) {
        while (true) {
            delay(60_000L)
            value = currentEpochDay(timeZone)
        }
    }
    var showPolicyMenu by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.colors.background),
    ) {
        // Pinned outside the list so the day headers below can be sticky without pushing it away.
        NuvioScreenHeader(
            title = stringResource(Res.string.watch_history_title),
            modifier = Modifier.padding(horizontal = tokens.spacing.screenHorizontal),
            onBack = onBack,
            actions = {
                if (uiState.isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(12.dp).size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = { WatchHistoryRepository.refresh(force = true) }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(Res.string.watch_history_refresh),
                            tint = tokens.colors.textPrimary,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { showPolicyMenu = true }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(Res.string.watch_history_policy_title),
                            tint = tokens.colors.textPrimary,
                        )
                    }
                    DropdownMenu(
                        expanded = showPolicyMenu,
                        onDismissRequest = { showPolicyMenu = false },
                    ) {
                        WatchHistoryMergePolicy.entries.forEach { policy ->
                            DropdownMenuItem(
                                text = { Text(watchHistoryMergePolicyLabel(policy)) },
                                leadingIcon = {
                                    RadioButton(selected = policy == uiState.policy, onClick = null)
                                },
                                onClick = {
                                    TrackingSettingsRepository.setWatchHistoryMergePolicy(policy)
                                    showPolicyMenu = false
                                },
                            )
                        }
                    }
                }
            },
        )

        NuvioScreen(
            modifier = Modifier.weight(1f),
            topPadding = 0.dp,
        ) {
            if (uiState.failedSources.isNotEmpty()) {
                item(key = "failed") {
                    Text(
                        text = stringResource(
                            Res.string.watch_history_refresh_failed,
                            uiState.failedSources.sortedBy { it.ordinal }.joinToString { it.label() },
                        ),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            uiState.lastUpdatedEpochMs?.let { updatedAt ->
                item(key = "updated") {
                    Text(
                        text = stringResource(
                            Res.string.watch_history_last_updated,
                            formatDateTime(updatedAt, timeZone),
                        ),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (uiState.isLoaded && uiState.sections.isEmpty() && !uiState.isRefreshing) {
                item(key = "empty") {
                    Text(
                        text = stringResource(Res.string.watch_history_empty),
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            uiState.sections.forEach { section ->
                stickyHeader(key = "day:${section.epochDay}") {
                    Text(
                        text = dayLabel(section.epochDay, todayEpochDay),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tokens.colors.background)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(
                    items = section.entries,
                    key = { "entry:${watchHistoryWatchKey(it.item)}" },
                ) { entry ->
                    WatchHistoryRow(
                        entry = entry,
                        timeZone = timeZone,
                        onClick = { onOpenItem(entry.item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchHistoryRow(
    entry: WatchHistoryEntry,
    timeZone: TimeZone,
    onClick: () -> Unit,
) {
    val item = entry.item
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 66.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                if (!item.poster.isNullOrBlank()) {
                    AsyncImage(
                        model = item.poster,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val season = item.season
                val episode = item.episode
                val time = formatTime(entry.watchedAtEpochMs, timeZone)
                val approximate = entry.dateConfidence == WatchHistoryDateConfidence.APPROXIMATE
                Text(
                    text = buildString {
                        if (season != null && episode != null) {
                            append(stringResource(Res.string.watch_history_episode_label, season, episode))
                            append(" · ")
                        }
                        if (approximate) append("~")
                        append(time)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (approximate) {
                    Text(
                        text = stringResource(Res.string.watch_history_approximate_date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    entry.sources.sortedBy { it.ordinal }.forEach { source ->
                        SourceBadge(source = source, isPrimary = source == entry.primarySource)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceBadge(source: WatchHistorySourceId, isPrimary: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isPrimary) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (isPrimary) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = source.label(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun WatchHistorySourceId.label(): String = when (this) {
    WatchHistorySourceId.NUVIO -> "Nuvio"
    WatchHistorySourceId.TRAKT -> "Trakt"
    WatchHistorySourceId.SIMKL -> "Simkl"
    WatchHistorySourceId.ANILIST -> "AniList"
    WatchHistorySourceId.WETRAKR -> "WeTrakr"
}

@Composable
private fun dayLabel(epochDay: Long, todayEpochDay: Long): String = when (epochDay) {
    todayEpochDay -> stringResource(Res.string.watch_history_today)
    todayEpochDay - 1 -> stringResource(Res.string.watch_history_yesterday)
    else -> {
        val date = LocalDate.fromEpochDays(epochDay)
        "${date.day} ${localizedShortMonthName(date.month.number)} ${date.year}"
    }
}

private fun currentEpochDay(timeZone: TimeZone): Long =
    Instant.fromEpochMilliseconds(EpisodeReleaseDatePlatform.nowEpochMs())
        .toLocalDateTime(timeZone).date.toEpochDays()

private fun formatTime(epochMs: Long, timeZone: TimeZone): String {
    val time = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone)
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

private fun formatDateTime(epochMs: Long, timeZone: TimeZone): String {
    val dateTime = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone)
    return "${dateTime.date.day} ${localizedShortMonthName(dateTime.date.month.number)} ${formatTime(epochMs, timeZone)}"
}
