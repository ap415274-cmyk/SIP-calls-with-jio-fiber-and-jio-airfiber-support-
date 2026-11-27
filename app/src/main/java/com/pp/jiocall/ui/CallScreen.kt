package com.pp.jiocall.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pp.jiocall.R
import com.pp.jiocall.data.PhoneUtils
import com.pp.jiocall.sip.CallQuality
import com.pp.jiocall.sip.CallSnapshot
import com.pp.jiocall.sip.CallState
import com.pp.jiocall.ui.components.ContactAvatar
import com.pp.jiocall.ui.components.IncomingSlideControlBar
import com.pp.jiocall.ui.components.OneUiIncomingAvatarHalo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private val DialpadKeys = listOf(
    listOf('1' to "", '2' to "ABC", '3' to "DEF"),
    listOf('4' to "GHI", '5' to "JKL", '6' to "MNO"),
    listOf('7' to "PQRS", '8' to "TUV", '9' to "WXYZ"),
    listOf('*' to "", '0' to "+", '#' to ""),
)

/**
 * Flagship Phone In-Call Screen.
 *
 * Designed from scratch with:
 * - Immersive gradient background
 * - Top bar with account badge and minimize action
 * - High-res Contact Avatar with pulse halo on incoming calls
 * - Responsive 2x3 in-call action grid (Mute, Keypad, Speaker, Hold, Record, HD Audio)
 * - Large tactile Floating End Call / Answer / Decline buttons
 * - Dedicated sliding Keypad overlay that never clips or distorts the screen layout
 */
