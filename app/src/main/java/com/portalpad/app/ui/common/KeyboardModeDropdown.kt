package com.portalpad.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.portalpad.app.PortalPadApp
import com.portalpad.app.data.PreferencesRepository
import com.portalpad.app.ui.theme.PhoneAccentSoft
import com.portalpad.app.ui.theme.PhoneOnSurface
import com.portalpad.app.ui.theme.PhoneOnSurfaceMuted
import com.portalpad.app.ui.theme.PhonePrimaryBright
import com.portalpad.app.ui.theme.PhonePrimaryDim
import com.portalpad.app.ui.theme.PhoneSurface
import com.portalpad.app.ui.theme.PhoneSurfaceElevated
import kotlinx.coroutines.launch

/**
 * The single keyboard choice, shared by Settings and the typing page so both
 * speak the same four words for the same four states.
 *
 * These used to be two independent controls (WHERE via IME_ON_EXTERNAL, WHICH
 * via AUTO_OPEN_RELAY_ON_FIELD) plus a pair of tabs on the typing page — three
 * surfaces for one decision, which is how the tab and the dropdown ended up with
 * overlapping names for different things. The combinations that actually exist
 * are exactly these four:
 *
 *   0 Custom keyboard          → external=false, autoOpen=true,  androidTab=false
 *   1 Android keyboard (direct)→ external=false, autoOpen=false
 *   2 Android (with text box)  → external=false, autoOpen=true,  androidTab=true
 *   3 Android (external display)→ external=true
 *
 * Stored in the same prefs as before, so nothing needs migrating.
 */
object KeyboardMode {
    // ORDER MATTERS. The two PortalPad modes must stay adjacent, because the
    // group headers are derived from where the family CHANGES down the list.
    // Appending the new one at the end would have produced PortalPad, Android,
    // Android, Android, PortalPad — two separate PortalPad groups split by the
    // Android ones. Safe to renumber: the index is derived from prefs on every
    // read and never stored, and every reference in the app is by name.
    // The three PortalPad modes stay contiguous (0,1,2) so the derived group
    // headers produce ONE PortalPad group, not two split by the Android ones.
    // FLOATING leads (SH's ordering, 2026-07-23); indices are derived from prefs
    // on every read and never stored, so renumbering is safe.
    const val FLOATING = 0
    const val CUSTOM = 1
    const val ON_DISPLAY = 2
    const val DIRECT = 3
    const val TEXT_BOX = 4
    const val EXTERNAL = 5

    /**
     * Named by WHO MADE the keyboard and WHERE it appears — the split that
     * actually predicts behaviour. PortalPad's keyboard puts text in the field
     * itself; Android's depends on its keyboard connecting to the external
     * display, which is exactly where this project's reliability problems have
     * lived. A flat list buried that distinction.
     */
    val labels = listOf(
        "PortalPad keyboard (on phone)",
        "PortalPad keyboard (with text box)",
        "PortalPad keyboard (on display)",
        "Android keyboard (on phone)",
        "Android keyboard (with text box)",
        "Android keyboard (on display)",
    )

    /** True for the modes PortalPad implements itself. */
    fun isPortalPad(mode: Int): Boolean = mode == CUSTOM || mode == FLOATING || mode == ON_DISPLAY

    /**
     * Stated once per family, above the per-mode detail.
     *
     * States WHY you would pick one family over the other, not only how each
     * works. A band that describes mechanism alone leaves the reader to derive
     * the consequence, which is the job this picker exists to do for them.
     *
     * Claims nothing about other devices. "Works the same on every device" was a
     * promise this project cannot back, being tested on one phone, and
     * "behaviour depends on your device" answered a question nobody asked: the
     * reader has exactly one device and still wants to know about theirs. The
     * per-mode reliability lines name concrete symptoms instead, which is what
     * actually sets expectations.
     */
    fun familyNote(mode: Int): String =
        if (isPortalPad(mode)) {
            // Short on purpose (SH: 2-3 lines in the band). Two claims, mirrored
            // by the Android band: opens on tap, and typing arrives.
            "Made by PortalPad. Always opens when you tap a text field, and your " +
                "typing always reaches the external display."
        } else {
            "Provided by Android. May not open when you tap a text field, and " +
                "your typing may not reach the external display."
        }

