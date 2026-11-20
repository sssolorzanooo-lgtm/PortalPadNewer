package com.portalpad.app.ui.trackpad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalpad.app.PortalPadApp
import com.portalpad.app.service.PortalPadAccessibilityService
import com.portalpad.app.service.PortalPadForegroundService
import com.portalpad.app.ui.theme.PhoneOnSurface
import com.portalpad.app.ui.theme.PhoneOnSurfaceDim
import com.portalpad.app.ui.theme.PhoneOnSurfaceMuted
import com.portalpad.app.ui.theme.PhonePrimary
import com.portalpad.app.ui.theme.PhonePrimaryBright
import com.portalpad.app.ui.theme.PhonePrimaryDim
import com.portalpad.app.ui.theme.PhoneSurface
import com.portalpad.app.ui.theme.PhoneSurfaceElevated
import com.portalpad.app.ui.theme.PhoneSurfaceVariant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * Radial window-actions menu for the trackpad / air-mouse interface, opened by
 * tapping the arrange button. Mirrors the top bar's eight desktop-window actions:
 * arrange-evenly is the center (one-tap, the safe default); the seven others sit
 * on an upper ring + a lower "window size" arch. Ring/arch actions arm on first
 * tap (showing their name) and run on a second tap; the center runs immediately.
 * Actions that don't apply at the current window count are dimmed and inert.
 * The unavailable caption action stays dimmed and uses the wheel's label pill
 * to explain why it cannot run instead of silently ignoring the tap.
 *
 * State comes from the shared [com.portalpad.app.service.WindowMonitor] snapshot +
 * the saved-session flow (same source the top bar uses). Actions are dispatched to
 * the top-bar overlay via [PortalPadForegroundService.runWindowAction], which owns
 * the tested handlers — closeAll runs directly since the two-tap arm is the confirm.
 */
