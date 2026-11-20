package com.portalpad.app.ui.trackpad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalpad.app.data.AppEntry
import com.portalpad.app.data.QUICK_WHEEL_SLOTS
import com.portalpad.app.ui.dock.AppIcon
import com.portalpad.app.ui.theme.PhoneAccentSoft
import com.portalpad.app.ui.theme.PhoneBackground
import com.portalpad.app.ui.theme.PhoneOnSurface
import com.portalpad.app.ui.theme.PhoneOnSurfaceMuted
import com.portalpad.app.ui.theme.PhonePrimary
import com.portalpad.app.ui.theme.PhonePrimaryBright
import com.portalpad.app.ui.theme.PhoneSurface
import com.portalpad.app.ui.theme.PhoneSurfaceElevated
import com.portalpad.app.ui.theme.PhoneSurfaceVariant
import com.portalpad.app.ui.theme.PhoneTeal
import com.portalpad.app.ui.theme.PhoneWarning
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The Quick Wheel: a phone-side radial quick-launcher that overlays the current
 * interface. The center is a permanent "All apps" drawer button; the ring holds
 * [QUICK_WHEEL_SLOTS] customizable slots (icons only).
 *
 * Gestures per ring slot:
 *  - filled: first tap latches its name in the always-visible label strip (no
 *    launch); a second tap on the same slot launches it. Tapping a different slot
 *    moves the latch. Long-press opens the picker to reconfigure/clear it.
 *  - empty ("+"): tap opens the picker to assign it.
 * Center: tap opens the full app drawer. Tap outside the wheel: dismiss.
 *
 * The whole cluster is bottom-anchored so the label strip lands in the gap just
 * above the bottom bar (see [WheelBottomLift]).
 */
// Bottom lift now equals the cluster's own 16dp internal gap: the space from
// the nav bar to the chips matches the chips→card gap (user-specified symmetry).
private val WheelBottomLift = 16.dp

/** One missing grant the wheel's actions depend on: shown in the permissions
 *  chip's popup with a per-row Fix. */
data class PermissionIssue(
    val label: String,
    val why: String,
    val fix: () -> Unit,
)