    val descriptions = listOf(
        "Tap a text field and PortalPad's own keyboard floats over your phone. No text box, no extra page. What you type shows up on the external display as you type it.",
        "Tap a text field and a typing page opens on your phone, with a text box above the keyboard. What you type shows up on the external display as you type it.",
        "Tap a text field and PortalPad's own keyboard opens on the external display. Nothing opens on your phone. Has a number pad and calculator.",
        "Tap a text field and your phone's normal keyboard opens on the field. No extra page.",
        "Tap a text field and a typing page opens with a box above your phone's keyboard.",
        "Your phone's keyboard opens on the external display instead of on your phone. No extra page.",
    )

    /** The honest reliability picture. Deliberately does NOT tell anyone to stop
     *  and start the accessibility service — a workaround in settings copy is a
     *  bug shipped as instructions.
     *
     *  All four answer one question: what happens when you tap a text field?
     *  Same question in the same shape, so the four can be compared at a glance.
     *  The mechanism that used to sit here ("drives the field directly, so no
     *  system keyboard has to cooperate") moved up to the family band, where it
     *  explains all of them at once instead of only this one. */
    val reliability = listOf(
        "Always opens when you tap a field. You type on your phone with your thumbs.",
        "Always opens when you tap a field.",
        "Always opens when you tap a field. You aim its keys with the cursor rather than your thumbs.",
        "Sometimes the keyboard does not open when you tap a field. Sometimes it opens but your typing never reaches the external display.",
        "Opens reliably. Your text only reaches the external display when you stop (or pause) typing.",
        "Android may hide it while the cursor is over the field. It can also show up on your phone instead.",
    )

    /** Resolve the current mode from the four stored booleans. */
    fun of(
        onExternal: Boolean,
        autoOpen: Boolean,
        androidTab: Boolean,
        ppOnDisplay: Boolean,
        ppFloating: Boolean,
    ): Int = when {
        // FIRST two. Each of these switches is the ONLY signal that means its
        // mode, so both must outrank the shared IME_ON_EXTERNAL / landing-tab
        // bits a stale earlier session could leave set. Floating and on-display
        // are mutually exclusive by construction (apply() sets exactly one), but
        // order them anyway so a corrupted both-true state is deterministic.
        ppFloating -> FLOATING
        ppOnDisplay -> ON_DISPLAY
        onExternal -> EXTERNAL
        !autoOpen -> DIRECT
        androidTab -> TEXT_BOX
        else -> CUSTOM
    }

    /**
     * Write EVERY pref the chosen mode implies. Writing only the one that
     * "changed" is what leaves stale combinations behind — e.g. picking Custom
     * while a previous session had set the Android landing tab.
     */
    suspend fun apply(prefs: PreferencesRepository, mode: Int) {
        val portalPadKbOnDisplay = mode == ON_DISPLAY
        val portalPadKbFloating = mode == FLOATING
        val imeOnExternal = mode == EXTERNAL
        // On-display AND floating join the native modes in NOT opening the typing
        // page: a field tap raises PortalPad's own keyboard (on the display, or
        // floating over the phone) instead.
        val autoOpenRelay =
            mode != DIRECT && mode != EXTERNAL && mode != ON_DISPLAY && mode != FLOATING
        val relayOpensOnAndroidTab = mode == TEXT_BOX
        prefs.setKeyboardMode(
            imeOnExternal,
            autoOpenRelay,
            relayOpensOnAndroidTab,
            portalPadKbOnDisplay,
            portalPadKbFloating,
        )
        android.util.Log.i(
            "PortalPadKbMode",
            "stored mode=${labels.getOrElse(mode) { "unknown($mode)" }} " +
                "external=$imeOnExternal autoRelay=$autoOpenRelay " +
                "androidTab=$relayOpensOnAndroidTab ppOnDisplay=$portalPadKbOnDisplay " +
                "ppFloating=$portalPadKbFloating",
        )
    }
}