@Composable
fun WindowRadialOverlay(onDismiss: () -> Unit) {
    val app = PortalPadApp.instance
    val snap by app.windowMonitor.snapshot.collectAsState()
    val session by app.prefs.lastSession.collectAsState(initial = null)
    val hasCaption by PortalPadAccessibilityService.captionAvailable.collectAsState()

    val count = snap.tasks.count {
        it.visible &&
            !it.packageName.contains("launcher", ignoreCase = true) &&
            !it.packageName.contains("nexuslauncher", ignoreCase = true) &&
            it.packageName != com.portalpad.app.BuildConfig.APPLICATION_ID
    }
    val hasMaximized = snap.hasMaximized
    val hasSession = session?.windows?.isNotEmpty() == true
    // Windows a maximize could still act on. Same gate the top bar uses: with
    // every open window already maximized, the slot dims instead of offering a
    // maximize that would do nothing.
    val maximizable = snap.tasks.count {
        it.visible &&
            !it.packageName.contains("launcher", ignoreCase = true) &&
            !it.packageName.contains("nexuslauncher", ignoreCase = true) &&
            it.packageName != com.portalpad.app.BuildConfig.APPLICATION_ID &&
            it.taskId != snap.maximizedId &&
            !app.freeform.isMaximizedPackage(it.packageName)
    }

    fun isLive(id: String): Boolean = when (id) {
        "arrangeEvenly", "arrangeInOrder", "stack" -> count >= 2
        "restoreSession" -> hasSession
        "restoreFreeform" -> hasMaximized
        "maximize" -> maximizable >= 1
        "windowTopBar" -> count >= 1 && hasCaption
        else -> count >= 1 // minimize, closeAll
    }

    var armed by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hasCaption, count) {
        if (!hasCaption && armed == "windowTopBar") armed = null
        feedback = when {
            hasCaption -> null
            feedback == null -> null
            count == 0 -> "Open a window to check support"
            else -> "Window title bar unavailable"
        }
    }

    fun fire(id: String) {
        com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
        PortalPadForegroundService.runWindowAction(id)
        onDismiss()
    }

    fun tap(id: String) {
        if (id == "windowTopBar" && !isLive(id)) {
            com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
            armed = null
            feedback = if (count == 0) {
                "Open a window to check support"
            } else {
                "Window title bar unavailable"
            }
            return
        }
        if (!isLive(id)) return
        feedback = null
        if (armed == id) {
            fire(id)
        } else {
            com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
            armed = id
        }
    }

    val countText = when (count) {
        0 -> "No windows open"
        1 -> "1 window open"
        else -> "$count windows open"
    }

    // Stay in TrackpadActivity's full-display window. Pixel limits a Compose
    // Dialog to its app frame, leaving a bottom strip outside any dialog scrim.
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    com.portalpad.app.ui.common.TransparentWheelNavigationBar()
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .padding(bottom = 72.dp)
                    .pointerInput(Unit) { detectTapGestures { } },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Mode exit, ABOVE the wheel rather than a slot on it, on purpose:
                // the ring/arch angles mirror each other by design, every slot is a
                // window operation while this changes what the whole wheel means,
                // and wheel slots need a tap-to-arm before their label shows - wrong
                // for an action that closes every window. A labelled pill explains
                // itself before committing. Styled as a CONTROL (elevated fill,
                // bright border, ripple) so it cannot be confused with the
                // informational count pill below, which is flat and inert.
                // Desktop-only by construction: the wheel opens from the Arrange
                // pill, which only renders in desktop mode, so the destination is
                // always theater. The display's top bar is the two-way switch.
                var confirmTheater by remember { mutableStateOf(false) }
                val prefScope = rememberCoroutineScope()
                Box(
                    Modifier
                        // Extra bottom margin: 16 on top of the column's spacedBy(8)
                        // = a 24dp gap, triple the pill-to-pill rhythm (SH's spec).
                        // Same idea as the top bar's separator group: the mode
                        // switch is a different category from the window actions,
                        // and the distance says so.
                        .padding(bottom = 16.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(PhoneSurfaceElevated)
                        .border(1.dp, PhonePrimaryBright.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .clickable {
                            PortalPadApp.instance.injector.buzz(longPress = false)
                            confirmTheater = true
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            painter = androidx.compose.ui.res.painterResource(
                                com.portalpad.app.R.drawable.ic_mode_theater,
                            ),
                            contentDescription = null,
                            tint = PhoneOnSurface,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            "  Switch to theater mode",
                            color = PhoneOnSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                if (confirmTheater) {
                    androidx.compose.material3.AlertDialog(
                        modifier = Modifier.border(
                            1.dp,
                            PhonePrimaryDim,
                            androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                        ),
                        onDismissRequest = { confirmTheater = false },
                        title = { Text("Switch to theater mode?") },
                        text = {
                            androidx.compose.foundation.layout.Column {
                                Text(
                                    "Theater mode shows one app at a time, filling the external display.",
                                    color = PhoneOnSurface,
                                )
                                androidx.compose.foundation.layout.Spacer(
                                    androidx.compose.ui.Modifier.height(8.dp),
                                )
                                Text(
                                    "This closes anything open on the external display. " +
                                        "Reopen apps from the dock afterward.",
                                    color = PhoneOnSurfaceMuted,
                                )
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                confirmTheater = false
                                android.util.Log.i(
                                    "TrackpadActivity",
                                    "MODE-SWITCH wheel: confirmed (toDesktop=false); writing pref",
                                )
                                // Write FIRST, dismiss on completion. onDismiss()
                                // removes this composable, which cancels
                                // rememberCoroutineScope - dismissing first could
                                // cancel the write mid-flight and silently leave the
                                // mode unchanged (the exact no-op the top bar's
                                // scope-null log exists to catch).
                                prefScope.launch {
                                    runCatching {
                                        PortalPadApp.instance.prefs.setBool(
                                            com.portalpad.app.data.PreferencesRepository.Keys.DESKTOP_MODE_ENABLED,
                                            false,
                                        )
                                    }.onSuccess {
                                        android.util.Log.i("TrackpadActivity", "MODE-SWITCH wheel: pref write done")
                                    }.onFailure {
                                        android.util.Log.w("TrackpadActivity", "MODE-SWITCH wheel: pref write FAILED: ${it.message}")
                                    }
                                    // Leaving desktop mode makes the wheel stale: its
                                    // opener (the Arrange pill) disappears with the mode.
                                    onDismiss()
                                }
                            }) { Text("Continue") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { confirmTheater = false }) {
                                Text("Cancel")
                            }
                        },
                    )
                }

                // Window-count header above the wheel.
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(PhoneSurface)
                        .border(1.dp, PhonePrimary.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(countText, color = PhonePrimaryBright, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }

                // The wheel: center + upper ring (4) + lower size-arch (3).
                // 250dp, not 268: content reaches 125dp from center (104 slot
                // radius + 26 half-slot), so 268 left ~9dp of invisible padding
                // above the top slots. That stacked on the column's spacedBy(8)
                // and made the count-pill-to-wheel gap read ~17dp while the pill
                // gaps were 8dp (visible in SH's screenshot). Compose does not
                // clip children, so an exact-fit box is safe.
                Box(
                    Modifier
                        .size(250.dp)
                        .pointerInput(Unit) { detectTapGestures { } },
                    contentAlignment = Alignment.Center,
                ) {
                    RING.forEach { (id, glyph, angle) ->
                        val rad = angle * PI / 180.0
                        WheelSlot(
                            glyph = glyph,
                            live = isLive(id),
                            armed = armed == id,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(
                                    x = (RING_RADIUS * cos(rad)).toFloat().dp,
                                    y = (RING_RADIUS * sin(rad)).toFloat().dp,
                                ),
                            onTap = { tap(id) },
                            iconRes = ICONS[id],
                        )
                    }
                    ARCH.forEach { (id, glyph, angle) ->
                        val rad = angle * PI / 180.0
                        WheelSlot(
                            glyph = glyph,
                            live = isLive(id),
                            armed = armed == id,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(
                                    x = (ARCH_RADIUS * cos(rad)).toFloat().dp,
                                    y = (ARCH_RADIUS * sin(rad)).toFloat().dp,
                            ),
                            onTap = { tap(id) },
                            iconRes = ICONS[id],
                            interactive = id == "windowTopBar" || isLive(id),
                        )
                    }
                    // Center: arrange evenly (one tap).
                    CenterHub(
                        live = isLive("arrangeEvenly"),
                        onTap = { if (isLive("arrangeEvenly")) fire("arrangeEvenly") },
                    )
                }

                // Label pill: icon + name of the armed action, else a prompt.
                LabelPill(armed, feedback)
            }
        }
}

@Composable
private fun WheelSlot(
    glyph: String,
    live: Boolean,
    armed: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
    // Drawn vector face; when set it replaces the font glyph. Same icons the top
    // bar uses (see ICONS), so an action has one picture on both surfaces.
    iconRes: Int? = null,
    interactive: Boolean = live,
) {
    val src = remember { MutableInteractionSource() }
    var m = modifier
        .size(52.dp)
        .clip(CircleShape)
        .background(if (armed) PhonePrimary else PhoneSurfaceElevated)
        .border(
            width = if (armed) 1.5.dp else 1.dp,
            color = if (armed) PhonePrimaryBright else PhoneOnSurfaceDim.copy(alpha = 0.4f),
            shape = CircleShape,
        )
    if (interactive) m = m.clickable(interactionSource = src, indication = null) { onTap() }
    Box(m.alpha(if (live) 1f else 0.3f), contentAlignment = Alignment.Center) {
        if (iconRes != null) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(iconRes),
                contentDescription = null,
                tint = PhoneOnSurface,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Text(glyph, color = PhoneOnSurface, fontSize = 22.sp)
        }
    }
}

