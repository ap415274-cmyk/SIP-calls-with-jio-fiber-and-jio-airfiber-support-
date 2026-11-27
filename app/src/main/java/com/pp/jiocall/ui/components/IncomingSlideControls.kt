package com.pp.jiocall.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pp.jiocall.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Dual Samsung One UI / Pixel Phone inspired zero-lag radial slide handles for Decline and Answer.
 *
 * Touch drags write synchronously into primitive [MutableFloatState] values read strictly inside
 * GPU draw/transform modifiers ([graphicsLayer] and [Canvas]) so active dragging allocates zero
 * coroutines and triggers zero Compose recomposition. Stationary pocket taps cannot accidentally
 * trigger Answer/Decline and show a brief slide bounce hint; deliberate drags past
 * [TRIGGER_THRESHOLD_DP] execute immediately on release.
 */
@Composable
fun IncomingSlideControlBar(
    onDecline: () -> Unit,
    onAnswer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "incomingHandlePulse")
    val idleRingProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "idleRing",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SlideActionHandle(
            iconRes = R.drawable.ic_call_end,
            label = "Decline",
            slideHint = "Slide to decline",
            baseColor = Color(0xFFE53935),
            glowColor = Color(0xFFF28B82),
            idleRingProgressProvider = { idleRingProgress },
            onTrigger = onDecline,
            modifier = Modifier.weight(1f),
        )
        SlideActionHandle(
            iconRes = R.drawable.ic_call,
            label = "Answer",
            slideHint = "Slide to answer",
            baseColor = Color(0xFF2EB857),
            glowColor = Color(0xFF81C995),
            idleRingProgressProvider = { idleRingProgress },
            onTrigger = onAnswer,
            modifier = Modifier.weight(1f),
        )
    }
}

private val TOUCH_ZONE_SIZE = 136.dp
private val THUMB_SIZE = 68.dp
private val ICON_SIZE = 30.dp
private val TRIGGER_THRESHOLD_DP = 44.dp
private val MAX_DRAG_DP = 64.dp
private val TAP_SLOP_DP = 10.dp

