package com.pp.jiocall.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pp.jiocall.R
import com.pp.jiocall.data.GooglePhoneColors
import com.pp.jiocall.data.RegistrationUiStatus
import com.pp.jiocall.sip.AccountStatus
import com.pp.jiocall.sip.RegState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val headerTimeFmt = ThreadLocal.withInitial { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

/**
 * Shared flagship pill Search Bar with live status indicator, voice input, and overflow menu.
 * Uses [BasicTextField] inside a lightweight bordered [Surface] to avoid heavy TextField layout passes.
 */
@Composable
fun GooglePhoneSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    voicePrompt: String,
    isDark: Boolean,
    regStatus: RegistrationUiStatus,
    onOpenAccounts: () -> Unit,
    onToggleDarkMode: () -> Unit,
    onOpenSettings: () -> Unit,
    extraMenuItems: (@Composable (dismissMenu: () -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrEmpty()) onQueryChange(spoken)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(26.dp),
        color = GooglePhoneColors.searchBarBg(isDark),
        border = BorderStroke(1.dp, GooglePhoneColors.cardBorder(isDark)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = GooglePhoneColors.textSecondary(isDark),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = GooglePhoneColors.textSecondary(isDark),
                        fontSize = 15.sp,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = GooglePhoneColors.textPrimary(isDark),
                        fontSize = 15.sp,
                    ),
                    cursorBrush = SolidColor(GooglePhoneColors.highlight(isDark)),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Live clickable line chip right inside search bar
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(regStatus.chipBg)
                    .clickable(onClick = onOpenAccounts)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(regStatus.dotColor),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = regStatus.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = regStatus.textColor,
                        maxLines = 1,
                    )
                }
            }

            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search",
                        tint = GooglePhoneColors.textSecondary(isDark),
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, voicePrompt)
                        }
                        runCatching { speechLauncher.launch(intent) }.onFailure {
                            Toast.makeText(context, "Voice search unavailable", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = "Voice Search",
                        tint = GooglePhoneColors.textSecondary(isDark),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Box(contentAlignment = Alignment.Center) {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = GooglePhoneColors.textSecondary(isDark),
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(GooglePhoneColors.menuBg(isDark)),
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Accounts", color = GooglePhoneColors.textPrimary(isDark))
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(regStatus.dotColor),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = regStatus.label,
                                    fontSize = 12.sp,
                                    color = regStatus.textColor,
                                )
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null, tint = GooglePhoneColors.highlight(isDark))
                        },
                        onClick = {
                            menuExpanded = false
                            onOpenAccounts()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (isDark) "Light theme" else "Dark theme", color = GooglePhoneColors.textPrimary(isDark)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(if (isDark) R.drawable.ic_light_mode else R.drawable.ic_dark_mode),
                                contentDescription = null,
                                tint = GooglePhoneColors.highlight(isDark),
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onToggleDarkMode()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Settings", color = GooglePhoneColors.textPrimary(isDark)) },
                        leadingIcon = {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = GooglePhoneColors.highlight(isDark))
                        },
                        onClick = {
                            menuExpanded = false
                            onOpenSettings()
                        },
                    )
                    extraMenuItems?.invoke { menuExpanded = false }
                }
            }
        }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(12.dp))
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        actions()
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
fun StatusLine(status: AccountStatus?, compact: Boolean = false) {
    val (color, text) = when (status?.state) {
        RegState.REGISTERED -> MaterialTheme.colorScheme.primary to "Registered"
        RegState.REGISTERING -> MaterialTheme.colorScheme.tertiary to "Registering…"
        RegState.FAILED -> MaterialTheme.colorScheme.error to if (compact) "Offline" else (status.reason ?: "Failed")
        RegState.DISABLED -> MaterialTheme.colorScheme.outline to "Disabled"
        RegState.UNREGISTERED -> MaterialTheme.colorScheme.outline to "Not registered"
        null -> MaterialTheme.colorScheme.outline to "Not started"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    enabled: Boolean = true,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        enabled = enabled,
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun FieldGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
fun InfoRow(label: String, value: String, muted: Boolean = false) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(130.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val rest = safe % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, rest)
    }
}

fun formatWhen(millis: Long): String = headerTimeFmt.get()!!.format(Date(millis))

/** A colour for a call state, used by rows and the call screen. */
@Composable
fun callStateLabel(state: com.pp.jiocall.sip.CallState, outgoing: Boolean): String = when (state) {
    com.pp.jiocall.sip.CallState.DIALING -> "Calling…"
    com.pp.jiocall.sip.CallState.RINGING -> if (outgoing) "Ringing…" else "Incoming call"
    com.pp.jiocall.sip.CallState.ACTIVE -> "In call"
    com.pp.jiocall.sip.CallState.HELD -> "On hold"
    com.pp.jiocall.sip.CallState.FAILED -> "Failed"
    com.pp.jiocall.sip.CallState.ENDED -> "Ended"
}

@Composable
fun stateColor(state: com.pp.jiocall.sip.CallState): Color = when (state) {
    com.pp.jiocall.sip.CallState.FAILED -> MaterialTheme.colorScheme.error
    com.pp.jiocall.sip.CallState.ACTIVE -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

val ZeroHeight = 0.sp
