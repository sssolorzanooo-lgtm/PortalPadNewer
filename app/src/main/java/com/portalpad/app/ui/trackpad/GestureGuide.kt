package com.portalpad.app.ui.trackpad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.portalpad.app.data.RemoteGesturePreset
import com.portalpad.app.ui.common.GesturePresetIcon
import com.portalpad.app.ui.theme.PhoneAccent
import com.portalpad.app.ui.theme.PhoneDanger
import com.portalpad.app.ui.theme.PhoneOnSurface
import com.portalpad.app.ui.theme.PhoneOnSurfaceMuted
import com.portalpad.app.ui.theme.PhonePrimaryBright
import com.portalpad.app.ui.theme.PhoneSuccess
import com.portalpad.app.ui.theme.PhoneSurface
import com.portalpad.app.ui.theme.PhoneSurfaceElevated

/**
 * A small, subtle "Guide" pill rendered on the trackpad/air-mouse surface (as a
 * sibling overlay, so its taps are consumed here and never reach the gesture
 * surface beneath it) plus the gesture-reference dialog it opens.
 *
 * Content is mode-aware: Air Mouse shares the trackpad's touch gestures and
 * ADDS phone-motion pointing, so its guide shows the motion line on top of the
 * same gesture list.
 */

/** One gesture row: bold name + plain description. */
private data class GestureRow(val name: String, val desc: String)

