package com.portalpad.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import com.portalpad.app.ui.theme.PhoneOnSurface
import com.portalpad.app.ui.theme.PhoneOnSurfaceMuted
import com.portalpad.app.ui.theme.PhonePrimaryBright
import com.portalpad.app.ui.theme.PhonePrimaryDim
import com.portalpad.app.ui.theme.PhoneSurfaceElevated

/**
 * The app's ONE dropdown scaffold (SH, 2026-07-24): every menu opens ATTACHED
 * to its trigger - directly below it, or directly above when the trigger sits
 * too low on the screen for the menu to fit underneath - and the trigger's
 * chevron announces the direction BEFORE the tap (the [anchor] lambda receives
 * `opensUp` live, recomputed as the page scrolls). The menu is never narrower
 * than its trigger (Compose's DropdownMenu wraps content and reads as
 * detached); it may grow wider only when an item's text needs the room. Lists
 * taller than [maxMenuHeight] scroll inside with a visible thumb.
 *
 * Built from the attached-popup technique proven on the relay keyboard picker,
 * the keyboard mini dropdowns, and the mapped-devices list - generalized here
 * so the remaining Settings dropdowns migrate one call-site at a time.
 */
@Composable
fun AttachedDropdownBox(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxMenuHeight: Dp = 320.dp,
    // Menu chrome. Defaults = the app's standardized look (keyboard-picker
    // family). STYLIZED triggers (the remote's Input pills) pass their own
    // colors so the menu reads as part of the control, not a settings menu
    // that wandered in (SH).
    menuBackground: androidx.compose.ui.graphics.Color = PhoneSurfaceElevated,
    menuBorder: androidx.compose.ui.graphics.Color = PhonePrimaryDim,
    anchor: @Composable (opensUp: Boolean) -> Unit,
    menuContent: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    var anchorWidthPx by remember { mutableIntStateOf(0) }
    var opensUp by remember { mutableStateOf(false) }
    Box(
        modifier.onGloballyPositioned { coords ->
            anchorWidthPx = coords.size.width
            val b = coords.boundsInWindow()
            val menuPx = with(density) { maxMenuHeight.toPx() }
            val below = view.height - b.bottom
            val above = b.top
            // Upward only when the cap can't fit below AND above offers more
            // room - a menu that fits nowhere still prefers the larger side.
            opensUp = below < menuPx && above > below
        },
    ) {
        anchor(opensUp)
        if (expanded) {
            Popup(
                // Keyed on opensUp: the menu must honor the direction the
                // chevron promised, even when content is short enough to fit
                // either way.
                popupPositionProvider = remember(opensUp) {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: androidx.compose.ui.unit.IntRect,
                            windowSize: androidx.compose.ui.unit.IntSize,
                            layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                            popupContentSize: androidx.compose.ui.unit.IntSize,
                        ): androidx.compose.ui.unit.IntOffset {
                            val x = anchorBounds.left
                                .coerceAtMost(windowSize.width - popupContentSize.width)
                                .coerceAtLeast(0)
                            val y = if (opensUp) {
                                (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
                            } else {
                                anchorBounds.bottom
                                    .coerceAtMost(windowSize.height - popupContentSize.height)
                                    .coerceAtLeast(0)
                            }
                            return androidx.compose.ui.unit.IntOffset(x, y)
                        }
                    }
                },
                onDismissRequest = onDismiss,
                properties = PopupProperties(focusable = true),
            ) {
                val minW = with(density) { anchorWidthPx.toDp() }
                Box(
                    Modifier
                        // SH's width rule, both halves: at least the trigger's
                        // width, and otherwise only as wide as the WIDEST row's
                        // natural size (IntrinsicSize.Max) - never inflated
                        // beyond what the text needs.
                        .width(IntrinsicSize.Max)
                        .widthIn(min = minW)
                        .heightIn(max = maxMenuHeight)
                        .clip(RoundedCornerShape(10.dp))
                        .background(menuBackground)
                        .border(1.dp, menuBorder, RoundedCornerShape(10.dp)),
                ) {
                    val menuScroll = rememberScrollState()
                    val overflow = menuScroll.maxValue > 0
                    Column(Modifier.fillMaxWidth()) {
                        if (overflow) {
                            ScrollEdgeChevron(menuScroll, up = true)
                        }
                        Box(Modifier.weight(1f, fill = false)) {
                            Column(
                                Modifier
                                    .verticalScroll(menuScroll)
                                    .fillMaxWidth(),
                            ) {
                                menuContent()
                            }
                            // Thumb (position) + chevrons (affordance) answer
                            // different questions - both stay (SH).
                            if (overflow) {
                                MenuScrollThumb(menuScroll)
                            }
                        }
                        if (overflow) {
                            ScrollEdgeChevron(menuScroll, up = false)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A slim tappable ▲/▼ strip framing a scrolling area (SH): bright when that
 * direction can still scroll, dimmed at the edge, and a tap nudges the list.
 * The bare thumb read as "maybe a gesture zone"; paired chevrons are the
 * affordance that says "this scrolls". Works for plain scroll states AND lazy
 * lists ([ScrollableState] covers both). Color comes from the hosting
 * surface's own palette so it never looks pasted in.
 */
@Composable
fun ScrollEdgeChevron(
    state: ScrollableState,
    up: Boolean,
    color: androidx.compose.ui.graphics.Color = PhonePrimaryBright,
    nudgePx: Float = 240f,
) {
    val scope = rememberCoroutineScope()
    val enabled = if (up) state.canScrollBackward else state.canScrollForward
    Box(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .clickable(enabled = enabled) {
                scope.launch { state.animateScrollBy(if (up) -nudgePx else nudgePx) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (up) "▲" else "▼",
            color = if (enabled) color else color.copy(alpha = 0.25f),
            fontSize = 9.sp,
        )
    }
}

/**
 * Contrast-safe hairline / border for a menu whose CONTENT color is already
 * chosen to contrast with its (possibly user-customized) background - the dock
 * and folder windows let the user recolor everything (SH). Low-alpha content
 * reads as a LIGHT line on a dark surface and a DARK line on a light one, so
 * dividers and the panel border stay visible whatever color the user picks,
 * without any hardcoded violet that could clash.
 */
fun adaptiveHairline(
    content: androidx.compose.ui.graphics.Color,
    alpha: Float = 0.18f,
): androidx.compose.ui.graphics.Color = content.copy(alpha = alpha)

/**
 * The page scrollbar THUMB (SH): a thin bar on the right edge, drawn only on
 * overflow, whose track reserves a 26dp zone at each end so it stops clear of
 * [PageScrollChevrons]. Matches the hand-rolled TabBody / ControlsSubpage bars
 * so the thumbless customization pages (Dock / Top Bar / Folder Window /
 * Display) get the SAME visible scrollbar. Call inside the page's root Box,
 * alongside PageScrollChevrons.
 */
@Composable
fun BoxScope.PageScrollbar(
    state: androidx.compose.foundation.ScrollState,
    thumbColor: androidx.compose.ui.graphics.Color = PhoneOnSurfaceMuted.copy(alpha = 0.5f),
) {
    Box(
        Modifier
            .matchParentSize()
            .drawWithContent {
                drawContent()
                val max = state.maxValue
                if (max > 0) {
                    val chevZone = 26.dp.toPx()
                    val trackTop = chevZone
                    val trackBottom = size.height - chevZone
                    val trackH = (trackBottom - trackTop).coerceAtLeast(1f)
                    val viewport = size.height
                    val total = viewport + max
                    val thumbH = (trackH * (viewport / total)).coerceIn(28.dp.toPx(), trackH)
                    val thumbY = trackTop + (trackH - thumbH) * (state.value.toFloat() / max)
                    val w = 4.dp.toPx()
                    val x = size.width - w - 3.dp.toPx()
                    drawRoundRect(
                        color = thumbColor,
                        topLeft = androidx.compose.ui.geometry.Offset(x, thumbY),
                        size = androidx.compose.ui.geometry.Size(w, thumbH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f),
                    )
                }
            },
    )
}

/**
 * Proportional thumb for MENU / dropdown scroll viewports (SH): thumb height
 * mirrors the visible fraction of the list - short thumb = lots more to
 * scroll, tall thumb = little. Replaces the old fixed-36dp thumb everywhere
 * a menu scrolls. Call inside the scroll viewport Box, AFTER the content so
 * it draws on top. [chevZone] insets the track at both ends where corner
 * chevrons are overlaid INSIDE the same viewport (the Android-tab field);
 * menus whose chevrons sit outside the viewport keep the default 0.
 */
@Composable
fun BoxScope.MenuScrollThumb(
    state: androidx.compose.foundation.ScrollState,
    endPadding: androidx.compose.ui.unit.Dp = 2.dp,
    chevZone: androidx.compose.ui.unit.Dp = 0.dp,
    color: androidx.compose.ui.graphics.Color = PhoneOnSurfaceMuted.copy(alpha = 0.7f),
) {
    Box(
        Modifier
            .matchParentSize()
            .drawWithContent {
                drawContent()
                val max = state.maxValue
                if (max > 0) {
                    val inset = chevZone.toPx()
                    val trackTop = inset
                    val trackH = (size.height - 2f * inset).coerceAtLeast(1f)
                    val viewport = size.height
                    val total = viewport + max
                    val thumbH = (trackH * (viewport / total))
                        .coerceIn(minOf(24.dp.toPx(), trackH), trackH)
                    val thumbY = trackTop + (trackH - thumbH) * (state.value.toFloat() / max)
                    val w = 3.dp.toPx()
                    val x = size.width - w - endPadding.toPx()
                    drawRoundRect(
                        color = color,
                        topLeft = androidx.compose.ui.geometry.Offset(x, thumbY),
                        size = androidx.compose.ui.geometry.Size(w, thumbH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f),
                    )
                }
            },
    )
}

/**
 * Corner scroll chevrons for FULL PAGES (SH): small ▲/▼ hugging the right
 * edge at top and bottom, shown only when the page actually scrolls, bright
 * when that direction can scroll and faded at the edge; a tap nudges the
 * page. Pages get chevrons but NO thumb - "can I scroll and which way" is
 * the page question; menus keep both because position matters in a small
 * window. Call inside the page's root Box, any order - zIndex keeps the
 * chevrons above the content.
 */
@Composable
fun BoxScope.PageScrollChevrons(state: ScrollableState) {
    val scope = rememberCoroutineScope()
    if (!(state.canScrollBackward || state.canScrollForward)) return

    @Composable
    fun chev(up: Boolean, alignment: Alignment) {
        val enabled = if (up) state.canScrollBackward else state.canScrollForward
        // Center the glyph over the page scrollbar (SH): the TabBody thumb
        // draws 4dp wide at end=3dp, so its center sits ~5dp from the right
        // edge. A right-aligned tap box with the glyph hugging its end (~1dp
        // pad) lands the glyph center on that column, so the two chevrons read
        // as the ends of the scrollbar instead of floating left of it.
        Box(
            Modifier
                .align(alignment)
                .zIndex(1f)
                .width(26.dp)
                .clickable(enabled = enabled) {
                    scope.launch { state.animateScrollBy(if (up) -600f else 600f) }
                }
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                if (up) "▲" else "▼",
                color = if (enabled) PhonePrimaryBright else PhonePrimaryBright.copy(alpha = 0.25f),
                fontSize = 9.sp,
                modifier = Modifier.padding(end = 1.dp),
            )
        }
    }
    chev(up = true, alignment = Alignment.TopEnd)
    chev(up = false, alignment = Alignment.BottomEnd)
}

/**
 * Compact menu row for SMALL-anchor dropdowns (the remote's Input pills, the
 * sort menu, gesture-to-map). Material's DropdownMenuItem enforces a built-in
 * minimum width, which is what inflated small menus far past their trigger
 * (SH) - this row is just text with modest padding, so the menu hugs its
 * longest label under AttachedDropdownBox's intrinsic sizing.
 */
@Composable
fun CompactMenuRow(
    label: String,
    selected: Boolean = false,
    selectedColor: androidx.compose.ui.graphics.Color = PhoneOnSurface,
    normalColor: androidx.compose.ui.graphics.Color = PhoneOnSurfaceMuted,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = if (selected) selectedColor else normalColor,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
