package com.portalpad.app.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.portalpad.app.data.RemoteGesturePreset

/**
 * One source of truth for Remote gesture-preset icons. The selector rail,
 * selected thumb, and guide browser all use this component so their symbols
 * cannot drift apart.
 */
@Composable
fun GesturePresetIcon(
    preset: RemoteGesturePreset,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val icon = when (preset) {
        RemoteGesturePreset.VOLUME -> Icons.AutoMirrored.Filled.VolumeUp
        RemoteGesturePreset.MEDIA -> Icons.Filled.PlayArrow
        RemoteGesturePreset.SCROLL -> VerticalScrollGestureIcon
        RemoteGesturePreset.SWIPE -> Icons.Filled.Swipe
        RemoteGesturePreset.DPAD -> Icons.Filled.Gamepad
        RemoteGesturePreset.POINTER -> Icons.Filled.Mouse
    }
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier,
    )
}

/**
 * Compact PortalPad-original scroll mark: an outlined mouse and wheel paired
 * with a separate vertical step indicator. It preserves the supplied symbol's
 * instantly recognizable scrolling idea while being rebuilt as a tintable,
 * small-size vector.
 */
private val VerticalScrollGestureIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "PortalPadVerticalScroll",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // Mouse body.
            moveTo(7.5f, 2.0f)
            curveTo(4.2f, 2.0f, 1.8f, 4.7f, 1.8f, 8.0f)
            lineTo(1.8f, 15.5f)
            curveTo(1.8f, 19.3f, 4.2f, 22.0f, 7.5f, 22.0f)
            curveTo(10.8f, 22.0f, 13.2f, 19.3f, 13.2f, 15.5f)
            lineTo(13.2f, 8.0f)
            curveTo(13.2f, 4.7f, 10.8f, 2.0f, 7.5f, 2.0f)
            close()

            // Button split and compact scroll wheel.
            moveTo(7.5f, 2.0f)
            lineTo(7.5f, 6.0f)
            moveTo(1.8f, 9.2f)
            curveTo(3.4f, 8.6f, 4.9f, 7.8f, 6.2f, 7.8f)
            moveTo(13.2f, 9.2f)
            curveTo(11.6f, 8.6f, 10.1f, 7.8f, 8.8f, 7.8f)
            moveTo(7.5f, 6.1f)
            curveTo(6.7f, 6.1f, 6.2f, 6.7f, 6.2f, 7.5f)
            lineTo(6.2f, 10.1f)
            curveTo(6.2f, 10.9f, 6.7f, 11.5f, 7.5f, 11.5f)
            curveTo(8.3f, 11.5f, 8.8f, 10.9f, 8.8f, 10.1f)
            lineTo(8.8f, 7.5f)
            curveTo(8.8f, 6.7f, 8.3f, 6.1f, 7.5f, 6.1f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // Separate up/down chevrons stay readable beside the mouse.
            moveTo(16.0f, 7.0f)
            lineTo(18.8f, 4.1f)
            lineTo(21.6f, 7.0f)
            moveTo(18.8f, 8.8f)
            lineTo(18.8f, 10.0f)
            moveTo(16.0f, 17.0f)
            lineTo(18.8f, 19.9f)
            lineTo(21.6f, 17.0f)
            moveTo(18.8f, 14.0f)
            lineTo(18.8f, 15.2f)
        }
        path(
            fill = SolidColor(Color.Black),
            stroke = null,
        ) {
            // Central scroll-step marker.
            moveTo(20.1f, 12.0f)
            curveTo(20.1f, 12.7f, 19.5f, 13.3f, 18.8f, 13.3f)
            curveTo(18.1f, 13.3f, 17.5f, 12.7f, 17.5f, 12.0f)
            curveTo(17.5f, 11.3f, 18.1f, 10.7f, 18.8f, 10.7f)
            curveTo(19.5f, 10.7f, 20.1f, 11.3f, 20.1f, 12.0f)
            close()
        }
    }.build()
}