@Composable
fun GuidePill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(PhoneSurface.copy(alpha = 0.55f))
            .border(
                1.dp,
                PhoneOnSurfaceMuted.copy(alpha = 0.35f),
                androidx.compose.foundation.shape.RoundedCornerShape(50),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Guide",
            color = PhoneOnSurfaceMuted,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun GestureGuideDialog(
    isAirMouse: Boolean,
    onDismiss: () -> Unit,
) {
    val edgeScrollEnabled = com.portalpad.app.PortalPadApp.instance.prefs.bool(
        com.portalpad.app.data.PreferencesRepository.Keys.EDGE_SCROLL,
        default = true,
    ).collectAsState(initial = true).value
    val edgeScrollSide = com.portalpad.app.PortalPadApp.instance.prefs.string(
        com.portalpad.app.data.PreferencesRepository.Keys.EDGE_SCROLL_SIDE,
        default = "right",
    ).collectAsState(initial = "right").value
    val prefs = com.portalpad.app.PortalPadApp.instance.prefs
    val gUp = prefs.gestureSwipeUp.collectAsState(initial = com.portalpad.app.data.GestureAction.NOTIF_CLOSE).value
    val gDown = prefs.gestureSwipeDown.collectAsState(initial = com.portalpad.app.data.GestureAction.NOTIF_OPEN).value
    val gLeft = prefs.gestureSwipeLeft.collectAsState(initial = com.portalpad.app.data.GestureAction.HOME).value
    val gRight = prefs.gestureSwipeRight.collectAsState(initial = com.portalpad.app.data.GestureAction.BACK).value
    val gUpApp = prefs.gestureAppUp.collectAsState(initial = null).value
    val gDownApp = prefs.gestureAppDown.collectAsState(initial = null).value
    val gLeftApp = prefs.gestureAppLeft.collectAsState(initial = null).value
    val gRightApp = prefs.gestureAppRight.collectAsState(initial = null).value
    fun gLabel(a: com.portalpad.app.data.GestureAction, app: com.portalpad.app.data.AppEntry?): String =
        if (a == com.portalpad.app.data.GestureAction.LAUNCH_APP && !app?.label.isNullOrBlank()) app!!.label else a.short
    val oneFingerRows = buildList {
        add(GestureRow("Slide", "move the cursor"))
        add(GestureRow("Tap", "left click"))
        add(GestureRow("Touch & hold", "select the word or item under the cursor"))
        add(
            GestureRow(
                "Touch & hold, then move",
                "drag a window, or select text; grab a window edge to resize",
            ),
        )
        if (edgeScrollEnabled) {
            val sideLabel = if (edgeScrollSide == "left") "Left" else "Right"
            add(GestureRow("$sideLabel edge strip", "drag one finger to scroll"))
        }
    }
    val twoFingerRows = listOf(
        GestureRow("Slide", "scroll"),
        GestureRow("Tap", "right click"),
        GestureRow("Pinch", "zoom"),
    )
    val threeFingerRows = buildList {
        // Reflect the user-configured swipe actions. Hidden if set to None.
        if (gRight != com.portalpad.app.data.GestureAction.NONE) {
            add(GestureRow("Swipe right", gLabel(gRight, gRightApp)))
        }
        if (gLeft != com.portalpad.app.data.GestureAction.NONE) {
            add(GestureRow("Swipe left", gLabel(gLeft, gLeftApp)))
        }
        if (gDown != com.portalpad.app.data.GestureAction.NONE) {
            add(GestureRow("Swipe down", gLabel(gDown, gDownApp)))
        }
        if (gUp != com.portalpad.app.data.GestureAction.NONE) {
            add(GestureRow("Swipe up", gLabel(gUp, gUpApp)))
        }
    }
    val sections = listOf(
        "1 finger" to oneFingerRows,
        "2 fingers" to twoFingerRows,
        "3 fingers" to threeFingerRows,
    )
    var viewedSection by remember { mutableStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val guideMaxHeight = minOf(560.dp, maxHeight - 32.dp)
            val outsideInteraction = remember { MutableInteractionSource() }
            val guideInteraction = remember { MutableInteractionSource() }

            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = outsideInteraction,
                        indication = null,
                        onClick = onDismiss,
                    ),
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .heightIn(max = guideMaxHeight)
                    .clip(RoundedCornerShape(20.dp))
                    .background(PhoneSurface)
                    .border(
                        1.dp,
                        PhoneAccent.copy(alpha = 0.4f),
                        RoundedCornerShape(20.dp),
                    )
                    .clickable(
                        interactionSource = guideInteraction,
                        indication = null,
                        onClick = {},
                    )
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (isAirMouse) "Air Mouse gestures" else "Trackpad gestures",
                    color = PhonePrimaryBright,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                if (isAirMouse) {
                    Text(
                        "Move the phone to point the cursor in the air. Touch gestures match Trackpad.",
                        color = PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sections.forEachIndexed { index, (label, _) ->
                        val viewed = index == viewedSection
                        Box(
                            Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (viewed) {
                                        PhoneAccent.copy(alpha = 0.24f)
                                    } else {
                                        PhoneSurfaceElevated
                                    },
                                )
                                .border(
                                    if (viewed) 1.5.dp else 1.dp,
                                    if (viewed) {
                                        PhonePrimaryBright
                                    } else {
                                        PhoneOnSurfaceMuted.copy(alpha = 0.24f)
                                    },
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable {
                                    if (!viewed) {
                                        viewedSection = index
                                        com.portalpad.app.PortalPadApp.instance.injector
                                            .buzz(longPress = false)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                color = if (viewed) PhonePrimaryBright else PhoneOnSurfaceMuted,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                SectionLabel(
                    sections[viewedSection].first.replaceFirstChar { it.uppercase() },
                )
                val viewedRows = sections[viewedSection].second
                if (viewedRows.isEmpty()) {
                    Text(
                        "No three-finger actions are assigned.",
                        color = PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    viewedRows.forEach { row ->
                        GestureLine(row, shortened = viewedSection == 2)
                    }
                }
                if (viewedSection == 2) {
                    Text(
                "The notifications panel shows on the external display — navigate it with the trackpad cursor.",
                color = PhoneOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
                }

                Text(
                    "Tap outside to close.",
                    color = PhoneDanger,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = PhonePrimaryBright,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun GestureLine(row: GestureRow, shortened: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "•",
                color = PhoneOnSurfaceMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                row.name,
                color = PhoneOnSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(140.dp),
            )
            if (shortened) {
                // Three-finger rows show a user-assigned action that can be long;
                // keep them to one line with an ellipsis.
                Text(
                    row.desc,
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                // One/two-finger + air-mouse rows show their full description,
                // wrapping to a second line rather than truncating.
                Text(
                    row.desc,
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Subtle hairline so each gesture reads as its own unit, especially now
        // that longer descriptions wrap to uneven heights.
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp),
            thickness = 1.dp,
            color = PhoneOnSurfaceMuted.copy(alpha = 0.12f),
        )
    }
}

private fun remoteGestureRows(preset: RemoteGesturePreset): List<GestureRow> =
    when (preset) {
        RemoteGesturePreset.DPAD -> listOf(
            GestureRow("Swipe", "send one D-pad direction"),
            GestureRow("Swipe + hold", "repeat that direction until released"),
            GestureRow("Tap", "OK / select"),
            GestureRow("Tap + hold", "Back"),
        )
        RemoteGesturePreset.SCROLL -> listOf(
            GestureRow("Drag", "scroll follows your finger horizontally or vertically"),
            GestureRow("Swipe + hold", "pause at the end to scroll continuously until released"),
            GestureRow("Tap", "click the foreground app"),
            GestureRow("Tap + hold", "long-click the foreground app"),
        )
        RemoteGesturePreset.SWIPE -> listOf(
            GestureRow("Swipe", "one live touch follows your finger until release"),
            GestureRow("Swipe + hold", "keep holding, then move to continue the drag"),
            GestureRow("Tap", "click the foreground app's center"),
            GestureRow("Tap + hold", "long-click the foreground app's center"),
        )
        RemoteGesturePreset.MEDIA -> listOf(
            GestureRow("Swipe left / right", "rewind / fast-forward 10 seconds"),
            GestureRow("Hold left / right", "continue rewinding / fast-forwarding"),
            GestureRow("Swipe up / down", "previous / next item"),
            GestureRow("Hold up / down", "one safe previous / next action; never repeats"),
            GestureRow("Tap", "play / pause"),
            GestureRow("Tap + hold", "stop"),
        )
        RemoteGesturePreset.VOLUME -> listOf(
            GestureRow("Turn clockwise", "raise media volume"),
            GestureRow("Turn counterclockwise", "lower media volume"),
            GestureRow("Hold still", "do nothing; only circular movement changes volume"),
            GestureRow("Hold the mute button", "mute / unmute intentionally"),
        )
        RemoteGesturePreset.POINTER -> listOf(
            GestureRow("Slide", "move the external cursor"),
            GestureRow("Tap", "left click"),
            GestureRow("Hold, then move", "drag, resize, or select text"),
            GestureRow("Hold, then release", "right click"),
        )
    }

/**
 * Browser for all six Remote > Gesture maps. [preset] remains the real active
 * selection; browsing this dialog never changes it.
 */
@Composable
fun RemoteGestureGuideDialog(
    preset: RemoteGesturePreset,
    onDismiss: () -> Unit,
) {
    val order = remember {
        listOf(
            RemoteGesturePreset.POINTER,
            RemoteGesturePreset.SCROLL,
            RemoteGesturePreset.SWIPE,
            RemoteGesturePreset.DPAD,
            RemoteGesturePreset.MEDIA,
            RemoteGesturePreset.VOLUME,
        )
    }
    var viewedPreset by remember(preset) { mutableStateOf(preset) }
    val rows = remoteGestureRows(viewedPreset)

    fun showGuide(next: RemoteGesturePreset) {
        if (next == viewedPreset) return
        viewedPreset = next
        com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val guideMaxHeight = minOf(560.dp, maxHeight - 32.dp)
            val outsideInteraction = remember { MutableInteractionSource() }
            val guideInteraction = remember { MutableInteractionSource() }

            // usePlatformDefaultWidth=false gives the guide enough width, but
            // also makes Android's Dialog window full-screen. Supply our own
            // outside-dismiss layer because there is no OS-level "outside" left.
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = outsideInteraction,
                        indication = null,
                        onClick = onDismiss,
                    ),
            )

            Column(
                Modifier
                    // Use the same 20dp page margin as the display selector
                    // shared by Remote, Trackpad, and Air Mouse.
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    // Four-row guides wrap tightly; Media naturally grows
                    // taller, while every guide retains the same safe ceiling.
                    .heightIn(max = guideMaxHeight)
                    .clip(RoundedCornerShape(20.dp))
                    .background(PhoneSurface)
                    .border(
                        1.dp,
                        PhoneAccent.copy(alpha = 0.4f),
                        RoundedCornerShape(20.dp),
                    )
                    // Consume taps within the card so only the surrounding
                    // transparent layer dismisses it. Nested guide buttons and
                    // horizontal guide swipes continue to receive input.
                    .clickable(
                        interactionSource = guideInteraction,
                        indication = null,
                        onClick = {},
                    )
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Gesture guides",
                    color = PhonePrimaryBright,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Browse without changing the active preset. Tap an icon or swipe the guide left and right.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    order.forEach { candidate ->
                        val viewed = candidate == viewedPreset
                        Box(
                            Modifier
                                .weight(1f)
                                .height(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (viewed) {
                                        PhoneAccent.copy(alpha = 0.24f)
                                    } else {
                                        PhoneSurfaceElevated
                                    },
                                )
                                .border(
                                    if (viewed) 1.5.dp else 1.dp,
                                    if (viewed) {
                                        PhonePrimaryBright
                                    } else {
                                        PhoneOnSurfaceMuted.copy(alpha = 0.24f)
                                    },
                                    RoundedCornerShape(14.dp),
                                )
                                .clickable { showGuide(candidate) },
                            contentAlignment = Alignment.Center,
                        ) {
                            GesturePresetIcon(
                                preset = candidate,
                                tint = if (viewed) PhonePrimaryBright else PhoneOnSurfaceMuted,
                                contentDescription = "${candidate.label} guide",
                                modifier = Modifier.size(23.dp),
                            )
                            if (candidate == preset) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(PhoneSuccess),
                                )
                            }
                        }
                    }
                }

                Text(
                    "${viewedPreset.label} gestures",
                    color = PhonePrimaryBright,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )

                Column(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(viewedPreset) {
                            var totalDrag = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { totalDrag = 0f },
                                onHorizontalDrag = { change, amount ->
                                    totalDrag += amount
                                    change.consume()
                                },
                                onDragEnd = {
                                    val index = order.indexOf(viewedPreset)
                                    val nextIndex = when {
                                        totalDrag <= -48.dp.toPx() ->
                                            (index + 1).coerceAtMost(order.lastIndex)
                                        totalDrag >= 48.dp.toPx() ->
                                            (index - 1).coerceAtLeast(0)
                                        else -> index
                                    }
                                    showGuide(order[nextIndex])
                                },
                                onDragCancel = { totalDrag = 0f },
                            )
                        },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionLabel("One finger")
                    rows.forEach { GestureLine(it) }
                }

                Text(
                    "Tap outside to close.",
                    color = PhoneDanger,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}