@Composable
fun CallScreen(
    call: CallSnapshot,
    speakerOn: Boolean,
    onAnswer: () -> Unit,
    onHangup: () -> Unit,
    onMute: () -> Unit,
    onHold: () -> Unit,
    onSpeaker: () -> Unit,
    onRecord: () -> Unit = {},
    onSilence: () -> Unit = {},
    onDtmf: (Char) -> Unit,
    onMinimize: (() -> Unit)? = null,
    onOpenContacts: (() -> Unit)? = null,
    secondaryCall: CallSnapshot? = null,
    onAnswerWaiting: () -> Unit = {},
    onDeclineWaiting: () -> Unit = {},
    onSwapCalls: () -> Unit = {},
    photoUri: String? = null,
    qualityFlow: StateFlow<CallQuality?>? = null,
) {
    var showKeypad by remember { mutableStateOf(false) }
    var dtmfDigits by remember { mutableStateOf("") }
    var ringerSilenced by remember(call.callId) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var elapsedSeconds by remember(call.callId, call.startedAt) {
        mutableLongStateOf(
            if (call.startedAt > 0) ((System.currentTimeMillis() - call.startedAt) / 1000L).coerceAtLeast(0L) else 0L,
        )
    }

    BackHandler(enabled = showKeypad) {
        showKeypad = false
    }

    LaunchedEffect(call.callId, showKeypad) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    LaunchedEffect(call.startedAt, call.state) {
        while (call.state == CallState.ACTIVE || call.state == CallState.HELD) {
            elapsedSeconds = if (call.startedAt > 0) (System.currentTimeMillis() - call.startedAt) / 1000 else 0
            delay(1_000)
        }
    }

    val formattedNumber = remember(call.number) {
        PhoneUtils.formatForDisplay(call.number).ifEmpty { call.number }
    }
    val callerDisplayName = call.name.ifEmpty { formattedNumber.ifEmpty { "Unknown Caller" } }
    val isIncomingRinging = !call.outgoing && call.state == CallState.RINGING
    val isCallActive = call.answered && call.state == CallState.ACTIVE
    val showControls = call.answered || (call.outgoing && (call.state == CallState.DIALING || call.state == CallState.RINGING))

    val glowColor by animateColorAsState(
        targetValue = when {
            isIncomingRinging -> Color(0x3034A853)
            call.state == CallState.HELD -> Color(0x28FFB74D)
            call.state == CallState.ACTIVE -> Color(0x221B72E8)
            call.state == CallState.FAILED -> Color(0x28E53935)
            else -> Color(0x208AB4F8)
        },
        animationSpec = tween(400),
        label = "ambientGlow",
    )

    val backdropInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = backdropInteraction, indication = null) {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
            }
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF171C24),
                        Color(0xFF0E1117),
                        Color(0xFF080A0E),
                    ),
                ),
            ),
    ) {
        // Subtle ambient One-UI top glow halo behind caller card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(glowColor, Color.Transparent),
                    ),
                ),
        )
        // --- 1. Main In-Call Content ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top Bar: Minimize button + Account Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (onMinimize != null && !isIncomingRinging && call.state != CallState.ENDED && call.state != CallState.FAILED) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF222630))
                            .border(1.dp, Color(0xFF323846), CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onMinimize()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_keyboard_arrow_down),
                            contentDescription = "Minimize call",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.size(42.dp))
                }

                // Account / SIP line badge
                if (call.accountTitle.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1E222A))
                            .border(1.dp, Color(0xFF2E3442), RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF34A853)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = call.accountTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFC4C7C5),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                Spacer(Modifier.size(42.dp))
            }

            Spacer(Modifier.height(16.dp))

            // Contact Avatar with single GPU-composited sonar halo on incoming calls
            Box(
                modifier = Modifier.size(160.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isIncomingRinging) {
                    OneUiIncomingAvatarHalo(
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .shadow(elevation = if (isIncomingRinging) 14.dp else 8.dp, shape = CircleShape)
                        .clip(CircleShape)
                        .border(2.dp, if (isIncomingRinging) Color(0x8834A853) else Color(0x33FFFFFF), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    ContactAvatar(
                        name = callerDisplayName,
                        photoUri = photoUri,
                        size = 112.dp,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Caller Name
            Text(
                text = callerDisplayName,
                fontSize = if (isIncomingRinging) 31.sp else 28.sp,
                fontWeight = if (isIncomingRinging) FontWeight.Bold else FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Phone Number (if different from name)
            if (call.name.isNotEmpty() && formattedNumber.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formattedNumber,
                    fontSize = 15.sp,
                    color = Color(0xFF9AA0A6),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(10.dp))

            // Call Status / Elapsed Timer Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (isCallActive) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34A853)),
                    )
                    Spacer(Modifier.width(8.dp))
                }

                val statusText = when (call.state) {
                    CallState.DIALING -> "Calling…"
                    CallState.RINGING -> if (call.outgoing) "Ringing…" else "Incoming call"
                    CallState.HELD -> "Call on hold"
                    CallState.ACTIVE -> {
                        val m = elapsedSeconds / 60
                        val s = elapsedSeconds % 60
                        String.format(java.util.Locale.US, "%02d:%02d", m, s)
                    }
                    CallState.ENDED -> "Call ended"
                    CallState.FAILED -> call.error ?: "Call failed"
                }

                val statusColor = when (call.state) {
                    CallState.ACTIVE -> Color(0xFFE8EAED)
                    CallState.HELD -> Color(0xFFFFB74D)
                    CallState.FAILED -> Color(0xFFF28B82)
                    CallState.ENDED -> Color(0xFF9AA0A6)
                    else -> Color(0xFF8AB4F8)
                }

                Text(
                    text = statusText,
                    fontSize = 16.sp,
                    fontWeight = if (isCallActive) FontWeight.Medium else FontWeight.Normal,
                    color = statusColor,
                )

                // Optional HD Voice Pill (observes live quality locally without recomposing CallScreen)
                CallCodecBadge(
                    isCallActive = isCallActive,
                    fallback = call.quality,
                    qualityFlow = qualityFlow,
                )
            }

            if (secondaryCall != null) {
                Spacer(Modifier.height(16.dp))
                SecondaryCallBanner(
                    other = secondaryCall,
                    onAnswerWaiting = onAnswerWaiting,
                    onDeclineWaiting = onDeclineWaiting,
                    onSwapCalls = onSwapCalls,
                )
            }

            Spacer(Modifier.weight(1f))

            // Smooth crossfade between Incoming Ringing pills and In-Call 2x3 action grid
            AnimatedContent(
                targetState = isIncomingRinging to showControls,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.96f, animationSpec = tween(200)))
                        .togetherWith(fadeOut(animationSpec = tween(140)))
                },
                label = "callControlsMode",
            ) { (incomingMode, controlsVisible) ->
                if (incomingMode) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(if (ringerSilenced) Color(0xFF262B36) else Color(0xFF1D222C))
                                    .border(1.dp, Color(0xFF313847), RoundedCornerShape(22.dp))
                                    .clickable(
                                        enabled = !ringerSilenced,
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = true),
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        ringerSilenced = true
                                        onSilence()
                                    }
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (ringerSilenced) "Ringer silenced" else "Silence ringer",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (ringerSilenced) Color(0xFF9AA0A6) else Color(0xFFE8EAED),
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(if (speakerOn) Color(0xFF223B5F) else Color(0xFF1D222C))
                                    .border(
                                        1.dp,
                                        if (speakerOn) Color(0xFF8AB4F8) else Color(0xFF313847),
                                        RoundedCornerShape(22.dp),
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = true),
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onSpeaker()
                                    }
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_volume_up),
                                        contentDescription = null,
                                        tint = if (speakerOn) Color(0xFF8AB4F8) else Color(0xFFE8EAED),
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (speakerOn) "Speaker on" else "Speaker",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (speakerOn) Color(0xFF8AB4F8) else Color(0xFFE8EAED),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                } else if (controlsVisible) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Row 1: Mute | Keypad | Speaker
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                CallActionButton(
                                    icon = painterResource(if (call.muted) R.drawable.ic_mic_off else R.drawable.ic_mic),
                                    label = if (call.muted) "Muted" else "Mute",
                                    active = call.muted,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onMute()
                                    },
                                )
                                CallActionButton(
                                    icon = painterResource(R.drawable.ic_dialpad),
                                    label = "Keypad",
                                    active = false,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        showKeypad = true
                                    },
                                )
                                CallActionButton(
                                    icon = painterResource(R.drawable.ic_volume_up),
                                    label = if (speakerOn) "Speaker on" else "Speaker",
                                    active = speakerOn,
                                    activeColor = Color(0xFF8AB4F8),
                                    activeContentColor = Color(0xFF0A1E3D),
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onSpeaker()
                                    },
                                )
                            }

                            // Row 2: Hold | Record | Contacts
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                CallActionButton(
                                    icon = painterResource(if (call.onHold) R.drawable.ic_play_arrow else R.drawable.ic_pause),
                                    label = if (call.onHold) "Unhold" else "Hold",
                                    active = call.onHold,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onHold()
                                    },
                                )
                                CallActionButton(
                                    icon = painterResource(R.drawable.ic_record),
                                    label = if (call.recording) "Recording" else "Record",
                                    active = call.recording,
                                    activeColor = Color(0xFFD93025),
                                    activeContentColor = Color.White,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onRecord()
                                    },
                                )
                                CallActionButton(
                                    icon = painterResource(R.drawable.ic_person),
                                    label = "Contacts",
                                    active = false,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        (onOpenContacts ?: onMinimize)?.invoke()
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(36.dp))
                    }
                } else {
                    Spacer(Modifier.height(12.dp))
                }
            }

            // Bottom Actions: Dual Zero-Lag Sliding Handles (Incoming) or Centered End Call FAB (Active/Dialing)
            if (isIncomingRinging) {
                androidx.compose.runtime.key(call.callId) {
                    IncomingSlideControlBar(
                        onDecline = onHangup,
                        onAnswer = onAnswer,
                        modifier = Modifier.padding(bottom = 18.dp),
                    )
                }
            } else {
                // Active / Dialing / Outgoing: Centered Large Crimson Red End Call FAB
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CallFabButton(
                        icon = painterResource(R.drawable.ic_call_end),
                        backgroundColor = Color(0xFFE53935),
                        contentDescription = "End Call",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onHangup()
                        },
                    )
                }
            }
        }

        // --- 2. Sliding In-Call DTMF Keypad Overlay ---
        AnimatedVisibility(
            visible = showKeypad,
            enter = slideInVertically(
                initialOffsetY = { it / 2 },
                animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
            ) + scaleIn(initialScale = 0.96f, animationSpec = tween(220)) + fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(
                targetOffsetY = { it / 2 },
                animationSpec = tween(220, easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = tween(160)),
        ) {
            val keypadBackdropInteraction = remember { MutableInteractionSource() }
            Surface(
                color = Color(0xFF13161C),
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = keypadBackdropInteraction, indication = null) {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Keypad Header: Hide button on left + Title
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showKeypad = false
                            },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF222630)),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_keyboard_arrow_down),
                                contentDescription = "Hide keypad",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }

                        Text(
                            text = "Keypad",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                        )

                        // Clear dialed digits button if not empty
                        if (dtmfDigits.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dtmfDigits = ""
                                },
                                modifier = Modifier.size(42.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_backspace),
                                    contentDescription = "Clear",
                                    tint = Color(0xFF9AA0A6),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            Spacer(Modifier.size(42.dp))
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Dialed Digits Display Field
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = dtmfDigits.ifEmpty { "Touch digits for IVR" },
                            fontSize = if (dtmfDigits.isEmpty()) 16.sp else 30.sp,
                            fontWeight = if (dtmfDigits.isEmpty()) FontWeight.Normal else FontWeight.Bold,
                            color = if (dtmfDigits.isEmpty()) Color(0xFF5F6368) else Color.White,
                            letterSpacing = if (dtmfDigits.isEmpty()) 0.sp else 4.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // 12 Dialpad Keys (1 to 9, *, 0, #)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        DialpadKeys.forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                row.forEach { (digit, letters) ->
                                    KeypadDialButton(
                                        digit = digit,
                                        letters = letters,
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            dtmfDigits += digit
                                            onDtmf(digit)
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // Keypad Bottom Actions: End Call FAB + Hide Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // "Hide" pill button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFF222630))
                                .border(1.dp, Color(0xFF333844), RoundedCornerShape(24.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showKeypad = false
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Hide keypad",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF8AB4F8),
                            )
                        }

                        // Prominent End Call button inside keypad
                        CallFabButton(
                            icon = painterResource(R.drawable.ic_call_end),
                            backgroundColor = Color(0xFFE53935),
                            contentDescription = "End Call",
                            size = 64.dp,
                            iconSize = 30.dp,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onHangup()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Standard Phone Circular Action Button (Mute, Speaker, Keypad, Hold, Record).
 */
@Composable
private fun CallActionButton(
    icon: Painter,
    label: String,
    active: Boolean,
    activeColor: Color = Color(0xFFE8EAED),
    activeContentColor: Color = Color(0xFF1F2228),
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "actionScale",
    )
    val containerColor by animateColorAsState(
        targetValue = if (active) activeColor else Color(0xFF222630),
        animationSpec = tween(180),
        label = "actionBg",
    )
    val iconColor by animateColorAsState(
        targetValue = if (active) activeContentColor else Color(0xFFE8EAED),
        animationSpec = tween(180),
        label = "actionIcon",
    )
    val borderColor by animateColorAsState(
        targetValue = if (active) activeColor else Color(0xFF323846),
        animationSpec = tween(180),
        label = "actionBorder",
    )
    val textColor by animateColorAsState(
        targetValue = if (active) Color.White else Color(0xFF9AA0A6),
        animationSpec = tween(180),
        label = "actionText",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(84.dp),
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(containerColor)
                .border(1.dp, borderColor, CircleShape)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = icon,
                contentDescription = label,
                tint = iconColor,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = textColor,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Floating Action Button for Call Management (Hangup, Answer, Decline).
 */
@Composable
private fun CallFabButton(
    icon: Painter,
    backgroundColor: Color,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp = 76.dp,
    iconSize: androidx.compose.ui.unit.Dp = 36.dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "fabScale",
    )
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(elevation = 8.dp, shape = CircleShape)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Clean In-Call Keypad Button with 120Hz RenderThread ripple (0 Compose animation overhead).
 */
@Composable
private fun KeypadDialButton(
    digit: Char,
    letters: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 86.dp, height = 62.dp)
            .clip(RoundedCornerShape(31.dp))
            .background(Color(0xFF222630))
            .border(1.dp, Color(0xFF333946), RoundedCornerShape(31.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = Color(0xFF8AB4F8)),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = digit.toString(),
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
            if (letters.isNotEmpty()) {
                Text(
                    text = letters,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFF9AA0A6),
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

@Composable
private fun SecondaryCallBanner(
    other: CallSnapshot,
    onAnswerWaiting: () -> Unit,
    onDeclineWaiting: () -> Unit,
    onSwapCalls: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val name = other.name.ifEmpty { other.number.ifEmpty { "Second Call" } }
    val isWaiting = !other.outgoing && other.state == CallState.RINGING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1A202C))
            .border(1.dp, Color(0xFF2D3748), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    isWaiting -> "Incoming call waiting…"
                    other.state == CallState.HELD -> "On hold"
                    else -> "Active"
                },
                fontSize = 12.sp,
                color = if (isWaiting) Color(0xFF81C995) else Color(0xFFFFB74D),
            )
        }
        if (isWaiting) {
            Box(
                modifier = Modifier
                    .semantics { contentDescription = "Decline waiting call" }
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x38E53935))
                    .border(1.dp, Color(0x66E53935), RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = Color(0xFFF28B82)),
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDeclineWaiting()
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Decline",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF28B82),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .semantics { contentDescription = "Hold and answer waiting call" }
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF34A853))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = Color.White),
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAnswerWaiting()
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Hold & Answer",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        } else {
            Text(
                text = "Swap",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF8AB4F8),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x288AB4F8))
                    .clickable(onClick = onSwapCalls)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun CallCodecBadge(
    isCallActive: Boolean,
    fallback: CallQuality?,
    qualityFlow: StateFlow<CallQuality?>?,
) {
    val live = qualityFlow?.collectAsStateWithLifecycle()?.value
    val q = live ?: fallback
    if (isCallActive && q != null) {
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E2632))
                .border(1.dp, Color(0xFF2E3E52), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = if (q.isHdVoice) "HD" else q.codec,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8AB4F8),
            )
        }
    }
}