@Composable
private fun SlideActionHandle(
    @DrawableRes iconRes: Int,
    label: String,
    slideHint: String,
    baseColor: Color,
    glowColor: Color,
    idleRingProgressProvider: () -> Float,
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val thresholdPx = with(density) { TRIGGER_THRESHOLD_DP.toPx() }
    val maxDragPx = with(density) { MAX_DRAG_DP.toPx() }
    val tapSlopPx = with(density) { TAP_SLOP_DP.toPx() }

    // Synchronous GPU-only coordinates during active touch (zero coroutine churn on MOVE)
    var currentX by remember { mutableFloatStateOf(0f) }
    var currentY by remember { mutableFloatStateOf(0f) }
    var currentScale by remember { mutableFloatStateOf(1f) }
    var isPressed by remember { mutableStateOf(false) }
    var isTriggered by remember { mutableStateOf(false) }
    var showTapHint by remember { mutableStateOf(false) }

    val resetSpring = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scaleAnim = remember { Animatable(1f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = label
                onClick(label = label) {
                    onTrigger()
                    true
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(TOUCH_ZONE_SIZE)
                .pointerInput(onTrigger) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (isTriggered) return@awaitEachGesture
                        settleJob?.cancel()
                        isPressed = true
                        showTapHint = false
                        currentX = 0f
                        currentY = 0f
                        currentScale = 1.10f
                        var maxTravelPx = 0f
                        var reachedThreshold = false
                        var rawX = 0f
                        var rawY = 0f

                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val delta = change.positionChange()
                            if (delta.x != 0f || delta.y != 0f) {
                                change.consume()
                                rawX += delta.x
                                rawY += delta.y
                                val dist = hypot(rawX, rawY)
                                if (dist > maxTravelPx) maxTravelPx = dist
                                val factor = if (dist > maxDragPx && dist > 0f) maxDragPx / dist else 1f
                                currentX = rawX * factor
                                currentY = rawY * factor

                                val nowCrossed = dist >= thresholdPx
                                if (nowCrossed && !reachedThreshold) {
                                    reachedThreshold = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentScale = 1.18f
                                } else if (!nowCrossed && reachedThreshold) {
                                    reachedThreshold = false
                                    currentScale = 1.10f
                                }
                            }
                        }

                        isPressed = false
                        val finalDist = hypot(currentX, currentY)
                        if (finalDist >= thresholdPx) {
                            isTriggered = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Hold triggered visual state cleanly while crossfading to active/closing,
                            // avoiding any awkward bounce-back before SIP transitions.
                            onTrigger()
                        } else {
                            val startOffset = Offset(currentX, currentY)
                            val startScale = currentScale
                            settleJob = scope.launch {
                                launch {
                                    scaleAnim.snapTo(startScale)
                                    scaleAnim.animateTo(1f, spring(stiffness = Spring.StiffnessMedium)) {
                                        currentScale = value
                                    }
                                }
                                resetSpring.snapTo(startOffset)
                                if (maxTravelPx < tapSlopPx) {
                                    showTapHint = true
                                    resetSpring.animateTo(Offset(0f, -16f), tween(85)) {
                                        currentX = value.x
                                        currentY = value.y
                                    }
                                    resetSpring.animateTo(
                                        Offset.Zero,
                                        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
                                    ) {
                                        currentX = value.x
                                        currentY = value.y
                                    }
                                    delay(1300)
                                    showTapHint = false
                                } else {
                                    resetSpring.animateTo(
                                        Offset.Zero,
                                        spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMedium),
                                    ) {
                                        currentX = value.x
                                        currentY = value.y
                                    }
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val minRadius = (THUMB_SIZE.toPx() / 2f) + 6.dp.toPx()
                val ringRadius = (THUMB_SIZE.toPx() / 2f) + thresholdPx
                val dist = hypot(currentX, currentY)
                val progress = (dist / thresholdPx).coerceIn(0f, 1f)
                val crossed = dist >= thresholdPx

                if (!isPressed && dist < 1f) {
                    val pulse = idleRingProgressProvider()
                    val waveR = minRadius + (ringRadius - minRadius) * pulse
                    val waveAlpha = (1f - pulse) * 0.34f
                    drawCircle(
                        color = glowColor.copy(alpha = waveAlpha),
                        radius = waveR,
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    drawCircle(
                        color = glowColor.copy(alpha = 0.16f),
                        radius = ringRadius,
                        center = center,
                        style = Stroke(width = 1.2.dp.toPx()),
                    )
                } else {
                    val activeRadius = minRadius + (ringRadius - minRadius) * (0.45f + 0.55f * progress)
                    val fillAlpha = if (crossed) 0.28f else 0.10f + 0.12f * progress
                    val strokeAlpha = if (crossed) 0.92f else 0.32f + 0.45f * progress
                    drawCircle(
                        color = baseColor.copy(alpha = fillAlpha),
                        radius = activeRadius,
                        center = center,
                    )
                    drawCircle(
                        color = glowColor.copy(alpha = strokeAlpha),
                        radius = ringRadius,
                        center = center,
                        style = Stroke(width = if (crossed) 2.5.dp.toPx() else 1.5.dp.toPx()),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(THUMB_SIZE)
                    .graphicsLayer {
                        translationX = currentX
                        translationY = currentY
                        scaleX = currentScale
                        scaleY = currentScale
                    }
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(baseColor, baseColor.copy(alpha = 0.90f)),
                        ),
                        shape = CircleShape,
                    )
                    .border(1.5.dp, Color(0x45FFFFFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(ICON_SIZE),
                )
            }
        }

        Spacer(Modifier.height(2.dp))
        Text(
            text = if (showTapHint) slideHint else label,
            fontSize = 13.5.sp,
            fontWeight = if (showTapHint) FontWeight.SemiBold else FontWeight.Medium,
            color = glowColor.copy(alpha = if (showTapHint) 1f else 0.92f),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * Single GPU Canvas halo behind the incoming call ContactAvatar for 120Hz zero-jank pulse.
 */
@Composable
fun OneUiIncomingAvatarHalo(
    ringColor: Color = Color(0xFF2EB857),
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "avatarHaloPulse")
    val p1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "halo1",
    )
    val p2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, delayMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "halo2",
    )
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val innerR = size.minDimension * 0.34f
        val maxR = size.minDimension * 0.49f
        val r1 = innerR + (maxR - innerR) * p1
        val r2 = innerR + (maxR - innerR) * p2
        drawCircle(
            color = ringColor.copy(alpha = (1f - p1) * 0.32f),
            radius = r1,
            center = center,
        )
        drawCircle(
            color = ringColor.copy(alpha = (1f - p1) * 0.55f),
            radius = r1,
            center = center,
            style = Stroke(width = 1.6.dp.toPx()),
        )
        drawCircle(
            color = ringColor.copy(alpha = (1f - p2) * 0.35f),
            radius = r2,
            center = center,
            style = Stroke(width = 1.2.dp.toPx()),
        )
    }
}