/**
 * Dropdown for [KeyboardMode]. Normally [onPicked] fires after the prefs are
 * written. A temporary Relay can limit this control to its two compatible
 * page modes and keep the user's saved keyboard preference untouched.
 */
@Composable
fun KeyboardModeDropdown(
    modifier: Modifier = Modifier,
    // What to SHOW as selected, when that differs from what's stored. The typing
    // page passes the keyboard you're actually using on it: arriving there from
    // "external display" mode is a temporary detour, so the page shows Custom
    // keyboard (what's under your thumb) while your stored setting stays
    // untouched. Showing "Android keyboard (external display)" on the very page
    // that exists because you're NOT using it read as a bug.
    displayedSelection: Int? = null,
    // A temporary Relay is only a fallback page, so it deliberately offers just
    // Custom and Android-with-text-box; Direct and External are native modes.
    availableModes: List<Int> = KeyboardMode.labels.indices.toList(),
    /** Reports whatever is currently SHOWN as selected, so a caller can describe
     *  it without re-deriving the mode from the prefs itself. */
    onSelectionShown: (Int) -> Unit = {},
    // Temporary Relay choices affect only that page. They must never rewrite the
    // stored Direct or External preference that opened the fallback.
    persistSelection: Boolean = true,
    // The trigger's chevron should agree with where the menu actually opens.
    // Compose flips a menu upward when the MEASURED menu doesn't fit below the
    // anchor - so the usual fix is menuMaxHeight (menu scrolls internally and
    // fits below, staying attached-down); this flag is for cases where upward
    // is genuinely wanted.
    menuOpensUpward: Boolean = false,
    // Cap the menu height so it fits below the anchor in short windows (e.g. the
    // relay's Android tab, where the phone IME halves the window) instead of
    // flipping upward. Null = unconstrained.
    menuMaxHeight: Dp? = null,
    // PIN the menu attached below the trigger with a custom Popup instead of
    // DropdownMenu. DropdownMenu decides direction from the measured window: in
    // the relay's IME-halved window even a height-capped menu didn't fit below
    // the picker, so it flipped upward against the down chevron (SH). A pinned
    // popup physically cannot flip; it scrolls inside menuMaxHeight instead.
    // Opening it also hides the soft keyboard (it dismisses on pick anyway, and
    // hiding it up front frees the very space the menu needs).
    pinMenuBelowAnchor: Boolean = false,
    onPicked: (Int) -> Unit = {},
) {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val onExternal by app.prefs.bool(PreferencesRepository.Keys.IME_ON_EXTERNAL, default = false)
        .collectAsState(initial = false)
    val autoOpen by app.prefs
        .bool(PreferencesRepository.Keys.AUTO_OPEN_RELAY_ON_FIELD, default = true)
        .collectAsState(initial = true)
    val androidTab by app.prefs
        .bool(PreferencesRepository.Keys.RELAY_OPENS_ON_ANDROID_TAB, default = false)
        .collectAsState(initial = false)
    val ppOnDisplay by app.prefs
        .bool(PreferencesRepository.Keys.PORTALPAD_KB_ON_DISPLAY, default = false)
        .collectAsState(initial = false)
    val ppFloating by app.prefs
        .bool(PreferencesRepository.Keys.PORTALPAD_KB_FLOATING, default = false)
        .collectAsState(initial = false)

    val stored = KeyboardMode.of(onExternal, autoOpen, androidTab, ppOnDisplay, ppFloating)
    // Optimistic, per the standing rule (4th instance of this class: status strip,
    // input mode, display mode, now here). [stored] is derived from four DataStore
    // flows written in one transaction, so reading it directly made the picked
    // mode wait on the disk flush before the trigger row and checkmark updated.
    // The tap sets [shownStored]; the flows reconcile it. displayedSelection still
    // wins, unchanged - the typing page's detour semantics are not touched.
    var shownStored by remember { mutableStateOf(stored) }
    LaunchedEffect(stored) { shownStored = stored }
    val selected = displayedSelection ?: shownStored
    LaunchedEffect(selected) { onSelectionShown(selected) }
    var expanded by remember { mutableStateOf(false) }
    // Measured so the menu matches the trigger's width instead of wrapping its
    // own content and floating over the text beside it.
    var triggerWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    // Shared by both menu renderings so a pick means the same thing regardless
    // of which popup mechanism showed the row.
    val pick: (Int) -> Unit = { idx ->
        expanded = false
        if (persistSelection) {
            // Repaint before the write lands. Only on the persisting
            // path: a temporary relay pick must not move the shown
            // stored selection it deliberately is not writing.
            shownStored = idx
            scope.launch {
                KeyboardMode.apply(app.prefs, idx)
                // Relay can close immediately after this callback.
                // When External Display is selected, pin LOCAL now
                // instead of waiting for the asynchronous preference
                // collector or a later field-tap event. This only
                // routes the next user-owned native request; it does
                // not focus, replay, or force-show a keyboard.
                if (idx == KeyboardMode.EXTERNAL) {
                    app.injector.showImeOnExternal()
                }
                onPicked(idx)
            }
        } else {
            onPicked(idx)
        }
    }

    // Trigger extracted so both menu mechanisms share one look; the chevron
    // direction is the caller's to decide (pinned = always down, settings =
    // live from AttachedDropdownBox).
    val trigger: @Composable (chevronUp: Boolean) -> Unit = { chevronUp ->
        Row(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { triggerWidth = with(density) { it.width.toDp() } }
                .clip(RoundedCornerShape(12.dp))
                .background(PhoneSurface)
                .border(1.dp, PhonePrimaryDim, RoundedCornerShape(12.dp))
                .clickable {
                    if (pinMenuBelowAnchor) keyboardController?.hide()
                    expanded = true
                }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(KeyboardMode.labels[selected], color = PhoneOnSurface, modifier = Modifier.weight(1f))
            Icon(
                if (chevronUp) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = PhoneAccentSoft,
            )
        }
    }
    if (!pinMenuBelowAnchor) {
        // Settings path now rides the shared attached scaffold (SH's app-wide
        // dropdown language): direction-aware chevron, width >= trigger,
        // internal scroll past the cap.
        AttachedDropdownBox(
            expanded = expanded,
            onDismiss = { expanded = false },
            modifier = modifier,
            maxMenuHeight = menuMaxHeight ?: 420.dp,
            anchor = { opensUp -> trigger(menuOpensUpward || opensUp) },
        ) {
            ModeMenuItems(availableModes, selected, pick)
        }
    } else Box(modifier) {
        trigger(false)
        run {
            if (expanded) {
                androidx.compose.ui.window.Popup(
                    popupPositionProvider = remember {
                        object : androidx.compose.ui.window.PopupPositionProvider {
                            override fun calculatePosition(
                                anchorBounds: androidx.compose.ui.unit.IntRect,
                                windowSize: androidx.compose.ui.unit.IntSize,
                                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                popupContentSize: androidx.compose.ui.unit.IntSize,
                            ): androidx.compose.ui.unit.IntOffset {
                                val x = anchorBounds.left
                                    .coerceAtMost(windowSize.width - popupContentSize.width)
                                    .coerceAtLeast(0)
                                // Attached below, clamped to the window only as a
                                // last resort (the height cap makes that rare).
                                val y = anchorBounds.bottom
                                    .coerceAtMost(windowSize.height - popupContentSize.height)
                                    .coerceAtLeast(0)
                                return androidx.compose.ui.unit.IntOffset(x, y)
                            }
                        }
                    },
                    onDismissRequest = { expanded = false },
                    properties = androidx.compose.ui.window.PopupProperties(focusable = true),
                ) {
                    Box(
                        Modifier
                            .width(if (triggerWidth > 0.dp) triggerWidth else 280.dp)
                            .then(
                                if (menuMaxHeight != null) Modifier.heightIn(max = menuMaxHeight)
                                else Modifier,
                            )
                            .clip(RoundedCornerShape(12.dp))
                            .background(PhoneSurfaceElevated)
                            .border(1.dp, PhonePrimaryDim, RoundedCornerShape(12.dp)),
                    ) {
                        val menuScroll = rememberScrollState()
                        val menuOverflow = menuScroll.maxValue > 0
                        Column(Modifier.fillMaxWidth()) {
                            if (menuOverflow) ScrollEdgeChevron(menuScroll, up = true)
                            Box(Modifier.weight(1f, fill = false)) {
                                Column(Modifier.verticalScroll(menuScroll).fillMaxWidth()) {
                                    ModeMenuItems(availableModes, selected, pick)
                                }
                                // Visible thumb when the list overflows the cap -
                                // same treatment as the keyboard's mini dropdowns.
                                if (menuOverflow) {
                                    MenuScrollThumb(menuScroll)
                                }
                            }
                            if (menuOverflow) ScrollEdgeChevron(menuScroll, up = false)
                        }
                    }
                }
            }
        }
    }
}

