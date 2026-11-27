package com.pp.jiocall.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.pp.jiocall.R
import com.pp.jiocall.data.CallLogEntry
import com.pp.jiocall.data.GooglePhoneColors
import com.pp.jiocall.data.PhoneUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared intent and number helpers for the expandable contact / recents quick-action tray.
 */
internal object ContactActions {
    private val WHATSAPP_GREEN = Color(0xFF25D366)
    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    /**
     * Converts a phone number into digits suitable for `https://wa.me/<digits>`.
     * Returns null for short/service codes (fewer than 7 digits).
     */
    internal fun formatWhatsAppDigits(number: String): String? {
        val normalized = PhoneUtils.normalizeForDialing(number).ifEmpty { number }
        val digits = normalized.filter(Char::isDigit)
        return if (digits.length >= 7) digits else null
    }

    /**
     * Filters [calls] for all records belonging to [number], matching 10-digit variants cleanly.
     */
    internal fun matchingHistory(calls: List<CallLogEntry>, number: String): List<CallLogEntry> {
        if (number.isBlank()) return emptyList()
        return calls.filter { entry ->
            PhoneUtils.matches(entry.number, number) ||
                entry.number.trim() == number.trim()
        }
    }

    fun copyNumber(context: Context, number: String) {
        if (number.isBlank()) {
            Toast.makeText(context, "No phone number to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val formatted = PhoneUtils.formatForDisplay(number).ifBlank { number.trim() }
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("Phone number", formatted))
        Toast.makeText(context, "Copied $formatted", Toast.LENGTH_SHORT).show()
    }

    fun openWhatsApp(context: Context, number: String) {
        val digits = formatWhatsAppDigits(number)
        if (digits == null) {
            Toast.makeText(context, "Not a valid WhatsApp number", Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = "https://wa.me/$digits".toUri()
        for (pkg in WHATSAPP_PACKAGES) {
            val targeted = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(targeted) }.isSuccess) {
                return
            }
        }
        val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(fallback) }.onFailure {
            Toast.makeText(context, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    fun whatsAppAccentColor(): Color = WHATSAPP_GREEN
}

@Stable
class RecordingPlaybackController(
    private val context: Context,
    private val getHasActiveCall: () -> Boolean,
) {
    var playingPath by mutableStateOf<String?>(null)
        private set
    var availableRecordings by mutableStateOf<Set<String>>(emptySet())
        internal set
    private var player: MediaPlayer? = null

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingPath = null
    }

    fun toggle(path: String) {
        if (getHasActiveCall()) {
            Toast.makeText(context, "Cannot play recording during a call", Toast.LENGTH_SHORT).show()
            return
        }
        if (playingPath == path) {
            stop()
            return
        }
        stop()
        val created = runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                setOnPreparedListener { mp ->
                    if (player === mp) {
                        mp.start()
                    } else {
                        runCatching { mp.release() }
                    }
                }
                setOnCompletionListener { mp ->
                    if (player === mp) {
                        playingPath = null
                        player = null
                    }
                    runCatching { mp.release() }
                }
                setOnErrorListener { mp, _, _ ->
                    if (player === mp) {
                        playingPath = null
                        player = null
                    }
                    runCatching { mp.release() }
                    true
                }
                prepareAsync()
            }
        }.onFailure {
            Toast.makeText(context, "Cannot play recording", Toast.LENGTH_SHORT).show()
        }.getOrNull()
        if (created != null) {
            player = created
            playingPath = path
        }
    }
}

@Composable
fun rememberRecordingPlaybackController(
    availableRecordings: Set<String>,
    hasActiveCall: Boolean,
): RecordingPlaybackController {
    val context = LocalContext.current
    var latestHasActiveCall by remember { mutableStateOf(hasActiveCall) }
    latestHasActiveCall = hasActiveCall

    val controller = remember(context) {
        RecordingPlaybackController(context) { latestHasActiveCall }
    }

    LaunchedEffect(availableRecordings) {
        controller.availableRecordings = availableRecordings
    }

    LaunchedEffect(hasActiveCall) {
        if (hasActiveCall) {
            controller.stop()
        }
    }

    DisposableEffect(controller) {
        onDispose { controller.stop() }
    }

    return controller
}

@Composable
fun rememberRecordingPlaybackController(
    calls: List<CallLogEntry>,
    hasActiveCall: Boolean,
): RecordingPlaybackController {
    val context = LocalContext.current
    var latestHasActiveCall by remember { mutableStateOf(hasActiveCall) }
    latestHasActiveCall = hasActiveCall

    val controller = remember(context) {
        RecordingPlaybackController(context) { latestHasActiveCall }
    }

    LaunchedEffect(calls) {
        val paths = calls.mapNotNull { it.recordingPath }.distinct()
        controller.availableRecordings = if (paths.isEmpty()) {
            emptySet()
        } else {
            withContext(Dispatchers.IO) {
                paths.filterTo(mutableSetOf()) { File(it).exists() }
            }
        }
    }

    LaunchedEffect(hasActiveCall) {
        if (hasActiveCall) {
            controller.stop()
        }
    }

    DisposableEffect(controller) {
        onDispose { controller.stop() }
    }

    return controller
}

/**
 * Target contact/number passed to [CallHistoryDialog].
 */
data class HistoryTarget(
    val title: String,
    val number: String,
)

/**
 * Google Phone / Samsung One UI expandable inline quick-action menu displayed
 * underneath a tapped Contact or Recent call item.
 * Computes number metadata lazily only when visible.
 */
