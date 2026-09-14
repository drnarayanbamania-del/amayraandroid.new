package com.amayra.maya.avatar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.amayra.maya.settings.SettingsRepository

/**
 * Canvas host for the avatar. The animation loop runs ONLY while this composable
 * is composed-and-visible, so backgrounding the app freezes the avatar with zero
 * battery cost. Style/size/animation settings all come from Settings — no
 * parallel state. Tap gestures are routed to Live2D reactions when active.
 */
@Composable
fun MayaAvatarView(
    controller: AvatarController,
    settings: SettingsRepository,
    modifier: Modifier = Modifier,
    sizeOverride: Dp? = null,
    onTap: (() -> Unit)? = null
) {
    val scene by controller.scene.collectAsState()
    val prefs by settings.prefs.collectAsState(initial = null)
    val activeFlow = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }

    // Animation lifetime == visibility lifetime.
    LaunchedEffect(Unit) { activeFlow.value = true }
    DisposableEffect(Unit) {
        onDispose { activeFlow.value = false }
    }

    // Start the frame loop exactly once per controller instance.
    LaunchedEffect(controller) { controller.start(activeFlow, settings) }

    val renderer = AvatarRenderers.byId(prefs?.avatarStyle ?: "anime_2d")
    val dpSize = sizeOverride ?: when (prefs?.avatarSize ?: "medium") {
        "small" -> 150.dp
        "large" -> 300.dp
        else -> 220.dp
    }

    Canvas(
        modifier = modifier
            .size(dpSize)
            .pointerInput(onTap) {
                if (onTap != null) detectTapGestures { onTap() }
            }
    ) {
        renderer.draw(this, scene)
    }
}