/** The menu body — headers, dividers, rows — shared by the DropdownMenu and the
 *  pinned Popup so the two renderings cannot drift apart. */
@Composable
private fun ModeMenuItems(
    availableModes: List<Int>,
    selected: Int,
    onPick: (Int) -> Unit,
) {
    availableModes.forEachIndexed { position, idx ->
        // Group headers rather than tabs. Tabs would be a second control
        // and a second piece of state for one choice, and a stored tab can
        // disagree with the stored mode — the same drift as defaults
        // declared in several places. A header is derived from the list.
        if (position > 0) HorizontalDivider(color = PhonePrimaryDim.copy(alpha = 0.5f))
        familyHeaderAt(availableModes, position)?.let {
            GroupHeader(it, KeyboardMode.isPortalPad(idx) == KeyboardMode.isPortalPad(selected))
        }
        DropdownMenuItem(
            text = { ModeRowContent(idx, selected) },
            onClick = { onPick(idx) },
        )
    }
}

/**
 * Section header. [highlighted] means "the current selection lives in THIS
 * group", not "this group is PortalPad".
 *
 * It used to colour PortalPad violet and Android muted unconditionally, which is
 * the same ranking implication the unified band was meant to remove — one family
 * permanently featured, the other permanently greyed. Keying the colour to the
 * selection turns it into a "you are here" marker, the way a selected tab works:
 * no family is privileged, and it tells you which group your current choice is in
 * even when the list is scrolled.
 */