@Composable
fun ExpandableContactActions(
    visible: Boolean,
    number: String,
    isDark: Boolean,
    onShowAllRecents: () -> Unit,
    onCall: () -> Unit,
    modifier: Modifier = Modifier,
    recentCount: Int = 0,
    subtitle: String? = null,
    whatsAppEnabled: Boolean? = null,
) {
    val context = LocalContext.current

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(200)) + fadeIn(tween(160)),
        exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
        modifier = modifier,
    ) {
        val waEnabled = remember(number, whatsAppEnabled) {
            whatsAppEnabled ?: (ContactActions.formatWhatsAppDigits(number) != null)
        }
        val hasNumber = number.isNotBlank()

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            shape = RoundedCornerShape(18.dp),
            color = GooglePhoneColors.surface(isDark),
            border = BorderStroke(1.dp, GooglePhoneColors.cardBorder(isDark)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 12.dp),
            ) {
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = GooglePhoneColors.textSecondary(isDark),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionPill(
                        iconRes = R.drawable.ic_history,
                        label = if (recentCount > 0) "All recent ($recentCount)" else "All recent",
                        iconTint = GooglePhoneColors.highlight(isDark),
                        badgeBg = GooglePhoneColors.highlight(isDark).copy(alpha = 0.14f),
                        textColor = GooglePhoneColors.textPrimary(isDark),
                        enabled = hasNumber,
                        onClick = onShowAllRecents,
                        modifier = Modifier.weight(1f),
                    )
                    ActionPill(
                        iconRes = R.drawable.ic_copy,
                        label = "Copy",
                        iconTint = GooglePhoneColors.textPrimary(isDark),
                        badgeBg = GooglePhoneColors.keyBg(isDark),
                        textColor = GooglePhoneColors.textPrimary(isDark),
                        enabled = hasNumber,
                        onClick = { ContactActions.copyNumber(context, number) },
                        modifier = Modifier.weight(1f),
                    )
                    ActionPill(
                        iconRes = R.drawable.ic_call,
                        label = "Call",
                        iconTint = Color.White,
                        badgeBg = GooglePhoneColors.CallGreen,
                        textColor = GooglePhoneColors.textPrimary(isDark),
                        enabled = hasNumber,
                        onClick = onCall,
                        modifier = Modifier.weight(1f),
                    )
                    ActionPill(
                        iconRes = R.drawable.ic_whatsapp,
                        label = "WhatsApp",
                        iconTint = if (waEnabled) ContactActions.whatsAppAccentColor() else GooglePhoneColors.textSecondary(isDark),
                        badgeBg = if (waEnabled) {
                            ContactActions.whatsAppAccentColor().copy(alpha = 0.16f)
                        } else {
                            GooglePhoneColors.keyBg(isDark)
                        },
                        textColor = if (waEnabled) GooglePhoneColors.textPrimary(isDark) else GooglePhoneColors.textSecondary(isDark),
                        enabled = hasNumber,
                        onClick = { ContactActions.openWhatsApp(context, number) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionPill(
    @DrawableRes iconRes: Int,
    label: String,
    iconTint: Color,
    badgeBg: Color,
    textColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(badgeBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Shared modal showing all recent call logs matching a contact or phone number,
 * with copy, WhatsApp, inline recording playback, and redial actions.
 */
@Composable
fun CallHistoryDialog(
    target: HistoryTarget,
    history: List<CallLogEntry>,
    isDark: Boolean,
    playback: RecordingPlaybackController,
    onCall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val formattedNumber = remember(target.number) {
        PhoneUtils.formatForDisplay(target.number).ifBlank { target.number }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(target.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (formattedNumber.isNotBlank() && target.title != formattedNumber) {
                    Text(
                        text = formattedNumber,
                        fontSize = 13.sp,
                        color = GooglePhoneColors.textSecondary(isDark),
                    )
                }
            }
        },
        text = {
            if (history.isEmpty()) {
                Text(
                    text = "No recent calls found for ${formattedNumber.ifBlank { target.title }}.",
                    fontSize = 14.sp,
                    color = GooglePhoneColors.textSecondary(isDark),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(history, key = { "${it.id}_${it.startedAt}" }) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = when {
                                    item.missed -> "↙"
                                    item.outgoing -> "↗"
                                    else -> "↙"
                                },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    item.missed -> Color(0xFFE53935)
                                    item.outgoing -> GooglePhoneColors.highlight(isDark)
                                    else -> GooglePhoneColors.CallGreen
                                },
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = buildString {
                                        append(
                                            when {
                                                item.missed -> "Missed"
                                                item.outgoing -> "Outgoing"
                                                else -> "Incoming"
                                            },
                                        )
                                        if (item.durationSec > 0) {
                                            append(" • ")
                                            append(item.formattedDuration())
                                        }
                                    },
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = buildString {
                                        if (item.accountTitle.isNotEmpty()) {
                                            append(item.accountTitle)
                                            append(" • ")
                                        }
                                        append(item.formattedTimestamp())
                                    },
                                    fontSize = 12.sp,
                                    color = GooglePhoneColors.textSecondary(isDark),
                                )
                            }
                            val itemRec = item.recordingPath
                            if (itemRec != null && itemRec in playback.availableRecordings) {
                                val isPlaying = playback.playingPath == itemRec
                                IconButton(
                                    onClick = { playback.toggle(itemRec) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                                        contentDescription = if (isPlaying) "Stop Recording" else "Play Recording",
                                        tint = if (isPlaying) Color(0xFFD32F2F) else GooglePhoneColors.highlight(isDark),
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (target.number.isNotBlank()) {
                    TextButton(onClick = { ContactActions.copyNumber(context, target.number) }) {
                        Text("Copy")
                    }
                    TextButton(onClick = onCall) {
                        Text("Call")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
