package com.pp.jiocall.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pp.jiocall.R
import com.pp.jiocall.data.Account
import com.pp.jiocall.data.CallLogEntry
import com.pp.jiocall.data.GooglePhoneColors
import com.pp.jiocall.sip.AccountStatus
import com.pp.jiocall.ui.components.CallHistoryDialog
import com.pp.jiocall.ui.components.ContactAvatar
import com.pp.jiocall.ui.components.ExpandableContactActions
import com.pp.jiocall.ui.components.HistoryTarget
import com.pp.jiocall.ui.components.RecordingPlaybackController
import com.pp.jiocall.ui.components.rememberRecordingPlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GroupedCallLog(
    val entry: CallLogEntry,
    val count: Int,
    val history: List<CallLogEntry>,
)

private enum class RecentsFilter(val label: String) {
    ALL("All"),
    MISSED("Missed"),
    RECORDED("Recorded"),
}

@Composable
fun RecentsScreen(
    calls: List<CallLogEntry>,
    onDial: (CallLogEntry) -> Unit,
    onClear: () -> Unit,
    resolveName: ((String) -> String?)? = null,
    onOpenDial: () -> Unit = {},
    accounts: List<Account> = emptyList(),
    statuses: Map<Long, AccountStatus> = emptyMap(),
    onOpenAccounts: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    isDark: Boolean = false,
    onToggleDarkMode: () -> Unit = {},
    resolvePhotoUri: ((String) -> String?)? = null,
    hasActiveCall: Boolean = false,
    precomputedItems: List<RecentsItemUi>? = null,
    sharedPlayback: RecordingPlaybackController? = null,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(RecentsFilter.ALL.name) }
    val activeFilter = remember(filterName) {
        runCatching { RecentsFilter.valueOf(filterName) }.getOrDefault(RecentsFilter.ALL)
    }
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var historyModal by remember { mutableStateOf<Pair<HistoryTarget, RecentsItemUi>?>(null) }

    val playback = sharedPlayback ?: rememberRecordingPlaybackController(calls = calls, hasActiveCall = hasActiveCall)

    val regStatus = remember(accounts, statuses, isDark) {
        GooglePhoneColors.getRegistrationUiStatus(accounts, statuses, isDark)
    }

    // Fallback path keeps standalone previews/tests working off Main thread
    var fallbackItems by remember { mutableStateOf<List<RecentsItemUi>>(emptyList()) }
    if (precomputedItems == null) {
        LaunchedEffect(calls, playback.availableRecordings) {
            fallbackItems = withContext(Dispatchers.Default) {
                val emptyIndex = ContactLookupIndex(emptyList())
                UiIndexBuilder.buildRecents(calls, emptyIndex, playback.availableRecordings)
            }
        }
    }
    val sourceItems = precomputedItems ?: fallbackItems

    val missedCount = remember(sourceItems) { sourceItems.count { it.entry.missed } }
    val recordedCount = remember(sourceItems) { sourceItems.count { it.hasRecording } }

    var displayedItems by remember(sourceItems) { mutableStateOf(sourceItems) }
    LaunchedEffect(sourceItems, searchQuery, activeFilter) {
        displayedItems = withContext(Dispatchers.Default) {
            val q = searchQuery.trim().lowercase()
            val byFilter = when (activeFilter) {
                RecentsFilter.ALL -> sourceItems
                RecentsFilter.MISSED -> sourceItems.filter { it.entry.missed }
                RecentsFilter.RECORDED -> sourceItems.filter { it.hasRecording }
            }
            if (q.isEmpty()) byFilter else byFilter.filter { it.searchKeyLower.contains(q) }
        }
    }

    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GooglePhoneColors.background(isDark)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            GooglePhoneSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search recent calls",
                voicePrompt = "Search recent calls",
                isDark = isDark,
                regStatus = regStatus,
                onOpenAccounts = onOpenAccounts,
                onToggleDarkMode = onToggleDarkMode,
                onOpenSettings = onOpenSettings,
                extraMenuItems = { dismissMenu ->
                    DropdownMenuItem(
                        text = { Text("Clear call history", color = GooglePhoneColors.textPrimary(isDark)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = GooglePhoneColors.textSecondary(isDark),
                            )
                        },
                        enabled = calls.isNotEmpty(),
                        onClick = {
                            dismissMenu()
                            showClearDialog = true
                        },
                    )
                },
            )

            Spacer(Modifier.height(10.dp))

            // Quick Filter Chips Row (All / Missed / Recorded)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RecentsFilter.entries.forEach { filter ->
                    val count = when (filter) {
                        RecentsFilter.ALL -> sourceItems.size
                        RecentsFilter.MISSED -> missedCount
                        RecentsFilter.RECORDED -> recordedCount
                    }
                    val selected = activeFilter == filter
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) GooglePhoneColors.chipSelectedBg(isDark) else GooglePhoneColors.chipBg(isDark),
                        border = BorderStroke(
                            1.dp,
                            if (selected) GooglePhoneColors.highlight(isDark).copy(alpha = 0.45f) else GooglePhoneColors.cardBorder(isDark),
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { filterName = filter.name },
                    ) {
                        Text(
                            text = if (count > 0 && filter != RecentsFilter.ALL) "${filter.label} ($count)" else filter.label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) GooglePhoneColors.highlight(isDark) else GooglePhoneColors.textSecondary(isDark),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (displayedItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(GooglePhoneColors.surface(isDark)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_call),
                                contentDescription = null,
                                tint = GooglePhoneColors.textSecondary(isDark),
                                modifier = Modifier.size(34.dp),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = when {
                                searchQuery.isNotEmpty() -> "No matching calls"
                                activeFilter == RecentsFilter.MISSED -> "No missed calls"
                                activeFilter == RecentsFilter.RECORDED -> "No recorded calls"
                                else -> "No recent calls"
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = GooglePhoneColors.textPrimary(isDark),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) {
                                "Try a different name or number"
                            } else {
                                "Calls you make and receive will appear here"
                            },
                            fontSize = 14.sp,
                            color = GooglePhoneColors.textSecondary(isDark),
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        items = displayedItems,
                        key = { it.key },
                        contentType = { "recent_row" },
                    ) { item ->
                        val entry = item.entry
                        val isExpanded = expandedKey == item.key
                        val displayName = item.displayName
                        val nameWithCount = if (item.count > 1) "$displayName (${item.count})" else displayName
                        val isMissed = entry.missed

                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (item.isFirstInBucket && searchQuery.isEmpty() && activeFilter == RecentsFilter.ALL) {
                                Text(
                                    text = item.dateBucket,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = GooglePhoneColors.highlight(isDark),
                                    modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 4.dp),
                                )
                            }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable {
                                        expandedKey = if (isExpanded) null else item.key
                                    },
                                shape = RoundedCornerShape(18.dp),
                                color = if (isExpanded) GooglePhoneColors.surface(isDark) else GooglePhoneColors.surfaceElevated(isDark),
                                border = BorderStroke(
                                    1.dp,
                                    if (isExpanded) GooglePhoneColors.highlight(isDark).copy(alpha = 0.35f) else GooglePhoneColors.cardBorder(isDark),
                                ),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        ContactAvatar(
                                            name = displayName,
                                            photoUri = item.photoUri,
                                            size = 44.dp,
                                        )

                                        Spacer(Modifier.width(14.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = nameWithCount,
                                                fontSize = 15.5.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isMissed) Color(0xFFE53935) else GooglePhoneColors.textPrimary(isDark),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(Modifier.height(3.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = when {
                                                        isMissed -> "↙"
                                                        entry.outgoing -> "↗"
                                                        else -> "↙"
                                                    },
                                                    fontSize = 13.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = when {
                                                        isMissed -> Color(0xFFE53935)
                                                        entry.outgoing -> GooglePhoneColors.highlight(isDark)
                                                        else -> GooglePhoneColors.CallGreen
                                                    },
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = buildString {
                                                        if (entry.accountTitle.isNotEmpty()) {
                                                            append(entry.accountTitle)
                                                            append(" • ")
                                                        }
                                                        append(item.timeText)
                                                        if (item.durationText.isNotEmpty()) {
                                                            append(" • ")
                                                            append(item.durationText)
                                                        }
                                                        if (item.hasRecording) {
                                                            append(" • Rec")
                                                        }
                                                    },
                                                    fontSize = 12.5.sp,
                                                    color = GooglePhoneColors.textSecondary(isDark),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }

                                        val recPath = entry.recordingPath
                                        if (recPath != null && item.hasRecording) {
                                            val isPlaying = playback.playingPath == recPath
                                            IconButton(
                                                onClick = { playback.toggle(recPath) },
                                                modifier = Modifier.size(36.dp),
                                            ) {
                                                Icon(
                                                    painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                                                    contentDescription = if (isPlaying) "Stop Recording" else "Play Recording",
                                                    tint = if (isPlaying) Color(0xFFD32F2F) else GooglePhoneColors.highlight(isDark),
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }

                                        Spacer(Modifier.width(4.dp))

                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(GooglePhoneColors.chipBg(isDark))
                                                .clickable {
                                                    playback.stop()
                                                    expandedKey = null
                                                    onDial(entry)
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_call),
                                                contentDescription = "Call $displayName",
                                                tint = GooglePhoneColors.CallGreen,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }

                                    ExpandableContactActions(
                                        visible = isExpanded,
                                        number = entry.number,
                                        isDark = isDark,
                                        recentCount = item.matchingCount,
                                        subtitle = if (item.showNumberSubtitle) item.formattedNumber else null,
                                        whatsAppEnabled = item.whatsAppEnabled,
                                        onShowAllRecents = {
                                            historyModal = HistoryTarget(title = displayName, number = entry.number) to item
                                        },
                                        onCall = {
                                            playback.stop()
                                            expandedKey = null
                                            onDial(entry)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onOpenDial,
            containerColor = GooglePhoneColors.fabContainer(isDark),
            contentColor = GooglePhoneColors.fabContent(isDark),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 22.dp)
                .size(56.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_dialpad),
                contentDescription = "Open Dialpad",
                modifier = Modifier.size(24.dp),
            )
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("Clear call history?") },
                text = { Text("This will also remove saved call recordings from this device.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearDialog = false
                            playback.stop()
                            expandedKey = null
                            onClear()
                        },
                    ) { Text("Clear", color = Color(0xFFD32F2F)) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
                },
            )
        }

        historyModal?.let { (target, recentsItem) ->
            CallHistoryDialog(
                target = target,
                history = recentsItem.matchingHistory,
                isDark = isDark,
                playback = playback,
                onCall = {
                    playback.stop()
                    historyModal = null
                    expandedKey = null
                    onDial(recentsItem.entry)
                },
                onDismiss = { historyModal = null },
            )
        }
    }
}