@Composable
private fun GroupHeader(text: String, highlighted: Boolean) {
    Text(
        text,
        color = if (highlighted) PhonePrimaryBright else PhoneOnSurfaceMuted,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(PhoneSurface)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
    )
}

/** Header to emit before [position], or null when the family has not changed.
 *  Pure, and shared by both the dropdown and the inline list so the two cannot
 *  disagree about where a group starts. */
private fun familyHeaderAt(availableModes: List<Int>, position: Int): String? {
    // Single-family list (e.g. the typing page's PortalPad-only picker): the header
    // would label the one and only family, which is noise. Suppress it.
    val allSameFamily = availableModes.all {
        KeyboardMode.isPortalPad(it) == KeyboardMode.isPortalPad(availableModes.first())
    }
    if (allSameFamily) return null
    val portalPad = KeyboardMode.isPortalPad(availableModes[position])
    val newFamily = position == 0 ||
        KeyboardMode.isPortalPad(availableModes[position - 1]) != portalPad
    if (!newFamily) return null
    return if (portalPad) "PortalPad keyboard" else "Android keyboard"
}

/** One option's label and tick. Shared so a selected row cannot look like two
 *  different things depending on which control is showing it. */
@Composable
private fun ModeRowContent(idx: Int, selected: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            KeyboardMode.labels[idx],
            color = if (idx == selected) PhoneOnSurface else PhoneOnSurfaceMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (idx == selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = PhonePrimaryBright)
        }
    }
}

/**
 * What the SELECTED mode does — not all four at once.
 *
 * Listing every description under the picker meant reading four paragraphs to
 * learn about the one you chose. The family note carries the distinction that
 * actually predicts behaviour, stated once, above the detail.
 */
