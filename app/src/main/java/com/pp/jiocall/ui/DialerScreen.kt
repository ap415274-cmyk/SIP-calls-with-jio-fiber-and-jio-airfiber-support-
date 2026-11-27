package com.pp.jiocall.ui

import android.content.Intent
import android.provider.ContactsContract
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pp.jiocall.R
import com.pp.jiocall.data.Account
import com.pp.jiocall.data.ContactItem
import com.pp.jiocall.data.GooglePhoneColors
import com.pp.jiocall.data.PhoneUtils
import com.pp.jiocall.data.SmartDial
import com.pp.jiocall.sip.AccountStatus
import com.pp.jiocall.ui.components.ContactAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val DialpadGrid = listOf(
    listOf("1" to "VM", "2" to "ABC", "3" to "DEF"),
    listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
    listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
    listOf("*" to "", "0" to "+", "#" to ""),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialerScreen(
    accounts: List<Account>,
    statuses: Map<Long, AccountStatus>,
    contacts: List<ContactItem> = emptyList(),
    initialNumber: String = "",
    lastNumber: String = "",
    onDial: (String, Long?) -> String?,
    onDialVoicemail: ((Long?) -> Unit)? = null,
    onOpenAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
    isDark: Boolean = false,
    onToggleDarkMode: () -> Unit = {},
    contactLookup: ContactLookupIndex? = null,
    onInitialNumberConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var number by rememberSaveable { mutableStateOf(initialNumber) }
    LaunchedEffect(initialNumber) {
        if (initialNumber.isNotEmpty()) {
            number = initialNumber
            onInitialNumberConsumed()
        }
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var accountMenuExpanded by remember { mutableStateOf(false) }

    val enabledAccounts = remember(accounts) { accounts.filter { it.enabled } }
    var selectedAccountId by rememberSaveable {
        mutableLongStateOf(enabledAccounts.firstOrNull()?.id ?: 0L)
    }
    LaunchedEffect(enabledAccounts) {
        if (enabledAccounts.none { it.id == selectedAccountId }) {
            selectedAccountId = enabledAccounts.firstOrNull()?.id ?: 0L
        }
    }
    val selectedAccount = remember(enabledAccounts, selectedAccountId) {
        enabledAccounts.firstOrNull { it.id == selectedAccountId } ?: enabledAccounts.firstOrNull()
    }

    // Never block keypad taps: run SmartDial on Dispatchers.Default while keeping zero-flicker list state.
    val idleMatches = remember(contactLookup, contacts, isDark) {
        if (contactLookup != null) {
            if (isDark) contactLookup.precomputedMatchesDark else contactLookup.precomputedMatchesLight
        } else {
            emptyList()
        }
    }
    var matches by remember { mutableStateOf(idleMatches) }
    LaunchedEffect(number, contacts, isDark, contactLookup) {
        if (number.isEmpty() && contactLookup != null) {
            matches = if (isDark) contactLookup.precomputedMatchesDark else contactLookup.precomputedMatchesLight
        } else {
            val computed = withContext(Dispatchers.Default) {
                SmartDial.search(contacts, number, isDark)
            }
            matches = computed
        }
    }

    val regStatus = remember(accounts, statuses, isDark) {
        GooglePhoneColors.getRegistrationUiStatus(accounts, statuses, isDark)
    }
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GooglePhoneColors.background(isDark)),
    ) {
        // Top Area: Contacts / SmartDial Matches List
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, bottom = 10.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        number.isNotEmpty() && matches.isEmpty() -> "Unsaved number"
                        number.isNotEmpty() -> "Suggestions (${matches.size})"
                        matches.isNotEmpty() -> "Quick contacts (${matches.size})"
                        else -> "All contacts"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GooglePhoneColors.textSecondary(isDark),
                )

                // Registration / Active Line Selector Pill
                Box {
                    val lineLabel = if (enabledAccounts.size > 1 && selectedAccount != null) {
                        selectedAccount.title()
                    } else {
                        regStatus.label
                    }
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = regStatus.chipBg,
                        border = BorderStroke(1.dp, regStatus.dotColor.copy(alpha = 0.28f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                if (enabledAccounts.size > 1) {
                                    accountMenuExpanded = true
                                } else {
                                    onOpenAccounts()
                                }
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(regStatus.dotColor),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = lineLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = regStatus.textColor,
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = accountMenuExpanded,
                        onDismissRequest = { accountMenuExpanded = false },
                        modifier = Modifier.background(GooglePhoneColors.menuBg(isDark)),
                    ) {
                        enabledAccounts.forEach { acc ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (acc.id == selectedAccount?.id) "✓ ${acc.title()}" else acc.title(),
                                        color = GooglePhoneColors.textPrimary(isDark),
                                    )
                                },
                                onClick = {
                                    selectedAccountId = acc.id
                                    accountMenuExpanded = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Manage accounts", color = GooglePhoneColors.highlight(isDark)) },
                            onClick = {
                                accountMenuExpanded = false
                                onOpenAccounts()
                            },
                        )
                    }
                }
            }

            if (number.isNotEmpty() && matches.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UnsavedActionCard(
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Create new contact",
                                tint = GooglePhoneColors.highlight(isDark),
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        title = "Create new contact",
                        isDark = isDark,
                        onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_INSERT).apply {
                                    type = ContactsContract.Contacts.CONTENT_TYPE
                                    putExtra(ContactsContract.Intents.Insert.PHONE, number)
                                }
                                context.startActivity(intent)
                            }
                        },
                    )
                    UnsavedActionCard(
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Add to a contact",
                                tint = GooglePhoneColors.highlight(isDark),
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        title = "Add to a contact",
                        isDark = isDark,
                        onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                                    type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                                    putExtra(ContactsContract.Intents.Insert.PHONE, number)
                                }
                                context.startActivity(intent)
                            }
                        },
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        items = matches,
                        key = { "${it.contact.id}_${it.contact.name}_${it.contact.number}" },
                        contentType = { "dial_contact" },
                    ) { match ->
                        val contact = match.contact
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    number = PhoneUtils.cleanDialInput(contact.number)
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = GooglePhoneColors.surfaceElevated(isDark),
                            border = BorderStroke(1.dp, GooglePhoneColors.cardBorder(isDark)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ContactAvatar(
                                    contact = contact,
                                    size = 42.dp,
                                )

                                Spacer(Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = match.highlightedName,
                                        fontSize = 15.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = GooglePhoneColors.textPrimary(isDark),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = match.highlightedNumber,
                                        fontSize = 13.sp,
                                        color = GooglePhoneColors.textSecondary(isDark),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                Spacer(Modifier.width(8.dp))

                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(GooglePhoneColors.chipBg(isDark))
                                        .clickable {
                                            if (onDial(contact.number, selectedAccount?.id) == null) {
                                                number = ""
                                            }
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
                    }
                }
            }
        }

        // Bottom Elevated Dialpad Sheet
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = GooglePhoneColors.surface(isDark),
            border = BorderStroke(1.dp, GooglePhoneColors.cardBorder(isDark)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val displayNumber = remember(number) {
                    PhoneUtils.formatAsYouType(number).ifEmpty { number }
                }
                val numberFontSize = when {
                    displayNumber.length <= 9 -> 34.sp
                    displayNumber.length <= 13 -> 27.sp
                    displayNumber.length <= 16 -> 22.sp
                    displayNumber.length <= 20 -> 18.sp
                    else -> 15.sp
                }

                // Fixed-height Number Display Row (zero relayout jitter on digit input)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Options",
                                tint = GooglePhoneColors.textSecondary(isDark),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 10.dp, end = 10.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(regStatus.dotColor),
                        )
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
                        }
                    }

                    Text(
                        text = displayNumber,
                        fontSize = numberFontSize,
                        fontWeight = FontWeight.Medium,
                        color = GooglePhoneColors.textPrimary(isDark),
                        textAlign = TextAlign.Center,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )

                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = number.isNotEmpty(),
                            enter = fadeIn(tween(90)) + scaleIn(initialScale = 0.8f, animationSpec = tween(90)),
                            exit = fadeOut(tween(80)) + scaleOut(targetScale = 0.8f, animationSpec = tween(80)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = true),
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            number = number.dropLast(1)
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            number = ""
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_backspace),
                                    contentDescription = "Backspace",
                                    tint = GooglePhoneColors.textSecondary(isDark),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // 3x4 Keypad Grid with 120Hz RenderThread Ripple (0 Compose animation overhead on key tap)
                DialpadGrid.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { (digit, letters) ->
                            GooglePillKey(
                                digit = digit,
                                subtext = letters,
                                modifier = Modifier.weight(1f),
                                onPress = { number += digit },
                                onLongPress = when (digit) {
                                    "0" -> {
                                        {
                                            when {
                                                number.isEmpty() -> number = "+"
                                                number == "0" -> number = "+"
                                                number.last() == '*' || number.last() == '#' -> number += "+"
                                            }
                                        }
                                    }
                                    "1" -> onDialVoicemail?.let { vm -> { vm(selectedAccount?.id) } }
                                    else -> null
                                },
                                isDark = isDark,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Pill Call Action Button
                val canPressCall = number.isNotBlank() || lastNumber.isNotBlank()
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = if (canPressCall) GooglePhoneColors.CallGreen else GooglePhoneColors.CallGreen.copy(alpha = 0.45f),
                    border = if (canPressCall) BorderStroke(1.dp, Color(0x30FFFFFF)) else null,
                    modifier = Modifier
                        .clip(RoundedCornerShape(28.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, color = Color.White),
                            enabled = canPressCall,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (number.isBlank() && lastNumber.isNotBlank()) {
                                    number = lastNumber
                                } else {
                                    if (onDial(number, selectedAccount?.id) == null) {
                                        number = ""
                                    }
                                }
                            },
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 36.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_call),
                            contentDescription = "Call",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Call",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnsavedActionCard(
    icon: @Composable () -> Unit,
    title: String,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = GooglePhoneColors.surfaceElevated(isDark),
        border = BorderStroke(1.dp, GooglePhoneColors.cardBorder(isDark)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(GooglePhoneColors.chipBg(isDark)),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = title,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
                color = GooglePhoneColors.highlight(isDark),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GooglePillKey(
    digit: String,
    subtext: String,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    isDark: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val rippleColor = remember(isDark) { GooglePhoneColors.highlight(isDark).copy(alpha = 0.35f) }

    Box(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(GooglePhoneColors.keyBg(isDark))
            .border(1.dp, GooglePhoneColors.keyBorder(isDark), RoundedCornerShape(27.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = rippleColor),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPress()
                },
                onLongClick = onLongPress?.let { lp ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        lp()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = digit,
                fontSize = 24.sp,
                fontWeight = FontWeight.Normal,
                color = GooglePhoneColors.textPrimary(isDark),
                lineHeight = 26.sp,
            )
            if (subtext.isNotEmpty()) {
                Text(
                    text = subtext,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDark) GooglePhoneColors.highlight(isDark) else GooglePhoneColors.TextMuted,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}
