package notes.app.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** The web app's --ease-out. */
val EaseOut = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** True when the system's "Remove animations" is on: decorative motion stops, state changes snap. */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun systemReducesMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** 0.4 → 1 → 0.4 while [active]; still at 1 otherwise or with reduced motion. */
@Composable
fun breathing(active: Boolean): Float {
    if (!active || LocalReduceMotion.current) return 1f
    val pulse by rememberInfiniteTransition("breathing").animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathing"
    )
    return pulse
}

/** Pressed controls sink slightly. */
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.96f): Modifier = composed {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) pressed else 1f,
        spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "press"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Shakes once each time [trigger] changes to a new value above zero (a rejected form). */
fun Modifier.shake(trigger: Int): Modifier = composed {
    val offset = remember { Animatable(0f) }
    val reduce = LocalReduceMotion.current
    val px = with(LocalDensity.current) { 6.dp.toPx() }
    LaunchedEffect(trigger) {
        if (trigger == 0 || reduce) return@LaunchedEffect
        offset.animateTo(
            0f,
            keyframes {
                durationMillis = 320
                -px at 60
                px at 140
                -px / 2 at 220
                0f at 320
            }
        )
    }
    graphicsLayer { translationX = offset.value }
}
