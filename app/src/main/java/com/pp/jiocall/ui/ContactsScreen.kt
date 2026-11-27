package com.pp.jiocall.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pp.jiocall.R
import com.pp.jiocall.data.Account
import com.pp.jiocall.data.CallLogEntry
import com.pp.jiocall.data.ContactItem
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

@Composable
fun ContactsScreen(
    contacts: List<ContactItem>,
    onImportContacts: () -> Unit,
    onDial: (String) -> Unit,
    onOpenDial: () -> Unit = {},
    accounts: List<Account> = emptyList(),
    statuses: Map<Long, AccountStatus> = emptyMap(),
    onOpenAccounts: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    isDark: Boolean = false,
    onToggleDarkMode: () -> Unit = {},
    calls: List<CallLogEntry> = emptyList(),
    hasActiveCall: Boolean = false,
    precomputedRows: List<ContactRowUi>? = null,
    sharedPlayback: RecordingPlaybackController? = null,
    onSyncContactsIfNeeded: () -> Unit = onImportContacts,
) {
    val context = LocalContext.current
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var historyRow by remember { mutableStateOf<ContactRowUi?>(null) }
    val playback = sharedPlayback ?: rememberRecordingPlaybackController(calls = calls, hasActiveCall = hasActiveCall)

    val regStatus = remember(accounts, statuses, isDark) {
        GooglePhoneColors.getRegistrationUiStatus(accounts, statuses, isDark)
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) {
            onImportContacts()
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            onSyncContactsIfNeeded()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_CONTACTS,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted && !hasPermission) {
                    hasPermission = true
                    onImportContacts()
                } else {
                    hasPermission = granted
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var searchQuery by rememberSaveable { mutableStateOf("") }

    var fallbackRows by remember { mutableStateOf<List<ContactRowUi>>(emptyList()) }
    if (precomputedRows == null) {
        LaunchedEffect(contacts, calls) {
            fallbackRows = withContext(Dispatchers.Default) {
                UiIndexBuilder.buildContacts(contacts, calls)
            }
        }
    }
    val allRows = precomputedRows ?: fallbackRows

    var filteredRows by remember(allRows) { mutableStateOf(allRows) }
    LaunchedEffect(allRows, searchQuery) {
        filteredRows = withContext(Dispatchers.Default) {
            val q = searchQuery.trim().lowercase()
            if (q.isEmpty()) {
                allRows
            } else {
                val matching = allRows.filter { it.searchKeyLower.contains(q) || it.contact.matches(q) }
                // Recompute first-in-group flag on search subset without expensive allocations
                var lastInit = ""
                matching.map { row ->
                    val first = row.initial != lastInit
                    lastInit = row.initial
                    if (row.isFirstOfGroup == first) row else row.copy(isFirstOfGroup = first)
                }
            }
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
                placeholder = "Search contacts",
                voicePrompt = "Search contacts",
                isDark = isDark,
                regStatus = regStatus,
                onOpenAccounts = onOpenAccounts,
                onToggleDarkMode = onToggleDarkMode,
                onOpenSettings = onOpenSettings,
            )

            if (!hasPermission) {
                Spacer(Modifier.height(28.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = GooglePhoneColors.surfaceElevated(isDark)),
                    border = BorderStroke(1.dp, GooglePhoneColors.cardBorder(isDark)),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(GooglePhoneColors.chipBg(isDark)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_person),
                                contentDescription = null,
                                tint = GooglePhoneColors.highlight(isDark),
                                modifier = Modifier.size(34.dp),
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Access device contacts",
                            style = MaterialTheme.typography.titleLarge,
                            color = GooglePhoneColors.textPrimary(isDark),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Grant permission to search, view and call your phone contacts seamlessly through JioFiber.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GooglePhoneColors.textSecondary(isDark),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { launcher.launch(Manifest.permission.READ_CONTACTS) },
                            colors = ButtonDefaults.buttonColors(containerColor = GooglePhoneColors.highlight(isDark)),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Text("Allow access", color = if (isDark) Color(0xFF041E49) else Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            runCatching {
                                val intent = Intent(Intent.ACTION_INSERT).apply {
                                    type = ContactsContract.Contacts.CONTENT_TYPE
                                }
                                context.startActivity(intent)
                            }
                        },
                    shape = RoundedCornerShape(16.dp),
                    color = GooglePhoneColors.chipBg(isDark),
                    border = BorderStroke(1.dp, GooglePhoneColors.cardBorder(isDark)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(GooglePhoneColors.highlight(isDark).copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Create new contact",
                                tint = GooglePhoneColors.highlight(isDark),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = "Create new contact",
                            color = GooglePhoneColors.highlight(isDark),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        items = filteredRows,
                        key = { it.key },
                        contentType = { "contact_row" },
                    ) { row ->
                        val contact = row.contact
                        val isExpanded = expandedKey == row.key

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable {
                                    expandedKey = if (isExpanded) null else row.key
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
                                        .padding(vertical = 10.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier.width(30.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (row.isFirstOfGroup) {
                                            Text(
                                                text = row.initial,
                                                color = GooglePhoneColors.highlight(isDark),
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }

                                    ContactAvatar(
                                        contact = contact,
                                        size = 42.dp,
                                    )

                                    Spacer(Modifier.width(14.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = contact.name,
                                            fontSize = 15.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = GooglePhoneColors.textPrimary(isDark),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (row.formattedNumber.isNotBlank()) {
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = row.formattedNumber,
                                                fontSize = 12.5.sp,
                                                color = GooglePhoneColors.textSecondary(isDark),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }

                                    if (contact.number.isNotBlank()) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(GooglePhoneColors.chipBg(isDark))
                                                .clickable {
                                                    playback.stop()
                                                    expandedKey = null
                                                    onDial(contact.number)
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_call),
                                                contentDescription = "Call ${contact.name}",
                                                tint = GooglePhoneColors.CallGreen,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }

                                ExpandableContactActions(
                                    visible = isExpanded,
                                    number = contact.number,
                                    isDark = isDark,
                                    recentCount = row.recentCount,
                                    whatsAppEnabled = row.whatsAppEnabled,
                                    onShowAllRecents = {
                                        historyRow = row
                                    },
                                    onCall = {
                                        playback.stop()
                                        expandedKey = null
                                        onDial(contact.number)
                                    },
                                )
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

        historyRow?.let { row ->
            CallHistoryDialog(
                target = HistoryTarget(title = row.contact.name, number = row.contact.number),
                history = row.matchingHistory,
                isDark = isDark,
                playback = playback,
                onCall = {
                    playback.stop()
                    historyRow = null
                    expandedKey = null
                    onDial(row.contact.number)
                },
                onDismiss = { historyRow = null },
            )
        }
    }
}