@Composable
private fun CenterHub(live: Boolean, onTap: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    var m = Modifier
        .size(88.dp)
        .clip(CircleShape)
        .background(PhoneSurfaceVariant)
        .border(1.5.dp, PhonePrimary.copy(alpha = 0.5f), CircleShape)
    if (live) m = m.clickable(interactionSource = src, indication = null) { onTap() }
    Box(m.alpha(if (live) 1f else 0.3f), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("\u2637", color = PhoneOnSurface, fontSize = 24.sp)
            Text("evenly", color = PhonePrimaryBright, fontSize = 9.sp)
        }
    }
}

@Composable
private fun LabelPill(armed: String?, feedback: String?) {
    val meta = if (feedback == null) armed?.let { META[it] } else null
    val confirm = meta != null
    Box(
        Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(PhoneSurfaceElevated)
            .border(
                1.dp,
                when {
                    confirm -> PhonePrimaryBright
                    feedback != null -> PhonePrimary.copy(alpha = 0.6f)
                    else -> PhoneOnSurfaceDim.copy(alpha = 0.4f)
                },
                RoundedCornerShape(15.dp),
            )
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (feedback != null) {
                Text(
                    feedback,
                    color = PhonePrimaryBright,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            } else if (meta != null) {
                val iconRes = armed?.let { ICONS[it] }
                if (iconRes != null) {
                    androidx.compose.material3.Icon(
                        painter = androidx.compose.ui.res.painterResource(iconRes),
                        contentDescription = null,
                        tint = PhonePrimaryBright,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Text(meta.first, color = PhonePrimaryBright, fontSize = 16.sp)
                }
                Text(
                    "${meta.second}  \u2014  tap again",
                    color = PhonePrimaryBright,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                Text("Tap an action", color = PhoneOnSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// id, glyph, angle(deg: 0=right, 90=down, -90=up). Upper ring, clockwise from
// upper-left: close-all sits far from the center's one-tap arrange-evenly.
private val RING = listOf(
    Triple("closeAll", "\u2715", -144.0),
    Triple("arrangeInOrder", "\u229E", -108.0),
    Triple("stack", "\u29C9", -72.0),
    Triple("restoreSession", "\u21BB", -36.0),
)

// Lower "window size" arch below center: minimize, restore-freeform, maximize,
// window-top-bar. Angles MIRROR the upper RING (144/108/72/36 vs -144/-108/-72/-36)
// at the same radius, so each arch button sits directly under a ring button —
// aligned columns, evenly spaced, no overlap.
private val ARCH = listOf(
    Triple("minimize", "\u2212", 144.0),
    Triple("restoreFreeform", "\u2750", 108.0),
    Triple("maximize", "\u26F6", 72.0),
    Triple("windowTopBar", "\u25AD", 36.0),
)

private const val RING_RADIUS = 104.0
private const val ARCH_RADIUS = 104.0

// Slots with a drawn vector face instead of a font glyph. Same drawables the top
// bar uses, so an action has ONE picture on both surfaces. Only genuinely cryptic
// glyphs earn an icon; universal symbols (minus, cross, arrows) stay as text.
// No entry for "maximize" on purpose: a drawn mirror of unmaximize differed only
// in arrow direction and the two were too easy to confuse (tried, reverted
// 2026-07-22). The glyph-vs-icon contrast is what keeps the pair readable.
private val ICONS = mapOf(
    "restoreFreeform" to com.portalpad.app.R.drawable.ic_window_unmaximize,
    "windowTopBar" to com.portalpad.app.R.drawable.ic_window_titlebar,
)

private val META = mapOf(
    "arrangeEvenly" to Pair("\u2637", "Arrange evenly"),
    "arrangeInOrder" to Pair("\u229E", "Arrange in order"),
    "stack" to Pair("\u29C9", "Stack"),
    "restoreSession" to Pair("\u21BB", "Restore session"),
    "closeAll" to Pair("\u2715", "Close all"),
    "minimize" to Pair("\u2212", "Minimize"),
    "restoreFreeform" to Pair("\u2750", "Unmaximize"),
    "maximize" to Pair("\u26F6", "Maximize"),
    // Matches the top bar's label for the same action. "Window top bar" read as
    // the bar the button sits on rather than the window's own caption.
    "windowTopBar" to Pair("\u25AD", "Show or hide window title bar"),
)
