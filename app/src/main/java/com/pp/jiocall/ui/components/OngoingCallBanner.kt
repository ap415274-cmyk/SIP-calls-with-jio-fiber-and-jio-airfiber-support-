package com.pp.jiocall.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pp.jiocall.R
import com.pp.jiocall.sip.CallQuality
import com.pp.jiocall.sip.CallSnapshot
import com.pp.jiocall.sip.CallState
import com.pp.jiocall.ui.callStateLabel
import com.pp.jiocall.ui.formatDuration
import com.pp.jiocall.ui.theme.GoogleCallAnswer
import com.pp.jiocall.ui.theme.GoogleCallEnd
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Persistent One-UI / Google-Phone inspired top Ongoing Call Bar.
 * Sits cleanly at the top when minimized across Dialer, Recents, Contacts, Settings, and Accounts,
 * with live call duration, waveform indicator, quick Mute/Speaker toggles, and 1-tap Hang up.
 */
@Composable
fun OngoingCallBanner(
    call: CallSnapshot,
    speakerOn: Boolean = false,
    onClick: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: (() -> Unit)? = null,
    onToggleSpeaker: (() -> Unit)? = null,
    onAnswer: (() -> Unit)? = null,
    qualityFlow: StateFlow<CallQuality?>? = null,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var elapsed by remember(call.callId, call.startedAt) {
        mutableLongStateOf(
            if (call.startedAt > 0) ((System.currentTimeMillis() - call.startedAt) / 1000).coerceAtLeast(0L) else 0L,
        )
    }

    LaunchedEffect(call.startedAt, call.state) {
        while (call.state == CallState.ACTIVE || call.state == CallState.HELD) {
            elapsed = if (call.startedAt > 0) ((System.currentTimeMillis() - call.startedAt) / 1000).coerceAtLeast(0L) else 0L
            delay(1_000)
        }
    }

    val callerName = call.name.ifEmpty { call.number.ifEmpty { "Call" } }
    val isRinging = call.state == CallState.RINGING || call.state == CallState.DIALING

    val bgBrush = when (call.state) {
        CallState.ACTIVE -> Brush.horizontalGradient(listOf(Color(0xFF143825), Color(0xFF1B2B23)))
        CallState.HELD -> Brush.horizontalGradient(listOf(Color(0xFF3A2A14), Color(0xFF282017)))
        else -> Brush.horizontalGradient(listOf(Color(0xFF162B47), Color(0xFF1B2230)))
    }
    val borderColor by animateColorAsState(
        targetValue = when (call.state) {
            CallState.ACTIVE -> Color(0xFF2C6B47)
            CallState.HELD -> Color(0xFF70501E)
            else -> Color(0xFF2C4D7D)
        },
        animationSpec = tween(250),
        label = "bannerBorder",
    )
    val accentColor = when (call.state) {
        CallState.ACTIVE -> Color(0xFF7AE59A)
        CallState.HELD -> Color(0xFFFFCA6C)
        else -> Color(0xFF8AB4F8)
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent,
        shadowElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(bgBrush)
            .border(1.dp, borderColor, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: Live Call Pill Badge / Equalizer (infinite transitions isolated to active/ringing states)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0x28FFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    call.state == CallState.ACTIVE && !call.muted -> LiveEqualizerBars(accentColor = accentColor)
                    isRinging -> PulsingRingIcon(muted = call.muted)
                    else -> StaticBannerIcon(muted = call.muted, held = call.state == CallState.HELD)
                }
            }

            Spacer(Modifier.width(10.dp))

            // Caller name + live duration / HD indicator
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = callerName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    BannerHdBadge(
                        answered = call.answered,
                        fallback = call.quality,
                        qualityFlow = qualityFlow,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when {
                            call.state == CallState.ACTIVE -> formatDuration(elapsed)
                            call.state == CallState.HELD -> "On hold · ${formatDuration(elapsed)}"
                            else -> callStateLabel(call.state, call.outgoing)
                        },
                        fontSize = 12.sp,
                        color = accentColor,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Quick Working Actions: Mute / Speaker / Hang up (or Answer + Decline when ringing)
            if (!call.outgoing && call.state == CallState.RINGING && onAnswer != null) {
                BannerActionCircle(
                    iconRes = R.drawable.ic_call,
                    contentDescription = "Answer Call",
                    backgroundColor = GoogleCallAnswer,
                    iconTint = Color.White,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAnswer()
                    },
                )
                Spacer(Modifier.width(8.dp))
            } else {
                if (onToggleMute != null && call.answered) {
                    BannerActionCircle(
                        iconRes = if (call.muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                        contentDescription = if (call.muted) "Unmute" else "Mute",
                        backgroundColor = if (call.muted) Color.White else Color(0x28FFFFFF),
                        iconTint = if (call.muted) Color(0xFF14181F) else Color.White,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleMute()
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (onToggleSpeaker != null) {
                    BannerActionCircle(
                        iconRes = R.drawable.ic_volume_up,
                        contentDescription = if (speakerOn) "Earpiece" else "Speaker",
                        backgroundColor = if (speakerOn) Color(0xFFA8C7FA) else Color(0x28FFFFFF),
                        iconTint = if (speakerOn) Color(0xFF042B63) else Color.White,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleSpeaker()
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }

            // Prominent Hang up Button right inside the top bar
            BannerActionCircle(
                iconRes = R.drawable.ic_call_end,
                contentDescription = "Hang up",
                backgroundColor = GoogleCallEnd,
                iconTint = Color.White,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHangup()
                },
            )
        }
    }
}

@Composable
private fun LiveEqualizerBars(accentColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "bannerEqualizer")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(680, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wavePhase",
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val s1 = 0.42f + 0.58f * wavePhase
        val s2 = 1.0f - 0.56f * wavePhase
        val s3 = 0.48f + 0.52f * (1f - wavePhase)
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .graphicsLayer { scaleY = s1 }
                .clip(RoundedCornerShape(2.dp))
                .background(accentColor),
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .graphicsLayer { scaleY = s2 }
                .clip(RoundedCornerShape(2.dp))
                .background(accentColor),
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .graphicsLayer { scaleY = s3 }
                .clip(RoundedCornerShape(2.dp))
                .background(accentColor),
        )
    }
}

@Composable
private fun PulsingRingIcon(muted: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "bannerRing")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    Box(
        modifier = Modifier
            .size(32.dp)
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
            }
            .clip(CircleShape)
            .background(GoogleCallAnswer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_call),
            contentDescription = "Active call",
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun StaticBannerIcon(muted: Boolean, held: Boolean) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (held) Color(0xFFE09F3E) else GoogleCallAnswer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_call),
            contentDescription = "Active call",
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun BannerActionCircle(
    iconRes: Int,
    contentDescription: String,
    backgroundColor: Color,
    iconTint: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "bannerBtnScale",
    )
    Box(
        modifier = Modifier
            .size(36.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun BannerHdBadge(
    answered: Boolean,
    fallback: CallQuality?,
    qualityFlow: StateFlow<CallQuality?>?,
) {
    val live = qualityFlow?.collectAsStateWithLifecycle()?.value
    val q = live ?: fallback
    if (q?.isHdVoice == true && answered) {
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0x338AB4F8))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(
                text = "HD",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFB3D1FF),
            )
        }
    }
}