@Composable
fun QuickWheelOverlay(
    slots: List<AppEntry?>,
    onLaunchSlot: (AppEntry) -> Unit,
    onAssignSlot: (Int) -> Unit,
    onOpenDrawer: () -> Unit,
    onWidgetOverlay: () -> Unit,
    onWidgetOverlayEdit: () -> Unit,
    onNotifications: () -> Unit,
    onQrScan: () -> Unit,
    permissionIssues: List<PermissionIssue> = emptyList(),
    qrFeed: (@Composable () -> Unit)? = null,
    // Nav-bar bottom inset measured in the Activity window and used as a floor.
    // Fullscreen windows can report 0 even while the bar is visible, which put
    // the bottom chips under navigation controls in the field.
    fallbackBottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    onDismiss: () -> Unit,
) {
    var preview by remember { mutableStateOf<Int?>(null) }

    val ringRadius = 116f
    val slotSize = 58.dp
    val centerSize = 112.dp
    // Clamped for compact phones: a fixed 300dp wheel (and the side buttons'
    // screen-relative offsets) misbehaved below ~330dp-wide screens.
    val screenWDp = com.portalpad.app.PinnedDensityActivity.TARGET_WIDTH_DP.dp
    val wheelSize = if (screenWDp - 40.dp < 300.dp) screenWDp - 40.dp else 300.dp

    // Soft crimson-tinted charcoal radial depth for the backdrop, and a vivid
    // crimson gradient for the center button.
    val backdropBrush = Brush.radialGradient(
        listOf(Color(0xFF281418), Color(0xFF171115), PhoneBackground),
    )
    val centerBrush = Brush.radialGradient(
        listOf(Color(0xFFEF4444), Color(0xFFDC2626), Color(0xFF991B1B)),
    )

    // Stay inside TrackpadActivity's already full-display window. A Compose
    // Dialog is size-limited to Android's app frame on some devices (live Pixel:
    // 1291x2576 inside a 1344x2992 display), so its scrim cannot paint the
    // excluded bottom strip regardless of the navigation-bar color.
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    com.portalpad.app.ui.common.TransparentWheelNavigationBar()

        // Keep interactive chips above Android navigation while the scrim itself
        // occupies the full Activity canvas behind that region.
        val diagDensity = androidx.compose.ui.platform.LocalDensity.current
        val navBottomPx = WindowInsets.navigationBars.getBottom(diagDensity)
        // The larger of the locally resolved inset and activity-measured
        // fallback; a missing fullscreen inset is filled deterministically.
        val dialogNavDp = with(diagDensity) { navBottomPx.toDp() }
        // THIRD source, and the only one that survives a fullscreen window:
        // ask the WindowManager for the nav bar's height ignoring visibility.
        // Pixel 8 Pro read dialog=0 AND fallback=0 (the trackpad window is
        // fullscreen, so the 3-button bar floats over it and nothing dispatches
        // an inset) — the chips then bottom-anchored UNDER the bar, putting
        // Home directly beneath "Widget Overlay". Taking the max keeps devices
        // whose dispatch works (S24) byte-identical.
        // ONE geometry subscription for both the size and the nav-bar height —
        // it re-measures on window layout changes (keyguard transitions), which
        // is what fixes "wheel opened before a screen off/on overlaps the nav
        // bar until you unlock" without needing the wheel to be reopened.
        val geometry = com.portalpad.app.ui.common.rememberDialogGeometry()
        val systemNavDp = geometry.navBottom
        val dispatched = if (dialogNavDp > fallbackBottomInset) dialogNavDp else fallbackBottomInset
        val bottomInset = if (systemNavDp > dispatched) systemNavDp else dispatched
        // Log the resolved value AND which source won, so a "chips are clipped"
        // report is answerable from one line. Reference case (v1.4, SM-S921U):
        // the Dialog reported 0 while the system's own screenshot metadata said
        // navigationBarHeight=126 / isNavigationBarVisible=true — i.e. dialog=0,
        // fallback=48dp, source=fallback is the HEALTHY reading on this ROM.
        androidx.compose.runtime.LaunchedEffect(navBottomPx, fallbackBottomInset, systemNavDp) {
            android.util.Log.d(
                "QuickWheel",
                "DIAG-INSET dialog=$dialogNavDp fallback=$fallbackBottomInset " +
                    "system=$systemNavDp applied=$bottomInset source=" +
                    when (bottomInset) {
                        systemNavDp -> "system"
                        dialogNavDp -> "dialog"
                        else -> "fallback"
                    },
            )
        }
        // The Activity is already pinned to PortalPad's phone density, so filling
        // its root constraints reaches the physical canvas without the Dialog
        // density mismatch that previously displaced this wheel.
        Box(
            Modifier.fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f))
                .padding(bottom = bottomInset)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .padding(bottom = WheelBottomLift)
                    // Consume taps on the cluster (gaps/strip) so they don't dismiss.
                    .pointerInput(Unit) { detectTapGestures { } },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            // Permissions heads-up: ONE quiet chip, only when something the
            // wheel exposes is missing its grant (invisible otherwise). Tap →
            // scrollable list, per-row Fix. Request-on-tap remains the primary
            // flow; this is the "why doesn't this button work" answer.
            var permPopupOpen by remember { mutableStateOf(false) }
            if (permissionIssues.isNotEmpty()) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(PhoneSurfaceVariant)
                        .border(1.dp, PhoneWarning.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                        .pointerInput(permissionIssues.size) {
                            detectTapGestures { permPopupOpen = !permPopupOpen }
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        "${permissionIssues.size} permission" +
                            (if (permissionIssues.size > 1) "s" else "") + " needed",
                        color = PhoneWarning,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (permPopupOpen) {
                    val permListState = androidx.compose.foundation.lazy.rememberLazyListState()
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(PhoneSurface.copy(alpha = 0.95f))
                            .border(1.dp, PhoneAccentSoft.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                            .padding(10.dp)
                            .heightIn(max = 190.dp)
                            .widthIn(max = 300.dp),
                    ) {
                        androidx.compose.foundation.lazy.LazyColumn(
                            state = permListState,
                            modifier = Modifier.weight(1f),
                        ) {
                            items(permissionIssues.size) { i ->
                                val issue = permissionIssues[i]
                                Row(
                                    Modifier.padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(issue.label, color = PhoneOnSurface,
                                            fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text(issue.why, color = PhoneOnSurfaceMuted,
                                            fontSize = 11.sp)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(PhonePrimary.copy(alpha = 0.25f))
                                            .pointerInput(i) {
                                                detectTapGestures { issue.fix() }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 5.dp),
                                    ) {
                                        Text("Fix", color = PhoneOnSurface, fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                        if (permissionIssues.size > 4) {
                            Spacer(Modifier.width(6.dp))
                            com.portalpad.app.ui.common.VerticalScrollbar(
                                listState = permListState,
                                modifier = Modifier.width(4.dp).heightIn(max = 190.dp),
                            )
                        }
                    }
                }
            }
            // Integrated QR feed: renders INSIDE the wheel overlay, above the
            // ring — no separate popup, wheel + cluster stay put. No idle
            // placeholder by design: the squircle is the discoverability.
            qrFeed?.invoke()
            Box(
                Modifier
                    .size(wheelSize)
                    // Swallow taps inside the wheel so a near-miss never dismisses;
                    // only taps on the scrim outside the wheel close it.
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                // QR squircle: icon-only, beside the ring, its center on the
                // wheel's vertical center and HORIZONTALLY CENTERED in the gap
                // between the ring's right edge and the screen edge (the pill
                // version overflowed the chip row into a crushed sliver).
                // Outward inset for BOTH side buttons (QR right, exit ✕ left).
                // Must come from the REAL canvas width, not
                // LocalConfiguration.screenWidthDp: inside a Dialog that reports
                // the DEVICE's dp width, while this content renders in the
                // activity's pinned 411dp canvas. SH's Pixel reported 527dp →
                // inset 99.5dp → the 56dp buttons landed ~44dp PAST the screen
                // edge (clipped, screenshot 2026-07-25). Samsung reports 360dp —
                // narrower than the canvas — so the same bug parked its buttons
                // ~25dp too far INWARD, which merely looked roomy. With the real
                // width both land at the intended ~14dp edge gap.
                val canvasW = com.portalpad.app.PinnedDensityActivity.TARGET_WIDTH_DP.dp
                val sideX = (canvasW / 2 - 164.dp).coerceAtLeast(8.dp)
                run {
                    // Equal-diagonal-gap point from the user's mock: below
                    // Disney+, outside the SE slot, above the card — solved so
                    // the gaps to the SE slot edge, the card's top edge, and
                    // the screen's right edge all land ~10-15dp. Right-edge
                    // anchored so the edge gap holds on any phone width.
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .offset(x = sideX, y = 128.dp)
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(PhoneSurfaceVariant)
                            .border(1.dp, PhoneAccentSoft.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                                    onQrScan()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = "QR scan",
                            tint = PhonePrimaryBright,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                // Exit squircle: the QR button's mirror twin (same equal-gap
                // solve, flipped) — dark fill so it can't vanish like the old
                // translucent chrome, red glyph + red-tinted border as the
                // destructive cue. Icon-only to match its sibling.
                run {
                    // Mirror of the QR button — same [sideX] solve, flipped.
                    // Bare red ✕ (user-picked over the squircle): the scrim
                    // is constant behind it so no chrome is needed; the 56dp
                    // box stays as an invisible generous tap target.
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = -sideX, y = 128.dp)
                            .size(56.dp)
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                                    onDismiss()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "✕",
                            color = Color(0xFFE05A5A),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                // Ring backdrop — soft crimson-tinted radial depth.
                Box(
                    Modifier
                        .size(wheelSize - 24.dp)
                        .clip(CircleShape)
                        .background(backdropBrush)
                        .border(0.5.dp, PhonePrimary.copy(alpha = 0.28f), CircleShape),
                )
                // Faint dashed "dial" ring through the slot positions.
                Box(
                    Modifier
                        .size((ringRadius * 2).dp)
                        .drawBehind {
                            drawCircle(
                                color = PhonePrimary.copy(alpha = 0.25f),
                                radius = size.minDimension / 2f,
                                style = Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 11f)),
                                ),
                            )
                        },
                )

                // Center: permanent app-drawer button — gradient fill + soft glow.
                Box(
                    Modifier
                        .size(centerSize)
                        .shadow(16.dp, CircleShape, ambientColor = PhonePrimary, spotColor = PhonePrimary)
                        .clip(CircleShape)
                        .background(centerBrush)
                        .border(1.dp, PhoneAccentSoft.copy(alpha = 0.55f), CircleShape)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
                                onOpenDrawer()
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Apps,
                            contentDescription = "All apps",
                            tint = PhoneOnSurface,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "All apps",
                            color = PhoneOnSurface,
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                // Ring slots.
                for (i in 0 until QUICK_WHEEL_SLOTS) {
                    val slot = slots.getOrNull(i)
                    val angle = Math.toRadians((i * 45.0) - 90.0)
                    val dx = (ringRadius * cos(angle)).toFloat()
                    val dy = (ringRadius * sin(angle)).toFloat()
                    SlotView(
                        slot = slot,
                        highlighted = preview == i && slot != null,
                        size = slotSize,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = dx.dp, y = dy.dp)
                            .pointerInput(slot, i) {
                                detectTapGestures(
                                    onTap = {
                                        when {
                                            slot == null -> {
                                                com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                                                onAssignSlot(i)
                                            }
                                            preview == i -> {                    // 2nd tap → launch
                                                com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
                                                onLaunchSlot(slot)
                                            }
                                            else -> {                            // 1st tap → latch name
                                                com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                                                preview = i
                                            }
                                        }
                                    },
                                    onLongPress = {
                                        com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
                                        onAssignSlot(i)
                                    },
                                )
                            },
                    )
                }
            }

            val armed = preview?.let { slots.getOrNull(it) }
            LabelStrip(
                slot = armed,
                onLaunch = armed?.let { s ->
                    {
                        com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
                        onLaunchSlot(s)
                    }
                },
            )

            // Two utility chips BELOW the tap-again card (user-picked position;
            // cluster spacedBy(16) supplies the gap). Slot-chrome styling so
            // they read as wheel furniture. Widget Overlay toggles the widget
            // layer; Expand Notifications toggles the external display's
            // notification panel — both via the existing PFS toggles.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WheelChip(
                    icon = Icons.Default.Widgets,
                    label = "Widget Overlay",
                    onLongPress = { onWidgetOverlayEdit() },
                ) { onWidgetOverlay() }
                WheelChip(
                    icon = Icons.Default.Notifications,
                    label = "Expand Notifications",
                ) { onNotifications() }

            }
            }
        }
}

