package com.pp.jiocall.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pp.jiocall.sip.SipDirection
import com.pp.jiocall.sip.SipLogBuffer
import com.pp.jiocall.sip.SipLogPacket
import kotlinx.coroutines.delay

private data class DiagnosticsView(
    val filtered: List<SipLogPacket>,
    val reversed: List<SipLogPacket>,
    val txCount: Int,
    val rxCount: Int,
)

@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var logs by remember { mutableStateOf(SipLogBuffer.getLogs()) }
    var lastVersion by remember { mutableLongStateOf(SipLogBuffer.version) }
    var filterDirection by remember { mutableStateOf<SipDirection?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    // Auto-refresh packets only when buffer version changes while viewing diagnostics
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_500)
            val currentVersion = SipLogBuffer.version
            if (currentVersion != lastVersion) {
                lastVersion = currentVersion
                logs = SipLogBuffer.getLogs()
            }
        }
    }

    val (filteredLogs, reversedLogs, txCount, rxCount) = remember(logs, filterDirection, searchQuery) {
        val tx = logs.count { it.direction == SipDirection.TX }
        val rx = logs.size - tx
        val dirFiltered = if (filterDirection == null) logs else logs.filter { it.direction == filterDirection }
        val q = searchQuery.trim()
        val matched = if (q.isEmpty()) {
            dirFiltered
        } else {
            dirFiltered.filter { packet ->
                packet.text.contains(q, ignoreCase = true) ||
                    packet.remote.contains(q, ignoreCase = true) ||
                    packet.transport.contains(q, ignoreCase = true)
            }
        }
        DiagnosticsView(matched, matched.asReversed(), tx, rx)
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear SIP logs?") },
            text = { Text("Remove all captured SIP diagnostic messages from memory.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        SipLogBuffer.clear()
                        lastVersion = SipLogBuffer.version
                        logs = emptyList()
                        Toast.makeText(context, "Buffer cleared", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // Header Bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "SIP Diagnostics",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    lastVersion = SipLogBuffer.version
                    logs = SipLogBuffer.getLogs()
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
                IconButton(onClick = {
                    val fullDump = filteredLogs.joinToString("\n\n---\n\n") {
                        "[${it.formattedTime}] ${it.direction} (${it.transport}) ${it.remote}\n${it.text}"
                    }
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("SIP Trace", fullDump))
                    Toast.makeText(context, "Copied ${filteredLogs.size} packets to clipboard", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Copy all")
                }
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear logs")
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search INVITE, Call-ID, 198, or IP") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )

            // Filter Chips
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = filterDirection == null,
                    onClick = { filterDirection = null },
                    label = { Text("All (${logs.size})") },
                )
                FilterChip(
                    selected = filterDirection == SipDirection.TX,
                    onClick = { filterDirection = if (filterDirection == SipDirection.TX) null else SipDirection.TX },
                    label = { Text("Sent TX ($txCount)") },
                )
                FilterChip(
                    selected = filterDirection == SipDirection.RX,
                    onClick = { filterDirection = if (filterDirection == SipDirection.RX) null else SipDirection.RX },
                    label = { Text("Received RX ($rxCount)") },
                )
            }

            Spacer(Modifier.height(8.dp))

            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No SIP messages captured yet.\nPlace or receive a call to see live traffic.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = reversedLogs,
                        key = { it.id },
                        contentType = { "sip_packet" },
                    ) { packet ->
                        val isExpanded = expandedId == packet.id
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedId = if (isExpanded) null else packet.id
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isExpanded) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    // Direction badge
                                    val isTx = packet.direction == SipDirection.TX
                                    val badgeBg = if (isTx) Color(0xFF1565C0) else Color(0xFF2E7D32)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(badgeBg)
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = if (isTx) "TX" else "RX",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = packet.formattedTime,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = packet.transport,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        text = "#${packet.id}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                }

                                Spacer(Modifier.height(6.dp))

                                // First line (e.g., "INVITE sip:..." or "SIP/2.0 200 OK")
                                Text(
                                    text = packet.firstLine,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )

                                // Expanded view with full headers and body
                                AnimatedVisibility(visible = isExpanded) {
                                    Column(Modifier.padding(top = 8.dp)) {
                                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "Remote: ${packet.remote}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                text = "Copy Packet",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .clickable {
                                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                        cm.setPrimaryClip(ClipData.newPlainText("SIP Packet #${packet.id}", packet.text))
                                                        Toast.makeText(context, "Packet copied", Toast.LENGTH_SHORT).show()
                                                    }
                                                    .padding(4.dp),
                                            )
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF1E1E1E))
                                                .padding(10.dp),
                                        ) {
                                            Text(
                                                text = packet.text,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = Color(0xFFE0E0E0),
                                                lineHeight = 16.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