/**
 * The same choice as [KeyboardModeDropdown], always expanded.
 *
 * For places whose entire purpose IS this choice, where a collapsed dropdown
 * costs a tap to reveal what you came to see. It also sidesteps a real bug: a
 * Material3 DropdownMenu nested inside a Popup anchors against the outer popup's
 * window root rather than its own trigger, so it opened up and to the left,
 * overlapping the thing that spawned it.
 *
 * [KeyboardModeDropdown] stays the right control on a settings page, where this
 * is one row among many and collapsing it is what keeps the page readable.
 */
@Composable
fun KeyboardModeList(
    modifier: Modifier = Modifier,
    availableModes: List<Int> = KeyboardMode.labels.indices.toList(),
    onSelectionShown: (Int) -> Unit = {},
    onPicked: (Int) -> Unit = {},
) {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val onExternal by app.prefs.bool(PreferencesRepository.Keys.IME_ON_EXTERNAL, default = false)
        .collectAsState(initial = false)
    val autoOpen by app.prefs
        .bool(PreferencesRepository.Keys.AUTO_OPEN_RELAY_ON_FIELD, default = true)
        .collectAsState(initial = true)
    val androidTab by app.prefs
        .bool(PreferencesRepository.Keys.RELAY_OPENS_ON_ANDROID_TAB, default = false)
        .collectAsState(initial = false)
    val ppOnDisplay by app.prefs
        .bool(PreferencesRepository.Keys.PORTALPAD_KB_ON_DISPLAY, default = false)
        .collectAsState(initial = false)
    val ppFloating by app.prefs
        .bool(PreferencesRepository.Keys.PORTALPAD_KB_FLOATING, default = false)
        .collectAsState(initial = false)

    val selected = KeyboardMode.of(onExternal, autoOpen, androidTab, ppOnDisplay, ppFloating)
    LaunchedEffect(selected) { onSelectionShown(selected) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PhoneSurfaceElevated)
            .border(1.dp, PhonePrimaryDim, RoundedCornerShape(12.dp)),
    ) {
        availableModes.forEachIndexed { position, idx ->
            if (position > 0) HorizontalDivider(color = PhonePrimaryDim.copy(alpha = 0.5f))
            familyHeaderAt(availableModes, position)?.let {
                GroupHeader(it, KeyboardMode.isPortalPad(idx) == KeyboardMode.isPortalPad(selected))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            KeyboardMode.apply(app.prefs, idx)
                            // Same as the dropdown: pin LOCAL now rather than wait
                            // on the asynchronous preference collector. Routes the
                            // next native request only; focuses nothing.
                            if (idx == KeyboardMode.EXTERNAL) {
                                app.injector.showImeOnExternal()
                            }
                            onPicked(idx)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 13.dp),
            ) {
                ModeRowContent(idx, selected)
            }
        }
    }
}

@Composable
fun KeyboardModeNotes(
    mode: Int,
    showReliability: Boolean = true,
    // The typing pages pass false (SH, 2026-07-24): the description explains
    // how a field tap GETS you to the page, which is noise to someone already
    // standing on it - and the band above makes the family claim. Settings
    // keeps it (default true): there the reader is still choosing.
    showDescription: Boolean = true,
) {
    val portalPad = KeyboardMode.isPortalPad(mode)
    Column {
        // Symmetric with the 8dp below the band: on the typing page the band sat
        // tight against the title above it while breathing below (SH screenshot).
        Spacer(Modifier.size(8.dp))
        Text(
            KeyboardMode.familyNote(mode),
            color = if (portalPad) PhonePrimaryBright else PhoneOnSurface,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (portalPad) PhonePrimaryDim.copy(alpha = 0.35f) else PhoneSurfaceElevated,
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
        )
        if (showDescription) {
        Spacer(Modifier.size(8.dp))
        Text(
            KeyboardMode.descriptions[mode],
            color = PhoneOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        }
        // Reliability line hidden on the typing page (showReliability=false): the
        // "Made by PortalPad" band above already makes the reliability point, and
        // the page is single-family so the comparison isn't needed. Settings keeps
        // it (default true).
        if (showReliability) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = buildAnnotatedString {
                    pushStyle(SpanStyle(color = PhoneOnSurface))
                    append("Reliability: ")
                    pop()
                    append(KeyboardMode.reliability[mode])
                },
                color = PhoneOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