@Composable
private fun SlotView(
    slot: AppEntry?,
    highlighted: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (highlighted) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "slotScale",
    )
    // Ring color encodes launch destination: violet = app, teal = activity (both
    // land on the glasses), amber = shortcut (lands on the PHONE).
    val accent = slot?.let { accentFor(it) } ?: PhoneOnSurfaceMuted
    val ringColor = when {
        slot == null -> PhoneOnSurfaceMuted.copy(alpha = 0.35f)
        highlighted -> accent
        else -> accent.copy(alpha = 0.7f)
    }
    val bg = when {
        highlighted -> accent.copy(alpha = 0.18f)
        slot != null -> PhoneSurfaceVariant
        else -> Color.Transparent
    }
    // Outer box is unclipped so the corner badge can overhang the ring.
    Box(
        modifier
            .size(size)
            .scale(scale),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (highlighted)
                        Modifier.shadow(16.dp, CircleShape, ambientColor = accent, spotColor = accent)
                    else Modifier,
                )
                .clip(CircleShape)
                .background(bg)
                .border(if (slot == null) 1.5.dp else 2.dp, ringColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (slot != null) {
                // Icon sits on a subtle elevated tile for depth. No clashing color —
                // real launcher icons already carry their own.
                Box(
                    Modifier
                        .size(size * 0.62f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(PhoneSurfaceElevated),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(packageName = slot.packageName, sizeDp = (size.value * 0.5f).toInt())
                }
            } else {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add app",
                    tint = PhoneOnSurfaceMuted,
                    modifier = Modifier.size(size * 0.4f),
                )
            }
        }
        // Phone badge — a shortcut launches on the phone, not the glasses.
        if (slot?.isShortcut == true) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-3).dp, y = 3.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(PhoneWarning)
                    .border(1.5.dp, PhoneBackground, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Smartphone,
                    contentDescription = "Launches on phone",
                    tint = Color(0xFF3A2A05),
                    modifier = Modifier.size(11.dp),
                )
            }
        } else if (slot?.isActivity == true) {
            // Layers badge — a specific activity (a screen within an app).
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-3).dp, y = 3.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(PhoneTeal)
                    .border(1.5.dp, PhoneBackground, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Layers,
                    contentDescription = "App activity",
                    tint = Color(0xFF05241B),
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

/** One utility chip under the tap-again card — slot chrome in pill form. */
@Composable
private fun WheelChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(21.dp))
            .background(PhoneSurfaceVariant)
            .border(1.dp, PhoneAccentSoft.copy(alpha = 0.45f), RoundedCornerShape(21.dp))
            .pointerInput(onLongPress != null) {
                detectTapGestures(
                    onTap = {
                        com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                        onClick()
                    },
                    onLongPress = if (onLongPress != null) {
                        {
                            com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
                            onLongPress()
                        }
                    } else null,
                )
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = PhonePrimaryBright,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = PhoneOnSurface,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** A slot's launch destination, encoded by ring color. */
private fun accentFor(entry: AppEntry): Color = when {
    entry.isShortcut -> PhoneWarning   // launches on the phone
    entry.isActivity -> PhoneTeal      // a specific activity (deeper entry point)
    else -> PhonePrimary               // the whole app
}

@Composable
private fun LabelStrip(slot: AppEntry?, onLaunch: (() -> Unit)? = null) {
    val accent = slot?.let { accentFor(it) } ?: PhonePrimary
    Box(
        Modifier
            .widthIn(min = 210.dp)
            // Fixed floor so the idle (1-line) and armed (2-line) states occupy
            // the same height — the bottom-anchored cluster must not jump when a
            // slot is latched.
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(14.dp))
            // The armed card LOOKED tappable (border, elevation) but wasn't —
            // "tap again" secretly meant the slot. Now tapping the card
            // launches too (same action + haptic as the confirming slot tap);
            // idle state stays inert.
            .then(
                if (slot != null && onLaunch != null) Modifier.clickable { onLaunch() }
                else Modifier,
            )
            .background(PhoneSurfaceElevated)
            .border(
                0.5.dp,
                if (slot != null) accent.copy(alpha = 0.4f) else PhoneOnSurfaceMuted.copy(alpha = 0.25f),
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (slot != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(packageName = slot.packageName, sizeDp = 24)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        slot.label,
                        color = PhoneOnSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (slot.isShortcut) {
                        // Spell out the consequence: this one opens on the phone.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Smartphone,
                                contentDescription = null,
                                tint = PhoneWarning,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "opens on phone · tap to launch",
                                color = PhoneWarning,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    } else {
                        Text(
                            if (slot.isActivity) "activity · tap here or slot to launch"
                            else "tap here or the slot again to launch",
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                // Accent dot, tinted to the slot type.
                Box(
                    Modifier
                        .size(7.dp)
                        .shadow(6.dp, CircleShape, ambientColor = accent, spotColor = accent)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        } else {
            Text(
                "Tap a slot to preview · tap outside to close",
                color = PhoneOnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
