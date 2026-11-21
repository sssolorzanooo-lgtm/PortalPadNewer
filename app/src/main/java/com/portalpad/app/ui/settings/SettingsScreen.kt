package com.portalpad.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.*
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.portalpad.app.PortalPadApp
import com.portalpad.app.OrientationLock
import com.portalpad.app.data.AccessMode
import com.portalpad.app.data.BackAction
import com.portalpad.app.data.DockConfig
import com.portalpad.app.data.findFolder
import com.portalpad.app.data.allFoldersFlat
import com.portalpad.app.data.removeRecursive
import androidx.datastore.preferences.core.edit
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SpeakerPhone
import androidx.compose.material.icons.filled.Tv
import com.portalpad.app.data.AudioOutputMode
import com.portalpad.app.data.InputMode
import com.portalpad.app.data.MirrorKeepAwakeMode
import com.portalpad.app.data.PreferencesRepository
import com.portalpad.app.service.AudioSinkCategory
import com.portalpad.app.service.AudioStatusSnapshot
import com.portalpad.app.service.XrealBeamProSettings
import com.portalpad.app.ui.common.PageScrollChevrons
import com.portalpad.app.ui.common.PageScrollbar
import com.portalpad.app.shizuku.ShizukuManager
import com.portalpad.app.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * Five-tab settings screen — feels nothing like a vertical scroll list. Distinctive
 * violet+amber palette. Each tab focuses on one part of the system.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onEnableClick: () -> Unit,
    onPickHome: () -> Unit,
    onPickBack: () -> Unit,
    onPickDock: () -> Unit,
    onAuthorizeShizuku: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onPickBackupFolder: () -> Unit,
    onBackupNow: () -> Unit,
    onRestoreFromFile: () -> Unit,
    onScheduleBackup: (dayOfWeek: Int, hour: Int) -> Unit,
    openToPrivilege: Boolean = false,
    openToSetup: Boolean = false,
    openToControls: Boolean = false,
    onLaunchTrackpad: (() -> Unit)? = null,
) {
    val tabs = remember {
        listOf(
            TabSpec("Start", Icons.Default.PlayArrow),
            TabSpec("Workspace", Icons.Default.GridView),
            TabSpec("Controls", Icons.Default.Tune),
        )
    }
    // Index 2 = Controls (see `tabs` above), where the keyboard section lives.
    var selectedTab by remember { mutableIntStateOf(if (openToControls) 2 else 0) }
    val pickerCtx = androidx.compose.ui.platform.LocalContext.current
    // Top-level section switch shown as a small edge-to-edge strip above the
    // tab row: "Settings" (the Start/Workspace/Controls tabbed UI) vs
    // "Permissions" (a transparency page listing every Android permission).
    // Permissions sub-page. Reached only via the 3-dot menu → Privilege Source,
    // then the "Permissions" link on the strip. The Settings|Permissions strip
    // only appears in this 3-dot sub-area (Privilege Source page + Permissions),
    // never on the main Start/Workspace/Controls tabs.
    var showPermissions by remember { mutableStateOf(false) }
    // Bumped when the user taps an already-active nav item (a main tab, or a
    // strip section they're already on) to signal that page to scroll to top.
    var scrollToTopTick by remember { mutableIntStateOf(0) }
    // Resources sub-page (reached from a note in the System tab). When true it
    // replaces the tab content with a full Resources screen + back arrow.
    var showResources by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showSystem by remember { mutableStateOf(openToPrivilege) }
    var showDisplay by remember { mutableStateOf(false) }
    var showWallpaperPicker by remember { mutableStateOf(false) }
    var showFolderManager by remember { mutableStateOf(false) }
    var showDockCustomization by remember { mutableStateOf(false) }
    var showTopBarCustomization by remember { mutableStateOf(false) }
    var showFolderWindowCustomization by remember { mutableStateOf(false) }
    var showWidgetOverlayCustomization by remember { mutableStateOf(false) }
    // Button-actions page (the same ControlsSubpage the trackpad/remote/air-mouse
    // interfaces open) launched from the Start tab's Home/Back buttons. "Back"
    // returns to Start.
    var showControlsSubpage by remember { mutableStateOf(false) }
    // Full Display tuning page shared with the display pill above the
    // Trackpad/Remote/Air Mouse interfaces.
    var showDisplaySubpage by remember { mutableStateOf(false) }
    // Start-setup subpage: a focused checklist (overlay, privilege, and an
    // optional notification-access question) gating a Continue button that
    // actually starts the service. Reached when the user taps Start Service while
    // requirements aren't met.
    var showStartSetup by remember { mutableStateOf(openToSetup) }
    val tabScroll = rememberScrollState()
    val tabScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            BrandedTopBar(
                onSettingsPage = showSystem || showPermissions || showResources,
                onOpenSettings = {
                    showResources = false; showDiagnostics = false; showSystem = true
                },
            )
        },
        containerColor = PhoneBackground,
    ) { pad ->
        if (showControlsSubpage) {
            // Full-screen button-actions page launched from the Start tab's
            // Home/Back buttons. Same page the trackpad/remote opens; "← Back"
            // returns to Start (Main) here.
            Box(Modifier.padding(pad)) {
                ControlsSubpage(
                    isAirMouse = false,
                    onBack = { showControlsSubpage = false },
                )
            }
        } else if (showDisplaySubpage) {
            Box(Modifier.padding(pad)) {
                DisplaySubpage(onBack = { showDisplaySubpage = false })
            }
        } else {
        Column(Modifier.padding(pad).fillMaxSize()) {
            ModeDebugStrip(
                onDebugPage = showDiagnostics,
                onOpenDebug = { showResources = false; showSystem = false; showDiagnostics = true },
                onFixPrivilege = { showResources = false; showDiagnostics = false; showSystem = false; showStartSetup = true },
            )
            // ── Main nav (Start | Workspace | Controls) — ALWAYS visible across
            // all Settings views, including every sub-page. Only the trackpad /
            // air-mouse / remote control surfaces omit it. No tab is highlighted
            // while on a sub-page (you're in a sub-area, not "on" a tab).
            // Centered when it fits; SCROLLS horizontally when it doesn't
            // (small screens / large font scales), instead of letting the last
            // label wrap to a second line and grow the bar (field, second
            // device: bold selected "Workspace" widened the row and "Controls"
            // wrapped — but only on that tab, because bolding the shorter
            // labels didn't overflow).
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier
                        .horizontalScroll(tabScroll)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val onSubPage = showResources || showDiagnostics || showSystem || showDisplay || showWallpaperPicker || showFolderManager || showPermissions || showDockCustomization || showTopBarCustomization || showFolderWindowCustomization || showWidgetOverlayCustomization || showStartSetup
                    tabs.forEachIndexed { i, spec ->
                        if (i > 0) {
                            VerticalDivider(
                                color = PhoneSurfaceElevated,
                                modifier = Modifier.height(20.dp),
                            )
                        }
                        // Start (tab 0) stays highlighted on the setup sub-page too,
                        // so it reads as part of the Start flow (you got there from
                        // Start Service). Other sub-pages highlight nothing.
                        val active = (selectedTab == i && !onSubPage) || (i == 0 && showStartSetup)
                        Row(
                            Modifier
                                .padding(horizontal = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    // Tapping a main tab leaves any sub-page and
                                    // shows that tab's real content. If already on
                                    // this tab (and not on a sub-page), scroll its
                                    // content back to the top.
                                    if (selectedTab == i && !onSubPage) {
                                        scrollToTopTick++
                                    }
                                    showResources = false
                                    showDiagnostics = false
                                    showSystem = false
                                    showDisplay = false
                                    showWallpaperPicker = false
                                    showFolderManager = false
                                    showDockCustomization = false
                                    showTopBarCustomization = false
                                    showFolderWindowCustomization = false
                                    showWidgetOverlayCustomization = false
                                    showPermissions = false
                                    showStartSetup = false
                                    selectedTab = i
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                spec.icon, contentDescription = null,
                                tint = if (active) PhonePrimary else PhoneOnSurfaceMuted,
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(contentAlignment = Alignment.Center) {
                                // Invisible SemiBold twin reserves the BOLD
                                // width, so selecting a tab never changes the
                                // row's total width — no reflow, ever. Labels
                                // are hard single-line; overflow scrolls (see
                                // container) instead of wrapping the bar taller.
                                Text(
                                    spec.label,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, softWrap = false,
                                    modifier = Modifier.alpha(0f),
                                )
                                Text(
                                    spec.label,
                                    color = if (active) PhonePrimary else PhoneOnSurfaceMuted,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1, softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
            // ── Settings | Permissions | Resources strip — BELOW the main nav,
            // ONLY on these three peer pages. Hidden everywhere else.
            if (showSystem || showPermissions || showResources) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(PhoneSurfaceElevated)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val sectionLabel: @Composable (String, Boolean, () -> Unit) -> Unit =
                        { label, sel, onTap ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onTap() }
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    label,
                                    color = if (sel) PhonePrimaryBright else PhoneOnSurfaceMuted,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    sectionLabel("Settings", showSystem && !showPermissions) {
                        if (showSystem && !showPermissions) scrollToTopTick++
                        showPermissions = false
                        showResources = false; showDiagnostics = false
                        showDisplay = false; showWallpaperPicker = false; showFolderManager = false
                        showDockCustomization = false; showTopBarCustomization = false; showFolderWindowCustomization = false
                        showSystem = true
                    }
                    VerticalDivider(color = PhoneSurface, modifier = Modifier.height(16.dp))
                    sectionLabel("Permissions", showPermissions) {
                        if (showPermissions) scrollToTopTick++
                        showSystem = false; showResources = false; showDiagnostics = false
                        showDisplay = false; showWallpaperPicker = false; showFolderManager = false
                        showDockCustomization = false; showTopBarCustomization = false; showFolderWindowCustomization = false
                        showPermissions = true
                    }
                    VerticalDivider(color = PhoneSurface, modifier = Modifier.height(16.dp))
                    sectionLabel("Resources", showResources) {
                        if (showResources) scrollToTopTick++
                        showSystem = false; showPermissions = false; showDiagnostics = false
                        showDisplay = false; showWallpaperPicker = false; showFolderManager = false
                        showDockCustomization = false; showTopBarCustomization = false; showFolderWindowCustomization = false
                        showResources = true
                    }
                }
            }
            HorizontalDivider(color = PhoneSurface, thickness = 1.dp)

            // The tab / sub-page content gets the column's REMAINING height as a
            // bounded box. Without this it was measured with unbounded height, so
            // the Start tab's ScaleToFit couldn't shrink-to-fit (it overflowed once
            // content grew past the screen) and scrolling sub-pages couldn't scroll.
            Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showPermissions) {
                PermissionsPage(scrollToTopSignal = scrollToTopTick)
            } else {

            if (showResources) {
                ResourcesPage()
            } else if (showDiagnostics) {
                DiagnosticsPage(onBack = { showDiagnostics = false })
            } else if (showSystem) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Settings",
                            color = PhoneOnSurface,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    SystemTab(
                        onAuthorizeShizuku = onAuthorizeShizuku,
                        onOpenOverlaySettings = onOpenOverlaySettings,
                        onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                        onPickBackupFolder = onPickBackupFolder,
                        onBackupNow = onBackupNow,
                        onRestoreFromFile = onRestoreFromFile,
                        onScheduleBackup = onScheduleBackup,
                    )
                }
            } else if (showDisplay) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Display",
                            color = PhoneOnSurface,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    DisplayTab(onOpenDisplaySettings = { showDisplaySubpage = true })
                }
            } else if (showWallpaperPicker) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Home backdrop",
                            color = PhoneOnSurface,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    WallpaperPickerPage()
                }
            } else if (showFolderManager) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Manage folders",
                            color = PhoneOnSurface,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    FolderManagerPage(onBack = { showFolderManager = false })
                }
            } else if (showDockCustomization) {
                DockCustomizationPage(onBack = { showDockCustomization = false })
            } else if (showTopBarCustomization) {
                TopBarCustomizationPage(onBack = { showTopBarCustomization = false })
            } else if (showFolderWindowCustomization) {
                FolderWindowCustomizationPage(onBack = { showFolderWindowCustomization = false })
            } else if (showWidgetOverlayCustomization) {
                WidgetOverlayCustomizationPage(onBack = { showWidgetOverlayCustomization = false })
            } else if (showStartSetup) {
                StartSetupPage(
                    onBack = { showStartSetup = false },
                    onGoToSystemTab = { showStartSetup = false; showSystem = true },
                    onContinue = {
                        showStartSetup = false
                        onEnableClick()
                    },
                )
            } else when (selectedTab) {
                0 -> StartTab(
                    onEnableClick = {
                        if (startRequirementsMet(pickerCtx)) onEnableClick()
                        else showStartSetup = true
                    },
                    onGoToSystemTab = { showSystem = true },
                    onGoToControls = { selectedTab = 2 },
                    onGoToDisplay = { showDisplay = true },
                    onLaunchTrackpad = onLaunchTrackpad,
                    onOpenButtonActions = { showControlsSubpage = true },
                    onOpenAutoLaunch = { com.portalpad.app.ui.dock.launchDockPickerOnPhone(pickerCtx, "autolaunch") },
                    onPickHome = onPickHome,
                    onPickBack = onPickBack,
                    onOpenStartSetup = { showStartSetup = true },
                )
                1 -> WorkspaceTab(
                    onPickDock,
                    onOpenWallpaperPicker = { showWallpaperPicker = true },
                    onOpenFolderManager = { showFolderManager = true },
                    onOpenDockCustomization = { showDockCustomization = true },
                    onOpenTopBarCustomization = { showTopBarCustomization = true },
                    onOpenFolderWindowCustomization = { showFolderWindowCustomization = true },
                    onOpenWidgetOverlayCustomization = { showWidgetOverlayCustomization = true },
                    scrollToTopSignal = scrollToTopTick,
                )
                2 -> ButtonsTab(onPickHome, onPickBack, scrollToTopSignal = scrollToTopTick)
                else -> StartTab(
                    onEnableClick = {
                        if (startRequirementsMet(pickerCtx)) onEnableClick()
                        else showStartSetup = true
                    },
                    onGoToSystemTab = { showSystem = true },
                    onGoToControls = { selectedTab = 2 },
                    onGoToDisplay = { showDisplay = true },
                    onLaunchTrackpad = onLaunchTrackpad,
                    onOpenButtonActions = { showControlsSubpage = true },
                    onOpenAutoLaunch = { com.portalpad.app.ui.dock.launchDockPickerOnPhone(pickerCtx, "autolaunch") },
                    onPickHome = onPickHome,
                    onPickBack = onPickBack,
                    onOpenStartSetup = { showStartSetup = true },
                )
            }
            }
            }
        }
        }
    }
}

private data class TabSpec(val label: String, val icon: ImageVector)

/**
 * Home-backdrop picker (separate Workspace sub-page). Lets the user choose the
 * external-display wallpaper from a set of PortalPad-owned images, plus a
 * "None (black)" option. Selection persists to prefs; the backdrop itself shows
 * on the external display via WallpaperActivity. Never touches Android's
 * system wallpaper.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WallpaperPickerPage() {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Canvas-aware: classify the connected external display so the picker can open
    // on the matching tab and highlight the canvas-appropriate default (ultrawide →
    // Cosmic Genesis) when the user hasn't picked anything yet.
    val canvasUltrawide = remember {
        runCatching {
            val dm = app.getSystemService(android.hardware.display.DisplayManager::class.java)
            val ext = dm?.displays?.firstOrNull { com.portalpad.app.DisplayUtil.isRealExternalDisplay(it) }
            if (ext != null) {
                val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") ext.getRealMetrics(it) }
                com.portalpad.app.data.Wallpaper.isUltrawideCanvas(m.widthPixels, m.heightPixels)
            } else false
        }.getOrDefault(false)
    }
    var selectedCategory by remember {
        mutableStateOf(
            if (canvasUltrawide) com.portalpad.app.data.WallpaperCategory.ULTRAWIDE
            else com.portalpad.app.data.WallpaperCategory.STANDARD
        )
    }
    val ultra = selectedCategory == com.portalpad.app.data.WallpaperCategory.ULTRAWIDE
    // Per-canvas selection: each tab remembers its own pick; the live external
    // canvas decides which one actually renders on the glasses.
    val stdRaw by app.prefs.homeWallpaperRaw(false).collectAsState(initial = null)
    val uwRaw by app.prefs.homeWallpaperRaw(true).collectAsState(initial = null)
    val current = (if (ultra) uwRaw else stdRaw) ?: com.portalpad.app.data.Wallpaper.effectiveDefault(ultra)
    val customPath by app.prefs.customWallpaperPath.collectAsState(initial = null)

    // Photo picker — copies the chosen image into app-private storage (robust
    // against the gallery URI later becoming invalid), saves the path, and
    // selects it as the custom backdrop.
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val dir = java.io.File(ctx.filesDir, "wallpapers").apply { mkdirs() }
                        val out = java.io.File(dir, "custom.jpg")
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                        out.absolutePath
                    }.getOrNull()
                }
                if (saved != null) {
                    app.prefs.setCustomWallpaperPath(saved)
                    app.prefs.setHomeWallpaper(
                        selectedCategory == com.portalpad.app.data.WallpaperCategory.ULTRAWIDE,
                        com.portalpad.app.data.Wallpaper.CUSTOM,
                    )
                    app.injector.buzz(longPress = false)
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Fixed header (through "Use my own image") stays put for quick upload access;
        // only the wallpaper grid below scrolls, via weight(1f) so it fills the leftover
        // space and scrolls within it — adapting to any device size.
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Shown on the external display when no home app is set, or after you close all apps. This is PortalPad's own backdrop — it does not change your phone's wallpaper.",
            color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                append("Note: ")
                pop()
                append("The preset images below were AI-generated using Google Gemini.")
            },
            color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
        )
        // Per-canvas tip (amber = informational, like the SBS note below).
        // (No manual Spacer — the TabBody 14dp arrangement gap above this Tip now
        // matches the gap above the "Note:" line above it.)
        Text(
            text = androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                append("Tip: ")
                pop()
                append("Standard and ultrawide canvases each keep their own wallpaper — the matching one is selected automatically for the current resolution.")
            },
            color = PhoneWarning, style = MaterialTheme.typography.bodySmall,
        )
        // Category switch: Standard (16:9 / 1920x1080) vs Ultrawide (21:9 & 32:9).
        // Filters the grid below; opens on the tab matching the connected canvas.
        // (No manual Spacer here — the TabBody 14dp arrangement gap already matches
        // the gap below the control, keeping the space above and below symmetric.)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(PhoneSurface)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(
                com.portalpad.app.data.WallpaperCategory.STANDARD to "Standard",
                com.portalpad.app.data.WallpaperCategory.ULTRAWIDE to "Ultrawide",
            ).forEach { (cat, label) ->
                val sel = selectedCategory == cat
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (sel) PhonePrimary else Color.Transparent)
                        .clickable {
                            app.injector.buzz(longPress = false)
                            selectedCategory = cat
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (sel) PhoneOnSurface else PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        val caption = if (ultra) "21:9 & 32:9 — Ultrawide displays" else "1920\u00D71080 — Standard displays"
        Text(
            caption,
            color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                append("Note: ")
                pop()
                append(
                    if (ultra)
                        "These also appear in Full 3D SBS (3840\u00D71080). Half 3D SBS is 1920\u00D71080 — a Standard wallpaper."
                    else
                        "Half 3D SBS (1920\u00D71080) also uses these. Full 3D SBS is 3840\u00D71080 — an Ultrawide wallpaper."
                )
            },
            color = PhoneWarning, style = MaterialTheme.typography.bodySmall,
        )
        // Use-your-own-image button at the top.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(PhoneAccent.copy(alpha = 0.14f))
                .border(1.dp, PhoneAccent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                .clickable {
                    pickImage.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = PhoneAccent)
            Spacer(Modifier.width(12.dp))
            Text(
                "Use my own image",
                color = PhoneOnSurface, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        } // end fixed header — only the grid below scrolls
        val gridScroll = rememberScrollState()
        val gridThumb = PhoneOnSurfaceMuted.copy(alpha = 0.5f)
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(gridScroll),
        ) {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            com.portalpad.app.data.Wallpaper.entries.forEach { wp ->
                // The CUSTOM tile only appears once the user has actually picked
                // an image (choice (a) — hidden until picked).
                if (wp == com.portalpad.app.data.Wallpaper.CUSTOM && customPath == null) return@forEach
                // Category filter: show this tab's wallpapers plus the universal
                // ones (None, Custom) which fit any canvas.
                if (wp.category != com.portalpad.app.data.WallpaperCategory.UNIVERSAL &&
                    wp.category != selectedCategory) return@forEach
                val selected = wp == current
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(150.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                            .then(
                                if (selected) Modifier.border(2.5.dp, PhonePrimaryBright, RoundedCornerShape(12.dp))
                                else Modifier.border(1.dp, PhoneSurfaceElevated, RoundedCornerShape(12.dp))
                            )
                            .clickable {
                                app.injector.buzz(longPress = false)
                                scope.launch { app.prefs.setHomeWallpaper(ultra, wp) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val res = wp.drawableRes
                        when {
                            wp == com.portalpad.app.data.Wallpaper.CUSTOM && customPath != null -> {
                                val bmp = remember(customPath) {
                                    runCatching { android.graphics.BitmapFactory.decodeFile(customPath) }.getOrNull()
                                }
                                if (bmp != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = wp.label,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    )
                                }
                                // Overlaid "Custom Image" bar at the bottom of the tile.
                                Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .padding(vertical = 3.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("Custom Image", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            res != null -> {
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(res),
                                    contentDescription = wp.label,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            }
                            else -> {
                                Text("None", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (selected) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(PhonePrimaryBright),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Check, contentDescription = "Selected",
                                    tint = Color.Black, modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // The custom tile shows its label ON the image; built-ins show it below.
                    if (wp != com.portalpad.app.data.Wallpaper.CUSTOM) {
                        Text(
                            wp.label,
                            color = if (selected) PhonePrimaryBright else PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        } // end inner scrolling grid
        // Thin scrollbar on the right edge — only paints when the grid overflows
        // (short/ultrawide content that fits shows nothing). Matches TabBody's,
        // plus CHEVRON caps: ▲ above the track and ▼ below it, each lit in the
        // accent while there's content THAT way (scroll position > 0 / < max)
        // and dimmed at the end stop — an at-a-glance "more up / more down".
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    val max = gridScroll.maxValue
                    if (max > 0) {
                        val chevZone = 16.dp.toPx()   // cap reserved per end
                        val trackTop = 8.dp.toPx() + chevZone
                        val trackBottom = size.height - 8.dp.toPx() - chevZone
                        val trackH = (trackBottom - trackTop).coerceAtLeast(1f)
                        val viewport = size.height
                        val total = viewport + max
                        val thumbH = (trackH * (viewport / total)).coerceIn(28.dp.toPx(), trackH)
                        val frac = gridScroll.value.toFloat() / max.toFloat()
                        val thumbY = trackTop + (trackH - thumbH) * frac
                        val w = 4.dp.toPx()
                        val x = size.width - w - 3.dp.toPx()
                        drawRoundRect(
                            color = gridThumb,
                            topLeft = androidx.compose.ui.geometry.Offset(x, thumbY),
                            size = androidx.compose.ui.geometry.Size(w, thumbH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f),
                        )
                        // Chevrons: small triangles centered on the track's x.
                        val cx = x + w / 2f
                        val half = 5.dp.toPx()
                        val rise = 5.dp.toPx()
                        val lit = PhoneAccent
                        val dim = gridThumb.copy(alpha = 0.25f)
                        val upColor = if (gridScroll.value > 0) lit else dim
                        val downColor = if (gridScroll.value < max) lit else dim
                        val upBase = trackTop - 6.dp.toPx()
                        drawPath(
                            androidx.compose.ui.graphics.Path().apply {
                                moveTo(cx - half, upBase)
                                lineTo(cx + half, upBase)
                                lineTo(cx, upBase - rise)
                                close()
                            },
                            color = upColor,
                        )
                        val downBase = trackBottom + 6.dp.toPx()
                        drawPath(
                            androidx.compose.ui.graphics.Path().apply {
                                moveTo(cx - half, downBase)
                                lineTo(cx + half, downBase)
                                lineTo(cx, downBase + rise)
                                close()
                            },
                            color = downColor,
                        )
                    }
                },
        )
        } // end grid + scrollbar Box
    }
}

/**
 * Phase A folder manager (a Workspace sub-page). Consolidates folder editing that was
 * previously only reachable by long-pressing dock items: browse folders, drill
 * into a folder (and subfolders), reorder its contents (up/down), remove items,
 * and sort. Rename and icon customization remain available via the dock's own
 * long-press. Edits write straight to the dock config, so the dock on the
 * external display updates as the config changes.
 */
@Composable
private fun FolderManagerPage(onBack: () -> Unit = {}) {
    val app = PortalPadApp.instance
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by app.prefs.dockConfig.collectAsState(initial = com.portalpad.app.data.DockConfig())
    var stack by remember { mutableStateOf<List<String>>(emptyList()) }
    // Which folder's appearance editor is open (by id), if any.
    var appearanceFolderId by remember { mutableStateOf<String?>(null) }
    // Bulk-select mode: when on, rows show checkboxes and a bulk action bar
    // appears. Selection is scoped to the current view and cleared on navigation.
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Move-to-folder picker (bulk) open?
    var bulkPickFolder by remember { mutableStateOf(false) }

    fun recentMillis(pkg: String): Long = runCatching {
        app.packageManager.getPackageInfo(pkg, 0).firstInstallTime
    }.getOrDefault(0L)

    val currentFolder = stack.lastOrNull()?.let { cfg.items.findFolder(it) }
    // Mirror the folder being managed onto the external display (real contents,
    // live) so reordering / edits are visible there as you make them. Clears when
    // you leave the folder or close the screen.
    androidx.compose.runtime.DisposableEffect(currentFolder?.id) {
        app.setManagedFolderId(currentFolder?.id)
        // Navigating to a different folder view: reset any bulk selection so we
        // never act on items that aren't visible here.
        selectMode = false
        selectedIds = emptySet()
        bulkPickFolder = false
        onDispose { app.setManagedFolderId(null) }
    }
    val shown: List<com.portalpad.app.data.DockItem> =
        if (currentFolder == null) cfg.items.filterIsInstance<com.portalpad.app.data.DockItem.Folder>()
        else currentFolder.contents

    val externalDisplayId by app.externalDisplayId.collectAsState()

    Box(Modifier.fillMaxSize()) {
    TabBody {
        // Heads-up when there's no external display: folder edits save to the
        // dock config and will show once a display is connected, but you won't
        // see them reflected live right now.
        if (externalDisplayId == null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF8A7A4D), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFFB8A66A),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "No display connected. Changes are saved and will appear on your external display once connected — you just won't see them live right now.",
                    color = Color(0xFFB8A66A),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        if (stack.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .clickable { stack = stack.dropLast(1) }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                ) {
                    Text("‹ Up", color = PhoneAccent, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    currentFolder?.label ?: "",
                    color = PhoneOnSurface, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                )
            }
        } else {
            Text(
                "Top-level folders. Tap a folder to manage its contents.",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
        }

        currentFolder?.let { folder ->
            SectionCard(title = "Sort contents") {
                com.portalpad.app.ui.dock.SortControl(
                    sort = folder.sort,
                    ascending = folder.sortAscending,
                    colors = com.portalpad.app.ui.dock.themeFolderControlColors(),
                    onChange = { mode, asc ->
                        scope.launch {
                            app.prefs.setDockConfig(cfg.sortFolder(folder.id, mode, asc, ::recentMillis))
                        }
                    },
                )
            }
        }

        SectionCard(title = if (currentFolder == null) "Folders" else "Contents") {
            // Add a new top-level folder (only at the top level, not inside a folder).
            if (currentFolder == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PhoneSurfaceElevated)
                        .clickable {
                            scope.launch {
                                if (cfg.isFull) {
                                    android.widget.Toast.makeText(
                                        app, "Dock is full (${com.portalpad.app.data.MAX_DOCK_ITEMS} items). Remove an item first.",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                } else {
                                    app.prefs.setDockConfig(
                                        cfg.addFolder("Folder ${cfg.items.count { it is com.portalpad.app.data.DockItem.Folder } + 1}")
                                    )
                                }
                            }
                        }
                        .padding(14.dp),
                ) {
                    Text(
                        "+  Add a folder",
                        color = PhoneAccent,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(Modifier.height(8.dp))
            } else {
                // Inside a folder: add apps directly to it. Reuses the same
                // phone app-picker the dock uses, targeted at this folder, so
                // picked apps land straight inside it.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PhoneSurfaceElevated)
                        .clickable {
                            com.portalpad.app.ui.dock.launchDockPickerOnPhone(
                                ctx, "folder:${currentFolder.id}",
                            )
                        }
                        .padding(14.dp),
                ) {
                    Text(
                        "+  Add apps to this folder",
                        color = PhoneAccent,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            // Bulk-select controls. Only meaningful when there are items to act on.
            if (shown.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp))
                            .clickable {
                                selectMode = !selectMode
                                if (!selectMode) { selectedIds = emptySet(); bulkPickFolder = false }
                            }
                            .padding(vertical = 6.dp, horizontal = 8.dp),
                    ) {
                        Text(
                            if (selectMode) "Done" else "Select",
                            color = PhoneAccent, style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (selectMode) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${selectedIds.size} selected",
                            color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.weight(1f))
                        // Select-all / none toggle.
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    selectedIds = if (selectedIds.size == shown.size) emptySet()
                                    else shown.map { it.id }.toSet()
                                }
                                .padding(vertical = 6.dp, horizontal = 8.dp),
                        ) {
                            Text(
                                if (selectedIds.size == shown.size) "None" else "All",
                                color = PhoneAccent, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                // Bulk action bar — visible once something is selected.
                if (selectMode && selectedIds.isNotEmpty() && !bulkPickFolder) {
                    val moveTargets = cfg.items.allFoldersFlat().filter { f ->
                        f.id != currentFolder?.id && f.id !in selectedIds
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (moveTargets.isNotEmpty()) {
                            BulkActionChip("Move to folder…") { bulkPickFolder = true }
                        }
                        // "Move out to dock" only matters inside a folder.
                        if (currentFolder != null) {
                            BulkActionChip("Move out to dock") {
                                scope.launch {
                                    var c = cfg
                                    selectedIds.forEach { id -> c = c.extractToTopLevel(id) }
                                    app.prefs.setDockConfig(c)
                                    selectMode = false; selectedIds = emptySet()
                                }
                            }
                        }
                        BulkActionChip("Remove", danger = true) {
                            scope.launch {
                                var c = cfg
                                selectedIds.forEach { id -> c = c.copy(items = c.items.removeRecursive(id)) }
                                app.prefs.setDockConfig(c)
                                selectMode = false; selectedIds = emptySet()
                            }
                        }
                    }
                }
                // Bulk move-to-folder picker.
                if (selectMode && bulkPickFolder) {
                    val moveTargets = cfg.items.allFoldersFlat().filter { f ->
                        f.id != currentFolder?.id && f.id !in selectedIds
                    }
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Move ${selectedIds.size} to…", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                        moveTargets.forEach { f ->
                            Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(PhoneSurfaceElevated)
                                    .clickable {
                                        scope.launch {
                                            var c = cfg
                                            selectedIds.forEach { id -> c = c.moveIntoFolder(id, f.id) }
                                            app.prefs.setDockConfig(c)
                                            selectMode = false; selectedIds = emptySet(); bulkPickFolder = false
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) { Text(f.label, color = PhoneOnSurface, style = MaterialTheme.typography.bodyMedium) }
                        }
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp)).clickable { bulkPickFolder = false }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) { Text("‹ Back", color = PhoneAccent, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            if (shown.isEmpty()) {
                Text(
                    if (currentFolder == null) "No folders yet. Tap “Add a folder” above."
                    else "This folder is empty.",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
            }
            shown.forEachIndexed { index, item ->
                val isFolder = item is com.portalpad.app.data.DockItem.Folder
                val checked = item.id in selectedIds
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .then(
                            if (selectMode) Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                selectedIds = if (checked) selectedIds - item.id else selectedIds + item.id
                            } else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectMode) {
                        Text(
                            if (checked) "\u2611" else "\u2610", // ☑ / ☐
                            color = if (checked) PhoneAccent else PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                    }
                    Column(
                        Modifier.weight(1f)
                            .then(
                                if (isFolder && !selectMode) Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { stack = stack + item.id }
                                else Modifier
                            )
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            (if (isFolder) "📁  " else "") + item.label,
                            color = PhoneOnSurface, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                        )
                        if (isFolder) {
                            val f = item as com.portalpad.app.data.DockItem.Folder
                            Text(
                                "${f.contents.size} items · tap to open",
                                color = PhoneAccent, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    // Per-row controls hidden in select mode (bulk bar handles actions).
                    if (!selectMode) {
                        if (isFolder) {
                            IconTextButton("🎨") { appearanceFolderId = item.id }
                        }
                        IconTextButton("↑", enabled = index > 0) {
                            scope.launch {
                                val newCfg = if (currentFolder == null)
                                    cfg.copy(items = cfg.items.moveFolderListItem(shown, index, index - 1))
                                else cfg.moveItemInFolder(currentFolder.id, index, index - 1)
                                app.prefs.setDockConfig(newCfg)
                            }
                        }
                        IconTextButton("↓", enabled = index < shown.size - 1) {
                            scope.launch {
                                val newCfg = if (currentFolder == null)
                                    cfg.copy(items = cfg.items.moveFolderListItem(shown, index, index + 1))
                                else cfg.moveItemInFolder(currentFolder.id, index, index + 1)
                                app.prefs.setDockConfig(newCfg)
                            }
                        }
                        IconTextButton("✕", danger = true) {
                            scope.launch { app.prefs.setDockConfig(cfg.copy(items = cfg.items.removeRecursive(item.id))) }
                        }
                    }
                }
            }
        }
        // Clear space so the pinned Back button doesn't cover the last row.
        Spacer(Modifier.height(72.dp))
    }

    // Per-folder appearance editor (reuses the dock's editor). Opens when a
    // folder's palette button is tapped; persists via setFolderAppearance.
    appearanceFolderId?.let { fid ->
        val folder = cfg.items.findFolder(fid)
        if (folder != null) {
            com.portalpad.app.ui.dock.FolderAppearanceEditor(
                folder = folder,
                onDismiss = { appearanceFolderId = null },
                onApply = { color, appearance, presetKey, symbol, iconPackPackage, iconPackDrawable ->
                    scope.launch {
                        app.prefs.setDockConfig(
                            cfg.setFolderAppearance(fid, color, appearance, presetKey, symbol, iconPackPackage, iconPackDrawable)
                        )
                    }
                    appearanceFolderId = null
                },
            )
        } else {
            appearanceFolderId = null
        }
    }

    // Pinned bottom Back. Inside a folder it goes up a level; at the top level
    // it exits the Manage Folders page (matches the other sub-pages' pattern).
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PhoneSurfaceElevated)
            .border(1.dp, PhoneAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable { if (stack.isNotEmpty()) stack = stack.dropLast(1) else onBack() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (stack.isNotEmpty()) "\u2190 Up" else "\u2190 Back",
            color = PhoneOnSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        )
    }
    }
}

/** Move within the filtered top-level folder list, mapping back to full items. */
private fun List<com.portalpad.app.data.DockItem>.moveFolderListItem(
    shown: List<com.portalpad.app.data.DockItem>,
    from: Int, to: Int,
): List<com.portalpad.app.data.DockItem> {
    if (from !in shown.indices || to !in shown.indices) return this
    val fromId = shown[from].id
    val toId = shown[to].id
    val absFrom = indexOfFirst { it.id == fromId }
    val absTo = indexOfFirst { it.id == toId }
    if (absFrom < 0 || absTo < 0) return this
    val m = toMutableList()
    val moved = m.removeAt(absFrom)
    m.add(absTo, moved)
    return m
}

/** Small text "icon" button used by the folder manager rows. */
@Composable
private fun BulkActionChip(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PhoneSurfaceElevated)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            color = if (danger) PhoneAccent else PhoneOnSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun IconTextButton(symbol: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PhoneSurfaceElevated)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            symbol,
            color = when {
                !enabled -> PhoneOnSurfaceDim
                danger -> PhoneAccent
                else -> PhoneOnSurface
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * Permissions transparency page (the "Permissions" top-section). Lists every
 * Android permission PortalPad declares, grouped Required / Optional / System,
 * each with a plain-language reason. Where a permission is user-toggleable, an
 * "Open settings" button deep-links to the most specific Android screen we can
 * reach; privileged (Shizuku/Root) and auto-granted permissions are shown for
 * transparency but are informational (no per-permission toggle exists).
 */
@Composable
private fun PermissionsPage(scrollToTopSignal: Int = 0) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Action kinds: which Android settings screen (if any) a row links to.
    fun openAppDetails() = runCatching {
        context.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // Overlay settings: pass the package URI so most devices open straight to
    // PortalPad's own "display over other apps" toggle instead of the full app
    // list. Falls back to the bare action (general list) if the OEM rejects the
    // URI, then to app details as a last resort.
    fun openOverlaySettings() = runCatching {
        context.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.recoverCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { openAppDetails() }

    // Notification-listener settings: on Android 11+ try the per-app detail
    // screen (jumps straight to PortalPad's listener toggle); fall back to the
    // general listener list on older versions / OEMs that don't support it.
    fun openNotificationListenerSettings() = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val cn = android.content.ComponentName(
                context, com.portalpad.app.service.PortalPadNotificationListenerService::class.java,
            )
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            throw IllegalStateException("pre-30")
        }
    }.recoverCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { openAppDetails() }
    fun openIntent(action: String) = runCatching {
        context.startActivity(
            android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { openAppDetails() }

    // Recompute statuses whenever we resume (e.g. returning from an Android
    // settings screen where the user just toggled something).
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun hasRuntime(perm: String): PermStatus {
        refreshTick // read so this re-evaluates on resume-driven recomposition
        return if (androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) PermStatus.GRANTED else PermStatus.DENIED
    }
    val overlayStatus = remember(refreshTick) {
        if (android.provider.Settings.canDrawOverlays(context)) PermStatus.GRANTED else PermStatus.DENIED
    }
    val notifListenerStatus = remember(refreshTick) {
        val enabled = runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners",
            )?.contains(context.packageName) == true
        }.getOrDefault(false)
        if (enabled) PermStatus.GRANTED else PermStatus.DENIED
    }
    val accessibilityStatus = remember(refreshTick) {
        val enabled = runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )?.contains(context.packageName) == true
        }.getOrDefault(false)
        if (enabled) PermStatus.GRANTED else PermStatus.DENIED
    }
    val batteryStatus = remember(refreshTick) {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) PermStatus.GRANTED else PermStatus.DENIED
    }
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val shizukuStatus by app.shizuku.status.collectAsState()
    val accessMode by app.prefs.accessMode.collectAsState(initial = AccessMode.NONE)
    val privilegeReady = when (accessMode) {
        AccessMode.SHIZUKU -> shizukuStatus == ShizukuManager.Status.READY
        AccessMode.ROOT -> app.root.isReady
        AccessMode.NONE -> shizukuStatus == ShizukuManager.Status.READY || app.root.isReady
    }
    var quickGrantBusy by remember { mutableStateOf(false) }
    var quickGrantNote by remember { mutableStateOf<String?>(null) }

    TabBody(scrollToTopSignal = scrollToTopSignal) {
        Text(
            "Every permission PortalPad uses and why. Privileged items are granted through your access source (Shizuku/Root); some are granted automatically and have nothing to toggle.",
            color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
        )

        SectionCard(title = "One-tap Shizuku setup") {
            Column(Modifier.fillMaxWidth().testTag("permissions_enable_all_shizuku_card")) {
                Text(
                    "Enable all required permissions and companion services in one tap via Shizuku or Root instead of checking them one by one. Camera is left untouched.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val shellAccess = listOf(app.access, app.shizuku, app.root)
                        .firstOrNull { it.isReady }
                    if (shellAccess == null) {
                        quickGrantNote = "Connect Shizuku or Root in Access source first."
                        return@Button
                    }
                    quickGrantBusy = true
                    quickGrantNote = "Applying required settings via ${if (app.root.isReady && accessMode == AccessMode.ROOT) "Root" else "Shizuku"}..."
                    scope.launch {
                        val pkg = context.packageName
                        val notifComp = "$pkg/${com.portalpad.app.service.PortalPadNotificationListenerService::class.java.name}"
                        val a11yComp = "$pkg/${com.portalpad.app.service.PortalPadAccessibilityService::class.java.name}"
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val cmds = mutableListOf(
                                "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow",
                                "appops set $pkg SYSTEM_ALERT_WINDOW allow",
                                "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
                                "pm grant $pkg android.permission.INJECT_EVENTS",
                                "cmd deviceidle whitelist +$pkg",
                                "dumpsys deviceidle whitelist +$pkg",
                                "cmd notification allow_listener $notifComp",
                                "pm grant $pkg android.permission.RECORD_AUDIO",
                                "pm grant $pkg android.permission.READ_PHONE_STATE",
                            )
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                cmds += "pm grant $pkg android.permission.POST_NOTIFICATIONS"
                            }
                            if (android.os.Build.VERSION.SDK_INT >= 31) {
                                cmds += "pm grant $pkg android.permission.BLUETOOTH_CONNECT"
                            }
                            cmds.forEach { cmd -> runCatching { shellAccess.execShell(cmd) } }

                            val curNotif = runCatching {
                                shellAccess.execShell("settings get secure enabled_notification_listeners").trim()
                            }.getOrDefault("")
                            if (!curNotif.split(":").contains(notifComp)) {
                                val nextNotif = when {
                                    curNotif.isEmpty() || curNotif == "null" -> notifComp
                                    else -> "$curNotif:$notifComp"
                                }
                                runCatching {
                                    shellAccess.execShell("settings put secure enabled_notification_listeners $nextNotif")
                                }
                            }

                            val curA11y = runCatching {
                                shellAccess.execShell("settings get secure enabled_accessibility_services").trim()
                            }.getOrDefault("")
                            val nextA11y = when {
                                curA11y.isEmpty() || curA11y == "null" -> a11yComp
                                curA11y.split(":").contains(a11yComp) -> curA11y
                                else -> "$curA11y:$a11yComp"
                            }
                            runCatching {
                                shellAccess.execShell("settings put secure enabled_accessibility_services $nextA11y")
                            }
                            runCatching {
                                shellAccess.execShell("settings put secure accessibility_enabled 1")
                            }
                        }

                        var notifOk = false
                        var a11yOk = false
                        for (i in 0 until 6) {
                            kotlinx.coroutines.delay(350)
                            refreshTick++
                            notifOk = runCatching {
                                android.provider.Settings.Secure.getString(
                                    context.contentResolver, "enabled_notification_listeners",
                                )?.contains(pkg) == true
                            }.getOrDefault(false)
                            a11yOk = runCatching {
                                android.provider.Settings.Secure.getString(
                                    context.contentResolver,
                                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                )?.contains(pkg) == true
                            }.getOrDefault(false)
                            if (notifOk && a11yOk) break
                        }
                        refreshTick++
                        val overlayOk = android.provider.Settings.canDrawOverlays(context)
                        quickGrantNote = if (overlayOk && notifOk && a11yOk) {
                            "All required permissions & services enabled (Camera untouched)."
                        } else {
                            val blocked = buildList {
                                if (!overlayOk) add("Overlay")
                                if (!notifOk) add("Notification access")
                                if (!a11yOk) add("Accessibility")
                            }.joinToString(", ")
                            "Applied Shizuku grants (Camera untouched). Still needs OS approval: $blocked (check App info \u2192 Allow restricted settings if blocked)."
                        }
                        quickGrantBusy = false
                    }
                },
                enabled = !quickGrantBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PhonePrimary,
                    contentColor = PhoneOnSurface,
                    disabledContainerColor = PhoneSurfaceElevated,
                    disabledContentColor = PhoneOnSurfaceDim,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("permissions_enable_all_shizuku_button"),
            ) {
                if (quickGrantBusy) {
                    CircularProgressIndicator(
                        color = PhoneOnSurface,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Enabling permissions...")
                } else {
                    Text(
                        if (privilegeReady) "Enable all required settings"
                        else "Enable all required settings (connect Shizuku)",
                    )
                }
            }
            quickGrantNote?.let { note ->
                Spacer(Modifier.height(8.dp))
                Text(
                    note,
                    color = if (note.startsWith("All required")) PhoneAccent else PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            }
        }

        SectionCard(title = "Required") {
            PermissionRow("Display over other apps",
                "Draws the cursor, dock, and top bar on top of apps on the external display.",
                status = overlayStatus,
                onOpen = { openOverlaySettings() },
                isFirst = true)
            PermissionRow("Inject input events",
                "Sends taps, clicks, and key presses to the external display so the trackpad and buttons work. Granted via Shizuku/Root.",
                status = PermStatus.PRIVILEGED)
            PermissionRow("Manage system windows",
                "Lets the cursor and overlays sit above system UI on the external display. Granted via Shizuku/Root.",
                status = PermStatus.PRIVILEGED)
            PermissionRow("Manage activity tasks",
                "Launches, arranges, and resizes app windows on the external display (freeform, Arrange). Granted via Shizuku/Root.",
                status = PermStatus.PRIVILEGED)
            PermissionRow("Write secure settings",
                "Lets PortalPad adjust protected system settings needed for input injection and display setup. Granted via Shizuku/ADB.",
                status = PermStatus.PRIVILEGED)
            PermissionRow("Foreground service",
                "Keeps PortalPad running reliably while it drives the external display. Granted automatically.",
                status = PermStatus.AUTO)
            PermissionRow("See installed apps",
                "Lists your installed apps so you can add them to the dock and launch them. Granted automatically.",
                status = PermStatus.AUTO)
        }

        SectionCard(title = "Optional") {
            PermissionRow("Notification access",
                "Powers media controls (play/pause, track info) and the volume readout in the remote interface.",
                status = notifListenerStatus,
                onOpen = { openNotificationListenerSettings() },
                isFirst = true)
            PermissionRow("Accessibility service",
                "Required for auto-open: detects when you tap a text field on the external display and opens the typing page automatically, showing the field's current text to edit. Without it, the typing page won't open on field taps.",
                status = accessibilityStatus,
                onOpen = { openIntent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS) })
            PermissionRow("Microphone",
                "Voice search from the dock.",
                status = hasRuntime(android.Manifest.permission.RECORD_AUDIO),
                onOpen = { openAppDetails() })
            PermissionRow("Camera",
                "Toggles the phone's flashlight/torch from the remote interface. Android controls the torch through the camera, so this is needed even though no photos are taken.",
                status = hasRuntime(android.Manifest.permission.CAMERA),
                onOpen = { openAppDetails() })
            PermissionRow("Notifications",
                "Shows the ongoing service notification and any alerts.",
                status = if (android.os.Build.VERSION.SDK_INT >= 33)
                    hasRuntime("android.permission.POST_NOTIFICATIONS") else PermStatus.AUTO,
                onOpen = { openAppDetails() })
            PermissionRow("Ignore battery optimization",
                "Stops Android from killing the service in the background so the external display doesn't drop.",
                status = batteryStatus,
                onOpen = { openIntent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) })
            PermissionRow("Phone state",
                "Powers the mobile network-type label (5G/LTE/4G) on the status icon. Signal bars don't need it. Granted via Shizuku/Root when you opt in during setup.",
                status = hasRuntime(android.Manifest.permission.READ_PHONE_STATE),
                onOpen = { openAppDetails() })
            PermissionRow("Bluetooth devices",
                "Counts the connected Bluetooth devices shown next to the Bluetooth tile in the status menu. Granted via Shizuku/Root when you opt in during setup.",
                status = hasRuntime(android.Manifest.permission.BLUETOOTH_CONNECT),
                onOpen = { openAppDetails() })
            PermissionRow("Start on boot",
                "Auto-starts the service after you restart your phone. Granted automatically.",
                status = PermStatus.AUTO)
        }

        SectionCard(title = "System") {
            PermissionRow("Vibrate",
                "Haptic feedback on taps and button presses. Granted automatically.",
                status = PermStatus.AUTO, isFirst = true)
            PermissionRow("Keep awake",
                "Keeps the connection alive while driving the external display. Granted automatically.",
                status = PermStatus.AUTO)
            PermissionRow("Network state",
                "Reads connectivity state for the Wi-Fi/mobile status indicators in the remote. Granted automatically.",
                status = PermStatus.AUTO)
        }
    }
}

/** Status shown as a badge on a permission row. GRANTED/DENIED are checkable;
 *  PRIVILEGED = granted via Shizuku/Root (a runtime check would falsely say
 *  denied); AUTO = normal permission granted automatically at install. */
private enum class PermStatus { GRANTED, DENIED, PRIVILEGED, AUTO }

/** One permission entry: name + reason + a status badge, with an optional
 *  "Open settings" deep-link. When onOpen is null the row is informational. */
@Composable
private fun PermissionRow(
    name: String,
    reason: String,
    status: PermStatus,
    onOpen: (() -> Unit)? = null,
    isFirst: Boolean = false,
) {
    Column {
        if (!isFirst) {
            HorizontalDivider(
                color = PhoneSurfaceElevated,
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = PhoneOnSurface, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(8.dp))
                    PermBadge(status)
                }
                Text(reason, color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
            }
            if (onOpen != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(PhoneSurfaceElevated)
                        .clickable { onOpen() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("Open", color = PhoneAccent, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Colored status pill for a permission row. */
@Composable
private fun PermBadge(status: PermStatus) {
    val (label, color) = when (status) {
        PermStatus.GRANTED -> "Granted" to androidx.compose.ui.graphics.Color(0xFF4CAF50)
        PermStatus.DENIED -> "Denied" to PhoneAccent
        PermStatus.PRIVILEGED -> "Shizuku/Root" to PhonePrimaryBright
        PermStatus.AUTO -> "Auto" to PhoneOnSurfaceMuted
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Full-screen Resources sub-page (companion apps + community guides). Reached
 * from the note at the top of the System tab. Kept off the tab bar since it's
 * setup-time reference content, not something you return to constantly.
 */
@Composable
private fun ResourcesPage() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        // Header — no back arrow: Resources is a top-strip peer of
        // Settings/Permissions now (was a Settings sub-page with a box).
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Resources",
                color = PhoneOnSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        TabBody {
            SectionCard(title = "Guides", tightSpacing = true, titleDivider = true) {
                val guideUrl = "https://xrealguide.wixsite.com/unofficial"
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(guideUrl),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        .padding(bottom = 6.dp),
                ) {
                    Text(
                        "XREAL Unofficial Guide",
                        color = PhoneOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "https://xrealguide.wixsite.com/unofficial",
                        color = PhonePrimaryBright,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            SectionCard(title = "Companion apps", tightSpacing = true, titleDivider = true) {
                Text(
                    "Shizuku",
                    color = PhoneOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Lets normal apps use system APIs at ADB level — or root level on rooted devices. PortalPad uses it to drive the external display, IME policy, and launching apps.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Recommended build — adds start-on-boot, a watchdog (auto-restarts if it crashes), and TCP mode so Shizuku stays running reliably.",
                    color = PhoneOnSurfaceDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                com.portalpad.app.ui.common.OpenInRow(
                    destinations = listOf(
                        com.portalpad.app.ui.common.gitHubDest { openUrl(ctx, com.portalpad.app.ui.common.SHIZUKU_BUILD_URL) },
                    ),
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    "Extinguish (screen power)",
                    color = PhoneOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Powers the Remote tab's red power button. Tap = screen off; long-press = open Extinguish.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                com.portalpad.app.ui.common.OpenInRow(
                    destinations = listOf(
                        com.portalpad.app.ui.common.playStoreDest { openStore(ctx, "own.moderpach.extinguish") },
                        com.portalpad.app.ui.common.gitHubDest { openUrl(ctx, "https://github.com/Moderpach/Extinguish") },
                    ),
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append("Komi Store")
                        }
                        withStyle(
                            androidx.compose.ui.text.SpanStyle(
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                fontWeight = FontWeight.Normal,
                            ),
                        ) {
                            append(" (formerly GitHub Store)")
                        }
                    },
                    color = PhoneOnSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Komi Store is an open \"app store\" for GitHub, Codeberg, Forgejo, and Gitea releases. Discover repositories that ship real installers and keep them updated \u2014 no scraping, no waiting on stores. Browse Trending, Hot Releases, Most Popular, and topic feeds (Privacy, Media, Productivity, Networking, Dev Tools). Search across GitHub or any forge host you trust. Tap an APK and install it \u2014 the app handles the rest.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                com.portalpad.app.ui.common.OpenInRow(
                    destinations = listOf(
                        com.portalpad.app.ui.common.gitHubDest {
                            openUrl(ctx, "https://github.com/kurikomi-labs/komi-store")
                        },
                        com.portalpad.app.ui.common.fDroidDest {
                            openUrl(ctx, "https://f-droid.org/en/packages/zed.rainxch.githubstore/")
                        },
                    ),
                )
            }

            SectionCard(title = "XR community apps", tightSpacing = true, titleDivider = true) {
                Text(
                    "DroidOS Launcher",
                    color = PhoneOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "DroidOS Launcher is an app launcher and multi-window controller for Android devices, including cover screens and external displays. It is designed to work with Shizuku to provide advanced launch and window management actions without requiring root.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                com.portalpad.app.ui.common.OpenInRow(
                    destinations = listOf(
                        com.portalpad.app.ui.common.gitHubDest {
                            openUrl(ctx, "https://github.com/Katsuyamaki/DroidOS")
                        },
                        com.portalpad.app.ui.common.fDroidDest {
                            openUrl(ctx, "https://f-droid.org/en/packages/com.katsuyamaki.DroidOSFOSSLauncher/")
                        },
                    ),
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    "ScreenLab XR: External Display",
                    color = PhoneOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Visual effects for your external display / XR glasses over USB-C: artificial HDR, HUD, split/portrait views, head-controlled cursor, screen casting, and more.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                ResourceLinkButton(
                    iconRes = com.portalpad.app.R.drawable.ic_playstore,
                    label = "Open in Play Store",
                    onClick = { openStore(ctx, "com.northnroro.nroro_shader") },
                )
            }

            SectionCard(title = "Other apps", tightSpacing = true, titleDivider = true) {
                Text(
                    "Win-X Launcher",
                    color = PhoneOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Brings a Windows 10/11 look and feel to Android phones and tablets: customizable Start panel and taskbar, pinned apps, widgets, file explorer, media player, notification panel, gestures, and daily Bing wallpapers. Highly configurable, no ads.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Ultimate version unlocks the full premium experience with a single upfront purchase.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                com.portalpad.app.ui.common.OpenInRow(
                    destinations = listOf(
                        com.portalpad.app.ui.common.playStoreDest(sublabel = "(Standard)") {
                            openStore(ctx, "com.InternityLabs.Launcher.WinX")
                        },
                        com.portalpad.app.ui.common.playStoreDest(sublabel = "(Ultimate)") {
                            openStore(ctx, "com.InternityLabs.Launcher.WinX.Ultimate")
                        },
                    ),
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    "Projectivy Launcher",
                    color = PhoneOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "A clean, ad-free, highly customizable launcher for Android TV, projectors, and set-top boxes: custom layouts, animated wallpapers, icon packs, parental controls, backups, and direct input-source shortcuts. Some features need a premium upgrade.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                com.portalpad.app.ui.common.OpenInRow(
                    destinations = listOf(
                        com.portalpad.app.ui.common.playStoreDest { openStore(ctx, "com.spocky.projengmenu") },
                        com.portalpad.app.ui.common.gitHubDest { openUrl(ctx, "https://github.com/spocky/miproja1") },
                    ),
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    "Kodi",
                    color = PhoneOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Free, open-source media center for the living room: browse and play your own videos, music, and photos from local storage, your network, or the internet, with a remote-friendly 10-foot interface. Ships with no content — you supply your own.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Recommended for 3D SBS content — navigation, subtitles, and the video itself all display properly in 3D.",
                    color = PhoneOnSurfaceDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                com.portalpad.app.ui.common.OpenInRow(
                    destinations = listOf(
                        com.portalpad.app.ui.common.playStoreDest { openStore(ctx, "org.xbmc.kodi") },
                        com.portalpad.app.ui.common.OpenInDest(
                            iconRes = com.portalpad.app.R.drawable.ic_kodi,
                            label = "Kodi.tv",
                            onClick = { openUrl(ctx, "https://kodi.tv/download/android/") },
                        ),
                    ),
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    "Key Mapper & Floating Buttons",
                    color = PhoneOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Remap phone buttons, keyboards, gamepads, mice, and custom floating controls. Build powerful macros with taps, gestures, media controls, app launches, repeating actions, and per-app conditions.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                com.portalpad.app.ui.common.OpenInRow(
                    destinations = listOf(
                        com.portalpad.app.ui.common.playStoreDest {
                            openStore(ctx, "io.github.sds100.keymapper")
                        },
                        com.portalpad.app.ui.common.gitHubDest {
                            openUrl(ctx, "https://github.com/keymapperorg/KeyMapper")
                        },
                    ),
                )
            }
        }
    }
}

/**
 * Diagnostics sub-page (opened by "Debug" on the mode strip). Hosts the
 * diagnostic toggles that used to live in the Controls tab, plus a button to
 * open the logcat viewer (which used to be the top-right "Logs" button). One
 * page, one entry point.
 */
@Composable
private fun DiagnosticsPage(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onBack() }
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Back",
                    tint = PhoneOnSurface,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "Diagnostics",
                color = PhoneOnSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        TabBody {
            SectionCard(title = "Logs") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            ctx.startActivity(
                                android.content.Intent(ctx, com.portalpad.app.diag.LogcatActivity::class.java),
                            )
                        }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = PhoneOnSurface,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "View logcat",
                        color = PhoneOnSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = PhoneOnSurfaceMuted,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrandedTopBar(onSettingsPage: Boolean, onOpenSettings: () -> Unit) {
    val app = PortalPadApp.instance
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val accessMode by app.prefs.accessMode.collectAsState(initial = AccessMode.NONE)
    val shizukuStatus by app.shizuku.status.collectAsState()
    val shizukuReady = shizukuStatus == ShizukuManager.Status.READY
    val rootReady = app.root.isReady
    // Settings needs attention when no full-feature source is set — mirror the
    // strip's warning condition so the gear nudges first-run users into setup.
    val needsSetup = when (accessMode) {
        AccessMode.SHIZUKU -> !shizukuReady
        AccessMode.ROOT -> !rootReady
        AccessMode.NONE -> true
    }
    // Violet when EITHER we're on the Settings page (active highlight) OR setup
    // is needed (attention). Uses PhonePrimary to match the nav tabs' and Debug
    // affordance's active color. The dot is shown ONLY for setup-needed.
    val gearTint = if (onSettingsPage) PhonePrimary else PhoneOnSurfaceMuted
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // App icon foreground glyph — small touch of brand presence
                // beside the title. Foreground only (no rounded background
                // mask) for visual lightness; the icon's own portal/pad
                // composition reads cleanly at 24dp on the dark app bar.
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        com.portalpad.app.R.drawable.ic_launcher_foreground,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "PortalPad",
                    color = PhoneOnSurface, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onLongPress = {
                            // Long-press the title → PortalPad's App info page (handy
                            // for the restricted-settings flow + permissions/force-stop).
                            // Always opens it, so always give the long-press haptic.
                            app.injector.buzz(longPress = true)
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.fromParts("package", ctx.packageName, null),
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        })
                    },
                )
                Spacer(Modifier.width(8.dp))
                // Version number — makes it easy to confirm which build is
                // actually installed without digging into system settings.
                // Amber when the last update check says this binary is NOT the
                // published latest (ahead of GitHub, or same tag but different
                // bytes); the update page explains why.
                Text(
                    "v${com.portalpad.app.BuildConfig.VERSION_NAME}",
                    color = if (com.portalpad.app.service.UpdateChecker.versionTintAmber.value) {
                        com.portalpad.app.ui.theme.PhoneWarning
                    } else {
                        PhoneOnSurfaceMuted
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(6.dp))
                // Check for updates — sits right after the version so "your build"
                // and "is there a newer one?" read together. Opens the in-app
                // update page (v1.3+); the page itself links out to GitHub.
                // Spins while a live update check is running (checking state).
                val updChecking = com.portalpad.app.service.UpdateChecker.checking.value
                val syncSpin = rememberInfiniteTransition(label = "sync")
                val syncAngle by syncSpin.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(
                            durationMillis = 900,
                            easing = androidx.compose.animation.core.LinearEasing,
                        ),
                    ),
                    label = "syncAngle",
                )
                Icon(
                    Icons.Default.Sync,
                    contentDescription = "Check for updates",
                    tint = PhoneOnSurfaceMuted,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (updChecking) syncAngle else 0f)
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            ctx.startActivity(
                                android.content.Intent(
                                    ctx,
                                    com.portalpad.app.ui.settings.UpdateActivity::class.java,
                                ),
                            )
                        },
                )
                // "« New" (purple) — a numerically newer, non-skipped release is out.
                if (com.portalpad.app.service.UpdateChecker.badgeVisible.value) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "\u00ab New",
                        color = PhoneAccent,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // "« Released" (amber) — same version tag, but the official build
                // isn't the one installed (different bytes / published after this
                // build). Mutually exclusive with "« New"; a genuine ahead/dev build
                // tints the version amber but shows no word here.
                if (com.portalpad.app.service.UpdateChecker.releasedWordVisible.value &&
                    !com.portalpad.app.service.UpdateChecker.badgeVisible.value
                ) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "\u00ab Released",
                        color = com.portalpad.app.ui.theme.PhoneWarning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        actions = {
            // Always-available Settings (formerly the System tab). Gear-first +
            // label + chevron, matching the Debug/Logs affordance style. The
            // gear tints to the accent color (with a dot) when setup is needed.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Open settings",
                        tint = gearTint,
                        modifier = Modifier.size(22.dp),
                    )
                    if (needsSetup) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFFE6B800)),
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = PhoneBackground),
    )
}

/**
 * Thin edge-to-edge strip under the title bar: the active privilege source on
 * the far left ("Mode: Shizuku" / Root / None) and a tappable
 * "Debug" affordance on the far right that opens the Diagnostics page.
 *
 * When the active source isn't giving full features (None, or a selected
 * source that isn't ready), a yellow warning glyph appears next to the mode and
 * taps through to the System tab so the user can pick Shizuku/Root.
 */
@Composable
private fun ModeDebugStrip(
    onDebugPage: Boolean,
    onOpenDebug: () -> Unit,
    onFixPrivilege: () -> Unit,
) {
    val app = PortalPadApp.instance
    val accessMode by app.prefs.accessMode.collectAsState(initial = AccessMode.NONE)
    val shizukuStatus by app.shizuku.status.collectAsState()
    val shizukuReady = shizukuStatus == ShizukuManager.Status.READY
    val rootReady = app.root.isReady
    val serviceRunning by app.serviceRunning.collectAsState()

    // (Just the value here; the "Mode:" prefix is rendered bold separately.)
    val modeValue = when (accessMode) {
        AccessMode.SHIZUKU -> "Shizuku"
        AccessMode.ROOT -> "Root"
        AccessMode.NONE -> "None"
    }
    val fullFeatures = when (accessMode) {
        AccessMode.SHIZUKU -> shizukuReady
        AccessMode.ROOT -> rootReady
        AccessMode.NONE -> false
    }
    val modeColor = if (fullFeatures) PhoneOnSurface else Color(0xFFE6B800)
    // Discoverability hint shown only in the (tappable) warning state. None routes to
    // Start > Setup; Shizuku/Root run their in-place fix (popup / root grant).
    // SHIZUKU CHOSEN BUT NOT INSTALLED is called out separately: "Tap to fix"
    // opened the Shizuku control dialog - auth key, Start/Stop, Open Shizuku -
    // every control of which needs an app that is not there. Label and action
    // both become "install" instead of promising a fix we cannot perform.
    val modeStripCtx = LocalContext.current
    val shizukuMissing = accessMode == AccessMode.SHIZUKU &&
        !remember(accessMode, shizukuStatus) {
            com.portalpad.app.ui.common.isShizukuInstalled(modeStripCtx)
        }
    val warnSuffix = when {
        accessMode == AccessMode.NONE -> "· Tap to set up"
        shizukuMissing -> "· Install Shizuku"
        else -> "· Tap to fix"
    }

    // Long-press the "Mode: Shizuku" text to open the Shizuku start/stop control,
    // matching the trackpad/remote interface. Gated to Shizuku mode.
    var showShizukuDialog by remember { mutableStateOf(false) }
    if (showShizukuDialog) {
        com.portalpad.app.ui.common.ShizukuControlDialog(
            onDismiss = { showShizukuDialog = false },
        )
    }

    val warnScope = rememberCoroutineScope()
    // Warning action by mode (mirrors ModeStatusLabel on the trackpad/remote):
    //   Shizuku → in-app Shizuku control popup.
    //   Root    → request root (`su` off-thread to trigger the grant prompt, then bind).
    //   None    → jump to the System tab privilege picker (onFixPrivilege).
    val onModeWarning: () -> Unit = {
        when (accessMode) {
            AccessMode.SHIZUKU ->
                if (shizukuMissing) {
                    com.portalpad.app.ui.common.openShizukuOrInstall(modeStripCtx)
                } else {
                    showShizukuDialog = true
                }
            AccessMode.ROOT -> {
                warnScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    app.root.refresh()
                    if (app.root.isReady) {
                        app.root.grantInjectEvents()
                        app.rootClickBackend.bind()
                    }
                }
            }
            AccessMode.NONE -> onFixPrivilege()
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(PhoneSurface)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Far-left service indicator: red/green dot + "Service" (colour conveys
        // running/stopped — no value text), followed by a divider, then Mode.
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (serviceRunning) PhoneSuccess else PhoneDanger),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "Service",
            color = if (serviceRunning) PhoneOnSurface else PhoneOnSurfaceMuted,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
        )
        androidx.compose.material3.VerticalDivider(
            modifier = Modifier
                .height(16.dp)
                .padding(horizontal = 10.dp),
            thickness = 1.dp,
            color = PhoneOnSurfaceDim.copy(alpha = 0.5f),
        )
        Text(
            text = androidx.compose.ui.text.buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) {
                    append("Mode: ")
                }
                append(modeValue)
            },
            color = modeColor,
            fontWeight = FontWeight.Normal,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.pointerInput(accessMode) {
                detectTapGestures(
                    onTap = {
                        // Healthy state does nothing on tap (no action → no haptic).
                        // Only the warning state has a tap action, so buzz only then.
                        if (!fullFeatures) {
                            app.injector.buzz(longPress = false)
                            onModeWarning()
                        }
                    },
                    onLongPress = {
                        if (accessMode == AccessMode.SHIZUKU) {
                            app.injector.buzz(longPress = true)
                            showShizukuDialog = true
                        }
                    },
                )
            },
        )
        if (!fullFeatures) {
            Spacer(Modifier.width(6.dp))
            // Yellow tappable warning → mode-appropriate fix (Shizuku popup /
            // request root / privilege picker).
            Icon(
                Icons.Default.Warning,
                contentDescription = "Limited features — tap to fix",
                tint = Color(0xFFE6B800),
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { app.injector.buzz(); onModeWarning() },
            )
            Spacer(Modifier.width(6.dp))
            Text(
                warnSuffix,
                color = Color(0xFFE6B800),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { app.injector.buzz(); onModeWarning() },
            )
        } else {
            // Green check when the active mode (Shizuku or Root) is properly
            // connected — the at-a-glance "you're good to go" signal. Themed
            // (PhoneSuccess) to match the strip rather than a system emoji.
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "$modeValue connected",
                tint = PhoneSuccess,
                modifier = Modifier.size(16.dp),
            )
        }
        // (Accessibility companion indicator moved to a standalone button below
        // the Status card on the Start tab.)
        Spacer(Modifier.weight(1f))
        val debugTint = if (onDebugPage) PhonePrimary else PhoneOnSurfaceMuted
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onOpenDebug() }
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                "Debug",
                color = debugTint,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(5.dp))
            Icon(
                Icons.Default.BugReport,
                contentDescription = null,
                tint = debugTint,
                modifier = Modifier.size(16.dp),
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Open diagnostics",
                tint = debugTint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    HorizontalDivider(color = PhoneSurface, thickness = 1.dp)
}

// ─────────────────────────── Tab 1: Start ───────────────────────────

/**
 * Whether the service can be started without the setup page: overlay granted
 * AND a working privilege source (Shizuku / Root / accessibility) AND the
 * notification-access question resolved (answered "no", or "yes" with the
 * notification listener actually enabled). Read synchronously for the gate at
 * the tap site.
 */
private fun startRequirementsMet(context: android.content.Context): Boolean {
    val app = PortalPadApp.instance
    val overlay = android.provider.Settings.canDrawOverlays(context)
    if (!overlay) return false
    val mode = kotlinx.coroutines.runBlocking { app.prefs.accessMode.first() }
    val privilege = when (mode) {
        AccessMode.SHIZUKU -> app.shizuku.isReady
        AccessMode.ROOT -> app.root.isReady
        AccessMode.NONE -> false
    }
    if (!privilege) return false
    val keys = com.portalpad.app.data.PreferencesRepository.Keys
    // Read every setup answer once. Each requirement is "resolved" when the user
    // said "no", OR said "yes" and the underlying permission/state is currently
    // satisfied. Unanswered ("") is NOT resolved. This MUST mirror canContinue in
    // StartSetupPage so that disabling something you opted into (e.g. turning off
    // the accessibility service after enabling it to pass setup) re-routes you to
    // the setup page on the next start instead of silently starting anyway.
    val answers = kotlinx.coroutines.runBlocking {
        mapOf(
            "notif" to app.prefs.string(keys.SETUP_WANTS_NOTIF_ACCESS).first(),
            "voice" to app.prefs.string(keys.SETUP_WANTS_VOICE_SEARCH).first(),
            "battery" to app.prefs.string(keys.SETUP_WANTS_BACKGROUND).first(),
            "phone" to app.prefs.string(keys.SETUP_WANTS_PHONE_STATE).first(),
            "a11y" to app.prefs.string(keys.SETUP_WANTS_ACCESSIBILITY).first(),
        )
    }
    fun granted(perm: String) =
        context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val notifEnabled = runCatching {
        android.provider.Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners",
        )?.contains(context.packageName) == true
    }.getOrDefault(false)
    val batteryExempt = runCatching {
        (context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)
    val a11yEnabled = runCatching {
        android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )?.contains(context.packageName) == true
    }.getOrDefault(false)
    fun resolved(answer: String?, met: Boolean) = when (answer) {
        "no" -> true
        "yes" -> met
        else -> false
    }
    return resolved(answers["notif"], notifEnabled) &&
        resolved(answers["voice"], granted(android.Manifest.permission.RECORD_AUDIO)) &&
        resolved(answers["battery"], batteryExempt) &&
        resolved(
            answers["phone"],
            granted(android.Manifest.permission.READ_PHONE_STATE) &&
                granted(android.Manifest.permission.BLUETOOTH_CONNECT),
        ) &&
        resolved(answers["a11y"], a11yEnabled)
}

/**
 * Bold the leading "Word:" lead-in of a description (SH): a short all-letter
 * prefix ending in a colon ("Note:", "Default:") renders bold. Live value
 * readouts ("Vol: 45%", the remote "Input:" pill) never route through here -
 * only static description texts call this.
 */
private fun leadInBold(text: String): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        val i = text.indexOf(':')
        if (i in 1..24 && text.take(i).all { it.isLetter() || it == ' ' }) {
            withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) {
                append(text.take(i + 1))
            }
            append(text.substring(i + 1))
        } else {
            append(text)
        }
    }

/**
 * Focused setup checklist gating the service start. Native-scrolling page (via
 * TabBody), reached when the user taps Start Service while requirements aren't
 * met. Lists overlay + privilege status, asks whether the user wants media /
 * notification features on the glasses (which need notification-listener
 * access), and enables Continue only when everything required is satisfied.
 */
@Composable
private fun StartSetupPage(
    onBack: () -> Unit,
    onGoToSystemTab: () -> Unit,
    onContinue: () -> Unit,
) {
    val app = PortalPadApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Re-check Android-settings-backed statuses on resume (user may have just
    // granted overlay / toggled the notification listener and returned).
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val accessMode by app.prefs.accessMode.collectAsState(initial = AccessMode.NONE)
    val shizukuStatus by app.shizuku.status.collectAsState()
    val notifAnswer by app.prefs.string(
        com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_NOTIF_ACCESS,
    ).collectAsState(initial = "")
    val voiceAnswer by app.prefs.string(
        com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_VOICE_SEARCH,
    ).collectAsState(initial = "")
    val batteryAnswer by app.prefs.string(
        com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_BACKGROUND,
    ).collectAsState(initial = "")
    val phoneStateAnswer by app.prefs.string(
        com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_PHONE_STATE,
    ).collectAsState(initial = "")
    val accessibilityAnswer by app.prefs.string(
        com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_ACCESSIBILITY,
    ).collectAsState(initial = "")

    val overlayGranted = remember(refreshTick) {
        android.provider.Settings.canDrawOverlays(context)
    }
    val notifEnabled = remember(refreshTick) {
        runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners",
            )?.contains(context.packageName) == true
        }.getOrDefault(false)
    }
    val micGranted = remember(refreshTick) {
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val batteryExempt = remember(refreshTick) {
        runCatching {
            context.getSystemService(android.os.PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)
    }
    val phoneStateGranted = remember(refreshTick) {
        context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val accessibilityEnabledNow = remember(refreshTick) {
        runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )?.contains(context.packageName) == true
        }.getOrDefault(false)
    }
    val privilegeReady = when (accessMode) {
        AccessMode.SHIZUKU -> shizukuStatus == ShizukuManager.Status.READY
        AccessMode.ROOT -> app.root.isReady
        AccessMode.NONE -> false // must pick Shizuku/Root; accessibility is optional, doesn't gate
    }
    // One-tap privileged grant: runs each shell command off the main thread, then
    // polls `readBack` up to 6×400ms (bumping refreshTick each tick so the row's
    // remember(refreshTick) state re-reads and the UI flips to met), and falls back
    // to the system screen if the OS never honors it. Mirrors the proven
    // accessibility "Tap to enable" path. Only call when privilegeReady.
    val grantViaShell: (List<String>, () -> Boolean, () -> Unit) -> Unit =
        { commands, readBack, fallback ->
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    commands.forEach { c -> runCatching { app.access.execShell(c) } }
                }
                var ok = false
                for (i in 0 until 6) {
                    kotlinx.coroutines.delay(400)
                    refreshTick++
                    ok = runCatching { readBack() }.getOrDefault(false)
                    if (ok) break
                }
                if (!ok) fallback()
            }
        }
    // "Allow screen overlays on Settings" (Settings.Secure secure_overlay_settings).
    // Readable only via the elevated shell, so resolved async: null = checking,
    // true/false = state. Re-read whenever privilege or the refresh tick changes.
    var secureOverlayOk by remember(refreshTick) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(refreshTick, privilegeReady) {
        secureOverlayOk = if (privilegeReady) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    app.access.execShell("settings get secure secure_overlay_settings")
                        .trim().startsWith("1")
                }.getOrDefault(false)
            }
        } else {
            false
        }
    }
    val notifResolved = when (notifAnswer) {
        "no" -> true
        "yes" -> notifEnabled
        else -> false
    }
    val voiceResolved = when (voiceAnswer) {
        "no" -> true
        "yes" -> micGranted
        else -> false
    }
    val batteryResolved = when (batteryAnswer) {
        "no" -> true
        "yes" -> batteryExempt
        else -> false
    }
    val phoneStateResolved = when (phoneStateAnswer) {
        "no" -> true
        "yes" -> phoneStateGranted
        else -> false
    }
    val accessibilityResolved = when (accessibilityAnswer) {
        "no" -> true
        "yes" -> accessibilityEnabledNow
        else -> false
    }
    // External mouse — a deliberate Yes/No the user must answer in setup. It grabs
    // their physical mouse exclusively, so it shouldn't switch on silently. Unset
    // ("") until they choose, so it blocks completion like the permission items; the
    // answer drives EXT_MOUSE_ENABLED (the same pref the Controls toggle uses).
    val mouseAnswer by app.prefs
        .string(com.portalpad.app.data.PreferencesRepository.Keys.SETUP_MOUSE_ANSWER, "")
        .collectAsState(initial = "")
    val mouseResolved = mouseAnswer == "yes" || mouseAnswer == "no"
    val canContinue = overlayGranted && privilegeReady && secureOverlayOk == true &&
        notifResolved && voiceResolved && batteryResolved && phoneStateResolved &&
        accessibilityResolved

    // Settings jumps that LEAVE PortalPad (overlay, notification, Shizuku) get a
    // long toast reminding the user to return and finish setup. In-place dialogs
    // (battery exemption, the mic runtime prompt) don't call this.
    fun showReturnToast() = runCatching {
        android.widget.Toast.makeText(
            context,
            "Come back to PortalPad's setup to finish the remaining permissions.",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    fun openIntent(action: String) = runCatching {
        showReturnToast()
        // For the overlay-permission action, attach the package URI so most
        // devices open straight to PortalPad's own toggle (falls back to the
        // bare action below if the OEM rejects the URI).
        if (action == android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION) {
            context.startActivity(
                android.content.Intent(action, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else if (action == android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS &&
            android.os.Build.VERSION.SDK_INT >= 30) {
            // Android 11+: jump straight to PortalPad's listener toggle.
            val cn = android.content.ComponentName(
                context, com.portalpad.app.service.PortalPadNotificationListenerService::class.java,
            )
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else if (action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
            // App-details needs the package URI to land on PortalPad's own page
            // (where the Permissions → Microphone toggle lives). Without the URI
            // most devices open the generic app list instead.
            context.startActivity(
                android.content.Intent(action, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            context.startActivity(
                android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }.recoverCatching {
        context.startActivity(
            android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    // Microphone runtime-permission request. Tapping "Enable microphone" shows
    // the real system dialog (one tap). Android only shows that dialog while the
    // permission hasn't been permanently denied; once it has, the launcher
    // returns immediately with no UI, so we fall back to PortalPad's app-details
    // page (where the user can flip the toggle manually).
    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshTick++ // re-evaluate micGranted on return
        if (!granted) {
            val activity = context as? android.app.Activity
            val canAskAgain = activity?.shouldShowRequestPermissionRationale(
                android.Manifest.permission.RECORD_AUDIO,
            ) ?: false
            // Denied AND the system won't show the dialog again → open settings.
            if (!canAskAgain) {
                openIntent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            }
        }
    }
    fun requestMic() {
        if (micGranted) return
        runCatching { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
            .onFailure { openIntent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }
    }

    Column(Modifier.fillMaxSize()) {
        // Thin accent line at the very top — green to rhyme with the Continue /
        // Start Service button below, signalling this page is the lit path to
        // starting the service.
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Color(0xFF49C77E)),
        )
        // Back header (matches other subpages).
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onBack() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Back",
                tint = PhoneOnSurface,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Setup", color = PhoneOnSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Required before PortalPad can start",
                    color = PhonePrimaryBright,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Setup progress — fills as each of the gating items is satisfied. An item
        // counts as done when granted, OR (for optional items) opted out with "No".
        // Selecting "Yes" without enabling does NOT advance it: the underlying
        // *Resolved flags stay false until the permission is actually granted.
        run {
            val doneCount = listOf(
                overlayGranted,
                privilegeReady,
                secureOverlayOk == true,
                accessibilityResolved,
                notifResolved,
                voiceResolved,
                batteryResolved,
                phoneStateResolved,
                mouseResolved,
            ).count { it }
            val total = 9
            val target = doneCount / total.toFloat()
            val animated by androidx.compose.animation.core.animateFloatAsState(
                targetValue = target,
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 450),
                label = "setupProgress",
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(PhoneSuccess.copy(alpha = 0.14f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(animated.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(PhoneSuccess),
                    )
                }
                Spacer(Modifier.height(5.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "$doneCount of $total complete",
                        color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${(target * 100).toInt()}%",
                        color = PhoneSuccess, style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        val bodyScroll = rememberScrollState()
        val thumbColor = PhoneOnSurfaceMuted.copy(alpha = 0.5f)
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(bodyScroll)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                "A couple of things need to be ready before PortalPad can drive your external display.",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Continue turns green once the required items are granted and every optional question below has a Yes or No — granting the optional ones is up to you.",
                color = PhoneOnSurfaceDim, style = MaterialTheme.typography.bodySmall,
            )

            SectionCard(title = "Required") {
                // Privilege source — chosen right here via the same dropdown used
                // elsewhere, so the user never leaves this page. Below it, the
                // status + fix action for the chosen mode's ACTUAL current state.
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "Privilege source",
                        color = PhoneOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Required to create the external display and send taps, clicks, and keys to it. Choose Shizuku or Root.",
                        color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                AccessModeSelector(
                    accessMode = accessMode,
                    shizukuReady = shizukuStatus == ShizukuManager.Status.READY,
                    rootReady = app.root.isReady,
                    onSelect = { mode ->
                        scope.launch { app.prefs.setAccessMode(mode) }
                        if (mode == AccessMode.SHIZUKU) {
                            app.shizuku.refresh()
                            if (app.shizuku.isReady) app.shizukuClickBackend.bind()
                        }
                    },
                )
                // One-tap hint: with a source selected, the privileged permissions
                // below grant via shell in a single tap (matches the amber Tip style
                // used elsewhere — bold prefix, no box).
                Spacer(Modifier.height(4.dp))
                Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                        append("Tip: ")
                        pop()
                        append("With a privilege source selected, most permissions below can be granted in one tap — no need to dig through Android settings.")
                    },
                    color = PhoneWarning, style = MaterialTheme.typography.bodySmall,
                )
                // Status + fix for the chosen mode, reflecting its real state.
                if (accessMode != AccessMode.NONE) {
                    Spacer(Modifier.height(3.dp))
                    // Title / detail / action / handler computed from the live state.
                    data class PrivState(val title: String, val detail: String, val action: String, val onAct: () -> Unit)
                    val openShizuku: () -> Unit = {
                        runCatching {
                            context.packageManager
                                .getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let {
                                    showReturnToast()
                                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(it)
                                }
                        }
                    }
                    val ps = when (accessMode) {
                        AccessMode.SHIZUKU -> when (shizukuStatus) {
                            ShizukuManager.Status.READY -> PrivState(
                                "Shizuku connected", "Authorized and ready to drive the external display.", "", {})
                            ShizukuManager.Status.NEEDS_PERMISSION -> PrivState(
                                "Shizuku needs authorization",
                                "Shizuku is running but hasn't been authorized for PortalPad yet.",
                                "Authorize Shizuku", { app.shizuku.requestPermission() })
                            ShizukuManager.Status.NOT_RUNNING -> PrivState(
                                "Shizuku not running",
                                "Shizuku is installed but its service isn't started. Open Shizuku and start it (via wireless debugging or ADB).",
                                "Open Shizuku", openShizuku)
                            ShizukuManager.Status.NOT_INSTALLED -> PrivState(
                                "Shizuku not installed",
                                "Shizuku grants the privileges PortalPad needs. The GitHub build is recommended (start-on-boot + watchdog). Install it, then start its service.",
                                "", {})
                            else -> PrivState(
                                "Shizuku status unknown",
                                "Couldn't read Shizuku's state. Open Shizuku, then return.",
                                "Open Shizuku", openShizuku)
                        }
                        AccessMode.ROOT -> if (app.root.isReady) PrivState(
                            "Root active", "Superuser granted and ready to drive the external display.", "", {})
                        else PrivState(
                            "Root not granted",
                            "PortalPad couldn't get superuser access. Grant it in your root manager (e.g. Magisk → Superuser), then re-check.",
                            "Re-check root", { app.root.refresh() })
                        else -> PrivState("", "", "", {})
                    }
                    SetupRequirementRow(
                        met = privilegeReady,
                        title = ps.title,
                        detail = ps.detail,
                        actionLabel = ps.action,
                        onAction = ps.onAct,
                        extra = if (accessMode == AccessMode.SHIZUKU &&
                            shizukuStatus == ShizukuManager.Status.NOT_INSTALLED) {
                            {
                                com.portalpad.app.ui.common.OpenInRow(
                                    destinations = listOf(
                                        com.portalpad.app.ui.common.gitHubDest { openUrl(context, "https://github.com/thedjchi/Shizuku") },
                                    ),
                                )
                            }
                        } else null,
                    )
                }
                Spacer(Modifier.height(6.dp))
                SetupRequirementRow(
                    met = overlayGranted,
                    title = "Display overlay",
                    detail = "Lets PortalPad draw the cursor, dock, and controls on top of apps on your external display.",
                    actionLabel = "Grant overlay permission",
                    onAction = {
                        if (privilegeReady) {
                            grantViaShell(
                                listOf("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"),
                                { android.provider.Settings.canDrawOverlays(context) },
                                { openIntent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION) },
                            )
                        } else {
                            openIntent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                val secOn = secureOverlayOk == true
                SetupRequirementRow(
                    met = secOn,
                    title = "Allow screen overlays on Settings",
                    note = "This is a different permission from the Display overlay above. " +
                        "That one lets PortalPad's cursor, dock, and controls appear over " +
                        "normal apps; this one re-allows them over Android's own Settings app, " +
                        "which Android blocks by default for security.",
                    detail = "Without it, opening Settings on the external display would leave " +
                        "it with no dock or cursor to drive it. Granting it keeps the dock, " +
                        "cursor, and top bar usable over Settings screens.",
                    actionLabel = if (secOn) "Tap to disable" else "Tap to enable",
                    actionEnabled = privilegeReady,
                    actionWhenMet = true,
                    onAction = {
                        if (privilegeReady) {
                            val target = if (secOn) "0" else "1"
                            scope.launch {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching {
                                        app.access.execShell("settings put secure secure_overlay_settings $target")
                                    }
                                }
                                refreshTick++
                            }
                        }
                    },
                    extra = if (!privilegeReady) {
                        {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    "Requires Root or Shizuku to quickly tap " +
                                        (if (secOn) "disable" else "enable") +
                                        ". Otherwise, manually " +
                                        (if (secOn) "disable" else "enable") +
                                        " the setting from Android developer settings.",
                                    color = PhoneOnSurfaceMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Open developer settings",
                                    color = PhonePrimaryBright,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            openIntent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                        }
                                        .padding(vertical = 2.dp),
                                )
                            }
                        }
                    } else null,
                )
            }

            SectionCard(title = "Media & notifications") {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SetupAnswerIcon(notifAnswer, notifAnswer == "yes" && notifEnabled)
                    Text(
                        "Will you use media controls or the notification panel on your external display?",
                        color = PhoneOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "The remote's media slider (play/pause, track info) and viewing notifications on the external display need notification access.",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SetupChoiceChip("Yes", selected = notifAnswer == "yes") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_NOTIF_ACCESS, "yes",
                            )
                        }
                    }
                    SetupChoiceChip("No", selected = notifAnswer == "no") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_NOTIF_ACCESS, "no",
                            )
                        }
                    }
                }
                if (notifAnswer == "yes") {
                    Spacer(Modifier.height(5.dp))
                    SetupRequirementRow(
                        met = notifEnabled,
                        title = "Notification access",
                        detail = "Powers media controls and the notification panel on the external display.",
                        actionLabel = "Enable notification access",
                        onAction = {
                            if (privilegeReady) {
                                val comp = "${context.packageName}/com.portalpad.app.service.PortalPadNotificationListenerService"
                                grantViaShell(
                                    // allow_listener is additive — it won't clobber other
                                    // enabled listeners the way a raw settings-put would.
                                    listOf("cmd notification allow_listener $comp"),
                                    {
                                        runCatching {
                                            android.provider.Settings.Secure.getString(
                                                context.contentResolver, "enabled_notification_listeners",
                                            )?.contains(context.packageName) == true
                                        }.getOrDefault(false)
                                    },
                                    { openIntent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
                                )
                            } else {
                                openIntent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            }
                        },
                    )
                }
            }

            SectionCard(title = "Voice search") {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SetupAnswerIcon(voiceAnswer, voiceAnswer == "yes" && micGranted)
                    Text(
                        "Will you use voice search?",
                        color = PhoneOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "The app drawer can search by voice. Enabling mic access now means it just works later — no interruption when you first reach for it. You can also turn this on anytime.",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SetupChoiceChip("Yes", selected = voiceAnswer == "yes") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_VOICE_SEARCH, "yes",
                            )
                        }
                    }
                    SetupChoiceChip("Not now", selected = voiceAnswer == "no") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_VOICE_SEARCH, "no",
                            )
                        }
                    }
                }
                if (voiceAnswer == "yes") {
                    Spacer(Modifier.height(5.dp))
                    SetupRequirementRow(
                        met = micGranted,
                        title = "Microphone access",
                        detail = "Lets you search the app drawer by voice. Tap to allow microphone access.",
                        actionLabel = "Enable microphone",
                        onAction = {
                            if (privilegeReady) {
                                grantViaShell(
                                    listOf("pm grant ${context.packageName} android.permission.RECORD_AUDIO"),
                                    {
                                        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                                            android.content.pm.PackageManager.PERMISSION_GRANTED
                                    },
                                    { requestMic() },
                                )
                            } else {
                                requestMic()
                            }
                        },
                    )
                }
            }

            SectionCard(title = "Connectivity details") {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SetupAnswerIcon(phoneStateAnswer, phoneStateAnswer == "yes" && phoneStateGranted)
                    Text(
                        "Show mobile network type and Bluetooth device count in the status menu?",
                        color = PhoneOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Optional. Unlocks two extras: the mobile network-type label (5G/LTE/4G) on " +
                        "the cellular icon, and the count of connected Bluetooth devices in the menu. " +
                        "Signal bars work without this. Granted through your access source " +
                        "(Shizuku/Root) — no system prompt, and nothing leaves your device.",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SetupChoiceChip("Yes", selected = phoneStateAnswer == "yes") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_PHONE_STATE, "yes",
                            )
                        }
                    }
                    SetupChoiceChip("No", selected = phoneStateAnswer == "no") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_PHONE_STATE, "no",
                            )
                        }
                    }
                }
                if (phoneStateAnswer == "yes") {
                    Spacer(Modifier.height(5.dp))
                    SetupRequirementRow(
                        met = phoneStateGranted,
                        title = "Connectivity details access",
                        detail = "Grants phone-state (network type) and Bluetooth (device count) access. " +
                            "Requires your access source (Shizuku/Root) to be active.",
                        actionLabel = "Grant access",
                        onAction = {
                            if (!privilegeReady) {
                                runCatching {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Set up your access source (Shizuku or Root) first.",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                            } else {
                                scope.launch {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching {
                                            app.access.execShell(
                                                "pm grant ${context.packageName} android.permission.READ_PHONE_STATE",
                                            )
                                        }
                                        runCatching {
                                            app.access.execShell(
                                                "pm grant ${context.packageName} android.permission.BLUETOOTH_CONNECT",
                                            )
                                        }
                                    }
                                    refreshTick++
                                }
                            }
                        },
                    )
                }
            }

            SectionCard(title = "Auto-open typing page") {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SetupAnswerIcon(accessibilityAnswer, accessibilityAnswer == "yes" && accessibilityEnabledNow)
                    Text(
                        "Open the typing page automatically when you tap a text field on the external display?",
                        color = PhoneOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Required for auto-open. The PortalPad accessibility service " +
                        "detects when you tap or open a text field on the external display, so the " +
                        "\"Type to external display\" page opens by itself — showing the " +
                        "field's current text to edit, no keyboard button needed. Choose " +
                        "No to skip auto-open; the typing page is still available manually " +
                        "via the keyboard button.",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SetupChoiceChip("Yes", selected = accessibilityAnswer == "yes") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_ACCESSIBILITY, "yes",
                            )
                        }
                    }
                    SetupChoiceChip("No", selected = accessibilityAnswer == "no") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_ACCESSIBILITY, "no",
                            )
                        }
                    }
                }
                if (accessibilityAnswer == "yes") {
                    Spacer(Modifier.height(5.dp))
                    // Restricted-settings helper (SH): fires when a "Tap to
                    // enable" attempt FAILS its ~2.4s poll (Samsung's
                    // restricted-settings gate silently swallowed the shell
                    // write) - not on any wall-clock timer, so the "Android
                    // blocked the automatic enable" copy is always literally
                    // true when it appears. Resets once the service enables.
                    var restrictedHelper by remember { mutableStateOf(false) }
                    LaunchedEffect(accessibilityEnabledNow) {
                        if (accessibilityEnabledNow) restrictedHelper = false
                    }
                    SetupRequirementRow(
                        met = accessibilityEnabledNow,
                        title = "Enable PortalPad accessibility",
                        detail = if (accessibilityEnabledNow) "Status: Enabled" else "Status: Not enabled",
                        // Once manual setup is needed, both destinations live
                        // together below the note instead of splitting one
                        // above it and one below it.
                        actionLabel = if (privilegeReady && !restrictedHelper) "Tap to enable" else "",
                        onAction = {
                            if (privilegeReady && !restrictedHelper) {
                                // Root/Shizuku present: enable the service directly via a
                                // read-modify-write of the secure list (append our component,
                                // never overwrite other services) + accessibility_enabled.
                                scope.launch {
                                    val comp =
                                        "${com.portalpad.app.BuildConfig.APPLICATION_ID}/com.portalpad.app.service.PortalPadAccessibilityService"
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        val cur = runCatching {
                                            app.access.execShell(
                                                "settings get secure enabled_accessibility_services",
                                            ).trim()
                                        }.getOrDefault("")
                                        val list = when {
                                            cur.isEmpty() || cur == "null" -> comp
                                            cur.split(":").contains(comp) -> cur
                                            else -> "$cur:$comp"
                                        }
                                        runCatching {
                                            app.access.execShell(
                                                "settings put secure enabled_accessibility_services $list",
                                            )
                                        }
                                        runCatching {
                                            app.access.execShell("settings put secure accessibility_enabled 1")
                                        }
                                    }
                                    // Poll for the OS to honor it (Samsung's restricted-
                                    // settings gate can silently reject a shell write);
                                    // refresh the row each tick, and reveal the restricted-
                                    // settings helper if it never takes.
                                    var enabled = false
                                    for (i in 0 until 6) {
                                        kotlinx.coroutines.delay(400)
                                        refreshTick++
                                        enabled = runCatching {
                                            android.provider.Settings.Secure.getString(
                                                context.contentResolver,
                                                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                            )?.contains(context.packageName) == true
                                        }.getOrDefault(false)
                                        if (enabled) break
                                    }
                                    if (!enabled) {
                                        // No auto-bounce to the Accessibility screen (SH):
                                        // show the steps in place; the user taps "Open
                                        // Accessibility" themselves once they've read them.
                                        restrictedHelper = true
                                    }
                                }
                            } else {
                                openIntent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            }
                        },
                        extra = if (!accessibilityEnabledNow && (!privilegeReady || restrictedHelper)) {
                            {
                                Text(
                                    text = androidx.compose.ui.text.buildAnnotatedString {
                                        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("Note: ") }
                                        append(
                                            if (restrictedHelper) {
                                                "Android blocked the automatic enable (restricted settings). " +
                                                    "Open Accessibility and tap PortalPad. If the switch is greyed " +
                                                    "out, tap it once, then open PortalPad's App info page, choose " +
                                                    "\"Allow restricted settings\" from the 3-dot menu, and come " +
                                                    "back to enable PortalPad."
                                            } else {
                                                "On some devices the switch is greyed out. Tap it once — " +
                                                    "Android then surfaces a 3-dot menu on PortalPad's App info page. " +
                                                    "Open that menu, choose \"Allow restricted settings,\" then return " +
                                                    "and enable PortalPad."
                                            },
                                        )
                                    },
                                    color = PhoneDanger.copy(alpha = 0.9f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                // Two related manual-setup destinations, centred
                                // as one action group. A real hairline divider
                                // separates them more cleanly than a "|" glyph;
                                // 12dp either side keeps it visible without
                                // making the links feel unrelated.
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Equal halves put the divider on the exact
                                    // centreline of the red note above. The old
                                    // content-sized Row centred the combined
                                    // labels, but the longer right label made
                                    // the pair look shifted to that side.
                                    Box(
                                        Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Text(
                                            "Open Accessibility",
                                            color = PhonePrimaryBright,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    openIntent(
                                                        android.provider.Settings
                                                            .ACTION_ACCESSIBILITY_SETTINGS,
                                                    )
                                                }
                                                .padding(horizontal = 2.dp, vertical = 4.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Box(
                                        Modifier
                                            .width(1.dp)
                                            .height(16.dp)
                                            .background(PhoneOnSurfaceDim.copy(alpha = 0.35f)),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Box(
                                        Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        Text(
                                            "Open PortalPad app info",
                                            color = PhonePrimaryBright,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    openIntent(
                                                        android.provider.Settings
                                                            .ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    )
                                                }
                                                .padding(horizontal = 2.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        } else null,
                    )
                }
            }

            SectionCard(title = "External mouse") {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SetupAnswerIcon(mouseAnswer, mouseAnswer == "yes")
                    Text(
                        "Do you use a physical mouse with your external display?",
                        color = PhoneOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Lets a Bluetooth or USB mouse drive the cursor on your external display, " +
                        "even with the phone screen blanked. You can change this any time under " +
                        "Controls. Pick No if you don't use one.",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SetupChoiceChip("Yes", selected = mouseAnswer == "yes") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_MOUSE_ANSWER, "yes",
                            )
                            app.prefs.setBool(
                                com.portalpad.app.data.PreferencesRepository.Keys.EXT_MOUSE_ENABLED, true,
                            )
                        }
                    }
                    SetupChoiceChip("No", selected = mouseAnswer == "no") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_MOUSE_ANSWER, "no",
                            )
                            app.prefs.setBool(
                                com.portalpad.app.data.PreferencesRepository.Keys.EXT_MOUSE_ENABLED, false,
                            )
                        }
                    }
                }
            }

            SectionCard(title = "Background") {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SetupAnswerIcon(batteryAnswer, batteryAnswer == "yes" && batteryExempt)
                    Text(
                        "Do you want PortalPad to keep running in the background?",
                        color = PhoneOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Recommended. Keeps PortalPad alive in the background so it can detect your " +
                        "glasses connecting and launch automatically, and keeps the remote and " +
                        "input working while your screen is off. Without it, Android may sleep " +
                        "or kill PortalPad during a session.",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SetupChoiceChip("Yes", selected = batteryAnswer == "yes") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_BACKGROUND, "yes",
                            )
                        }
                    }
                    SetupChoiceChip("No", selected = batteryAnswer == "no") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_BACKGROUND, "no",
                            )
                        }
                    }
                }
                if (batteryAnswer == "yes") {
                    Spacer(Modifier.height(5.dp))
                    SetupRequirementRow(
                        met = batteryExempt,
                        title = "Ignore battery optimization",
                        detail = "Lets PortalPad keep running so it can detect your glasses and " +
                            "keep the remote and input working while the screen is off.",
                        actionLabel = "Allow background running",
                        onAction = {
                            val openBatteryScreen = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            android.net.Uri.parse("package:${context.packageName}"),
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                                Unit
                            }
                            if (privilegeReady) {
                                grantViaShell(
                                    listOf(
                                        "cmd deviceidle whitelist +${context.packageName}",
                                        "dumpsys deviceidle whitelist +${context.packageName}",
                                    ),
                                    {
                                        context.getSystemService(android.os.PowerManager::class.java)
                                            ?.isIgnoringBatteryOptimizations(context.packageName) == true
                                    },
                                    openBatteryScreen,
                                )
                            } else {
                                openBatteryScreen()
                            }
                        },
                    )
                }
            }

            // Accessibility section hidden — service de-registered; PortalPad uses
            // Shizuku/Root for everything, including dock-menu long-press (handled
            // in-process now). Kept commented for easy restoration.
            // SectionCard(title = "Accessibility") { … "Accessibility companion" … }
        }
        // Visible scrollbar — only paints a thumb when content overflows
        // (maxValue > 0), so short screens that fit show nothing. Same look as
        // the other settings subpages (TabBody).
        Box(
            Modifier
                .matchParentSize()
                .drawWithContent {
                    drawContent()
                    val max = bodyScroll.maxValue
                    if (max > 0) {
                        // Clear the overlaid PageScrollChevrons (SH) - same
                        // chevZone as every other page scrollbar.
                        val chevZone = 26.dp.toPx()
                        val trackTop = chevZone
                        val trackBottom = size.height - chevZone
                        val trackH = (trackBottom - trackTop).coerceAtLeast(1f)
                        val viewport = size.height
                        val total = viewport + max
                        val thumbH = (trackH * (viewport / total)).coerceIn(28.dp.toPx(), trackH)
                        val frac = bodyScroll.value.toFloat() / max.toFloat()
                        val thumbY = trackTop + (trackH - thumbH) * frac
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
        PageScrollChevrons(bodyScroll)
        }

        // Continue — gated. Greyed/disabled until all requirements are met.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .then(
                        if (canContinue)
                            Modifier.background(Color(0xFF2E9E5B))
                                .border(1.5.dp, Color(0xFF49C77E), RoundedCornerShape(16.dp))
                        else
                            Modifier.background(PhoneSurface)
                                .border(1.5.dp, PhoneOnSurfaceDim.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    )
                    .then(if (canContinue) Modifier.clickable { onContinue() } else Modifier)
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            ) {
                Text(
                    "Continue",
                    color = if (canContinue) PhoneOnSurface else PhoneOnSurfaceMuted,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** Side Mode corner glyph: a screen outline with a shrunk filled quadrant in the
 *  matching corner — i.e. the mini-display parked where Side Mode would dock it. */
@Composable
private fun SideCornerGlyph(corner: com.portalpad.app.data.SideCorner, selected: Boolean) {
    val c = if (selected) PhoneAccent else PhoneOnSurfaceMuted
    androidx.compose.foundation.Canvas(Modifier.size(width = 34.dp, height = 23.dp)) {
        val w = size.width; val h = size.height
        val sw = h * 0.09f
        drawRoundRect(
            color = c.copy(alpha = if (selected) 0.55f else 0.45f),
            topLeft = androidx.compose.ui.geometry.Offset(sw / 2f, sw / 2f),
            size = androidx.compose.ui.geometry.Size(w - sw, h - sw),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.22f, h * 0.22f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw),
        )
        val bw = w * 0.40f; val bh = h * 0.42f
        val pad = w * 0.12f
        val left = when (corner) {
            com.portalpad.app.data.SideCorner.TL, com.portalpad.app.data.SideCorner.BL -> pad
            else -> w - pad - bw
        }
        val top = when (corner) {
            com.portalpad.app.data.SideCorner.TL, com.portalpad.app.data.SideCorner.TR -> pad * 0.7f
            else -> h - pad * 0.7f - bh
        }
        drawRoundRect(
            color = c,
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(bw, bh),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.12f, h * 0.12f),
        )
    }
}

/** One checklist row: status icon + title + explanation + (when unmet) action. */
@Composable
private fun SetupRequirementRow(
    met: Boolean,
    title: String,
    detail: String,
    note: String? = null,
    actionLabel: String,
    onAction: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
    actionEnabled: Boolean = true,
    actionWhenMet: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!met) Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE6B800).copy(alpha = 0.12f))
                    .border(1.dp, Color(0xFFE6B800).copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(9.dp)
                else Modifier
            ),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (met) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = if (met) "$title ready" else "$title needed",
            tint = if (met) PhoneSuccess else Color(0xFFE6B800),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = PhoneOnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            if (note != null) {
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("Note: ") }
                        append(note)
                    },
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(detail, color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
            if ((!met || actionWhenMet) && actionLabel.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    actionLabel,
                    color = if (actionEnabled) PhonePrimaryBright else PhoneOnSurfaceMuted.copy(alpha = 0.5f),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (actionEnabled) Modifier.clickable { onAction() } else Modifier)
                        .padding(vertical = 2.dp),
                )
            }
            if (extra != null) {
                Spacer(Modifier.height(2.dp))
                extra()
            }
        }
    }
}

/**
 * Leading status marker for an optional setup question.
 *  - Unanswered ("") → a soft amber ring: a pending choice that still blocks
 *    Continue, but calmer than the required rows' warning triangle.
 *  - Answered + feature actually on (active) → green check.
 *  - Answered otherwise (declined, or "yes" but not yet granted) → muted check;
 *    when "yes"-but-ungranted, the amber lives on the grant sub-row instead.
 */
@Composable
private fun SetupAnswerIcon(answer: String, active: Boolean) {
    if (answer.isEmpty()) {
        Box(
            Modifier
                .size(18.dp)
                .border(2.dp, Color(0xFFE6B800), androidx.compose.foundation.shape.CircleShape),
        )
    } else {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = if (active) "enabled" else "answered",
            tint = if (active) PhoneSuccess else PhoneOnSurfaceMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Yes/No pill for the setup question. */
@Composable
private fun SetupChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) PhonePrimaryBright.copy(alpha = 0.25f) else PhoneSurfaceElevated)
            .border(
                1.dp,
                if (selected) PhonePrimaryBright else PhoneOnSurfaceDim.copy(alpha = 0.4f),
                RoundedCornerShape(20.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) PhonePrimaryBright else PhoneOnSurface,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StartTab(onEnableClick: () -> Unit, onGoToSystemTab: () -> Unit, onGoToControls: () -> Unit, onGoToDisplay: () -> Unit = {}, onLaunchTrackpad: (() -> Unit)? = null, onOpenButtonActions: () -> Unit = {}, onOpenAutoLaunch: () -> Unit = {}, onPickHome: () -> Unit = {}, onPickBack: () -> Unit = {}, onOpenStartSetup: () -> Unit = {}) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val shizukuStatus by app.shizuku.status.collectAsState()
    val accessMode by prefs.accessMode.collectAsState(initial = AccessMode.NONE)
    val foreground by app.appInForeground.collectAsState()
    val serviceRunning by app.serviceRunning.collectAsState()
    val externalDisplayId by app.externalDisplayId.collectAsState()
    val physicalExternalDisplayId by app.physicalExternalDisplayId.collectAsState()
    val context = LocalContext.current

    // Live external-display detection (independent of the service) so the Display
    // button can show the connected display's name the moment glasses are plugged
    // in, even before the service starts. Null name when none connected.
    var detectedDisplayName by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
            as android.hardware.display.DisplayManager
        fun looksExternal(d: android.view.Display): Boolean {
            if (d.displayId == android.view.Display.DEFAULT_DISPLAY) return false
            if (d.name == "PortalPad Session") return false
            val type = runCatching {
                android.view.Display::class.java.getMethod("getType").invoke(d) as? Int
            }.getOrNull()
            if (type != null) return type == 2 || type == 4 // EXTERNAL or OVERLAY
            val n = d.name.lowercase()
            return !(n.contains("ghost") || n.contains("virtual") || n.contains("projection") ||
                n.contains("componentinfo") || n.contains("scrcpy"))
        }
        fun rescan() {
            val ext = runCatching { dm.displays.firstOrNull { looksExternal(it) } }.getOrNull()
            detectedDisplayName = ext?.name?.takeIf { it.isNotBlank() }
        }
        rescan()
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = rescan()
            override fun onDisplayRemoved(displayId: Int) = rescan()
            override fun onDisplayChanged(displayId: Int) = rescan()
        }
        dm.registerDisplayListener(listener, null)
        onDispose { dm.unregisterDisplayListener(listener) }
    }

    val homeAction by prefs.homeAction.collectAsState(initial = null)
    val backAction by prefs.backAction.collectAsState(initial = com.portalpad.app.data.BackAction.System)
    val autoLaunch by prefs.autoLaunchOnStart.collectAsState(initial = com.portalpad.app.data.AutoLaunch.Wallpaper)

    // Last-used interface, for the Launch button label. Mode-switches persist
    // these; a display connect / fresh start resets them (applyAutoLaunchInterface),
    // so the label defaults back to "Launch trackpad" on reconnect.
    val lastWasRemote by prefs.bool(
        com.portalpad.app.data.PreferencesRepository.Keys.LAST_MODE_WAS_REMOTE,
        default = false,
    ).collectAsState(initial = false)
    val storedInputMode by prefs.inputMode.collectAsState(
        initial = com.portalpad.app.data.InputMode.TRACKPAD,
    )
    val launchLabel = when {
        lastWasRemote -> "Launch remote"
        storedInputMode == com.portalpad.app.data.InputMode.AIR_MOUSE -> "Launch air mouse"
        else -> "Launch trackpad"
    }

    // Saved Shizuku auth key enables one-tap Start from the launch prompt below.
    val savedShizukuKey by prefs.shizukuAuthKey.collectAsState(initial = "")

    // Shown when the user taps Launch Trackpad while relying on Shizuku and its
    // service is down — launching would land them in a frozen trackpad. If an auth
    // key is saved we offer one-tap Start (broadcast); otherwise we redirect them
    // to open Shizuku. Never relevant to root users.
    var showShizukuLaunchPrompt by remember { mutableStateOf(false) }
    var startingShizuku by remember { mutableStateOf(false) }
    // When the user has no saved auth key, the launch prompt routes here instead of
    // duplicating the key-entry UI — setup lives in one place (the shared dialog).
    var showShizukuSetupDialog by remember { mutableStateOf(false) }
    if (showShizukuSetupDialog) {
        com.portalpad.app.ui.common.ShizukuControlDialog(
            onDismiss = { showShizukuSetupDialog = false },
        )
    }

    // Single launch path reused by the button and the prompt's auto-launch.
    val launchTrackpadNow: () -> Unit = {
        val cb = onLaunchTrackpad
        if (cb != null) {
            cb()
        } else {
            context.startActivity(
                android.content.Intent(context, com.portalpad.app.TrackpadActivity::class.java)
                    .addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    )
                    .putExtra(com.portalpad.app.TrackpadActivity.EXTRA_KEEP_CURRENT, true),
            )
        }
    }

    // On "Start Shizuku": send the START broadcast, then poll live status up to ~3s.
    // If it comes up, dismiss and launch automatically; otherwise leave the prompt
    // open for the Open-Shizuku fallback. Depends on the broadcast actually reviving
    // a stopped Shizuku.
    LaunchedEffect(startingShizuku) {
        if (!startingShizuku) return@LaunchedEffect
        com.portalpad.app.ui.common.sendShizukuControl(context, start = true, auth = savedShizukuKey)
        var waited = 0
        // Wait for the backend BINDER (what actually drives the trusted VD wrap),
        // not just app-permission status — the binder connects ~100-200ms later and
        // is the signal the service uses to recreate the display + re-lay wallpaper.
        while (waited < 3000 && app.activeBoundBackend?.isReady != true) {
            kotlinx.coroutines.delay(300)
            waited += 300
        }
        val ready = app.activeBoundBackend?.isReady == true
        startingShizuku = false
        if (ready) {
            showShizukuLaunchPrompt = false
            launchTrackpadNow()
        }
    }

    if (showShizukuLaunchPrompt) {
        val openShizuku: () -> Unit = {
            val pm = context.packageManager
            val launch = pm.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (launch != null) {
                runCatching {
                    context.startActivity(
                        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
        androidx.compose.material3.AlertDialog(
            modifier = Modifier.border(
                1.dp,
                com.portalpad.app.ui.theme.PhonePrimaryDim,
                androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            ),
            onDismissRequest = { if (!startingShizuku) showShizukuLaunchPrompt = false },
            title = { Text(if (startingShizuku) "Starting Shizuku…" else "Shizuku is not running") },
            text = {
                Text(
                    when {
                        startingShizuku ->
                            "Asked Shizuku to start. Waiting for it to come up, then " +
                                "launching the trackpad…"
                        savedShizukuKey.isNotBlank() ->
                            "PortalPad needs Shizuku to control the trackpad. Tap Start " +
                                "Shizuku to bring it up, or open Shizuku to start it manually."
                        else ->
                            "PortalPad needs Shizuku to control the trackpad. Tap Set up " +
                                "Shizuku to save an auth key (for one-tap Start) and start " +
                                "the service, then return and launch."
                    },
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!startingShizuku) {
                        if (savedShizukuKey.isNotBlank()) {
                            androidx.compose.material3.TextButton(onClick = { startingShizuku = true }) {
                                Text("Start Shizuku")
                            }
                            androidx.compose.material3.TextButton(onClick = {
                                showShizukuLaunchPrompt = false
                                openShizuku()
                            }) { Text("Open Shizuku") }
                        } else {
                            // No saved key → one-tap Start can't work yet. Send them to
                            // the setup dialog (auth key + Start/Stop + Open Shizuku),
                            // rather than duplicating the key field here.
                            androidx.compose.material3.TextButton(onClick = {
                                showShizukuLaunchPrompt = false
                                showShizukuSetupDialog = true
                            }) { Text("Set up Shizuku") }
                        }
                    }
                }
            },
            dismissButton = {
                if (!startingShizuku) {
                    androidx.compose.material3.TextButton(onClick = { showShizukuLaunchPrompt = false }) {
                        Text("Cancel")
                    }
                }
            },
        )
    }
    ScaleToFit(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        // DRM / HDCP warning — muted amber border (no background), above the
        // primary actions so it's seen before launching anything. Calm "caution"
        // styling rather than alarming red: it's a known limitation, not an error.
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF8A7A4D), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column {
                Text(
                    "DRM-protected video may not play on the external display.\n" +
                        "In streaming apps you may see a black screen, an HDCP error, or a copyright-protection notice.",
                    color = Color(0xFFB8A66A),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Turning on System mirror (Display settings) may fix this for some DRM-protected streaming apps.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        // Two primary actions side by side: Start/Stop Service (left) and Launch
        // trackpad (right). Equal size, shorter height, each with a two-line label
        // (action on top, hint underneath). The "No click path ready" banner was
        // removed — the top strip already shows access state and the first-run
        // popup covers setup, so it only added clutter/scroll here.
        val canLaunchTrackpad = serviceRunning && externalDisplayId != null
        val greenStart = Color(0xFF2E9E5B)        // calm "go" green (not alarm-bright)
        val greenBorder = Color(0xFF49C77E)       // brighter green so the border reads on green
        val roseStop = Color(0xFFB05566)          // muted rose — "heads up, ends your session"
        val roseBorder = Color(0xFFD07686)        // brighter rose so the border reads on rose
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Start / Stop service.
            Box(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .then(
                        if (serviceRunning)
                            Modifier
                                .background(roseStop)
                                .border(1.5.dp, roseBorder, RoundedCornerShape(18.dp))
                        else
                            Modifier
                                .background(greenStart)
                                .border(1.5.dp, greenBorder, RoundedCornerShape(18.dp))
                    )
                    .pointerInput(serviceRunning) {
                        detectTapGestures(
                            onTap = {
                                app.injector.buzz(longPress = false)
                                if (serviceRunning) {
                                    com.portalpad.app.service.PortalPadForegroundService.stop(context)
                                } else {
                                    onEnableClick()
                                }
                            },
                            onLongPress = {
                                // Long-press → jump straight to Start > Setup (power-user
                                // shortcut), regardless of running state. Firmer buzz.
                                app.injector.buzz(longPress = true)
                                onOpenStartSetup()
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (serviceRunning) "Stop Service" else "Start Service",
                        color = PhoneOnSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (serviceRunning) "(stops the external display)" else "(required before launching)",
                        color = PhoneOnSurface.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            // Launch trackpad — greyed/non-tappable until the service is running
            // (and a display connected).
            Box(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (canLaunchTrackpad) greenStart else PhoneSurface)
                    .then(
                        if (canLaunchTrackpad)
                            Modifier.border(1.5.dp, greenBorder, RoundedCornerShape(18.dp))
                        else
                            Modifier  // greyed/disabled → no border
                    )
                    .clickable(enabled = canLaunchTrackpad) {
                        // If the user is on Shizuku and it's not ready, launching
                        // gives a frozen trackpad — redirect them to open Shizuku
                        // first. Root users are never gated here.
                        if (accessMode == AccessMode.SHIZUKU &&
                            shizukuStatus != ShizukuManager.Status.READY
                        ) {
                            showShizukuLaunchPrompt = true
                        } else {
                            launchTrackpadNow()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        launchLabel,
                        color = if (canLaunchTrackpad) PhoneOnSurface else PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            !serviceRunning -> "(start service first)"
                            externalDisplayId == null -> "(connect a display)"
                            else -> "(opens on the display)"
                        },
                        color = if (canLaunchTrackpad) PhoneOnSurface.copy(alpha = 0.85f) else PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Display settings — moved off the top nav. Full-width, distinct violet
        // (accent) tonal/outlined treatment so it reads as a "go to a section"
        // destination rather than a primary action button.
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(PhoneAccent.copy(alpha = 0.14f))
                .border(1.dp, PhoneAccent.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                .clickable { onGoToDisplay() }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Icon + label centered in the button…
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 28.dp),
            ) {
                Icon(Icons.Default.Devices, contentDescription = null, tint = PhoneAccent)
                Spacer(Modifier.width(12.dp))
                // Resolve a connected display's name: prefer the service's
                // physical/external id, fall back to live-detected. When present,
                // the label reads "Display – <name>".
                val serviceDisplayName = remember(externalDisplayId, physicalExternalDisplayId) {
                    val id = physicalExternalDisplayId ?: externalDisplayId
                    id?.let {
                        val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
                            as android.hardware.display.DisplayManager
                        runCatching { dm.getDisplay(it)?.name?.takeIf { n -> n.isNotBlank() } }.getOrNull()
                    }
                }
                val shownName = serviceDisplayName ?: detectedDisplayName
                Text(
                    if (shownName != null) "Display \u2013 $shownName" else "Display",
                    color = PhoneOnSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            // …chevron pinned to the right edge.
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Open Display settings",
                tint = PhoneAccent,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        // Home / Back buttons — same centered "Buttons" card layout these had
        // when they lived on the Controls page: two columns (Home | Back) with a
        // divider, the action label above and the assigned action name in
        // smaller text below. Both open the button-actions page (returns here).
        // The AppEntry behind each button (null = not an app/activity action).
        val homeEntry: com.portalpad.app.data.AppEntry? = homeAction
        val backEntry: com.portalpad.app.data.AppEntry? =
            (backAction as? com.portalpad.app.data.BackAction.Launch)?.entry
        val homeLabel = homeAction?.label ?: "Wallpaper (default)"
        val backLabel = when (val b = backAction) {
            is com.portalpad.app.data.BackAction.System -> "Back action (default)"
            is com.portalpad.app.data.BackAction.Launch -> b.entry.label
        }
        val buttonActionColumn: @Composable RowScope.(String, String, com.portalpad.app.data.AppEntry?, () -> Unit) -> Unit = { title, value, entry, onClick ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onClick() }
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    title, color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
                )
                // For an app/activity action, show icon + app label (+ activity
                // name) so it's clear which app it is. Otherwise plain value text.
                if (entry != null) {
                    com.portalpad.app.ui.common.AssignedActionLabel(
                        entry = entry, fallback = value, iconSizeDp = 28,
                    )
                } else {
                    Text(
                        value, color = PhoneAccent,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
        SectionCard(title = "", centeredTitleNoDot = true) {
            // Auto Launch on Start — what appears on the glasses when a session
            // starts (independent of the Home button). Yellow value, truncates.
            val autoLaunchEntry = (autoLaunch as? com.portalpad.app.data.AutoLaunch.Launch)?.entry
            val autoLaunchLabel = when (val a = autoLaunch) {
                com.portalpad.app.data.AutoLaunch.Wallpaper -> "Wallpaper (default)"
                com.portalpad.app.data.AutoLaunch.LastApp -> "Last app"
                is com.portalpad.app.data.AutoLaunch.Launch -> a.entry.label
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenAutoLaunch() }
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "Auto Launch on Start", color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
                )
                if (autoLaunchEntry != null) {
                    com.portalpad.app.ui.common.AssignedActionLabel(
                        entry = autoLaunchEntry, fallback = autoLaunchLabel, iconSizeDp = 28,
                    )
                } else {
                    Text(
                        autoLaunchLabel, color = PhoneAccent,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(vertical = 3.dp),
                thickness = 1.dp,
                color = PhoneOnSurfaceDim.copy(alpha = 0.5f),
            )
            ToggleRowWithDesc(
                label = "Auto-resume last app (30 min)",
                desc = "Automatically reopen the last active app in its original window state and bounds if reconnected within 30 minutes.",
                key = PreferencesRepository.Keys.AUTO_RESUME_LAST_APP_30M,
                default = true,
            )
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(vertical = 3.dp),
                thickness = 1.dp,
                color = PhoneOnSurfaceDim.copy(alpha = 0.5f),
            )
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                buttonActionColumn("Home button", homeLabel, homeEntry, onPickHome)
                androidx.compose.material3.VerticalDivider(
                    modifier = Modifier.fillMaxHeight().padding(horizontal = 4.dp),
                    thickness = 1.dp,
                    color = PhoneOnSurfaceDim.copy(alpha = 0.5f),
                )
                buttonActionColumn("Back button", backLabel, backEntry, onPickBack)
            }
        }

        DeviceTakeoverNote()

        // (Status card removed — Service state now shows in the top strip, and
        // Shizuku/Root readiness is shown live by the strip's Mode indicator.)
        // Accessibility companion button hidden — service de-registered; the dock
        // long-press menu now works in-process via Shizuku, so accessibility is no
        // longer needed. (Block removed from UI; restore from git if re-enabling.)
      }
    }
}

/**
 * Divider plus a CENTRED action group, closing out a device section.
 *
 * Every section ends with one so the "here is what to do" controls read as a
 * deliberate zone rather than more prose. Descriptive text above stays LEFT
 * aligned - centred paragraphs are measurably harder to read, since every line
 * starts at a different x. Toggle rows are never placed in here either: they
 * are full-width label-left/switch-right controls and cannot centre.
 */
@Composable
private fun DeviceActionZone(
    showDivider: Boolean = true,
    topGap: androidx.compose.ui.unit.Dp = 10.dp,
    bottomGap: androidx.compose.ui.unit.Dp = 4.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (showDivider) {
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(horizontal = 24.dp),
            thickness = 1.dp,
            // FAINT on purpose (SH): it separates without drawing attention.
            // PhonePrimaryDim read as a hard rule against this background.
            color = PhoneOnSurfaceDim.copy(alpha = 0.2f),
        )
    }
    // EQUAL GAPS BY CONSTRUCTION, from one shared target:
    //     above = topGap + (padding carried by the element ABOVE the zone)
    //     below = bottomGap + 10dp (the enclosing Box's bottom padding)
    // Target is ACTION_ZONE_GAP; each caller passes topGap = target minus
    // whatever padding its preceding element already contributes, and
    // bottomGap = target minus the Box's 10dp.
    //
    // WHY bottomGap EXISTS AT ALL: without it only the TOP was adjustable and
    // the bottom was fixed by the Box, so the two could never be made equal -
    // raising the top merely swapped which side was larger. Two rounds of
    // tuning failed to converge before this was added.
    Spacer(Modifier.height(topGap))
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
    Spacer(Modifier.height(bottomGap))
}

/**
 * Target optical gap above and below every device action zone. One knob: if
 * the zones look lopsided, change THIS, not the individual call sites.
 */
private val ACTION_ZONE_GAP = 14.dp

/**
 * Manual "reconnect the display" escape hatch, shown only where the device
 * has a safe display-cycle lever (see DisplayReconnect.reconnectMethod). A secondary text
 * link rather than a button: it blanks the display for a few seconds and kills
 * any running session, so it must not look routine.
 *
 * The caption differs by lever because the OUTCOME differs. On Pixel it exists
 * to re-raise the Desktop/Mirror sheet; on Samsung there is no such sheet and
 * it is simply a replug without touching the cable. Saying "asks again how to
 * use the display" on an S24 would be a lie.
 */
@Composable
private fun ReconnectDisplayAction(
    enabled: Boolean,
    method: com.portalpad.app.service.DisplayReconnect.ReconnectMethod,
    onTap: () -> Unit,
) {
    if (method == com.portalpad.app.service.DisplayReconnect.ReconnectMethod.NONE) return
    Text(
        // "Tap to ..." ONLY while it can be tapped - the app's convention for
        // tappable text that is not a bordered button. Dropping it when dim
        // keeps the invitation from promising something it cannot do.
        if (enabled) "Tap to reconnect display" else "Reconnect display",
        color = if (enabled) PhonePrimaryBright else PhoneOnSurfaceDim,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clickable(enabled = enabled) { onTap() }
            .padding(vertical = 4.dp),
    )
    Text(
        // A dim control must always say WHY. Keeping a caption in BOTH states
        // also stops the layout jumping on plug/unplug.
        if (!enabled) "Connect an external display first."
        else "Briefly disconnects and reconnects the display.",
        color = PhoneOnSurfaceMuted,
        style = MaterialTheme.typography.labelSmall,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceTakeoverNote() {
    val app = PortalPadApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accessMode by app.prefs.accessMode.collectAsState(initial = AccessMode.NONE)
    val shizukuStatus by app.shizuku.status.collectAsState()
    // Guidance-card hierarchy: the warning keeps its muted red, available
    // instructions use a softer lavender-white than the app's primary text,
    // body copy uses PhoneOnSurfaceMuted, and disabled words remain readable at
    // the full tertiary token. Extra alpha belongs on disabled outlines, not
    // on text the user still needs to understand.
    val guidanceEnabled = PhoneOnSurface.copy(alpha = 0.82f)
    val guidanceDisabled = PhoneOnSurfaceDim

    val isSamsung = android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    val hasXrealGlasses = remember {
        XrealBeamProSettings.isAvailable(context)
    }
    val hasMotoConnect = remember {
        context.packageManager.getLaunchIntentForPackage("com.motorola.mobiledesktop") != null
    }
    // Stock Android (Pixel) raises its own "Connect to external display" sheet
    // on connect, and choosing Mirror leaves PortalPad without the separate
    // display it needs - in EVERY mode, theater included.
    // This flag gates GUIDANCE TEXT only. The auto-answer behaviour itself is
    // capability-detected (it matches a systemui VIEW ID that only exists on
    // ROMs which show the sheet), per the standing rule that behaviour must
    // not be manufacturer-gated.
    val isPixel = remember {
        (android.os.Build.BRAND.equals("google", ignoreCase = true) ||
            android.os.Build.MANUFACTURER.equals("google", ignoreCase = true)) &&
            android.os.Build.MODEL.startsWith("Pixel", ignoreCase = true)
    }
    // NO EARLY RETURN any more: a device with no specific guidance still shows
    // the Note (so the user knows something might need doing) but NOT "Adjust
    // these settings first:" nor any empty steps under it - SH's requirement.
    val hasGuidance = isSamsung || hasXrealGlasses || hasMotoConnect || isPixel

    // Pick the privilege backend by ACTUAL readiness, not the mode label, so a
    // bound Shizuku still drives DeX even when the access-mode pref is NONE
    // (mirrors app.access's NONE->shizuku fallback). Root is only consulted when
    // the user is in root mode or has explicitly tapped "Request Root", so we
    // never fire an `su` probe (Magisk prompt) behind their back.
    var rootRequested by remember { mutableStateOf(false) }
    var dexBackend by remember { mutableStateOf<com.portalpad.app.service.ElevatedAccess?>(null) }
    var privReady by remember { mutableStateOf(false) }
    var dexOn by remember { mutableStateOf<Boolean?>(null) }
    var dexRefresh by remember { mutableStateOf(0) }
    var showEnableDexConfirm by remember { mutableStateOf(false) }
    // Hoisted above any conditional UI below so the hook count stays stable
    // (inline collectAsState inside a new conditional would crash the slot
    // table). Samsung-only behavior; the toggle that reads it is gated to
    // isSamsung && privReady further down.
    val stopDexOnStart by app.prefs.bool(
        PreferencesRepository.Keys.STOP_DEX_ON_START, default = true,
    ).collectAsState(initial = true)
    LaunchedEffect(accessMode, shizukuStatus, dexRefresh, rootRequested) {
        val backend: com.portalpad.app.service.ElevatedAccess? =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val rootReady = (accessMode == AccessMode.ROOT || rootRequested) &&
                    runCatching { app.root.isReady }.getOrDefault(false)
                val shizukuReady = runCatching { app.shizuku.isReady }.getOrDefault(false)
                when {
                    rootReady -> app.root
                    shizukuReady -> app.shizuku
                    else -> null
                }
            }
        dexBackend = backend
        privReady = backend != null
        dexOn = if (isSamsung && backend != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    backend.execShell("settings get system dex_on_external_display").trim() == "1"
                }.getOrNull()
            }
        } else {
            null
        }
    }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) dexRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Pixel-section state. Hoisted for the same reason as stopDexOnStart
    // above: read unconditionally so the hook count stays stable whichever
    // device sections render.
    val autoDesktopPref by app.prefs.bool(
        PreferencesRepository.Keys.AUTO_DESKTOP_ON_CONNECT, default = true,
    ).collectAsState(initial = true)
    // Optimistic UI (standing rule): the switch repaints from local state on
    // tap, DataStore reconciles through the LaunchedEffect.
    var autoDesktopLocal by remember { mutableStateOf(autoDesktopPref) }
    LaunchedEffect(autoDesktopPref) { autoDesktopLocal = autoDesktopPref }
    // Reuses dexRefresh, which the DisposableEffect above already bumps on
    // ON_RESUME. DisplayManager registration gates reconnect and Pixel display-
    // settings actions; while Android's connection sheet is pending, those
    // controls remain dim until the user or the auto-answer chooses a mode.
    val externalIdForLink by app.externalDisplayId.collectAsState()
    // Display add/remove straight from the framework.
    // NOT PortalPadApp.externalDisplayId on its own: that flow is maintained
    // only by the foreground SERVICE, so with the service stopped, plugging a
    // display in fired NOTHING and both controls stayed grey until the next
    // resume - SH hit exactly that and found screen-off/on "fixed" it.
    // DisplayManager notifies regardless of our service state.
    var displayTick by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { displayTick++ }
            override fun onDisplayRemoved(displayId: Int) { displayTick++ }
            // DELIBERATELY IGNORED. onDisplayChanged fires constantly - cursor
            // motion alone triggers it, which is the documented feedback loop
            // that once tore down and rebuilt the VD dozens of times per second
            // (see refreshExternalDisplay's idempotent fast path). Re-probing
            // here would still perform unnecessary work on every cursor move.
            override fun onDisplayChanged(displayId: Int) = Unit
        }
        runCatching { dm?.registerDisplayListener(listener, null) }
        onDispose { runCatching { dm?.unregisterDisplayListener(listener) } }
    }
    // DisplayManager is free, instant and reactive through the listener above.
    val displayLinkPresent = remember(dexRefresh, externalIdForLink, displayTick) {
        com.portalpad.app.service.DisplayReconnect.externalDisplayRegistered(context)
    }
    // Which reconnect lever this device has. Resolved once off the main thread;
    // re-keyed on privReady because it needs the privileged backend to answer.
    var reconnectMethod by remember {
        mutableStateOf(com.portalpad.app.service.DisplayReconnect.ReconnectMethod.NONE)
    }
    LaunchedEffect(privReady) {
        reconnectMethod = kotlinx.coroutines.withContext(
            kotlinx.coroutines.Dispatchers.IO,
        ) {
            com.portalpad.app.service.DisplayReconnect.reconnectMethod(
                runCatching { app.access }.getOrNull(),
            )
        }
    }
    // Shizuku missing while the mode is set to Shizuku. Re-read on ON_RESUME
    // (dexRefresh) so installing it and coming back updates the block.
    val shizukuInstalled = remember(dexRefresh) {
        com.portalpad.app.ui.common.isShizukuInstalled(context)
    }
    // Root specifically, NOT privReady: in Root mode privReady can be true via
    // the Shizuku fallback, which would hide a notice the user still needs.
    val rootReady = remember(dexRefresh, rootRequested, accessMode) {
        if (accessMode == AccessMode.ROOT || rootRequested) {
            runCatching { app.root.isReady }.getOrDefault(false)
        } else {
            false
        }
    }
    // Beam Pro's Air Casting property is protected from shell/Shizuku, even
    // though My Glasses exposes the matching provider row. Only show a real
    // switch after Root is explicitly selected/requested and verified.
    var beamAirCastingEnabled by remember { mutableStateOf<Boolean?>(null) }
    var beamAirCastingBusy by remember { mutableStateOf(false) }
    var beamAirCastingChanged by remember { mutableStateOf(false) }
    LaunchedEffect(hasXrealGlasses, rootReady, dexRefresh) {
        if (!hasXrealGlasses || !rootReady) {
            beamAirCastingEnabled = null
            beamAirCastingBusy = false
            return@LaunchedEffect
        }
        beamAirCastingBusy = true
        beamAirCastingEnabled = kotlinx.coroutines.withContext(
            kotlinx.coroutines.Dispatchers.IO,
        ) {
            XrealBeamProSettings.readAirCastingState(context, app.root)
                .verifiedEnabled
        }
        beamAirCastingBusy = false
    }
    val onBeamAirCastingChanged: (Boolean) -> Unit = { enabled ->
        if (rootReady && !beamAirCastingBusy) {
            val previous = beamAirCastingEnabled
            beamAirCastingEnabled = enabled
            beamAirCastingBusy = true
            scope.launch {
                val confirmed = kotlinx.coroutines.withContext(
                    kotlinx.coroutines.Dispatchers.IO,
                ) {
                    XrealBeamProSettings.setAirCastingEnabled(
                        context,
                        app.root,
                        enabled,
                    )
                }
                if (confirmed) {
                    beamAirCastingEnabled = enabled
                    beamAirCastingChanged = true
                    android.widget.Toast.makeText(
                        context,
                        "Default Air Casting ${if (enabled) "enabled" else "disabled"} — " +
                            "unplug and replug your glasses",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                } else {
                    beamAirCastingEnabled = previous
                    android.widget.Toast.makeText(
                        context,
                        "Couldn’t change Default Air Casting",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                beamAirCastingBusy = false
            }
        }
    }
    val a11yEnabled = remember(dexRefresh) {
        runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )?.contains(context.packageName) == true
        }.getOrDefault(false)
    }
    // Pixel's reconnect behavior is an Android per-display setting. Keep the
    // last value successfully read from the system so a temporary Shizuku/root
    // loss disables the picker without making its displayed choice jump.
    // Derive directly from the collected preference instead of the App's
    // asynchronously mirrored mode, so switching access source immediately
    // moves this composable onto the new backend's readyFlow.
    val pixelBoundBackend = when (accessMode) {
        AccessMode.SHIZUKU -> app.shizukuClickBackend
        AccessMode.ROOT -> app.rootClickBackend
        AccessMode.NONE -> null
    }
    val noPixelBackendReady = remember {
        kotlinx.coroutines.flow.MutableStateFlow(false)
    }
    val pixelBackendReady by (
        pixelBoundBackend?.readyFlow ?: noPixelBackendReady
        ).collectAsState()
    var pixelConnectionPreference by remember {
        mutableIntStateOf(com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_UNSUPPORTED)
    }
    var pixelPreferenceUnsupported by remember { mutableStateOf(false) }
    var pixelPreferenceBusy by remember { mutableStateOf(true) }
    var pixelPreferenceExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(
        isPixel,
        pixelBoundBackend,
        pixelBackendReady,
        displayLinkPresent,
        displayTick,
        dexRefresh,
    ) {
        if (!isPixel || !pixelBackendReady || !displayLinkPresent) return@LaunchedEffect
        val backend = pixelBoundBackend ?: return@LaunchedEffect
        pixelPreferenceBusy = true
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            backend.getExternalDisplayConnectionPreference()
        }
        when (result) {
            com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_ASK,
            com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_SEPARATE,
            com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_MIRROR,
            -> {
                pixelConnectionPreference = result
                pixelPreferenceUnsupported = false
            }
            com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_UNSUPPORTED ->
                pixelPreferenceUnsupported = true
        }
        pixelPreferenceBusy = false
    }
    val pixelPreferenceEnabled =
        pixelBackendReady &&
            displayLinkPresent &&
            !pixelPreferenceUnsupported &&
            !pixelPreferenceBusy
    val pixelPreferenceHelper = when {
        accessMode == AccessMode.NONE ->
            "Choose Shizuku or Root to change this setting."
        accessMode == AccessMode.SHIZUKU && !pixelBackendReady ->
            "Start or authorize Shizuku to change this setting."
        accessMode == AccessMode.ROOT && !pixelBackendReady ->
            "Set up or grant root to change this setting."
        !displayLinkPresent ->
            "Connect an external display to change this setting."
        pixelPreferenceUnsupported ->
            "This Android version doesn't expose this setting."
        pixelPreferenceBusy ->
            "Applying…"
        else -> null
    }
    val onPixelPreferenceSelected: (Int) -> Unit = { requested ->
        pixelPreferenceExpanded = false
        val backend = pixelBoundBackend
        if (backend != null && pixelPreferenceEnabled) {
            scope.launch {
                pixelPreferenceBusy = true
                val confirmed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val written = backend.setExternalDisplayConnectionPreference(requested)
                    if (written) backend.getExternalDisplayConnectionPreference()
                    else com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_UNSUPPORTED
                }
                if (confirmed == requested) {
                    pixelConnectionPreference = confirmed
                    pixelPreferenceUnsupported = false
                } else {
                    android.widget.Toast.makeText(
                        context,
                        "Couldn’t change the display connection setting",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                pixelPreferenceBusy = false
            }
        }
    }

    val onReconnectTap: () -> Unit = {
        scope.launch {
            runCatching {
                android.widget.Toast.makeText(
                    context,
                    "Reconnecting the external display",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.portalpad.app.service.DisplayReconnect.reconnectDisplay(
                    context,
                    runCatching { app.access }.getOrNull(),
                    "manual",
                    minIntervalMs = com.portalpad.app.service
                        .DisplayReconnect.USER_INITIATED_FLOOR_MS,
                )
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF8A4D4D), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column {
            Text(
                text = androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("\u26A0 Note: ")
                    }
                    append(
                        "Some devices auto-launch their own display experience " +
                            "(e.g. Samsung DeX, Motorola Smart Connect, or nebulaOS on the " +
                            "XREAL Beam Pro) that takes over instead of PortalPad.",
                    )
                },
                color = Color(0xFFD98A8A),
                style = MaterialTheme.typography.bodySmall,
            )
            if (hasGuidance) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Adjust these settings first:",
                    // BOLD and neutral. It introduces the device sections below,
                    // but was plain while those headings are SemiBold - a parent
                    // label lighter than its children reads as a mistake.
                    // Neutral because only the Note above is a WARNING; these are
                    // instructions, and painting the whole box alarm-red made it
                    // read as an error state when most of it is ordinary
                    // guidance. The red border and the warning glyph still carry
                    // the caution.
                    color = guidanceEnabled,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (isSamsung) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Samsung DeX:",
                    color = guidanceEnabled,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "How to disable DeX (video)",
                    color = PhonePrimaryBright,
                    // Underlined: these leave the app for a web page and had no
                    // affordance beyond colour. The in-app "Tap to reconnect
                    // display" is deliberately NOT underlined - it says "Tap
                    // to" already, and underline conventionally means "opens a
                    // page", which it does not.
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openUrl(context, "https://www.youtube.com/watch?v=l1CEsDTjDMo") }
                        .padding(vertical = 4.dp),
                )

                val canDisable = dexOn == true
                val statusText = when (dexOn) {
                    true -> "DeX: On"
                    false -> "DeX: Off"
                    null -> "DeX: Unknown"
                }
                val statusColor = if (dexOn == true) Color(0xFFD98A8A) else PhoneOnSurfaceMuted
                Spacer(Modifier.height(2.dp))
                Text(
                    statusText,
                    color = statusColor,
                    style = MaterialTheme.typography.labelSmall,
                )

                Spacer(Modifier.height(5.dp))
                val disableTextTint = if (canDisable) PhonePrimaryBright else guidanceDisabled
                val disableBorderTint =
                    if (canDisable) PhonePrimaryBright else guidanceDisabled.copy(alpha = 0.45f)
                // Blind toggle of the Samsung DeX quick-settings tile. Tap disables
                // (only when DeX is on); long-press asks before ENABLING, since
                // turning DeX on switches to desktop mode and takes over the screen.
                val toggleDexTile: () -> Unit = {
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                dexBackend?.execShell(
                                    "cmd statusbar click-tile com.sec.android.app.launcher/com.honeyspace.dexservice.DesktopModeTile",
                                )
                            }
                        }
                        kotlinx.coroutines.delay(700)
                        dexRefresh++
                    }
                }
                // Extracted so the two layouts below share one definition
                // instead of duplicating the gesture wiring.
                val disableDexButton: @Composable () -> Unit = {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, disableBorderTint, RoundedCornerShape(8.dp))
                            .pointerInput(canDisable) {
                                detectTapGestures(
                                    onTap = { if (canDisable) toggleDexTile() },
                                    onLongPress = { showEnableDexConfirm = true },
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            "Disable DeX",
                            color = disableTextTint,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                val disableDexHint: @Composable () -> Unit = {
                    Text(
                        // SHORTENED so it fits ONE line in a narrow column.
                        // SH wanted "above to enable DeX." on a second line, but a
                        // natural wrap breaks where the words fit, not where you
                        // want: at ~155dp this would have broken after "above".
                        // Hitting SH's exact break needs a 115-130dp column - a
                        // ~15dp window that moves with font size - or a hardcoded
                        // \n, correct at one size and one language. Removing the
                        // wrap removes the problem, and "the button above" is
                        // redundant with the button sitting directly above it.
                        "Long-press to enable DeX.",
                        color = PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Spacer(Modifier.height(5.dp))
                // TWO COLUMNS when the toggle exists (SH's layout): the Disable
                // button and the Stop-DeX switch sit on the same line.
                //
                // WEIGHTS ARE 1f/2f, NOT EVEN. The right side has to fit
                // "Stop DeX when PortalPad starts" on ONE line beside a 52dp
                // switch (~225dp of the ~355dp usable width), while the left
                // only needs "Long-press the button" (~115dp). An even split
                // forced the label to wrap, which is what SH was seeing.
                //
                // The line breaks are NOT hardcoded anywhere: each side wraps
                // naturally at its width. A literal break would be right only at
                // one font size, one screen width and in English. At a large
                // system font this reflows rather than clipping.
                if (isSamsung && privReady) {
                    // CENTRED AS A PAIR (SH). Neither column is weighted, so each
                    // sizes to its content and Arrangement.Center balances the two
                    // in the box. This SUPERSEDES the earlier flush-right
                    // arrangement, where the left column was weighted to absorb the
                    // slack and push the label to the box edge.
                    // Now identical in shape to the no-privilege row below, so the
                    // old "these two rows are deliberately different" note no
                    // longer applies - they should stay in step.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.Top,
                    ) {
                        // Centred internally so the button sits over its hint,
                        // mirroring the switch column.
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            disableDexButton()
                            Spacer(Modifier.height(4.dp))
                            disableDexHint()
                        }
                        Spacer(Modifier.width(DEX_COLUMN_GAP))
                        // Sizes to its label; the switch centres over it. With the
                        // row centred, this no longer reaches the box's right edge -
                        // that flush-right behaviour was superseded by SH's
                        // centre-the-pair request.
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // MATERIAL3 WRAPS Switch IN A 48dp MINIMUM INTERACTIVE
                            // TARGET while the visible track is only 32dp - so it
                            // is centred with ~8dp of invisible padding above, and
                            // top-aligning the COLUMNS aligned the boxes, not the
                            // visuals. That 8dp is exactly the offset SH saw; no
                            // alignment change could fix it because the space is
                            // inside the component.
                            // Dropping the enforcement makes the box 52x32, level
                            // with the ~32dp Disable DeX button and visibly smaller,
                            // which is what SH asked for.
                            // COST, accepted: the tap area goes 48dp -> 32dp tall.
                            // It stays 52dp wide and nothing else in the row is
                            // clickable, so a miss simply does nothing. Revert this
                            // one CompositionLocalProvider to undo.
                            androidx.compose.runtime.CompositionLocalProvider(
                                androidx.compose.material3
                                    .LocalMinimumInteractiveComponentEnforcement provides false,
                            ) {
                            Switch(
                                checked = stopDexOnStart,
                                onCheckedChange = { v ->
                                    scope.launch {
                                        app.prefs.setBool(
                                            PreferencesRepository.Keys.STOP_DEX_ON_START, v,
                                        )
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = PhonePrimary,
                                    checkedThumbColor = PhonePrimaryBright,
                                    uncheckedTrackColor = PhoneSurfaceElevated,
                                    uncheckedThumbColor = PhoneOnSurfaceDim,
                                ),
                            )
                            }
                            // 4dp, matching the left column's gap under the button,
                            // so this label lands level with the hint opposite.
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Stop DeX when PortalPad starts",
                                color = guidanceEnabled,
                                // Left unconstrained rather than maxLines = 1: at a
                                // large system font it should wrap, not clip.
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                if (showEnableDexConfirm) {
                    androidx.compose.material3.AlertDialog(
                        modifier = Modifier.border(
                            1.dp,
                            com.portalpad.app.ui.theme.PhonePrimaryDim,
                            androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                        ),
                        onDismissRequest = { showEnableDexConfirm = false },
                        title = { Text("Enable DeX?") },
                        text = {
                            Text(
                                "This turns Samsung DeX on, which switches the phone into " +
                                    "desktop mode and takes over the screen. Enabling DeX will " +
                                    "also stop the PortalPad service. Continue?",
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    showEnableDexConfirm = false
                                    // DeX takes over the external display, which fights
                                    // our VirtualDisplay — stop PortalPad's service first
                                    // so they don't conflict, then toggle DeX on.
                                    com.portalpad.app.service.PortalPadForegroundService.stop(context)
                                    toggleDexTile()
                                },
                            ) { Text("Enable DeX") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { showEnableDexConfirm = false },
                            ) { Text("Cancel") }
                        },
                    )
                }

                if (!privReady) {
                    // The setup buttons, unchanged - just lifted into a lambda so
                    // both layouts below can place them.
                    val setupButtons: @Composable () -> Unit = {
        when (accessMode) {
            AccessMode.SHIZUKU -> {
                if (shizukuStatus == ShizukuManager.Status.NEEDS_PERMISSION) {
                    DexSetupButton("Authorize Shizuku") {
                        app.shizuku.requestPermission()
                        scope.launch {
                            kotlinx.coroutines.delay(800)
                            app.shizuku.refresh()
                            dexRefresh++
                        }
                    }
                } else {
                    // Label follows what the tap can actually DO. With Shizuku
                    // missing, "Open Shizuku" promised something impossible;
                    // openShizukuApp now routes to the build page instead of
                    // silently doing nothing, so the label has to say so.
                    DexSetupButton(
                        if (shizukuInstalled) "Open Shizuku" else "Install Shizuku",
                    ) { openShizukuApp(context) }
                }
            }
            AccessMode.ROOT -> {
                DexSetupButton("Request Root") {
                    rootRequested = true
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { app.root.refresh() }
                        }
                        dexRefresh++
                    }
                }
            }
            else -> {
                val shizukuAuth = shizukuStatus == ShizukuManager.Status.NEEDS_PERMISSION
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DexSetupButton(
                        when {
                            !shizukuInstalled -> "Install Shizuku"
                            shizukuAuth -> "Authorize Shizuku"
                            else -> "Open Shizuku"
                        },
                        Modifier.weight(1f),
                    ) {
                        if (shizukuAuth) {
                            app.shizuku.requestPermission()
                            scope.launch {
                                kotlinx.coroutines.delay(800)
                                app.shizuku.refresh()
                                dexRefresh++
                            }
                        } else {
                            openShizukuApp(context)
                        }
                    }
                    DexSetupButton("Request Root", Modifier.weight(1f)) {
                        rootRequested = true
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { app.root.refresh() }
                            }
                            dexRefresh++
                        }
                    }
                }
            }
        }
                    }
                    // THREE ACROSS only when there is exactly ONE setup button.
                    // The Shizuku+Root pair already fills the width on its own, so
                    // that case keeps the original stack.
                    val oneSetupButton =
                        accessMode == AccessMode.SHIZUKU || accessMode == AccessMode.ROOT
                    // SHIZUKU CHOSEN BUT NOT INSTALLED -> say nothing here.
                    // The "Shizuku is not installed:" notice further down already
                    // states exactly this and links to the build, so a second
                    // message plus an Install button would say it twice in one box
                    // (SH spotted it in build 44). Adding that notice without
                    // removing the path it superseded was the mistake.
                    // With no right column left to balance against, the pair is
                    // CENTRED in the box (SH). Each child centres independently in
                    // the full width, so the narrower button lands centred over the
                    // wider hint - the same shape as the other action groups.
                    val shizukuMissing =
                        accessMode == AccessMode.SHIZUKU && !shizukuInstalled
                    if (shizukuMissing) {
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            disableDexButton()
                            Spacer(Modifier.height(4.dp))
                            disableDexHint()
                        }
                    } else if (oneSetupButton) {
                        // CENTRED as a pair (SH). Both columns are content-sized,
                        // so Arrangement.Center balances them in the box instead of
                        // packing them left. The toggle row above now uses the SAME
                        // arrangement, so these two should be kept in step rather
                        // than drifting apart.
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.Top,
                        ) {
                            // NEITHER column is weighted here, unlike the toggle
                            // row. A weighted left column expands to fill and
                            // shoves this pair to the far right; with both sized to
                            // content they pack from the left and sit beside the
                            // Disable DeX group, which is what makes them read as
                            // belonging to it. Leftover space goes on the right.
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                disableDexButton()
                                Spacer(Modifier.height(4.dp))
                                disableDexHint()
                            }
                            Spacer(Modifier.width(DEX_COLUMN_GAP))
                            // Message ABOVE its button (SH), mirroring the toggle
                            // column opposite. Button stays centred under the text.
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    // SHORTENED for this layout. The full sentence
                                    // needs ~240dp and this column has far less; with
                                    // a greyed "Disable DeX" alongside and the setup
                                    // button directly below, "to read or disable DeX"
                                    // is obvious from context. The STACKED fallback
                                    // keeps the full wording, where there is room.
                                    "Needs Shizuku or root.",
                                    color = PhoneOnSurfaceMuted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Spacer(Modifier.height(6.dp))
                                setupButtons()
                            }
                        }
                    } else {
                        // Two setup buttons: keep the original full-width stack,
                        // and the full explanatory sentence, which has room here.
                        disableDexButton()
                        Spacer(Modifier.height(4.dp))
                        disableDexHint()
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Needs Shizuku or root to read or disable DeX.",
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.height(5.dp))
                        setupButtons()
                    }
                }

                // DeX's own controls (Disable DeX, the Stop-DeX toggle, the
                // Shizuku/Root setup buttons) are LEFT where they are. They sit
                // mid-section with a toggle row after them, so folding them into
                // a centred trailing zone would mean restructuring a working,
                // shipped block for cosmetics. The zone is additive here.
                //
                // SKIPPED ENTIRELY when there is no reconnect lever: this zone's
                // ONLY content is the reconnect action, so without it the divider
                // would separate nothing from nothing - which is exactly what SH
                // saw on a Samsung with no privileged backend. Every other
                // section's zone still holds a launch button, so only this one
                // can go empty. Consistent with the app's rule that a control
                // appears only when it would actually do something.
                if (reconnectMethod !=
                    com.portalpad.app.service.DisplayReconnect.ReconnectMethod.NONE
                ) {
                // -4dp: ReconnectDisplayAction's text carries 4dp of touch
                // padding, which already counts toward the gap above it.
                DeviceActionZone(
                    topGap = ACTION_ZONE_GAP - 4.dp,
                    bottomGap = ACTION_ZONE_GAP - 10.dp,
                ) {
                        ReconnectDisplayAction(
                            enabled = displayLinkPresent,
                            method = reconnectMethod,
                            onTap = onReconnectTap,
                        )
                    }
                }
            }

            if (hasXrealGlasses) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "XREAL Beam Pro:",
                    color = guidanceEnabled,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (rootReady) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Default Air Casting",
                                color = guidanceEnabled,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                when {
                                    beamAirCastingBusy && beamAirCastingEnabled == null ->
                                        "Checking..."
                                    beamAirCastingBusy -> "Applying..."
                                    beamAirCastingEnabled == true -> "On"
                                    beamAirCastingEnabled == false -> "Off"
                                    else -> "Couldn’t verify this My Glasses version"
                                },
                                color = PhoneOnSurfaceMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Switch(
                            checked = beamAirCastingEnabled == true,
                            enabled = beamAirCastingEnabled != null && !beamAirCastingBusy,
                            onCheckedChange = onBeamAirCastingChanged,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = PhonePrimary,
                                checkedThumbColor = PhonePrimaryBright,
                                uncheckedTrackColor = PhoneSurfaceElevated,
                                uncheckedThumbColor = PhoneOnSurfaceDim,
                            ),
                        )
                    }
                    Text(
                        "After changing this, unplug and replug your glasses.",
                        color = if (beamAirCastingChanged) PhonePrimaryBright else PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (!beamAirCastingBusy && beamAirCastingEnabled == null) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            SetupActionButton("Open Air Casting settings") {
                                if (!XrealBeamProSettings.openAirCastingSettings(context)) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Couldn’t open My Glasses settings",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                } else {
                    Text(
                        "Enable Default Air Casting in My Glasses, then unplug and replug your glasses.",
                        color = PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Shizuku cannot write the protected Beam Pro property.
                    // Use an honest action-specific fallback instead of a
                    // switch that only looks automatic.
                    DeviceActionZone(
                        showDivider = false,
                        topGap = ACTION_ZONE_GAP,
                        bottomGap = ACTION_ZONE_GAP - 10.dp,
                    ) {
                        SetupActionButton("Open Air Casting settings") {
                            if (!XrealBeamProSettings.openAirCastingSettings(context)) {
                                android.widget.Toast.makeText(
                                    context,
                                    "Couldn’t open My Glasses settings",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
            }

            if (hasMotoConnect) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Motorola Smart Connect:",
                    color = guidanceEnabled,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "How to disable Smart Connect (video)",
                    color = PhonePrimaryBright,
                    // Underlined: these leave the app for a web page and had no
                    // affordance beyond colour. The in-app "Tap to reconnect
                    // display" is deliberately NOT underlined - it says "Tap
                    // to" already, and underline conventionally means "opens a
                    // page", which it does not.
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openUrl(context, "https://www.youtube.com/watch?v=yIoSGFFuih4") }
                        .padding(vertical = 4.dp),
                )
                // No divider here (SH): these sections are a heading, one
                // line of guidance and a single launch button - a rule would
                // be separating almost nothing. The zone still centres them.
                // -4dp: the video link above carries its own 4dp of vertical
                // padding. Identical STRUCTURE to Beam Pro does not give
                // identical spacing, because that section's middle line is
                // plain text and this one's is a padded link.
                DeviceActionZone(
                    showDivider = false,
                    topGap = ACTION_ZONE_GAP - 4.dp,
                    bottomGap = ACTION_ZONE_GAP - 10.dp,
                ) {
                    DeviceLaunchButton("Open Smart Connect", "com.motorola.mobiledesktop", context)
                    Spacer(Modifier.height(8.dp))
                    ReconnectDisplayAction(
                        enabled = displayLinkPresent,
                        method = reconnectMethod,
                        onTap = onReconnectTap,
                    )
                }
            }

            if (isPixel) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Google Pixel:",
                    color = guidanceEnabled,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "PortalPad requires a separate display; Mirror isn’t supported. " +
                        when (pixelConnectionPreference) {
                            com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_SEPARATE ->
                                "Your phone will use this as a separate display when it reconnects."
                            com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_MIRROR ->
                                "Your phone is currently set to Mirror; choose an option below."
                            com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_ASK ->
                                "Your phone will ask how to use this display when it reconnects."
                            else ->
                                "Choose what your phone should do when it reconnects."
                        },
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(10.dp))
                // One explicit width shared by the closed control and its popup
                // keeps their edges aligned. 300dp leaves room for the longer
                // "(recommended)" option without crowding the card.
                val pixelConnectionMenuWidth = 300.dp
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "When this display reconnects",
                        color = guidanceEnabled,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box {
                        OutlinedButton(
                            onClick = { pixelPreferenceExpanded = true },
                            enabled = pixelPreferenceEnabled,
                            modifier = Modifier.width(pixelConnectionMenuWidth),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = guidanceEnabled,
                                disabledContentColor = guidanceDisabled,
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (pixelPreferenceEnabled) PhonePrimary.copy(alpha = 0.65f)
                                else PhoneOnSurfaceDim.copy(alpha = 0.35f),
                            ),
                        ) {
                            Text(
                                when (pixelConnectionPreference) {
                                    com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_SEPARATE ->
                                        "Use as separate display"
                                    com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_MIRROR ->
                                        "Mirror (unsupported)"
                                    else ->
                                        "Show connection dialog"
                                },
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                            )
                        }
                        DropdownMenu(
                            expanded = pixelPreferenceExpanded,
                            onDismissRequest = { pixelPreferenceExpanded = false },
                            modifier = Modifier
                                .width(pixelConnectionMenuWidth)
                                .clip(RoundedCornerShape(12.dp))
                                .background(PhoneSurfaceElevated)
                                .border(
                                    1.dp,
                                    PhonePrimary.copy(alpha = 0.45f),
                                    RoundedCornerShape(12.dp),
                                ),
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        androidx.compose.ui.text.buildAnnotatedString {
                                            append("Show connection dialog ")
                                            withStyle(
                                                androidx.compose.ui.text.SpanStyle(
                                                    color = PhoneOnSurfaceMuted,
                                                ),
                                            ) {
                                                append("(default)")
                                            }
                                        },
                                        color = guidanceEnabled,
                                    )
                                },
                                onClick = {
                                    onPixelPreferenceSelected(
                                        com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_ASK,
                                    )
                                },
                            )
                            // A short inset hairline, vertically centred in an
                            // equal-height gap, separates choices without making
                            // the popup feel like two unrelated cards.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(17.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    thickness = 1.dp,
                                    color = PhoneOnSurfaceDim.copy(alpha = 0.28f),
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        androidx.compose.ui.text.buildAnnotatedString {
                                            append("Use as separate display ")
                                            withStyle(
                                                androidx.compose.ui.text.SpanStyle(
                                                    color = PhonePrimaryBright.copy(alpha = 0.82f),
                                                ),
                                            ) {
                                                append("(recommended)")
                                            }
                                        },
                                        color = guidanceEnabled,
                                    )
                                },
                                onClick = {
                                    onPixelPreferenceSelected(
                                        com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_SEPARATE,
                                    )
                                },
                            )
                        }
                    }
                    pixelPreferenceHelper?.let { helper ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            helper,
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }

                // When Android itself is set to use a separate display, there
                // is no connection dialog to answer. Preserve the saved auto-
                // answer preference but hide its control until Ask is selected.
                if (pixelConnectionPreference !=
                    com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_SEPARATE
                ) {
                    Spacer(Modifier.height(8.dp))
                    if (a11yEnabled) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Choose Desktop automatically",
                                    color = guidanceEnabled,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "PortalPad answers that question for you.",
                                    color = PhoneOnSurfaceMuted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Switch(
                                checked = autoDesktopLocal,
                                onCheckedChange = { v ->
                                    autoDesktopLocal = v
                                    scope.launch {
                                        app.prefs.setBool(
                                            PreferencesRepository.Keys.AUTO_DESKTOP_ON_CONNECT, v,
                                        )
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = PhonePrimary,
                                    checkedThumbColor = PhonePrimaryBright,
                                    uncheckedTrackColor = PhoneSurfaceElevated,
                                    uncheckedThumbColor = PhoneOnSurfaceDim,
                                ),
                            )
                        }
                    } else {
                        // A default-ON switch that cannot act is worse than one
                        // the user turned on themselves, so show the fix instead
                        // of an inert toggle.
                        Text(
                            "Turn on the PortalPad accessibility service and PortalPad " +
                                "can answer that question for you.",
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.height(5.dp))
                        SetupActionButton("Open Accessibility") {
                            openSystemSettingsWithExitPill(
                                context,
                                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                val forceSeparateTakeover by app.prefs.forceSeparateDisplay.collectAsState(initial = false)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            "Force separate display",
                            color = guidanceEnabled,
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Lock connection preference to Separate Display and automatically switch out of Mirror when connected.",
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Switch(
                        checked = forceSeparateTakeover,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                app.prefs.setForceSeparateDisplay(enabled)
                                if (enabled) {
                                    val backend = pixelBoundBackend
                                    if (backend != null && backend.isReady) {
                                        val confirmed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            val written = backend.setExternalDisplayConnectionPreference(
                                                com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_SEPARATE,
                                            )
                                            if (written) backend.getExternalDisplayConnectionPreference()
                                            else com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_UNSUPPORTED
                                        }
                                        if (confirmed == com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_SEPARATE) {
                                            pixelConnectionPreference = confirmed
                                            pixelPreferenceUnsupported = false
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.testTag("takeover_force_separate_switch"),
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = PhonePrimary,
                            checkedThumbColor = PhonePrimaryBright,
                            uncheckedTrackColor = PhoneSurfaceElevated,
                            uncheckedThumbColor = PhoneOnSurfaceDim,
                        ),
                    )
                }
            }

            // SHIZUKU IS THE SELECTED MODE BUT IS NOT INSTALLED.
            // Shown for EVERY device, not inside a device section: it is a
            // prerequisite, not device-specific guidance. Gated on the mode
            // actually being Shizuku - a root user, or someone who has not chosen
            // a mode, does not need to be told this.
            // Reuses OpenInRow + gitHubDest, the same component the Resources page
            // and the existing not-installed dialogs use, so they all look alike.
            if (accessMode == AccessMode.SHIZUKU && !shizukuInstalled) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Shizuku is not installed:",
                    color = guidanceEnabled,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Your access mode is set to Shizuku, but it is not on this " +
                        "phone. Install the recommended build to continue.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                // centered = true, an option OpenInRow already had. Only the
                // ACTION centres; the heading and body above stay left, matching
                // every device section in this box (left heading, left
                // description, centred action group).
                com.portalpad.app.ui.common.OpenInRow(
                    destinations = listOf(
                        com.portalpad.app.ui.common.gitHubDest {
                            openUrl(context, com.portalpad.app.ui.common.SHIZUKU_BUILD_URL)
                        },
                    ),
                    centered = true,
                )
            }

            // ROOT MODE SELECTED BUT ROOT IS NOT AVAILABLE.
            // Same shape and placement as the Shizuku notice above: global, not
            // inside a device section, because Root mode is not device-specific.
            // Only one of the two can ever show, since accessMode is one value.
            //
            // WITHOUT THIS the user is simply stuck: "Request Root" runs `su`,
            // finds nothing and silently does nothing - the same dead-action shape
            // as the Shizuku launch bug.
            //
            // The copy covers BOTH failure modes (root manager present but not
            // granted / no root at all) because they cannot be told apart
            // reliably - Magisk, KernelSU and APatch are all different packages.
            //
            // "erases the phone" is the OUTCOME; the mechanism (unlocking the
            // bootloader) is deliberately left out per the copy conventions, and
            // it is shorter.
            //
            // TWO SENTENCES, TWO Text()s, because SH wants the consequence on its
            // OWN line rather than trailing the instruction - as one paragraph it
            // wrapped and orphaned the last word. Each half must therefore fit a
            // single line on its own: the box gives ~72 characters at bodySmall.
            // The instruction is 45 characters, the Samsung warning is 63, and
            // the non-Samsung warning is 60. Anything longer wraps and undoes the
            // point, so measure before adding a word here.
            //
            // The Knox half is Samsung-only because it is a different KIND of
            // consequence: an irreversible hardware fuse, not a setting. Do not
            // drop it to save space - this notice can appear on devices whose
            // bootloader cannot be unlocked at all (US Snapdragon Galaxy models
            // such as SM-S921U), where following the Magisk link cannot succeed
            // but the Knox damage would still be permanent if it did.
            if (accessMode == AccessMode.ROOT && !rootReady) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Root is not set up:",
                    color = guidanceEnabled,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Grant root to PortalPad in your root manager.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (isSamsung) {
                    // "Samsung Knox" is an INLINE link, so the sentence stays one
                    // line and still explains itself: the earlier wording spelled
                    // the loss out ("Samsung Pay and Secure Folder") and did not
                    // fit. Compose here is 1.6 (bom 2024.06.00), which predates
                    // LinkAnnotation/withLink, so this is the ClickableText +
                    // string-annotation pattern UpdateActivity's markdown renderer
                    // already uses. Built OUTSIDE the composable call so onClick
                    // resolves offsets against the same instance it is showing.
                    val knoxWarning = androidx.compose.ui.text.buildAnnotatedString {
                        append("Setting it up erases the phone and trips ")
                        pushStringAnnotation("URL", KNOX_URL)
                        withStyle(
                            androidx.compose.ui.text.SpanStyle(
                                color = PhonePrimaryBright,
                                textDecoration = androidx.compose.ui.text.style
                                    .TextDecoration.Underline,
                            ),
                        ) {
                            append("Samsung Knox")
                        }
                        pop()
                        append(" for good.")
                    }
                    androidx.compose.foundation.text.ClickableText(
                        text = knoxWarning,
                        // ClickableText takes no color parameter - the muted body
                        // colour has to ride in on the style, or the text renders
                        // black and the notice looks broken in dark mode.
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = PhoneOnSurfaceMuted,
                        ),
                        onClick = { offset ->
                            knoxWarning
                                .getStringAnnotations("URL", offset, offset)
                                .firstOrNull()
                                ?.let { openUrl(context, it.item) }
                        },
                    )
                } else {
                    Text(
                        "Rooting erases the phone; Google Play or DRM may need fixes.",
                        color = PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                // A TEXT LINK, not the OpenInRow chip the Shizuku notice uses.
                // The chip means "download this app and you are done" - true for
                // Shizuku, false for Magisk, where installing it is one step of a
                // process that starts by erasing the phone. Different kind of
                // action, different affordance; the two notices never show at the
                // same time, so they do not need to match.
                // Underlined like the other external links in this box, and
                // centred because only the ACTION centres here.
                // The generic URL is the Magisk repo, not a step-by-step guide,
                // so its label avoids "How to ...". Beam Pro and Pixel each have
                // a verified device-specific walkthrough and can accurately use
                // that wording.
                val rootSetupLabel = when {
                    hasXrealGlasses -> "How to root XREAL Beam Pro"
                    isPixel -> "How to root Google Pixel devices"
                    else -> "Set up root with Magisk"
                }
                val rootSetupUrl = when {
                    hasXrealGlasses -> BEAM_PRO_ROOT_URL
                    isPixel -> PIXEL_ROOT_URL
                    else -> MAGISK_URL
                }
                Text(
                    rootSetupLabel,
                    color = PhonePrimaryBright,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable { openUrl(context, rootSetupUrl) }
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceLaunchButton(label: String, pkg: String, context: android.content.Context) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, PhonePrimaryBright, RoundedCornerShape(8.dp))
            .clickable {
                runCatching {
                    context.packageManager.getLaunchIntentForPackage(pkg)?.let {
                        it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        // Pin to the PHONE (display 0) so the app — and the centered
                        // "Tap to exit" pill — land on the phone regardless of where
                        // this settings screen is showing.
                        val opts = android.app.ActivityOptions.makeBasic()
                            .setLaunchDisplayId(0)
                            .toBundle()
                        context.startActivity(it, opts)
                        // Centered "⠿ Tap to exit" pill, shown service-INDEPENDENTLY:
                        // these buttons are typically used during setup, before the
                        // foreground service is running (which is why the pill never
                        // appeared before). Tapping returns to PortalPad WITHOUT closing
                        // the device app, since the user may be mid-configuration.
                        val appCtx = context.applicationContext
                        com.portalpad.app.presentation.PhoneExitBandsOverlay.showStandalone(
                            appCtx,
                            onExit = {
                                runCatching {
                                    val back = android.content.Intent(
                                        appCtx, com.portalpad.app.MainActivity::class.java,
                                    ).addFlags(
                                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                            android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                                    )
                                    val backOpts = android.app.ActivityOptions.makeBasic()
                                        .setLaunchDisplayId(0).toBundle()
                                    appCtx.startActivity(back, backOpts)
                                }
                            },
                        )
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = PhonePrimaryBright,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Same visual as [DexSetupButton] under a non-DeX-specific name, plus a real
 *  disabled state. Disabled words use the full tertiary token so they remain
 *  readable; only the outline gets additional transparency. */
@Composable
private fun SetupActionButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val textTint = if (enabled) PhonePrimaryBright else PhoneOnSurfaceDim
    val borderTint =
        if (enabled) PhonePrimaryBright else PhoneOnSurfaceDim.copy(alpha = 0.45f)
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderTint, RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = textTint,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Opens a system settings screen the way the device-app buttons do: pinned to
 * the PHONE, with the shared centered "Tap to exit" pill so the user can get
 * back to PortalPad in one tap.
 *
 * Copied from [DeviceLaunchButton], which carries the reasoning: the pill is
 * shown SERVICE-INDEPENDENTLY because these buttons are used during setup,
 * before the foreground service runs, and exiting returns to PortalPad WITHOUT
 * closing the settings screen, since the user may be mid-configuration.
 * Pinning to display 0 keeps both the settings screen and the pill on the
 * phone rather than letting either land on the external display.
 */
private fun openSystemSettingsWithExitPill(context: android.content.Context, action: String) {
    runCatching {
        val opts = android.app.ActivityOptions.makeBasic()
            .setLaunchDisplayId(0)
            .toBundle()
        context.startActivity(
            android.content.Intent(action)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            opts,
        )
        val appCtx = context.applicationContext
        com.portalpad.app.presentation.PhoneExitBandsOverlay.showStandalone(
            appCtx,
            onExit = {
                runCatching {
                    val back = android.content.Intent(
                        appCtx, com.portalpad.app.MainActivity::class.java,
                    ).addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    )
                    val backOpts = android.app.ActivityOptions.makeBasic()
                        .setLaunchDisplayId(0).toBundle()
                    appCtx.startActivity(back, backOpts)
                }
            },
        )
    }
}

@Composable
private fun DexSetupButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, PhonePrimaryBright, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = PhonePrimaryBright,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Gap between the two columns of the DeX block (button+hint | switch+label,
 * and button+hint | message+setup button).
 *
 * ONE constant for BOTH rows: since they became structurally identical they
 * were still on 12dp and 16dp, which is arbitrary and drifts.
 *
 * 12 -> 24 -> 32dp across three passes, each because SH wanted more air.
 * **32dp is close to the practical ceiling.** The two columns are roughly
 * 138dp ("Long-press to enable DeX.") + 165dp ("Stop DeX when PortalPad
 * starts") = ~303dp of content in ~355dp of usable width, so 32dp leaves only
 * ~10dp of margin either side. Push it further and the pair stops reading as
 * CENTRED - it just spans the box - and the right label starts to wrap.
 * If more separation is ever wanted, shorten one of the two strings rather
 * than growing this number.
 */
private val DEX_COLUMN_GAP = 32.dp

/**
 * Magisk, the root manager PortalPad points at. Root is NOT required - it is an
 * alternative to Shizuku for users who already have it - so this is only ever
 * surfaced to someone who has deliberately chosen Root mode.
 */
private const val MAGISK_URL = "https://github.com/topjohnwu/Magisk"

/**
 * Device-specific Beam Pro root walkthrough. This is a real step-by-step guide,
 * unlike [MAGISK_URL], so the Start-page link can accurately say "How to".
 */
private const val BEAM_PRO_ROOT_URL =
    "https://xrealguide.wixsite.com/unofficial/how-to-root-xreal-beam-pro"

/**
 * Root walkthrough for Google Pixel devices. The same `isPixel` guidance gate
 * that identifies the Pixel Start-page block selects this link.
 */
private const val PIXEL_ROOT_URL =
    "https://github.com/awesome-android-root/awesome-android-root/blob/main/" +
        "docs/rooting-guides/how-to-root-pixel-phone.md"

/**
 * What tripping Knox actually costs the user.
 *
 * WIKIPEDIA, NOT samsungknox.com, and the choice matters: Samsung's own page
 * is an enterprise pitch for Knox as a platform and says nothing about the
 * warranty fuse. The Wikipedia article has a dedicated e-Fuse section stating
 * that tripping it "prevents some preinstalled apps from running, such as
 * Secure Folder and Samsung Pay", that the Knox Workspace and any data in it
 * become unreachable, and that clearing the fuse is only possible on some
 * older Knox versions. That is the whole point of the link - do not
 * "upgrade" it to the official site.
 */
private const val KNOX_URL = "https://en.wikipedia.org/wiki/Samsung_Knox"

/**
 * Thin delegate kept so the existing call sites here read unchanged. The real
 * implementation, the package id and the build URL all live in
 * [com.portalpad.app.ui.common.openShizukuOrInstall] - one copy, because three
 * hand-written copies is how the dead-tap bug survived in two of them.
 */
private fun openShizukuApp(context: android.content.Context) =
    com.portalpad.app.ui.common.openShizukuOrInstall(context)

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean, compact: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = if (compact) 5.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(if (compact) 9.dp else 10.dp).clip(CircleShape)
                .background(if (ok) PhoneSuccess else PhoneDanger),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = PhoneOnSurface,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            value,
            color = PhoneOnSurfaceMuted,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ─────────────────────────── Tab 2: Buttons ───────────────────────────

@Composable
private fun ButtonsTab(onPickHome: () -> Unit, onPickBack: () -> Unit, scrollToTopSignal: Int = 0) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val homeAction by prefs.homeAction.collectAsState(initial = null)
    val backAction by prefs.backAction.collectAsState(initial = BackAction.System)

    TabBody(scrollToTopSignal = scrollToTopSignal) {
        SectionCard(title = "Controls") {
            // Two parallel collapsible dropdowns, both collapsed by default to
            // keep the card compact:
            //   "Trackpad & Air Mouse" — surface settings shared by both modes.
            //   "Air Mouse Only"       — tilt-specific settings.
            val collapsible: @Composable (String, @Composable () -> Unit) -> Unit =
                { title, content ->
                    var open by remember { mutableStateOf(false) }
                    val rot by animateFloatAsState(if (open) 180f else 0f, label = "chev")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { open = !open }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            color = PhoneAccent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = if (open) "Collapse" else "Expand",
                            tint = PhoneOnSurfaceMuted,
                            modifier = Modifier.rotate(rot),
                        )
                    }
                    if (open) {
                        Column(Modifier.padding(start = 4.dp, bottom = 4.dp)) { content() }
                    }
                    HorizontalDivider(color = PhoneSurfaceElevated, thickness = 1.dp)
                }

            collapsible("Trackpad & Air Mouse") {
                TrackpadSurfaceControls()
            }
            collapsible("Air Mouse Only") {
                SpeedSlider(
                    label = "Sensitivity",
                    desc = "How far the cursor moves per degree you tilt the glasses. Higher = faster.",
                    key = PreferencesRepository.Keys.AIR_MOUSE_SENSITIVITY,
                    default = 20f,
                    range = 2f..20f,
                )
                SpeedSlider(
                    label = "Smoothing",
                    desc = "Filters sensor jitter for steadier motion. Higher = smoother but slightly laggier; lower = snappier but jitterier.",
                    key = PreferencesRepository.Keys.AIR_MOUSE_SMOOTHING,
                    default = 0.3f,
                    range = 0f..0.9f,
                )
                SpeedSlider(
                    label = "Acceleration",
                    desc = "How much the cursor speeds up when you sweep the glasses faster — slow aim stays precise, quick sweeps cover more screen. Set to 0 for a constant speed.",
                    key = PreferencesRepository.Keys.AIR_MOUSE_ACCEL,
                    default = 1.0f,
                    range = 0f..2f,
                )
                ToggleRowWithDesc(
                    label = "Invert horizontal direction",
                    desc = "Flip left/right if swinging the glasses moves the cursor the wrong way.",
                    key = PreferencesRepository.Keys.AIR_MOUSE_INVERT_X,
                    default = false,
                )
                ToggleRowWithDesc(
                    label = "Invert vertical direction",
                    desc = "Flip up/down if tilting the glasses moves the cursor the wrong way.",
                    key = PreferencesRepository.Keys.AIR_MOUSE_INVERT_Y,
                    default = false,
                )
            }
            collapsible("External mouse (Beta)") {
                ExternalMouseControls()
            }
        }
        SectionCard(title = "Three-Finger Swipes") {
            Text(
                "Assign an action to each 3-finger swipe direction on the trackpad / air-mouse surface. Swipe with three fingers to trigger it.",
                color = PhoneOnSurfaceMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            val gUp by prefs.gestureSwipeUp.collectAsState(initial = com.portalpad.app.data.GestureAction.NOTIF_CLOSE)
            val gDown by prefs.gestureSwipeDown.collectAsState(initial = com.portalpad.app.data.GestureAction.NOTIF_OPEN)
            val gLeft by prefs.gestureSwipeLeft.collectAsState(initial = com.portalpad.app.data.GestureAction.HOME)
            val gRight by prefs.gestureSwipeRight.collectAsState(initial = com.portalpad.app.data.GestureAction.BACK)
            val gUpApp by prefs.gestureAppUp.collectAsState(initial = null)
            val gDownApp by prefs.gestureAppDown.collectAsState(initial = null)
            val gLeftApp by prefs.gestureAppLeft.collectAsState(initial = null)
            val gRightApp by prefs.gestureAppRight.collectAsState(initial = null)
            GestureActionRow("Swipe up", gUp, "gesture_up", gUpApp) { scope.launch { prefs.setGestureSwipeUp(it) } }
            GestureActionRow("Swipe down", gDown, "gesture_down", gDownApp) { scope.launch { prefs.setGestureSwipeDown(it) } }
            GestureActionRow("Swipe left", gLeft, "gesture_left", gLeftApp) { scope.launch { prefs.setGestureSwipeLeft(it) } }
            GestureActionRow("Swipe right", gRight, "gesture_right", gRightApp) { scope.launch { prefs.setGestureSwipeRight(it) } }
        }
        SectionCard(title = "Vibration") {
            Text(
                "Haptic feedback strength for taps — trackpad clicks and on-screen buttons. Affects the whole app. Some devices only support on/off regardless of level.",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            // Presets → milliseconds. 0 = Off.
            val vibOptions = listOf(
                "Off" to 0,
                "Light" to 10,
                "Medium" to 25,
                "Strong" to 40,
            )
            val vibMs by prefs.vibrationMs.collectAsState(initial = 25)
            val vibLabel = vibOptions.firstOrNull { it.second == vibMs }?.first ?: "Medium"
            var vibExpanded by remember { mutableStateOf(false) }
            com.portalpad.app.ui.common.AttachedDropdownBox(
                expanded = vibExpanded,
                onDismiss = { vibExpanded = false },
                modifier = Modifier.fillMaxWidth(),
                anchor = { opensUp ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PhoneSurfaceElevated)
                            .border(1.dp, PhonePrimaryDim, RoundedCornerShape(10.dp))
                            .clickable { vibExpanded = true }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            vibLabel,
                            color = PhoneOnSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (opensUp) Icons.Filled.ArrowDropUp
                            else Icons.Filled.ArrowDropDown,
                            contentDescription = "Open vibration menu",
                            tint = PhoneOnSurfaceMuted,
                        )
                    }
                },
            ) {
                vibOptions.forEachIndexed { i, (label, ms) ->
                    if (i > 0) HorizontalDivider(color = PhonePrimaryDim.copy(alpha = 0.5f))
                    DropdownMenuItem(
                        text = { Text(label, color = PhoneOnSurface) },
                        onClick = {
                            vibExpanded = false
                            scope.launch { prefs.setVibrationMs(ms) }
                        },
                    )
                }
            }
        }
        SectionCard(title = "Cursor") {
            ToggleRowWithDesc(
                label = "Custom cursor on external display",
                desc = "Render an amber cursor on the external display at the current pointer location. Useful when the OEM doesn't render a system cursor for injected mouse events.",
                key = PreferencesRepository.Keys.ENABLE_MOUSE_HOVER,
                default = true,
            )
            MediaCursorHideSection()
        }
        SectionCard(title = "Keyboard & navigation") {
            // 1) Whether the keyboard pill appears in the trackpad bottom bar.
            ToggleRowWithDesc(
                label = "Show keyboard button",
                desc = "Add a keyboard button to the trackpad bottom bar. Tapping it brings up the keyboard you choose below.",
                key = PreferencesRepository.Keys.SHOW_KEYBOARD,
                default = true,
            )
            // 2) WHICH keyboard a field tap produces — the single four-way choice.
            //    Absorbed the old "Auto-open typing page on field tap" toggle: it
            //    was a second control over one of these same four states, and its
            //    off position had to be explained in terms of the other control.
            KeyboardDisplaySelector()

            // Sync-pause applies ONLY to Android keyboard (with text box) - it
            // is the relay Android tab's debounced push (SH). Shown only while
            // that mode is selected, so the slider can't read as a setting for
            // keyboards it does not affect.
            run {
                val gOnExternal by prefs.bool(PreferencesRepository.Keys.IME_ON_EXTERNAL, default = false)
                    .collectAsState(initial = false)
                val gAutoOpen by prefs.bool(PreferencesRepository.Keys.AUTO_OPEN_RELAY_ON_FIELD, default = true)
                    .collectAsState(initial = true)
                val gAndroidTab by prefs.bool(PreferencesRepository.Keys.RELAY_OPENS_ON_ANDROID_TAB, default = false)
                    .collectAsState(initial = false)
                val gPpOnDisplay by prefs.bool(PreferencesRepository.Keys.PORTALPAD_KB_ON_DISPLAY, default = false)
                    .collectAsState(initial = false)
                val gPpFloating by prefs.bool(PreferencesRepository.Keys.PORTALPAD_KB_FLOATING, default = false)
                    .collectAsState(initial = false)
                val kbMode = com.portalpad.app.ui.common.KeyboardMode.of(
                    gOnExternal, gAutoOpen, gAndroidTab, gPpOnDisplay, gPpFloating,
                )
                if (kbMode == com.portalpad.app.ui.common.KeyboardMode.TEXT_BOX) {
            val pauseFlush by prefs.int(PreferencesRepository.Keys.RELAY_PAUSE_FLUSH_MS, default = 600)
                .collectAsState(initial = 600)
            Text(
                "Android keyboard sync pause: $pauseFlush ms" +
                    if (pauseFlush == 600) "  (default)" else "",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "On the \"Type to external display\" page's Android keyboard, typed text is sent to the external field only after you stop typing for this long. Shorter = the external display updates sooner; longer = fewer syncs mid-thought, so a resumed keystroke is less likely to be missed while a sync settles.",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = pauseFlush.toFloat(),
                onValueChange = { n ->
                    scope.launch { prefs.setInt(PreferencesRepository.Keys.RELAY_PAUSE_FLUSH_MS, n.toInt()) }
                },
                valueRange = 450f..900f,
                steps = 2,
            )
                }
            }
            // Accessibility is the detector for auto-open: it receives real focus/tap
            // events with the field's display id, and for fields that focus silently it
            // probes the focused node directly. When it's off, auto-open is unavailable
            // (there's no fallback). Hidden when the keyboard is set to appear on the
            // external display, where nothing here applies. The status refreshes when
            // the user returns from the settings screen.
            val keyboardOnExternalDisplay by prefs
                .bool(PreferencesRepository.Keys.IME_ON_EXTERNAL, default = false)
                .collectAsState(initial = false)
            if (!keyboardOnExternalDisplay) {
                val a11yLifecycleOwner = LocalLifecycleOwner.current
                var a11yTick by remember { mutableIntStateOf(0) }
                DisposableEffect(a11yLifecycleOwner) {
                    val obs = LifecycleEventObserver { _, e ->
                        if (e == Lifecycle.Event.ON_RESUME) a11yTick++
                    }
                    a11yLifecycleOwner.lifecycle.addObserver(obs)
                    onDispose { a11yLifecycleOwner.lifecycle.removeObserver(obs) }
                }
                val a11yEnabled = remember(a11yTick) {
                    runCatching {
                        android.provider.Settings.Secure.getString(
                            app.contentResolver,
                            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        )?.contains(app.packageName) == true
                    }.getOrDefault(false)
                }
                Text(
                    "Required for auto-open: Enable the PortalPad accessibility service to detect when you tap or open a text field on the glasses. It also shows the field's current text in the typing page so you can edit it. Without it, the typing page won't open automatically.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                )
                // Restricted-settings workaround — only while not yet enabled (once
                // it's on, the workaround is moot). Worded as "on some devices" since
                // the exact steps vary by OEM (Samsung/Pixel both use this flow).
                if (!a11yEnabled) {
                    Text(
                        text = androidx.compose.ui.text.buildAnnotatedString {
                            withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("Note: ") }
                            append("On some devices the PortalPad switch in Accessibility is greyed out. Tap it once — Android then surfaces a 3-dot menu on PortalPad's App info page. Open that menu, choose \"Allow restricted settings,\" then return to Accessibility and enable PortalPad.")
                        },
                        color = PhoneDanger.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
                    )
                    Text(
                        "App info",
                        color = PhonePrimaryBright,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(top = 4.dp, start = 4.dp)
                            .clickable {
                                runCatching {
                                    app.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            android.net.Uri.fromParts("package", app.packageName, null),
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                    )
                }
                // Status line carries the enabled/disabled state.
                Text(
                    if (a11yEnabled) "Status: Accessibility enabled ✓" else "Status: Not enabled",
                    color = if (a11yEnabled) PhoneSuccess else PhoneOnSurfaceMuted,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
                // Button stays action-focused ("Open Accessibility") and stays
                // tappable even when enabled (so you can go turn it off) — just
                // muted/secondary when already on, rather than a dead disabled look.
                OutlinedButton(
                    onClick = {
                        runCatching {
                            app.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (a11yEnabled) PhoneOnSurfaceMuted else PhonePrimaryBright,
                    ),
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                ) {
                    Text("Open Accessibility")
                }
            }
        }
        var showResetConfirm by remember { mutableStateOf(false) }
        ResetToDefaultsButton("Reset Controls to Defaults") { showResetConfirm = true }
        if (showResetConfirm) {
            ResetConfirmDialog(
                title = "Reset Controls to defaults?",
                message = "This resets trackpad, scrolling, cursor, vibration, and air-mouse settings to their defaults. Your Home/Back button assignments are kept.",
                onConfirm = {
                    showResetConfirm = false
                    scope.launch {
                        prefs.setBool(PreferencesRepository.Keys.TAP_TO_CLICK, true)
                        prefs.setBool(PreferencesRepository.Keys.EDGE_SCROLL, true)
                        prefs.setString(PreferencesRepository.Keys.EDGE_SCROLL_SIDE, "right")
                        prefs.setFloat(PreferencesRepository.Keys.CURSOR_SPEED, 1.0f)
                        prefs.setFloat(PreferencesRepository.Keys.SCROLL_SPEED, 1.0f)
                        prefs.setBool(PreferencesRepository.Keys.INVERT_SCROLL, false)
                        prefs.setBool(PreferencesRepository.Keys.SHOW_FINGER_DOTS, false)
                        prefs.setVibrationMs(25)
                        prefs.setBool(PreferencesRepository.Keys.ENABLE_MOUSE_HOVER, true)
                        prefs.setFloat(PreferencesRepository.Keys.AIR_MOUSE_SENSITIVITY, 20f)
                        prefs.setFloat(PreferencesRepository.Keys.AIR_MOUSE_SMOOTHING, 0.3f)
                        prefs.setBool(PreferencesRepository.Keys.AIR_MOUSE_INVERT_X, false)
                        prefs.setBool(PreferencesRepository.Keys.AIR_MOUSE_INVERT_Y, false)
                        prefs.setBool(PreferencesRepository.Keys.SHOW_KEYBOARD, true)
                        prefs.setBool(PreferencesRepository.Keys.IME_ON_EXTERNAL, false)
                        prefs.setBool(PreferencesRepository.Keys.AUTO_OPEN_RELAY_ON_FIELD, true)
                        // Together these three restore "Custom keyboard" in the
                        // keyboard dropdown — they must be reset as a SET, or a
                        // stale landing tab survives into a "default" state.
                        prefs.setBool(PreferencesRepository.Keys.RELAY_OPENS_ON_ANDROID_TAB, false)
                    }
                },
                onCancel = { showResetConfirm = false },
            )
        }
    }
}

/** Toggle row with multi-line description under the label. Used to explain what each toggle does. */
/**
 * Privilege requirement for a setting, shown as a small badge so the user knows
 * which access mode it needs. SHIZUKU_ONLY = needs the bound Shizuku UserService
 * (trusted virtual display / privileged reflection / IME policy) which root does
 * not yet provide. ROOT_OR_SHIZUKU = works via plain elevated shell under either.
 */
private enum class Requirement { NONE, SHIZUKU_ONLY, ROOT_OR_SHIZUKU }

@Composable
private fun RequirementBadge(req: Requirement) {
    if (req == Requirement.NONE) return
    val (text, fg, bg) = when (req) {
        Requirement.SHIZUKU_ONLY -> Triple(
            "Requires Shizuku", PhonePrimaryBright, PhonePrimary.copy(alpha = 0.18f),
        )
        Requirement.ROOT_OR_SHIZUKU -> Triple(
            "Root or Shizuku", PhoneOnSurface, PhoneSurfaceElevated,
        )
        Requirement.NONE -> return
    }
    Box(
        Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * "Auto-hide cursor during media playback" — a toggle plus a conditional seconds
 * slider. Detecting playback uses MediaSessionManager.getActiveSessions, which
 * requires the notification listener; if it isn't enabled we show a tap row that
 * deep-links to Android's notification-access settings (no shell-grant — that path
 * is unreliable for listeners on modern Android, matching the rest of the app).
 * The actual hide/show logic lives in PortalPadForegroundService; this is just the
 * controls.
 */
/** One line of the external mouse guide: what you do, and what it does. Mirrors
 *  the two-column shape of the Remote Gestures dialog so anyone who has read
 *  that one recognises this immediately. */
@Composable
private fun GuideRow(gesture: String, action: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            gesture,
            color = PhoneOnSurface,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            action,
            color = PhoneOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1.1f),
        )
    }
}

@Composable
private fun ExternalMouseControls() {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val keys = PreferencesRepository.Keys

    Text(
        "Use a physical mouse \u2014 Bluetooth or USB \u2014 to drive the cursor on your " +
            "external display. Works with the screen blanked via Extinguish.",
        color = PhoneOnSurfaceMuted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )

    // Optimistic, per the standing rule (6th instance; see storedInputMode just
    // below for the pattern's rationale). The tap flips the switch NOW; the
    // stored flow reconciles it, so background arming/disarming still shows up.
    val storedMouseOn by app.prefs.bool(keys.EXT_MOUSE_ENABLED, false)
        .collectAsState(initial = app.btMouse.running)
    var mouseOn by remember { mutableStateOf(storedMouseOn) }
    LaunchedEffect(storedMouseOn) { mouseOn = storedMouseOn }
    val storedAutoCapture by app.prefs.bool(keys.EXT_MOUSE_AUTO_CAPTURE, true)
        .collectAsState(initial = true)
    var autoCapture by remember { mutableStateOf(storedAutoCapture) }
    LaunchedEffect(storedAutoCapture) { autoCapture = storedAutoCapture }
    val status by app.btMouse.lastStatusFlow.collectAsState()
    val searching by app.btMouse.searchingFlow.collectAsState()
    val armed by app.btMouse.runningFlow.collectAsState()
    // EXT_MOUSE_GRAB is no longer read here: grabbing folded into Enable
    // (SH, 2026-07-24) - see BluetoothMouseController's collector.
    // Whether an external display is attached at all. A flow, not a read of
    // injector.displayId, because a composition-time snapshot would go stale the
    // moment a display appeared and would repeat the exact staleness bug the
    // status strip already had. Null means none attached.
    val hasDisplay by app.externalDisplayId.collectAsState()
    // Optimistic, for the same reason setInputModeNow() exists on the controller.
    // Writing the pref goes through DataStore, which means a disk flush before the
    // flow re-emits — so reading the flow directly made every mode-dependent thing
    // on this page (the dropdown label, its checkmark, the description, and the
    // whole Mouse/Remote slider block) wait on storage before it caught up with a
    // tap that had already taken effect. The tap sets this; the flow only reconciles
    // it, which keeps a failed or external write from leaving the UI lying.
    val storedInputMode by app.prefs.int(keys.EXT_MOUSE_INPUT_MODE, 0).collectAsState(initial = 0)
    var inputMode by remember { mutableIntStateOf(storedInputMode) }
    LaunchedEffect(storedInputMode) { inputMode = storedInputMode }
    val chosenDevice by app.prefs.string(keys.EXT_MOUSE_DEVICE, "").collectAsState(initial = "")

    var devices by remember { mutableStateOf<List<String>>(emptyList()) }
    var otherCount by remember { mutableIntStateOf(0) }
    fun refreshDevices() {
        val raw = runCatching { app.btMouse.listPointerDevices() }.getOrNull().orEmpty()
        val names = mutableListOf<String>()
        var others = 0
        raw.lineSequence().forEach { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@forEach
            if (parts[0] == "OTHER") others = parts[1].trim().toIntOrNull() ?: 0
            else names += parts[1]
        }
        devices = names
        otherCount = others
    }
    // Refresh on arm, not on a timer. listPointerDevices() spawns getevent as a
    // process, so polling it would be real work repeated for a list that changes
    // maybe once a session; keying it to the moment capture succeeds catches the
    // one transition that actually matters.
    LaunchedEffect(mouseOn, armed) { refreshDevices() }
    // ...and on Bluetooth connect/disconnect broadcasts (SH: powering the mouse
    // off left it listed until a manual Refresh). Event-driven - zero polling,
    // zero battery cost while nothing changes. The delay gives the Bluetooth
    // stack time to actually remove/add the input node, which lags the
    // broadcast by a beat.
    DisposableEffect(Unit) {
        val filter = android.content.IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                // Twice, staggered: the input node's add/removal can lag the
                // ACL broadcast by several seconds (the stack tears the node
                // down on its own schedule), and one early refresh read the
                // old list (SH: powered-off device still shown "sometimes").
                scope.launch {
                    kotlinx.coroutines.delay(1500)
                    refreshDevices()
                    kotlinx.coroutines.delay(4000)
                    refreshDevices()
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            app, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { app.unregisterReceiver(receiver) } }
    }

    // The captured device name, parsed once from the status string. Also tells the
    // Device row whether anything is actually connected.
    val connectedName = if (status.startsWith("OK")) {
        status.substringAfter("name=", "").substringBefore(" grab=").trim()
    } else {
        ""
    }
    val isConnected = (mouseOn || autoCapture) && status.startsWith("OK")

    // ── Enable | Input mode, two columns ────────────────────────────────
    // The status used to hang under "Enable" as a subtitle, which forced this
    // whole row full-width and left the Input dropdown oversized enough to flip
    // its menu upward. Status now has its own strip below, so both controls are
    // compact and the dropdown anchor is short.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Enable", color = PhoneOnSurface, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            Switch(
                checked = mouseOn,
                onCheckedChange = { on ->
                    mouseOn = on
                    scope.launch { app.prefs.setBool(keys.EXT_MOUSE_ENABLED, on) }
                    if (on) app.btMouse.enable(true) else app.btMouse.disable(explicit = true)
                },
                modifier = Modifier.testTag("ext_mouse_enable_switch"),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Input mode", color = PhoneOnSurface, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            // AttachedDropdownBox (SH's app-wide dropdown language): menu glued
            // below/above the trigger, chevron announces the direction, width
            // never narrower than the trigger.
            var inputExpanded by remember { mutableStateOf(false) }
            com.portalpad.app.ui.common.AttachedDropdownBox(
                expanded = inputExpanded,
                onDismiss = { inputExpanded = false },
                modifier = Modifier.fillMaxWidth(),
                anchor = { opensUp ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PhoneSurfaceElevated)
                            .border(1.dp, PhonePrimaryDim, RoundedCornerShape(10.dp))
                            .clickable {
                                PortalPadApp.instance.injector.buzz(longPress = false)
                                inputExpanded = true
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (inputMode == 0) "Mouse" else "Remote",
                            color = PhoneOnSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (opensUp) Icons.Filled.ArrowDropUp
                            else Icons.Filled.ArrowDropDown,
                            contentDescription = "Open input mode menu",
                            tint = PhoneOnSurfaceMuted,
                        )
                    }
                },
            ) {
                listOf("Mouse", "Remote").forEachIndexed { index, label ->
                    if (index > 0) HorizontalDivider(color = PhonePrimaryDim.copy(alpha = 0.5f))
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    label,
                                    color = if (index == inputMode) PhoneOnSurface else PhoneOnSurfaceMuted,
                                    modifier = Modifier.weight(1f),
                                )
                                if (index == inputMode) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = PhoneAccent)
                                }
                            }
                        },
                        onClick = {
                            inputExpanded = false
                            // Apply now, persist in the background. The pref
                            // round-trip through disk is what made the flip lag.
                            // All three lines matter: the first repaints, the
                            // second changes behaviour, the third remembers.
                            inputMode = index
                            app.btMouse.setInputModeNow(index)
                            scope.launch { app.prefs.setInt(keys.EXT_MOUSE_INPUT_MODE, index) }
                        },
                    )
                }
            }
        }
    }

    // ── Status strip, edge to edge ──────────────────────────────────────
    // The ONLY place the grabbed device and grab result are shown. It used to be
    // a subtitle under Enable, where a long device name would be cramped; its own
    // full-width strip means the name never truncates, and a tinted background
    // separates "what is happening" from the controls that choose it. Green when
    // grabbed, muted otherwise, so state reads at a glance.
    val statusText = when {
        !mouseOn && !autoCapture -> "Off"
        hasDisplay == null -> "Waiting for external display"
        status == "no-display" -> "Waiting for external display"
        searching -> "Searching for mouse\u2026 move it to wake it"
        status.startsWith("OK") -> {
            val who = if (connectedName.isBlank()) "" else " \u2014 $connectedName"
            when {
                status.contains("grab=OK") -> "Connected$who (black mouse + white touchpad)"
                status.contains("grab=skipped") -> "Connected$who (shared, grab off)"
                else -> "Connected$who \u2014 grab refused (" +
                    status.substringAfter("grab=", "").trim() + ")"
            }
        }
        !mouseOn && autoCapture -> "Auto-capture ready (connect a mouse)"
        else -> status
    }
    val strip = if (isConnected) PhoneAccent.copy(alpha = 0.14f) else PhoneSurfaceElevated
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(strip)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isConnected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = PhoneAccent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            statusText,
            color = if (isConnected) PhoneOnSurface else PhoneOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    // Reconnect, CENTERED directly under the status strip (SH, 2026-07-24 -
    // moved up from the Device group): it is an action ABOUT the connection,
    // so the strip that reports the problem and the button that fixes it read
    // as one unit. It is the only pure action on the page, so it is the only
    // thing that goes inactive - and the label says which of the two reasons
    // applies rather than leaving a dimmed button with no explanation.
    val canReconnect = (mouseOn || autoCapture) && hasDisplay != null
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.TextButton(
            onClick = { if (canReconnect) app.btMouse.enable(true) },
            enabled = canReconnect,
        ) {
            Text(
                when {
                    !mouseOn && !autoCapture -> "Reconnect (enable first)"
                    hasDisplay == null -> "Reconnect (needs external display)"
                    else -> "Reconnect"
                }
            )
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                "Auto-capture connected mice",
                color = PhoneOnSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Automatically capture USB/Bluetooth mice when an external display attaches (Default: On)",
                color = PhoneOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = autoCapture,
            onCheckedChange = { on ->
                autoCapture = on
                scope.launch { app.prefs.setBool(keys.EXT_MOUSE_AUTO_CAPTURE, on) }
            },
            colors = SwitchDefaults.colors(
                checkedTrackColor = PhonePrimary,
                checkedThumbColor = PhonePrimaryBright,
                uncheckedTrackColor = PhoneSurfaceElevated,
                uncheckedThumbColor = PhoneOnSurfaceDim,
            ),
            modifier = Modifier.testTag("ext_mouse_auto_capture_switch"),
        )
    }

    // Mode description, directly under the status strip. It explains the dropdown
    // in the row above, so it belongs next to it rather than three controls later
    // — reading order beats keeping the mode-dependent block strictly contiguous.
    Text(
        if (inputMode == 0) {
            "Routes mouse clicks, drag, scroll, and Back button directly to the external display. Displays a black cursor for the external mouse and seamlessly switches to white when you use the touchpad."
        } else {
            "Turns the device into a remote. Best for TV-style apps: it does " +
                "nothing in apps that do not support arrow-key navigation."
        },
        color = PhoneOnSurfaceMuted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )

    // ── Capture setup: same in both modes ───────────────────────────────
    // Device, Refresh and Reconnect are grouped here BECAUSE
    // they do not change with the input mode. Keeping them above the
    // mode-dependent block means flipping Mouse/Remote only redraws the bottom of
    // the page; these hold still.
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Device",
            color = PhoneOnSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        // Beside the label with a small gap, not parked at the far edge (SH):
        // the action reads as belonging to "Device" when it sits with it.
        Spacer(Modifier.width(14.dp))
        Text(
            "Refresh",
            color = PhonePrimaryBright,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.clickable {
                PortalPadApp.instance.injector.buzz(longPress = false)
                refreshDevices()
            }.padding(6.dp),
        )
        Spacer(Modifier.weight(1f))
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        var devExpanded by remember { mutableStateOf(false) }
        val missing = chosenDevice.isNotBlank() && chosenDevice !in devices
        // Automatic reflects reality rather than always claiming "first found":
        // it names the grabbed device when there is one and says "no device" when
        // the list is empty, which is also the signal that a disconnect refreshed.
        val autoLabel = when {
            isConnected && connectedName.isNotBlank() -> "Automatic \u2014 $connectedName"
            devices.isEmpty() -> "Automatic (no device)"
            else -> "Automatic (first found)"
        }
        com.portalpad.app.ui.common.AttachedDropdownBox(
            expanded = devExpanded,
            onDismiss = { devExpanded = false },
            modifier = Modifier.fillMaxWidth(),
            anchor = { opensUp ->
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(PhoneSurfaceElevated)
                .border(1.dp, PhonePrimaryDim, RoundedCornerShape(10.dp))
                .clickable {
                    PortalPadApp.instance.injector.buzz(longPress = false)
                    refreshDevices()
                    devExpanded = true
                }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    chosenDevice.isBlank() -> autoLabel
                    missing -> "$chosenDevice (not connected)"
                    else -> chosenDevice
                },
                color = if (missing) PhoneOnSurfaceMuted else PhoneOnSurface,
                style = MaterialTheme.typography.bodyLarge,
                // Ellipsis only on the COLLAPSED row: the name shares this line
                // with the chevron, so a long one would otherwise push it off or
                // wrap. Menu items below get the full width and can wrap freely.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (opensUp) Icons.Filled.ArrowDropUp
                else Icons.Filled.ArrowDropDown,
                contentDescription = "Open device menu",
                tint = PhoneOnSurfaceMuted,
            )
        }
            },
        ) {
            // Automatic is a RULE; the entries below are specific devices. The
            // separator and header make that difference visible instead of leaving
            // one list where the first row means something different from the rest.
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            autoLabel,
                            color = if (chosenDevice.isBlank()) PhoneOnSurface else PhoneOnSurfaceMuted,
                            modifier = Modifier.weight(1f),
                        )
                        if (chosenDevice.isBlank()) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = PhoneAccent)
                        }
                    }
                },
                onClick = {
                    devExpanded = false
                    scope.launch { app.prefs.setString(keys.EXT_MOUSE_DEVICE, "") }
                },
            )
            androidx.compose.material3.HorizontalDivider(color = PhonePrimaryDim.copy(alpha = 0.5f))
            Text(
                "Device list",
                color = PhoneOnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                // DARKER than the menu body, not lighter. It previously used a
                // surface colour a shade off the menu's own, so it vanished; a
                // header has to sit visibly behind the rows it introduces.
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PhoneBackground)
                    .padding(vertical = 6.dp),
            )
            val entries = devices.map { it to it } +
                (if (missing) listOf(chosenDevice to "$chosenDevice (not connected)") else emptyList())
            if (entries.isEmpty()) {
                Text(
                    "None found",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            entries.forEach { (value, label) ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label,
                                color = if (value == chosenDevice) PhoneOnSurface else PhoneOnSurfaceMuted,
                                modifier = Modifier.weight(1f),
                            )
                            if (value == chosenDevice) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = PhoneAccent)
                            }
                        }
                    },
                    onClick = {
                        devExpanded = false
                        scope.launch { app.prefs.setString(keys.EXT_MOUSE_DEVICE, value) }
                    },
                )
            }
        }
    }
    if (devices.isEmpty()) {
        Text(
            if (otherCount > 0) {
                "No pointer devices found. $otherCount other input device" +
                    (if (otherCount == 1) "" else "s") +
                    " present, none reporting as a pointer."
            } else {
                "No input devices found. Connect a mouse over Bluetooth or USB."
            },
            color = PhoneOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }

    // ── Mode-toggle button remap (SH feature, 2026-07-24) ───────────────
    // One button per device flips mouse<->remote. Learn-by-press: the button
    // opens an 8s window in the controller; the next extra button on the
    // captured device becomes the mapping. Left/right/middle can never be
    // captured (they travel in the button bitmask, not as key records).
    val learnState by app.btMouse.learnStateFlow.collectAsState()
    val toggleMapRaw by app.prefs.string(keys.EXT_MOUSE_MODE_TOGGLE_MAP, "")
        .collectAsState(initial = "")
    // Optimistic mirror, standing rule (7th instance).
    val storedActivation by app.prefs.int(keys.EXT_MOUSE_TOGGLE_ACTIVATION, 0)
        .collectAsState(initial = 0)
    var activation by remember { mutableIntStateOf(storedActivation) }
    LaunchedEffect(storedActivation) { activation = storedActivation }
    val activationLabels = listOf("Tap", "Double tap", "Hold")
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Text(
            "Switch mode with a mouse button",
            color = PhoneOnSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Map one button on your mouse to switch between mouse and remote. " +
                "Works only while the external display is connected. If another " +
                "app has remapped the same button, the two may conflict.",
            color = PhoneOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        // Map button LEFT, activation-style dropdown RIGHT (SH layout).
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Countdown while listening (SH): says how long the window stays
            // open before "No button seen". Ticks by polling the controller's
            // deadline - the state flow only carries the phase changes.
            var learnRemaining by remember { mutableIntStateOf(0) }
            LaunchedEffect(learnState) {
                if (learnState == "listening") {
                    while (true) {
                        learnRemaining = app.btMouse.learnRemainingSecs()
                        if (learnRemaining <= 0) break
                        kotlinx.coroutines.delay(200)
                    }
                }
            }
            androidx.compose.material3.TextButton(
                onClick = { if (learnState != "listening") app.btMouse.startLearn() },
                // Always tappable (SH): with no display connected the
                // controller starts a temporary listen-only capture just for
                // the window; a missing mouse surfaces as "No mouse found".
                enabled = true,
            ) {
                Text(
                    when (learnState) {
                        "listening" -> "Press a button… $learnRemaining"
                        "timeout" -> "No button seen"
                        "no-device" -> "No mouse found"
                        else -> "Map button"
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            // Names the dropdown's ONE job (SH): it selects the gesture for the
            // NEXT recording; every stored mapping stays active on its own
            // gesture regardless of this selection.
            Text(
                "Gesture to map",
                color = PhoneOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(8.dp))
            run {
                var actExpanded by remember { mutableStateOf(false) }
                com.portalpad.app.ui.common.AttachedDropdownBox(
                    expanded = actExpanded,
                    onDismiss = { actExpanded = false },
                    anchor = { opensUp ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(PhoneSurfaceElevated)
                        .border(1.dp, PhonePrimaryDim, RoundedCornerShape(10.dp))
                        .clickable { actExpanded = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        activationLabels[activation.coerceIn(0, 2)],
                        color = PhoneOnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Icon(
                        imageVector = if (opensUp) Icons.Filled.ArrowDropUp
                        else Icons.Filled.ArrowDropDown,
                        contentDescription = "Choose the gesture to map",
                        tint = PhoneOnSurfaceMuted,
                    )
                }
                    },
                ) {
                    activationLabels.forEachIndexed { i, label ->
                        if (i > 0) HorizontalDivider(color = PhonePrimaryDim.copy(alpha = 0.5f))
                        com.portalpad.app.ui.common.CompactMenuRow(
                            label = label,
                            selected = i == activation,
                        ) {
                            actExpanded = false
                            activation = i
                            scope.launch {
                                app.prefs.setInt(keys.EXT_MOUSE_TOGGLE_ACTIVATION, i)
                            }
                        }
                    }
                }
            }
        }
        // Firmware-gesture guidance, directly under the controls it explains
        // (SH placement): the learn step adapts to whichever kind of button
        // answers, this just tells the user to perform the real gesture.
        Text(
            leadInBold(
                "Note: Your device decides which press sends each signal. A " +
                "signal your device ties to one gesture, like double press, " +
                "always needs that gesture to fire, no matter which option " +
                "you record it under. A signal sent on a single tap follows " +
                "the dropdown instead: record it under double tap and two " +
                "quick taps will trigger it. If your device sends nothing " +
                "for a gesture, such as hold on many devices, that gesture " +
                    "cannot switch modes.",
            ),
            // Attention amber (SH): this is the paragraph that saves a
            // confused mapping session, and muted grey buried it. Wording
            // co-authored with SH (2026-07-24) from their device's actual
            // asymmetry - keep the three rules: gesture-tied signals keep
            // their gesture, tap signals follow the dropdown, absent signals
            // cannot fire.
            color = PhoneAccent,
            style = MaterialTheme.typography.bodySmall,
        )
        // Transient just-mapped strip - confirms WHAT was learned, then steps
        // aside (the controller clears the state after a few seconds; the
        // mapped-devices dropdown below is the durable record).
        if (learnState?.startsWith("mapped") == true) {
            val parts = learnState!!.split('\t')
            Text(
                "Mapped: ${parts.getOrElse(1) { "" }} · ${parts.getOrElse(2) { "" }}",
                color = PhoneAccent,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PhoneAccent.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        // Mapped devices, collapsed into a dropdown (SH) - one row of page
        // space however many devices are mapped. Custom attached-below popup
        // so long lists scroll with a visible thumb. No device cap on
        // purpose: one mapping per device, and a mapping can only be created
        // from a device you physically captured - the list cannot outgrow
        // the hardware you actually own.
        val toggleEntries = com.portalpad.app.service.BluetoothMouseController
            .decodeToggleMap(toggleMapRaw)
        if (toggleEntries.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            run {
                var mapExpanded by remember { mutableStateOf(false) }
                com.portalpad.app.ui.common.AttachedDropdownBox(
                    expanded = mapExpanded,
                    onDismiss = { mapExpanded = false },
                    modifier = Modifier.fillMaxWidth(),
                    maxMenuHeight = 240.dp,
                    anchor = { opensUp ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(PhoneSurfaceElevated)
                        .border(1.dp, PhonePrimaryDim, RoundedCornerShape(10.dp))
                        .clickable { mapExpanded = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Mapped devices (${toggleEntries.size})",
                        color = PhoneOnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (opensUp) Icons.Filled.ArrowDropUp
                        else Icons.Filled.ArrowDropDown,
                        contentDescription = "Open mapped devices",
                        tint = PhoneOnSurfaceMuted,
                    )
                }
                    },
                ) {
                    run {
                                toggleEntries.forEachIndexed { i, entry ->
                                    if (i > 0)

                                        androidx.compose.material3.HorizontalDivider(
                                            color = PhonePrimaryDim.copy(alpha = 0.5f),
                                        )
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "${entry.device} · " +
                                                com.portalpad.app.service
                                                    .BluetoothMouseController
                                                    .buttonLabel(entry.code) +
                                                com.portalpad.app.service
                                                    .BluetoothMouseController
                                                    .gestureSuffix(entry.gesture),
                                            color = PhoneOnSurface,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            "Clear",
                                            color = PhonePrimaryBright,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier
                                                .clickable {
                                                    app.btMouse.clearMapping(
                                                        entry.device, entry.code,
                                                    )
                                                    if (toggleEntries.size <= 1) {
                                                        mapExpanded = false
                                                    }
                                                }
                                                .padding(6.dp),
                                        )
                                    }
                                }
                    }
                }
            }
        }
    }

    // The "Exclusive grab" toggle that lived here was REMOVED (SH, 2026-07-24):
    // a setting whose correct value is always on is a bug with a checkbox.
    // Enable now always grabs; the pref stays registered for revert.
    // Reconnect also moved out of this group - it lives centered under the
    // status strip now (same SH pass), beside the state it acts on.

    // ── Mode behaviour: everything below changes with the input mode ─────
    androidx.compose.material3.HorizontalDivider(
        color = PhonePrimaryDim.copy(alpha = 0.4f),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
    )

    // Guide table — Remote only. Mouse mode's two non-obvious bindings are folded
    // into the description above instead, since the rest of a mouse is a mouse.
    if (inputMode == 1) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Remote gestures",
            color = PhoneAccent,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        // The latch stated ONCE, in plain words, above the table. It used to be
        // spread across three rows as "keeps going" and "scrubbing", which made
        // the reader assemble the idea themselves — and "scrubbing" is an editing
        // term, not something to put in front of someone choosing a mouse mode.
        Text(
            "Rewind and fast-forward keep running until you tap to stop.",
            color = PhoneOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Spacer(Modifier.height(4.dp))
        GuideRow("Swipe", "up / down / left / right = D-pad arrows")
        GuideRow("Tap, or left click", "OK / select, or stop rewind and fast-forward")
        GuideRow("Hold, or hold left click", "Back")
        GuideRow("Two-finger swipe left / right", "rewind / fast-forward")
        GuideRow("Two-finger swipe the opposite way", "switch between rewind and fast-forward")
        GuideRow("Two-finger swipe up", "Home")
        GuideRow("Two-finger tap, or right click", "play / pause")
        GuideRow("Two-finger hold, or hold right click", "Stop")
    }

    Spacer(Modifier.height(12.dp))
    if (inputMode == 0) {
        val sens by app.prefs.float(keys.EXT_MOUSE_SENSITIVITY, 2.5f).collectAsState(initial = 2.5f)
        Text(
            "Mouse sensitivity: ${"%.1f".format(sens)}\u00d7",
            color = PhoneOnSurfaceMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Slider(
            value = sens,
            onValueChange = { v -> scope.launch { app.prefs.setFloat(keys.EXT_MOUSE_SENSITIVITY, v) } },
            valueRange = 0.5f..6f,
            colors = sliderColors(),
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Spacer(Modifier.height(8.dp))
        val scrollSpd by app.prefs.float(keys.EXT_MOUSE_SCROLL_SPEED, 1.0f).collectAsState(initial = 1.0f)
        Text(
            "Scroll speed: ${"%.2f".format(scrollSpd)}\u00d7",
            color = PhoneOnSurfaceMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Slider(
            value = scrollSpd,
            onValueChange = { v -> scope.launch { app.prefs.setFloat(keys.EXT_MOUSE_SCROLL_SPEED, v) } },
            valueRange = 0.25f..4f,
            colors = sliderColors(),
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        ToggleRowWithDesc(
            label = "Reverse scroll direction",
            desc = "Flip the wheel direction if your mouse scrolls the opposite way.",
            key = keys.EXT_MOUSE_NATURAL_SCROLL,
            default = false,
        )
    } else {
        val swipe by app.prefs.int(keys.EXT_MOUSE_SWIPE_DISTANCE, 60).collectAsState(initial = 60)
        Text(
            "Swipe distance: $swipe",
            color = PhoneOnSurfaceMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Text(
            "How far to swipe before an arrow fires. Lower is more sensitive.",
            color = PhoneOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Slider(
            value = swipe.toFloat(),
            onValueChange = { v -> scope.launch { app.prefs.setInt(keys.EXT_MOUSE_SWIPE_DISTANCE, v.toInt()) } },
            valueRange = 20f..150f,
            colors = sliderColors(),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        ToggleRowWithDesc(
            label = "Reverse seek direction",
            desc = "Flip which way a two-finger swipe rewinds or fast-forwards, if " +
                "yours is backwards.",
            key = keys.EXT_MOUSE_REVERSE_SEEK,
            default = true,
        )
    }
}

@Composable
private fun MediaCursorHideSection() {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Re-check listener status when returning from Android settings.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var refreshTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val notifEnabled = remember(refreshTick) {
        runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners",
            )?.contains(context.packageName) == true
        }.getOrDefault(false)
    }

    val enabled by prefs.bool(PreferencesRepository.Keys.MEDIA_AUTOHIDE_CURSOR, default = false)
        .collectAsState(initial = false)
    val secs by prefs.int(PreferencesRepository.Keys.MEDIA_AUTOHIDE_CURSOR_SEC, default = 5)
        .collectAsState(initial = 5)

    fun openNotifSettings() = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val cn = android.content.ComponentName(
                context, com.portalpad.app.service.PortalPadNotificationListenerService::class.java,
            )
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            throw IllegalStateException("pre-30")
        }
    }.recoverCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    ToggleRowWithDesc(
        label = "Auto-hide cursor during media playback",
        desc = "When fullscreen media is playing on the external display, hide the cursor after a few idle seconds. It reappears the moment you use the trackpad again. (Stays visible for media in a freeform window.)",
        key = PreferencesRepository.Keys.MEDIA_AUTOHIDE_CURSOR,
        default = false,
    )
    if (enabled && !notifEnabled) {
        // Listener required for playback detection — offer to grant it.
        Spacer(Modifier.height(4.dp))
        Text(
            text = androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                append("Needs notification access. ")
                pop()
                append("Tap to enable it so PortalPad can detect when media is playing.")
            },
            color = PhoneWarning,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { app.injector.buzz(); openNotifSettings() }
                .padding(vertical = 4.dp),
        )
    } else if (enabled) {
        // Listener present — reveal the idle-delay slider.
        Spacer(Modifier.height(4.dp))
        Text(
            "Hide after: ${secs}s of no trackpad movement",
            color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = secs.toFloat(),
            onValueChange = { n ->
                scope.launch {
                    prefs.setInt(PreferencesRepository.Keys.MEDIA_AUTOHIDE_CURSOR_SEC, n.toInt())
                }
            },
            valueRange = 2f..20f, steps = 17, colors = sliderColors(),
        )
    }
}

@Composable
private fun ToggleRowWithDesc(
    label: String,
    desc: String,
    key: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
    default: Boolean = false,
    requirement: Requirement = Requirement.NONE,
    // When set, toggling shows a confirm dialog with this message; the pref is only
    // written if the user confirms. Used by the desktop-windows toggle, which
    // closes all external windows when it flips.
    confirmMessage: String? = null,
    // When false, the row is greyed out and the switch is non-interactive. Used to
    // show a toggle whose effect is moot under the current configuration (e.g.
    // auto-open-relay when the keyboard is set to the glasses). Optional note
    // explains WHY it's disabled.
    enabled: Boolean = true,
    disabledNote: String? = null,
    testTag: String? = null,
) {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val value by app.prefs.bool(key, default).collectAsState(initial = default)
    var pendingValue by remember { mutableStateOf<Boolean?>(null) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                label,
                color = if (enabled) PhoneOnSurface else PhoneOnSurfaceDim,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                desc,
                color = if (enabled) PhoneOnSurfaceMuted else PhoneOnSurfaceDim,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!enabled && disabledNote != null) {
                Text(
                    disabledNote,
                    color = PhoneAccent,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                leadInBold("Default: ${if (default) "On" else "Off"}"),
                color = PhoneOnSurfaceDim,
                style = MaterialTheme.typography.bodySmall,
            )
            RequirementBadge(requirement)
        }
        Switch(
            checked = value,
            enabled = enabled,
            modifier = Modifier.then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            onCheckedChange = { newVal ->
                if (confirmMessage != null) pendingValue = newVal
                else scope.launch { app.prefs.setBool(key, newVal) }
            },
            colors = SwitchDefaults.colors(
                checkedTrackColor = PhonePrimary,
                checkedThumbColor = PhonePrimaryBright,
                uncheckedTrackColor = PhoneSurfaceElevated,
                uncheckedThumbColor = PhoneOnSurfaceDim,
            ),
        )
    }
    // Confirm dialog (only used when confirmMessage != null). The Switch stays at
    // the old value until confirmed, so cancelling leaves everything unchanged.
    if (confirmMessage != null) {
        pendingValue?.let { pv ->
            androidx.compose.material3.AlertDialog(
                modifier = Modifier.border(
                    1.dp,
                    com.portalpad.app.ui.theme.PhonePrimaryDim,
                    androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                ),
                onDismissRequest = { pendingValue = null },
                // Generic: the title used to hardcode "desktop windows", which was the
                // only caller. That caller is now DisplayModeSelector with its own
                // dialog, so this path has NO users - left in place rather than
                // deleted, but it must not name a specific setting any more.
                title = { Text(label) },
                text = { Text(confirmMessage, color = PhoneOnSurfaceMuted) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        scope.launch { app.prefs.setBool(key, pv) }
                        pendingValue = null
                    }) { Text("Continue") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { pendingValue = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

/**
 * Theater vs desktop as one named choice, replacing a toggle whose off state had
 * no name at all.
 *
 * The naming is the point, not the control. "Enable desktop windows" could only
 * describe half of what this setting does, so the other half had to be referred to
 * as "off" everywhere it came up. Both modes now have a name that can be said out
 * loud, which is what lets the external display's top bar and the trackpad's window
 * wheel talk about them too.
 *
 * Shaped like the external mouse Input mode dropdown on purpose: two binary pickers
 * in the same app should not be a switch in one place and a menu in the other.
 *
 * Optimistic from the moment Continue is pressed, like the input mode picker.
 * An earlier version of this reasoned that the confirm dialog made that wrong. It
 * only makes it wrong BEFORE the dialog is answered: nothing may look changed while
 * the question is still on screen. Once the user confirms, the decision is made,
 * and making the page wait on a DataStore flush to repaint is the same lag in a
 * later place.
 */
@Composable
private fun DisplayModeSelector() {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    // The flow reconciles; the confirm button sets. Reading the flow directly left
    // the collapsed label, the menu checkmark and the mode description all waiting
    // on a disk write after the change had already been agreed to.
    val storedDesktop by app.prefs.bool(PreferencesRepository.Keys.DESKTOP_MODE_ENABLED, false)
        .collectAsState(initial = false)
    var desktop by remember { mutableStateOf(storedDesktop) }
    LaunchedEffect(storedDesktop) { desktop = storedDesktop }
    var expanded by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Boolean?>(null) }
    var anchorWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("Display mode", color = PhoneOnSurface, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        com.portalpad.app.ui.common.AttachedDropdownBox(
            expanded = expanded,
            onDismiss = { expanded = false },
            modifier = Modifier.fillMaxWidth(),
            anchor = { opensUp ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PhoneSurfaceElevated)
                    .border(1.dp, PhonePrimaryDim, RoundedCornerShape(10.dp))
                    .clickable {
                        PortalPadApp.instance.injector.buzz(longPress = false)
                        expanded = true
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Same icons the external display's top bar and the arrange wheel
                // use, so the mode has one picture everywhere it is named.
                Icon(
                    painter = androidx.compose.ui.res.painterResource(
                        if (desktop) com.portalpad.app.R.drawable.ic_mode_desktop
                        else com.portalpad.app.R.drawable.ic_mode_theater,
                    ),
                    contentDescription = null,
                    tint = PhoneOnSurface,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (desktop) "Desktop mode" else "Theater mode",
                    color = PhoneOnSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (opensUp) Icons.Filled.ArrowDropUp
                    else Icons.Filled.ArrowDropDown,
                    contentDescription = "Open display mode menu",
                    tint = PhoneOnSurfaceMuted,
                )
            }
            },
        ) {
                listOf(false to "Theater mode", true to "Desktop mode").forEach { (value, label) ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(
                                        if (value) com.portalpad.app.R.drawable.ic_mode_desktop
                                        else com.portalpad.app.R.drawable.ic_mode_theater,
                                    ),
                                    contentDescription = null,
                                    tint = if (value == desktop) PhoneOnSurface else PhoneOnSurfaceMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    label,
                                    color = if (value == desktop) PhoneOnSurface else PhoneOnSurfaceMuted,
                                    modifier = Modifier.weight(1f),
                                )
                                if (value == desktop) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = PhoneAccent)
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            // Only prompt on an actual change. Reselecting the current
                            // mode should not offer to close all your windows.
                            if (value != desktop) pending = value
                        },
                    )
                }
            }
        Spacer(Modifier.height(6.dp))
        // No per-mode description here. The section header describes BOTH modes as
        // bullets, so a swapping text under the dropdown would say the same thing
        // twice and jump on every switch. SH asked for the bulleted layout.
        Text(
            leadInBold("Default: Theater mode"),
            color = PhoneOnSurfaceDim,
            style = MaterialTheme.typography.bodySmall,
        )
        RequirementBadge(Requirement.ROOT_OR_SHIZUKU)
    }
    pending?.let { pv ->
        androidx.compose.material3.AlertDialog(
            modifier = Modifier.border(
                1.dp,
                com.portalpad.app.ui.theme.PhonePrimaryDim,
                androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            ),
            onDismissRequest = { pending = null },
            title = { Text(if (pv) "Switch to desktop mode?" else "Switch to theater mode?") },
            text = {
                Column {
                    // WHAT the target mode is, before what switching costs (SH):
                    // same sentence in all three switch popups (workspace / top
                    // bar / arrange wheel) so the modes have one voice.
                    Text(
                        if (pv) {
                            "Desktop mode opens apps as movable, resizable windows on the external display."
                        } else {
                            "Theater mode shows one app at a time, filling the external display."
                        },
                        color = PhoneOnSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This closes anything open on the external display. Reopen apps from " +
                            "the dock afterward.",
                        color = PhoneOnSurfaceMuted,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    // Repaint, then persist. Every mode-dependent thing on this page
                    // reads [desktop], so this one assignment updates all of them.
                    desktop = pv
                    scope.launch {
                        app.prefs.setBool(PreferencesRepository.Keys.DESKTOP_MODE_ENABLED, pv)
                    }
                    pending = null
                }) { Text("Continue") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pending = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Settings' view of the keyboard choice. The control itself lives in
 * [com.portalpad.app.ui.common.KeyboardModeDropdown] so this page and the typing
 * page cannot drift apart in wording or behavior.
 */
@Composable
private fun KeyboardDisplaySelector() {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "Choose your preferred keyboard",
            color = PhoneOnSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Which keyboard you get when you tap a text field on the external display.",
            color = PhoneOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.size(8.dp))
        // Notes describe the SELECTED mode, so the picker has to report it back.
        var shown by remember { mutableIntStateOf(com.portalpad.app.ui.common.KeyboardMode.CUSTOM) }
        com.portalpad.app.ui.common.KeyboardModeDropdown(onSelectionShown = { shown = it })
        Spacer(Modifier.size(10.dp))
        // showReliability = false (SH, 2026-07-24): the family band above
        // already makes the reliability claim; the per-mode line said it twice.
        com.portalpad.app.ui.common.KeyboardModeNotes(shown, showReliability = false)
        RequirementBadge(Requirement.ROOT_OR_SHIZUKU)
    }
}

// ─────────────────────────── Tab 3: Dock ───────────────────────────

/**
 * A focused, mode-specific control subpage opened from the gear on the
 * trackpad / air-mouse surface. Surfaces the SAME Controls-tab settings (same
 * pref keys — one source of truth) filtered to the active mode, plus a back
 * button that returns to the interface the user came from.
 */
/**
 * Which DEVICE you are configuring, not which mode is active.
 *
 * Named deliberately: "Air Mouse | External mouse" would echo a word from the
 * top-level Air Mouse / Trackpad / Remote switcher a short scroll away, and that
 * one really does mean pick-one. These two devices drive the display at the same
 * time, so pick-one framing would be wrong on top of being ambiguous.
 */
@Composable
private fun DeviceTabRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(PhoneSurfaceElevated)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("This device", "External mouse").forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) PhonePrimary else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable {
                        PortalPadApp.instance.injector.buzz(longPress = false)
                        onSelect(index)
                    }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) PhoneBackground else PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun ControlsSubpage(
    isAirMouse: Boolean,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val prefs = PortalPadApp.instance.prefs
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxSize()
            .background(PhoneBackground),
    ) {
        PageScrollChevrons(scrollState)
        // Scrollable content. Bottom padding leaves room for the pinned Back
        // button so the last setting isn't hidden behind it. A thin scrollbar
        // is painted on the right while there's overflow.
        Column(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    val max = scrollState.maxValue
                    if (max > 0) {
                        // Reserve a zone at each end so the thumb stops clear of
                        // the overlaid PageScrollChevrons instead of sliding
                        // under them (SH).
                        val chevZone = 26.dp.toPx()
                        val viewH = size.height
                        val total = viewH + max
                        val trackTop = chevZone
                        val trackBottom = size.height - chevZone
                        val trackH = (trackBottom - trackTop).coerceAtLeast(1f)
                        val thumbH = (trackH * (viewH / total)).coerceIn(28.dp.toPx(), trackH)
                        val y = trackTop + (trackH - thumbH) * (scrollState.value.toFloat() / max)
                        drawRoundRect(
                            color = PhoneOnSurfaceMuted.copy(alpha = 0.5f),
                            topLeft = androidx.compose.ui.geometry.Offset(size.width - 6f, y),
                            size = androidx.compose.ui.geometry.Size(4f, thumbH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
                        )
                    }
                }
                .verticalScroll(scrollState)
                .padding(16.dp)
                .padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (isAirMouse) "Air Mouse controls" else "Trackpad controls",
                color = PhoneAccent,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            // Split by DEVICE, not by mode. "Air Mouse | External mouse" would sit
            // a few hundred px below the top-level Air Mouse / Trackpad / Remote
            // switcher, which genuinely does mean pick-one, and would repeat one of
            // its words — two segmented controls meaning different things. These
            // two devices also work at the same time, so pick-one framing would be
            // wrong as well as confusing.
            var deviceTab by remember { mutableIntStateOf(0) }
            DeviceTabRow(
                selected = deviceTab,
                onSelect = { deviceTab = it },
            )

            if (deviceTab == 1) {
                SectionCard(title = "External mouse (Beta)") {
                    Text(
                        "Settings for a physical mouse connected over Bluetooth or USB. " +
                            "It drives the cursor on the external display alongside this " +
                            "phone's surface, so both work at once.",
                        color = PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // The SAME composable the Settings page renders, not a copy.
                    // Two hand-maintained copies of one set of preferences drift,
                    // and the one you are not looking at is always the stale one.
                    ExternalMouseControls()
                }
                return@Column
            }

            if (isAirMouse) {
                SectionCard(title = "Air Mouse") {
                    SpeedSlider(
                        label = "Sensitivity",
                        desc = "How far the cursor moves per degree you tilt the glasses. Higher = faster.",
                        key = PreferencesRepository.Keys.AIR_MOUSE_SENSITIVITY,
                        default = 20f,
                        range = 2f..20f,
                    )
                    SpeedSlider(
                        label = "Smoothing",
                        desc = "Filters sensor jitter for steadier motion. Higher = smoother but slightly laggier; lower = snappier but jitterier.",
                        key = PreferencesRepository.Keys.AIR_MOUSE_SMOOTHING,
                        default = 0.3f,
                        range = 0f..0.9f,
                    )
                    SpeedSlider(
                        label = "Acceleration",
                        desc = "How much the cursor speeds up when you sweep the glasses faster — slow aim stays precise, quick sweeps cover more screen. Set to 0 for a constant speed.",
                        key = PreferencesRepository.Keys.AIR_MOUSE_ACCEL,
                        default = 1.0f,
                        range = 0f..2f,
                    )
                    ToggleRowWithDesc(
                        label = "Invert horizontal direction",
                        desc = "Flip left/right if swinging the glasses moves the cursor the wrong way.",
                        key = PreferencesRepository.Keys.AIR_MOUSE_INVERT_X,
                        default = false,
                    )
                    ToggleRowWithDesc(
                        label = "Invert vertical direction",
                        desc = "Flip up/down if tilting the glasses moves the cursor the wrong way.",
                        key = PreferencesRepository.Keys.AIR_MOUSE_INVERT_Y,
                        default = false,
                    )
                }
                // Air Mouse still uses the trackpad surface for taps/scroll, so
                // it gets the same surface controls (same order as Trackpad).
                SectionCard(title = "Trackpad surface") {
                    TrackpadSurfaceControls()
                }
            } else {
                SectionCard(title = "Trackpad") {
                    TrackpadSurfaceControls()
                }
            }

        // 3-finger swipe gestures — apply to both trackpad and air mouse.
        SectionCard(title = "Three-Finger Swipes") {
            Text(
                "Assign an action to each 3-finger swipe direction. Swipe with three fingers on the surface to trigger it.",
                color = PhoneOnSurfaceMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            val gUp by prefs.gestureSwipeUp.collectAsState(initial = com.portalpad.app.data.GestureAction.NOTIF_CLOSE)
            val gDown by prefs.gestureSwipeDown.collectAsState(initial = com.portalpad.app.data.GestureAction.NOTIF_OPEN)
            val gLeft by prefs.gestureSwipeLeft.collectAsState(initial = com.portalpad.app.data.GestureAction.HOME)
            val gRight by prefs.gestureSwipeRight.collectAsState(initial = com.portalpad.app.data.GestureAction.BACK)
            val gUpApp by prefs.gestureAppUp.collectAsState(initial = null)
            val gDownApp by prefs.gestureAppDown.collectAsState(initial = null)
            val gLeftApp by prefs.gestureAppLeft.collectAsState(initial = null)
            val gRightApp by prefs.gestureAppRight.collectAsState(initial = null)
            GestureActionRow("Swipe up", gUp, "gesture_up", gUpApp) { scope.launch { prefs.setGestureSwipeUp(it) } }
            GestureActionRow("Swipe down", gDown, "gesture_down", gDownApp) { scope.launch { prefs.setGestureSwipeDown(it) } }
            GestureActionRow("Swipe left", gLeft, "gesture_left", gLeftApp) { scope.launch { prefs.setGestureSwipeLeft(it) } }
            GestureActionRow("Swipe right", gRight, "gesture_right", gRightApp) { scope.launch { prefs.setGestureSwipeRight(it) } }
        }

        // Cursor settings apply to both modes (the cursor shows in both).
        SectionCard(title = "Cursor") {
            ToggleRowWithDesc(
                label = "Custom cursor on external display",
                desc = "Render an amber cursor on the external display at the current pointer location. Useful when the OEM doesn't render a system cursor for injected mouse events.",
                key = PreferencesRepository.Keys.ENABLE_MOUSE_HOVER,
                default = true,
            )
            MediaCursorHideSection()
        }
        }

        // Pinned Back button — always visible at the bottom (with a small gap
        // above the edge), full width of the cards, taller tap target. Returns
        // to the interface the user came from.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PhoneSurfaceElevated)
                .border(1.dp, PhoneAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .clickable { onBack() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "\u2190 Back",
                color = PhoneOnSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CompactDpiField(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = if (enabled) PhoneOnSurface else PhoneOnSurfaceMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(PhoneAccent),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        ),
        modifier = Modifier.width(64.dp),
        decorationBox = { inner ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        1.dp,
                        PhoneOnSurfaceMuted.copy(alpha = if (enabled) 0.5f else 0.25f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) { inner() }
        },
    )
}

// ── Color-tuning value field helpers ────────────────────────────────────────
// The 14-value tuning vector mixes units, so the manual field formats/parses per
// family: gamma (idx 5) is a raw exponent; the signed shifts/offsets (temp, tint,
// black level, RGB offsets) are a % of range with 0 = neutral; everything else is
// a multiplier shown as % where 1.0 = 100%. The editable text holds the NUMBER
// only (no sign forced on positives) — the "%" is a separate suffix label.
private val TUNE_SIGNED = setOf(3, 4, 6, 11, 12, 13)

private fun tuneFieldText(index: Int, value: Float, range: ClosedFloatingPointRange<Float>): String =
    when {
        index == 5 -> "%.2f".format(value)
        index in TUNE_SIGNED -> {
            val end = if (range.endInclusive != 0f) range.endInclusive else 1f
            Math.round(value / end * 100f).toString()
        }
        else -> Math.round(value * 100f).toString()
    }

private fun tuneSuffix(index: Int): String = if (index == 5 || index in TUNE_SIGNED) "" else "%"

/** Friendly, value-dependent label for the direction-based biases. Temperature
 *  and Tint read as a direction word + relative magnitude (0–100) — e.g.
 *  "Temperature — Warm (40)". NO Kelvin: this control is a relative red/blue bias,
 *  not a calibrated color temperature, so a K number would be fabricated. Other
 *  params keep their plain label. */
private fun tuneDynamicLabel(base: String, index: Int, value: Float, range: ClosedFloatingPointRange<Float>): String {
    if (index != 3 && index != 4) return base
    val end = if (range.endInclusive != 0f) range.endInclusive else 1f
    val mag = Math.abs(Math.round(value / end * 100f))
    val dir = when {
        mag == 0 -> "Neutral"
        index == 3 -> if (value > 0) "Warm ($mag)" else "Cool ($mag)"
        else -> if (value > 0) "Green ($mag)" else "Magenta ($mag)"
    }
    return "$base — $dir"
}

/** Inverse of [tuneFieldText]: the typed number → the raw vals[index] value, or
 *  null when the text isn't a parseable number yet (mid-type). */
private fun tuneParse(index: Int, text: String, range: ClosedFloatingPointRange<Float>): Float? {
    val f = text.trim().toFloatOrNull() ?: return null
    return when {
        index == 5 -> f
        index in TUNE_SIGNED -> {
            val end = if (range.endInclusive != 0f) range.endInclusive else 1f
            f / 100f * end
        }
        else -> f / 100f
    }
}

/** Compact editable value field for a color-tuning slider. Two-way bound: a slider
 *  drag updates the number (while the field isn't focused), and typing a value
 *  moves the slider — but only once the typed number is a complete, in-range value,
 *  so the slider doesn't flail on partial input. Phone-side, so it opens the system
 *  keyboard (never the relay). On blur the text canonicalises to the live value. */
@Composable
private fun TuneValueField(
    index: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    dim: Float,
    onValue: (Float) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(tuneFieldText(index, value, range)) }
    // Track the slider value while the field ISN'T being edited — a drag updates
    // the number without clobbering the user mid-type.
    LaunchedEffect(value, focused) { if (!focused) text = tuneFieldText(index, value, range) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { raw ->
                // digits, one leading '-', one '.'
                val filtered = raw.filterIndexed { i, c ->
                    c.isDigit() || (c == '-' && i == 0) || (c == '.' && !raw.take(i).contains('.'))
                }.take(7)
                text = filtered
                val parsed = tuneParse(index, filtered, range)
                if (parsed != null && parsed >= range.start && parsed <= range.endInclusive) {
                    onValue(parsed)
                }
            },
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = if (enabled) PhoneAccentSoft else PhoneOnSurfaceMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(PhoneAccent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = if (index == 5) androidx.compose.ui.text.input.KeyboardType.Decimal
                    else androidx.compose.ui.text.input.KeyboardType.Number,
            ),
            modifier = Modifier
                .width(52.dp)
                .onFocusChanged { fs ->
                    if (focused && !fs.isFocused) {
                        text = tuneFieldText(index, value.coerceIn(range.start, range.endInclusive), range)
                    }
                    focused = fs.isFocused
                },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            1.dp,
                            PhoneOnSurfaceMuted.copy(alpha = if (enabled) 0.5f else 0.25f),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { inner() }
            },
        )
        val sfx = tuneSuffix(index)
        if (sfx.isNotEmpty()) {
            Spacer(Modifier.width(3.dp))
            Text(sfx, color = PhoneOnSurfaceMuted.copy(alpha = dim), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
/**
 * Display subpage — opened from the display-info pill on the trackpad / remote /
 * air-mouse interface. Holds external-display tuning: aspect ratio now, with
 * color tuning to follow. Full-screen overlay with a pinned "← Back" at the
 * bottom (same position/style as the button-actions page's Back).
 */
@Composable
private fun rememberLiveExternalDisplayId(): Int? {
    val context = LocalContext.current
    var detectedId by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(context) {
        val manager = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
            as android.hardware.display.DisplayManager
        fun rescan() {
            detectedId = runCatching {
                manager.displays.firstOrNull {
                    com.portalpad.app.DisplayUtil.isRealExternalDisplay(it)
                }?.displayId
            }.getOrNull()
        }
        rescan()
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = rescan()
            override fun onDisplayRemoved(displayId: Int) = rescan()
            override fun onDisplayChanged(displayId: Int) = rescan()
        }
        manager.registerDisplayListener(listener, null)
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    return detectedId
}

@Composable
fun DisplaySubpage(onBack: () -> Unit) {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val aspect by app.prefs.aspectRatio.collectAsState(initial = "AUTO")
    val dpi by app.prefs.displayDpi.collectAsState(initial = 0)
    val screenSizePct by app.prefs.screenSizePct.collectAsState(initial = 100)
    val context = androidx.compose.ui.platform.LocalContext.current
    val externalDisplayId by app.externalDisplayId.collectAsState()
    val physicalExternalDisplayId by app.physicalExternalDisplayId.collectAsState()
    val detectedDisplayId = rememberLiveExternalDisplayId()
    val effectiveDisplayId =
        physicalExternalDisplayId ?: externalDisplayId ?: detectedDisplayId
    // Detected external-display width (for the desktop/mobile DPI threshold hint).
    val externalWidthPx = remember(effectiveDisplayId) {
        effectiveDisplayId?.let {
            val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager
            dm.getDisplay(it)?.let { d ->
                android.util.DisplayMetrics().also { m -> d.getRealMetrics(m) }.widthPixels
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(PhoneBackground),
    ) {
        PageScrollChevrons(scrollState)
        PageScrollbar(scrollState)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
                .padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Display",
                color = PhoneAccent,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            SectionCard(title = "Display pipeline") {
                val mirrorOn = app.prefs.panelSystemMirror.collectAsState(initial = false).value
                val mirrorConnected = effectiveDisplayId != null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "System mirror (experimental)",
                        color = PhoneOnSurface,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                    androidx.compose.material3.Switch(
                        checked = mirrorOn,
                        onCheckedChange = { scope.launch { app.prefs.setPanelSystemMirror(it) } },
                    )
                }
                MirrorStatusStrip(mirrorOn = mirrorOn, connected = mirrorConnected)
                Text(
                    "Carries the external display's picture through the system compositor instead of an app-overlay window, so it no longer goes black when a USB or permission dialog appears, and some DRM video that otherwise wouldn't play — like Netflix or Prime Video — now does.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Trade-off: color tuning and the performance overlay's FPS stats aren't available in this mode. Screen size and Side Mode work in both modes.\nTurn OFF for color tuning and the full performance overlay.",
                    color = PhoneOnSurfaceDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                val forceSeparateSubpage by app.prefs.forceSeparateDisplay.collectAsState(initial = false)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            "Force separate display",
                            color = PhoneOnSurface,
                        )
                        Text(
                            "Prefer separate (extended) desktop display on Pixel and recover automatically if Android falls back to mirroring.",
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = forceSeparateSubpage,
                        onCheckedChange = { scope.launch { app.prefs.setForceSeparateDisplay(it) } },
                        modifier = Modifier.testTag("display_subpage_force_separate_switch"),
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = PhonePrimary,
                            checkedThumbColor = PhonePrimaryBright,
                            uncheckedTrackColor = PhoneSurfaceElevated,
                            uncheckedThumbColor = PhoneOnSurfaceDim,
                        ),
                    )
                }
            }
            SectionCard(title = "Mirroring keep-awake") {
                val keepAwakeMode by app.prefs.mirrorKeepAwakeMode.collectAsState(initial = MirrorKeepAwakeMode.OFF)
                Text(
                    "Choose how your phone screen stays awake while mirroring to an external display, so screen timeout does not turn off the external picture.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MirrorKeepAwakeMode.entries.forEach { mode ->
                        val selected = keepAwakeMode == mode
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) PhonePrimary.copy(alpha = 0.2f) else PhoneSurfaceElevated,
                                )
                                .border(
                                    1.dp,
                                    if (selected) PhonePrimaryBright else PhonePrimaryDim,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable {
                                    scope.launch { app.prefs.setMirrorKeepAwakeMode(mode) }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("keep_awake_option_${mode.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    mode.title,
                                    color = if (selected) PhoneAccent else PhoneOnSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    mode.description,
                                    color = PhoneOnSurfaceMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "${mode.title} selected",
                                    tint = PhonePrimaryBright,
                                )
                            }
                        }
                    }
                }
            }
            SectionCard(title = "Display DPI") {
                // In an app-owned session with Shizuku stopped, DPI can't be
                // reconfigured (reconfigure needs the shell identity), so flag it
                // rather than let the slider look silently broken. Screen Size
                // still works without Shizuku (it's app-side GL scaling).
                val appOwnedSession by app.sessionAppOwned.collectAsState(initial = false)
                val shizukuStatus by app.shizuku.status.collectAsState(
                    initial = com.portalpad.app.shizuku.ShizukuManager.Status.NOT_RUNNING,
                )
                if (appOwnedSession &&
                    shizukuStatus == com.portalpad.app.shizuku.ShizukuManager.Status.NOT_RUNNING
                ) {
                    Text(
                        "DPI changes need Shizuku connected — restart Shizuku to adjust it. " +
                            "Screen Size below works without it.",
                        color = PhoneAccent,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                val connected = effectiveDisplayId != null
                val dpiAlpha = if (connected) 1f else 0.4f
                // Detected density of the external display — the Auto value and the
                // manual-field starting value. 213 fallback if implausible.
                val detectedDpi = remember(effectiveDisplayId) {
                    runCatching {
                        val id = effectiveDisplayId ?: return@runCatching 213
                        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                        val d = dm.getDisplay(id) ?: return@runCatching 213
                        val m = android.util.DisplayMetrics().also { d.getRealMetrics(it) }
                        m.densityDpi.takeIf { it in 120..480 } ?: 213
                    }.getOrDefault(213)
                }
                val sliderValue = (if (dpi == 0) detectedDpi else dpi).toFloat().coerceIn(100f, 640f)

                var dpiText by remember(dpi, detectedDpi, connected) {
                    mutableStateOf(
                        when {
                            dpi != 0 -> dpi.toString()
                            connected -> detectedDpi.toString()
                            else -> "Auto"
                        }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (dpi == 0) "Display DPI (Auto):" else "Display DPI:",
                        color = PhoneOnSurfaceMuted.copy(alpha = dpiAlpha),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    CompactDpiField(value = dpiText, enabled = connected) { raw ->
                        val digits = raw.filter { it.isDigit() }.take(3)
                        dpiText = digits
                        digits.toIntOrNull()?.let { v ->
                            if (v in 80..640) scope.launch { app.prefs.setDisplayDpi(v) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Reset to Auto",
                        color = PhoneAccent.copy(alpha = dpiAlpha),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = connected) { scope.launch { app.prefs.setDisplayDpi(0) } }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Text(
                    if (connected) "Auto uses the display's detected density ($detectedDpi)."
                    else "Connect a display to adjust.",
                    color = PhoneOnSurfaceMuted.copy(alpha = dpiAlpha),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = sliderValue,
                    onValueChange = { scope.launch { app.prefs.setDisplayDpi(it.toInt()) } },
                    valueRange = 100f..640f,
                    enabled = connected,
                    colors = sliderColors(),
                )
                val widthForCalc = externalWidthPx ?: 1920
                val thresholdDpi = ((widthForCalc / 1280f) * 160f).toInt().coerceIn(161, 639)
                Text(
                    "Controls UI scale on the external display (not the dock or top bar).\n" +
                        "Adjusts live as you change it — you may need to reopen an app to see it fully update. Saves automatically.\n" +
                        "\n" +
                        "• Lower DPI (100 - $thresholdDpi) = bigger desktop-style layouts (Chrome shows a tab strip).\n" +
                        "• Higher DPI (${thresholdDpi + 1} - 640) = mobile-style apps with smaller UI.\n",
                    color = PhoneOnSurfaceMuted.copy(alpha = dpiAlpha),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard(title = "Aspect ratio") {
                Text(
                    "Sets the shape of the external display. Auto uses the external display's native resolution. Other ratios fit inside the panel — the unused area shows as black bars.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                val options = listOf(
                    "AUTO" to "Auto (Detected)",
                    "4:3" to "4:3",
                    "16:9" to "16:9",
                    "16:10" to "16:10",
                    "21:9" to "21:9 (ultrawide)",
                    "21:10" to "21:10 (ultrawide)",
                    "32:9" to "32:9 (super ultrawide)",
                    "32:10" to "32:10 (super ultrawide)",
                )
                val currentValue = if (aspect.isBlank()) "AUTO" else aspect
                val currentLabel = options.firstOrNull { it.first == currentValue }?.second
                    ?: "Auto (Detected)"
                var expanded by remember { mutableStateOf(false) }
                com.portalpad.app.ui.common.AttachedDropdownBox(
                    expanded = expanded,
                    onDismiss = { expanded = false },
                    modifier = Modifier.fillMaxWidth(),
                    anchor = { opensUp ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(PhoneSurfaceElevated)
                                .border(1.dp, PhonePrimaryDim, RoundedCornerShape(12.dp))
                                .clickable { expanded = true }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                currentLabel,
                                color = PhoneOnSurface,
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                if (opensUp) Icons.Filled.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = "Choose aspect ratio",
                                tint = PhoneOnSurfaceMuted,
                            )
                        }
                    },
                ) {
                    options.forEachIndexed { i, (value, label) ->
                        if (i > 0) HorizontalDivider(color = PhonePrimaryDim.copy(alpha = 0.5f))
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    color = if (value == currentValue) PhoneAccent else PhoneOnSurface,
                                    fontWeight = if (value == currentValue) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                expanded = false
                                scope.launch { app.prefs.setAspectRatio(value) }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Changes apply right away on the connected external display \u2014 no need to reconnect.",
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // No longer greyed under system mirror: since the 2026-07-23 projection
            // spike, screen size has a second engine there (compositor display
            // projection, DRM-safe) - the GL quad still serves the overlay path.
            SectionCard(title = "Screen size") {
                val connected = effectiveDisplayId != null
                val sizeAlpha = if (connected) 1f else 0.4f
                val sizeValue = screenSizePct.toFloat().coerceIn(50f, 100f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Screen size: $screenSizePct%",
                        color = PhoneOnSurfaceMuted.copy(alpha = sizeAlpha),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Reset to 100%",
                        color = PhoneAccent.copy(alpha = sizeAlpha),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = connected) { scope.launch { app.prefs.setScreenSizePct(100) } }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Drives the pref directly (like the DPI slider); the service
                // debounces the actual VD resize so dragging doesn't thrash it.
                Slider(
                    value = sizeValue,
                    onValueChange = { scope.launch { app.prefs.setScreenSizePct(it.toInt()) } },
                    valueRange = 50f..100f,
                    enabled = connected,
                    colors = sliderColors(),
                )
                Text(
                    if (connected)
                        "Shrinks the whole virtual screen within the panel \u2014 the unused area shows as black bars, " +
                            "so the screen gets smaller in view while the layout and sharpness stay the same. " +
                            "100% = full panel. Adjusts live; saves automatically."
                    else "Connect a display to adjust.",
                    color = PhoneOnSurfaceMuted.copy(alpha = sizeAlpha),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Side Mode — per-interface corner dock. Auto-scoped to the live input
            // interface (Air Mouse / Trackpad / Remote): the toggle, chosen corner,
            // and each corner's size are saved separately for whichever interface is
            // active. Screen size above stays the (centered) control; Side Mode owns
            // the corner sizes.
            run {
                val sideCfg by app.prefs.sideModeConfig
                    .collectAsState(initial = com.portalpad.app.data.SideModeConfig())
                val activeIface by app.prefs.activeSideInterface
                    .collectAsState(initial = com.portalpad.app.data.SideInterface.TRACKPAD)
                val si = sideCfg.forInterface(activeIface)
                val connected = effectiveDisplayId != null
                val ifaceName = when (activeIface) {
                    com.portalpad.app.data.SideInterface.AIR_MOUSE -> "Air Mouse"
                    com.portalpad.app.data.SideInterface.TRACKPAD -> "Trackpad"
                    com.portalpad.app.data.SideInterface.REMOTE -> "Remote"
                }
                val cornerName = when (si.corner) {
                    com.portalpad.app.data.SideCorner.TL -> "top-left"
                    com.portalpad.app.data.SideCorner.TR -> "top-right"
                    com.portalpad.app.data.SideCorner.BL -> "bottom-left"
                    com.portalpad.app.data.SideCorner.BR -> "bottom-right"
                }
                // Un-greyed with Screen size above - same second engine.
                SectionCard(title = "Side Mode") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Dock to a corner — $ifaceName",
                                color = PhoneOnSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Saved separately for each input interface.",
                                color = PhoneOnSurfaceMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = si.enabled,
                            enabled = connected,
                            onCheckedChange = { on ->
                                scope.launch {
                                    app.prefs.setSideModeConfig(
                                        sideCfg.withInterface(activeIface, si.copy(enabled = on)),
                                    )
                                }
                            },
                        )
                    }
                    if (si.enabled && connected) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Corner",
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                com.portalpad.app.data.SideCorner.TL,
                                com.portalpad.app.data.SideCorner.TR,
                                com.portalpad.app.data.SideCorner.BL,
                                com.portalpad.app.data.SideCorner.BR,
                            ).forEach { corner ->
                                val selected = si.corner == corner
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (selected) PhoneAccent.copy(alpha = 0.22f)
                                            else Color.White.copy(alpha = 0.06f),
                                        )
                                        .border(
                                            1.dp,
                                            if (selected) PhoneAccent else PhoneOnSurfaceMuted.copy(alpha = 0.3f),
                                            RoundedCornerShape(10.dp),
                                        )
                                        .clickable {
                                            scope.launch {
                                                app.prefs.setSideModeConfig(
                                                    sideCfg.withInterface(activeIface, si.copy(corner = corner)),
                                                )
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    SideCornerGlyph(corner = corner, selected = selected)
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        val cornerSize = si.sizeFor(si.corner)
                        Text(
                            "Corner size: $cornerSize%",
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = cornerSize.toFloat().coerceIn(40f, 90f),
                            onValueChange = { v ->
                                scope.launch {
                                    app.prefs.setSideModeConfig(
                                        sideCfg.withInterface(activeIface, si.withSize(si.corner, v.toInt())),
                                    )
                                }
                            },
                            valueRange = 40f..90f,
                            colors = sliderColors(),
                        )
                        Text(
                            "Docks $ifaceName's screen into the $cornerName corner at this size. " +
                                "Switching interface restores that interface\u2019s own corner and size. " +
                                "Adjusts live; saves automatically.",
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (connected)
                                "Off \u2014 screen stays centered at the Screen size above. Turn on to dock it into a corner."
                            else "Connect a display to use Side Mode.",
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Color tuning — full control set grouped into collapsible sections,
            // applied to the glasses via the GPU pipeline. Values persist and are
            // re-applied on reconnect. Greyed/disabled when the pipeline is off.
            run {
                val gpuOn by app.prefs.gpuColorPipeline.collectAsState(initial = true)
                val mirrorOn by app.prefs.panelSystemMirror.collectAsState(initial = false)
                // Color tuning needs the GL shader. Unlike screen size (which got a
                // second engine via setDisplayProjection), the display-token COLOR
                // transform is NOT reachable on this ROM: setDisplayColorTransform
                // throws NoSuchMethod at every signature, and Samsung exposes only
                // LAYER-level setColorTransform, not a display-token one (proven by
                // SH's 2026-07-23 method dump). So tuning is GPU-pipeline-only, and
                // the card greys under system mirror exactly as before.
                val tuningActive = gpuOn && !mirrorOn
                val savedTuning by app.prefs.colorTuning.collectAsState(initial = "")
                val initial = remember(savedTuning) { com.portalpad.app.presentation.GlColorRenderer.parseValues(savedTuning) }
                // 14 values: 0 brightness,1 contrast,2 saturation,3 temp,4 tint,
                // 5 gamma,6 blackLevel,7 whitePoint,8 rGain,9 gGain,10 bGain,
                // 11 rOffset,12 gOffset,13 bOffset.
                val vals = remember(savedTuning) {
                    mutableStateListOf<Float>().apply { initial.forEach { add(it) } }
                }
                var live by remember { mutableStateOf(true) }
                var status by remember { mutableStateOf("Adjust to tune the external display. Settings are saved and re-applied on reconnect.") }

                fun matrix() = com.portalpad.app.presentation.GlColorRenderer.matrixFromValues(vals.toFloatArray())
                fun gamma() = vals[5]
                fun persist() {
                    scope.launch { app.prefs.setColorTuning(vals.joinToString(",")) }
                }
                fun apply() {
                    scope.launch {
                        val mtx = matrix(); val g = gamma()
                        val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val svc = com.portalpad.app.service.PortalPadForegroundService.instance
                            if (svc == null) "FAIL: service not running."
                            else runCatching { svc.applyGlassesColorMatrix(mtx, g) }.getOrElse { "call failed: ${it.message}" }
                        }
                        status = out
                    }
                }
                fun maybeLive() { if (live && tuningActive) { apply(); persist() } }

                SectionCard(title = "Color tuning", disabled = mirrorOn) {
                    Text(
                        "Tunes the external display color (not the phone), via a GPU color pass. Not available while System mirror is on. Settings are saved and re-applied automatically when you reconnect the external display.",
                        color = PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("GPU color pipeline", color = PhoneOnSurface)
                            Text(
                                "Required for color tuning. Turn off if the display stutters on slower devices (disables color). Applies on reconnect.",
                                color = PhoneOnSurfaceMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = gpuOn,
                            onCheckedChange = { scope.launch { app.prefs.setGpuColorPipeline(it) } },
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    val dim = if (gpuOn) 1f else 0.4f

                    // Live preview — placed right under the GPU pipeline toggle (it
                    // depends on it: disabled when the pipeline is off) so the user
                    // sets how changes apply before touching the sliders below.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Live preview", color = PhoneOnSurface.copy(alpha = dim))
                            Text(
                                "Preview changes on the external display as you adjust. Tap Apply (near the bottom) to save them.",
                                color = PhoneOnSurfaceMuted.copy(alpha = dim),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = live, enabled = gpuOn,
                            onCheckedChange = { live = it },
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    // One slider bound to vals[index], with an editable value field
                    // (see TuneValueField / tuneFieldText for the per-family units).
                    val tuneSlider: @Composable (String, Int, ClosedFloatingPointRange<Float>) -> Unit =
                        { label, index, range ->
                            val clamped = vals[index].coerceIn(range.start, range.endInclusive)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    tuneDynamicLabel(label, index, clamped, range),
                                    color = PhoneOnSurface.copy(alpha = dim),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TuneValueField(
                                    index = index,
                                    value = clamped,
                                    range = range,
                                    enabled = gpuOn,
                                    dim = dim,
                                    onValue = { vals[index] = it; maybeLive() },
                                )
                            }
                            androidx.compose.material3.Slider(
                                value = clamped,
                                onValueChange = { vals[index] = it; maybeLive() },
                                valueRange = range,
                                enabled = gpuOn,
                                colors = sliderColors(),
                            )
                        }

                    // Collapsible section header with a chevron that flips up when
                    // expanded, down when collapsed. Tap toggles. Defaults: Basic
                    // open, the rest collapsed.
                    val section: @Composable (String, Boolean, @Composable () -> Unit) -> Unit =
                        { title, defaultOpen, content ->
                            var open by remember { mutableStateOf(defaultOpen) }
                            val rot by animateFloatAsState(if (open) 180f else 0f, label = "chev")
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { open = !open }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    title,
                                    color = PhoneAccent.copy(alpha = dim),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.Default.ExpandMore,
                                    contentDescription = if (open) "Collapse" else "Expand",
                                    tint = PhoneOnSurfaceMuted.copy(alpha = dim),
                                    modifier = Modifier.rotate(rot),
                                )
                            }
                            if (open) {
                                Column(Modifier.padding(start = 4.dp, bottom = 4.dp)) { content() }
                            }
                            HorizontalDivider(color = PhoneSurfaceElevated, thickness = 1.dp)
                        }

                    section("Basic", true) {
                        tuneSlider("Brightness", 0, 0.5f..1.5f)
                        tuneSlider("Contrast", 1, 0.5f..1.5f)
                        tuneSlider("Saturation", 2, 0f..2f)
                    }
                    section("White balance", false) {
                        tuneSlider("Temperature", 3, -0.3f..0.3f)
                        tuneSlider("Tint", 4, -0.3f..0.3f)
                    }
                    section("Tone", false) {
                        tuneSlider("Gamma", 5, 0.5f..2.2f)
                        tuneSlider("Black level", 6, -0.2f..0.2f)
                        tuneSlider("White point", 7, 0.8f..1.2f)
                    }
                    section("Channels", false) {
                        tuneSlider("Red gain", 8, 0.5f..1.5f)
                        tuneSlider("Green gain", 9, 0.5f..1.5f)
                        tuneSlider("Blue gain", 10, 0.5f..1.5f)
                        tuneSlider("Red offset", 11, -0.2f..0.2f)
                        tuneSlider("Green offset", 12, -0.2f..0.2f)
                        tuneSlider("Blue offset", 13, -0.2f..0.2f)
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val btn: @Composable RowScope.(String, () -> Unit) -> Unit = { label, onTap ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(PhoneSurfaceElevated.copy(alpha = dim))
                                    .border(1.dp, PhoneAccent.copy(alpha = 0.45f * dim), RoundedCornerShape(12.dp))
                                    .clickable(enabled = gpuOn) { onTap() }
                                    .padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(label, color = PhoneOnSurface.copy(alpha = dim), fontWeight = FontWeight.SemiBold) }
                        }
                        btn("Apply") { apply(); persist() }
                        btn("Reset") {
                            val d = com.portalpad.app.presentation.GlColorRenderer.DEFAULTS
                            for (i in vals.indices) vals[i] = d[i]
                            apply()
                            scope.launch { app.prefs.setColorTuning("") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            PerformanceOverlaySection(app, scope, mirrorOn = app.prefs.panelSystemMirror.collectAsState(initial = false).value)

            // Page-wide reset: return all Display *appearance* settings to their
            // defaults in one tap — DPI (Auto), aspect (AUTO), screen size (100%),
            // Side Mode, color tuning + GPU pipeline. Intentionally does NOT touch
            // the System-mirror mode toggle or the Performance-overlay rows (those
            // are modes/diagnostics, not appearance).
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(PhoneSurfaceElevated)
                    .border(1.dp, PhoneAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .clickable {
                        scope.launch {
                            app.prefs.setDisplayDpi(0)
                            app.prefs.setAspectRatio("AUTO")
                            app.prefs.setScreenSizePct(100)
                            app.prefs.setSideModeConfig(com.portalpad.app.data.SideModeConfig())
                            app.prefs.setColorTuning("")
                            app.prefs.setGpuColorPipeline(true)
                        }
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Reset all display settings",
                    color = PhoneOnSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // Pinned "← Back" — same position/style as the button-actions page.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PhoneSurfaceElevated)
                .border(1.dp, PhoneAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .clickable { onBack() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "\u2190 Back",
                color = PhoneOnSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun PerformanceOverlaySection(
    app: PortalPadApp,
    scope: kotlinx.coroutines.CoroutineScope,
    mirrorOn: Boolean = false,
) {
    val enabled by app.prefs.bool(PreferencesRepository.Keys.HUD_ENABLED, false)
        .collectAsState(initial = false)
    val corner by app.prefs.string(PreferencesRepository.Keys.HUD_CORNER, "top_right")
        .collectAsState(initial = "top_right")
    val showFps by app.prefs.bool(PreferencesRepository.Keys.HUD_SHOW_FPS, true)
        .collectAsState(initial = true)
    val showFrameTime by app.prefs.bool(PreferencesRepository.Keys.HUD_SHOW_FRAMETIME, true)
        .collectAsState(initial = true)
    val showMode by app.prefs.bool(PreferencesRepository.Keys.HUD_SHOW_MODE, true)
        .collectAsState(initial = true)
    val showDropped by app.prefs.bool(PreferencesRepository.Keys.HUD_SHOW_DROPPED, true)
        .collectAsState(initial = true)

    SectionCard(title = "Performance overlay") {
        Text(
            "A small FPS / frame-time readout drawn on the external display, for spotting " +
                "stutter. Delivered-fps is measured from the GL pipeline; it reads 0 if the " +
                "GPU color pipeline is off.",
            color = PhoneOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (mirrorOn) {
            Text(
                "System mirror is on: the overlay shows the resolution and refresh rate. " +
                    "Frame-rate stats (fps, draw time, frames behind) need overlay mode.",
                color = PhoneOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Show overlay",
                color = PhoneOnSurface,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = { v ->
                    scope.launch { app.prefs.setBool(PreferencesRepository.Keys.HUD_ENABLED, v) }
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = PhonePrimary,
                    checkedThumbColor = PhonePrimaryBright,
                    uncheckedTrackColor = PhoneSurfaceElevated,
                    uncheckedThumbColor = PhoneOnSurfaceDim,
                ),
            )
        }
        if (enabled) {
            Text("Position", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
            val corners = listOf(
                "top_left" to "Top-left",
                "top_right" to "Top-right",
                "bottom_left" to "Bottom-left",
                "bottom_right" to "Bottom-right",
            )
            val currentCornerLabel = corners.firstOrNull { it.first == corner }?.second ?: "Top-right"
            var cornerMenuOpen by remember { mutableStateOf(false) }
            com.portalpad.app.ui.common.AttachedDropdownBox(
                expanded = cornerMenuOpen,
                onDismiss = { cornerMenuOpen = false },
                modifier = Modifier.fillMaxWidth(),
                anchor = { opensUp ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(PhoneSurfaceElevated)
                            .border(1.dp, PhonePrimaryDim, RoundedCornerShape(12.dp))
                            .clickable { cornerMenuOpen = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            currentCornerLabel,
                            color = PhoneOnSurface,
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (opensUp) Icons.Filled.ArrowDropUp else Icons.Default.ArrowDropDown,
                            contentDescription = "Choose overlay position",
                            tint = PhoneOnSurfaceMuted,
                        )
                    }
                },
            ) {
                corners.forEachIndexed { i, (value, label) ->
                    if (i > 0) HorizontalDivider(color = PhonePrimaryDim.copy(alpha = 0.5f))
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                color = if (value == corner) PhonePrimaryBright else PhoneOnSurface,
                                fontWeight = if (value == corner) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            cornerMenuOpen = false
                            scope.launch { app.prefs.setString(PreferencesRepository.Keys.HUD_CORNER, value) }
                        },
                    )
                }
            }
            Text("Show", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
            val mirrorOn by app.prefs.panelSystemMirror.collectAsState(initial = false)
            // Active mode (res·Hz) DOES show under mirror, so it stays enabled.
            // fps / frame time / dropped can't be measured in mirror — grey them
            // (disabled color) while keeping each toggle's REAL saved state, so the
            // user sees what they'll get back when mirror is off (we don't force
            // them off).
            HudShowRow("Active mode (resolution · Hz)", showMode) { v ->
                scope.launch { app.prefs.setBool(PreferencesRepository.Keys.HUD_SHOW_MODE, v) }
            }
            HudShowRow("FPS (delivered)", showFps, enabled = !mirrorOn) { v ->
                scope.launch { app.prefs.setBool(PreferencesRepository.Keys.HUD_SHOW_FPS, v) }
            }
            HudShowRow("Frame time (GL draw+swap)", showFrameTime, enabled = !mirrorOn) { v ->
                scope.launch { app.prefs.setBool(PreferencesRepository.Keys.HUD_SHOW_FRAMETIME, v) }
            }
            HudShowRow("Dropped / behind vsync", showDropped, enabled = !mirrorOn) { v ->
                scope.launch { app.prefs.setBool(PreferencesRepository.Keys.HUD_SHOW_DROPPED, v) }
            }
        }
    }
}

@Composable
private fun HudShowRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = if (enabled) PhoneOnSurface else PhoneOnSurfaceDim,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = PhonePrimary,
                checkedThumbColor = PhonePrimaryBright,
                uncheckedTrackColor = PhoneSurfaceElevated,
                uncheckedThumbColor = PhoneOnSurfaceDim,
            ),
        )
    }
}

/**
 * A labelled slider for a Float preference. Drags update local state for
 * smooth feedback; the value is committed to prefs on release
 * (onValueChangeFinished) so we don't write to DataStore on every pixel.
 */
@Composable
private fun SpeedSlider(
    label: String,
    desc: String,
    key: androidx.datastore.preferences.core.Preferences.Key<Float>,
    default: Float,
    range: ClosedFloatingPointRange<Float>,
) {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val stored by app.prefs.float(key, default).collectAsState(initial = default)
    var local by remember(stored) { mutableFloatStateOf(stored) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(label, color = PhoneOnSurface, style = MaterialTheme.typography.bodyLarge)
                Text(desc, color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                Text(
                    leadInBold(String.format("Default: %.1f×", default)),
                    color = PhoneOnSurfaceDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                String.format("%.1f×", local),
                color = PhonePrimaryBright,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { scope.launch { app.prefs.setFloat(key, local) } },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = PhonePrimaryBright,
                activeTrackColor = PhonePrimary,
                inactiveTrackColor = PhoneSurfaceElevated,
            ),
        )
    }
}

@Composable
private fun WorkspaceTab(
    onPickDock: () -> Unit,
    onOpenWallpaperPicker: () -> Unit = {},
    onOpenFolderManager: () -> Unit = {},
    onOpenDockCustomization: () -> Unit = {},
    onOpenTopBarCustomization: () -> Unit = {},
    onOpenFolderWindowCustomization: () -> Unit = {},
    onOpenWidgetOverlayCustomization: () -> Unit = {},
    scrollToTopSignal: Int = 0,
) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val dockCfg by prefs.dockConfig.collectAsState(initial = DockConfig())
    val topBarCfg by prefs.topBarConfig.collectAsState(initial = com.portalpad.app.data.TopBarConfig())
    val widgetOverlayCfg by prefs.widgetOverlayConfig.collectAsState(initial = com.portalpad.app.data.WidgetOverlayConfig())
    val dockEnabled by prefs.bool(PreferencesRepository.Keys.DOCK_ENABLED, default = true)
        .collectAsState(initial = true)
    val wallpaperCanvasUltrawide = remember {
        runCatching {
            val dm = app.getSystemService(android.hardware.display.DisplayManager::class.java)
            val ext = dm?.displays?.firstOrNull { com.portalpad.app.DisplayUtil.isRealExternalDisplay(it) }
            if (ext != null) {
                val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") ext.getRealMetrics(it) }
                com.portalpad.app.data.Wallpaper.isUltrawideCanvas(m.widthPixels, m.heightPixels)
            } else false
        }.getOrDefault(false)
    }
    val currentWallpaper by prefs.homeWallpaperFor(wallpaperCanvasUltrawide)
        .collectAsState(initial = com.portalpad.app.data.Wallpaper.effectiveDefault(wallpaperCanvasUltrawide))

    TabBody(scrollToTopSignal = scrollToTopSignal) {
        // One-time discovery card for the widget overlay — shown until dismissed
        // (persisted). Chosen over defaulting the Home button to Widget Overlay,
        // which would hijack the most important control on a fresh install for an
        // untested feature. Points the user at the assignment instead.
        val nudgeSeen by prefs.bool(PreferencesRepository.Keys.WIDGET_OVERLAY_NUDGE_SEEN)
            .collectAsState(initial = true)
        if (!nudgeSeen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PhoneAccent.copy(alpha = 0.12f))
                    .border(1.dp, PhoneAccent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Default.Widgets, contentDescription = null, tint = PhoneAccent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("New: Widget Overlay", color = PhoneOnSurface,
                        fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Put Android widgets on your external display. Open Widget Overlay from the app drawer, or assign it to a Home or Back button (Controls tab) to summon it — then add widgets right there.",
                        color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    // Compact "Got it" — a clickable Text, not a Material TextButton, so it
                    // doesn't carry the 48dp min-height that made the box feel airy.
                    Text(
                        "Got it",
                        color = PhonePrimaryBright,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                scope.launch {
                                    prefs.setBool(PreferencesRepository.Keys.WIDGET_OVERLAY_NUDGE_SEEN, true)
                                }
                            }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
        // Home backdrop — opens a separate picker page (kept off this page so it
        // doesn't crowd the Workspace). Shows the current selection as a subtitle.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, PhoneSurfaceElevated, RoundedCornerShape(16.dp))
                .clickable { onOpenWallpaperPicker() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Wallpaper, contentDescription = null, tint = PhoneAccent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Home backdrop", color = PhoneOnSurface, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "External display wallpaper — ${currentWallpaper.label}",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Open home backdrop picker", tint = PhoneOnSurfaceMuted)
        }

        SectionCard(title = "Display mode (beta)", tightSpacing = true) {
            // One bullet per mode, both always visible, replacing a paragraph plus a
            // swapping per-mode text under the dropdown. Both modes on screen at
            // once is what lets you compare before choosing; the dynamic text could
            // only describe the mode you had already picked.
            // No "Requires Shizuku or Root" in prose: RequirementBadge below the
            // dropdown says it in the pill styling the rest of the page uses.
            Text(
                // Dim, one step below the bullets (SH's call): this line is a
                // lead-in, the bullets are the content, and same-color meant the
                // eye had no reason to land on the bullets first.
                "How apps fill the external display.",
                color = PhoneOnSurfaceDim, style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = androidx.compose.ui.text.buildAnnotatedString {
                    append("• ")
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("Theater mode") }
                    append(": one app at a time, filling the display. Best for video and reading.")
                },
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = androidx.compose.ui.text.buildAnnotatedString {
                    append("• ")
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("Desktop mode") }
                    append(
                        ": apps open in resizable, movable windows with a taskbar at the " +
                            "bottom and window controls at the top edge.",
                    )
                },
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            DisplayModeSelector()
            Text(
                text = androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("Note:") }
                    append(" Each floating desktop window has a system tab bar at the top, but its split button opens the split on the phone, not the external display (an Android limitation). Instead, hover to the top of the display — top-center by default, or wherever you've set the top bar — to reveal PortalPad's top bar, then tap the grid icon to tile all windows evenly across the external display.")
                },
                color = PhoneDanger,
                style = MaterialTheme.typography.bodySmall,
            )
            ToggleRowWithDesc(
                label = "Open dock apps in a window",
                desc = "In desktop mode, tapping a dock app opens it as a window instead of filling the display.",
                key = PreferencesRepository.Keys.DOCK_OPENS_FREEFORM,
                default = true,
            )
            ToggleRowWithDesc(
                label = "Handle permission prompts on the phone",
                desc = "When an app opened on the external display asks for a permission (notifications, camera, etc.), Android shows the prompt there as an unusable black window. With this on, PortalPad closes the app, shows an Allow/Deny popup on the trackpad screen, applies your choices via Shizuku or Root, and reopens the app on the external display. Turn off to leave the system prompt as-is.",
                key = PreferencesRepository.Keys.PERM_DIALOG_KEEP_ON_EXTERNAL,
                default = true,
                requirement = Requirement.ROOT_OR_SHIZUKU,
            )
            ToggleRowWithDesc(
                label = "Closing a window also removes it from Recents",
                desc = "Closing a window — from Close all, Manage windows, PortalPad's window controls, or the window's own close (X) button — fully ends the task, so it no longer appears in the phone's Recents. When off, closed apps may remain as a Recents card.",
                key = PreferencesRepository.Keys.CLOSE_REMOVES_FROM_RECENTS,
                default = true,
                requirement = Requirement.ROOT_OR_SHIZUKU,
            )
            ToggleRowWithDesc(
                label = "Forward playing video on connect",
                desc = "When an app is actively playing video on the phone right before you connect an external display, PortalPad opens that app first on the external display and keeps playback going. Takes priority over 30-minute Auto-resume.",
                key = PreferencesRepository.Keys.FORWARD_VIDEO_ON_CONNECT,
                default = true,
                requirement = Requirement.ROOT_OR_SHIZUKU,
                testTag = "workspace_forward_video_toggle",
            )
            val maxWin by prefs.int(PreferencesRepository.Keys.MAX_WINDOWS, default = 5)
                .collectAsState(initial = 5)
            Text(
                "Maximum open windows: $maxWin",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Limits how many windows can be open at once for performance. At the limit, you'll be asked to close one before opening another.",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = maxWin.toFloat(),
                onValueChange = { n ->
                    scope.launch { prefs.setInt(PreferencesRepository.Keys.MAX_WINDOWS, n.toInt()) }
                },
                valueRange = 3f..6f,
                steps = 2,
                colors = sliderColors(),
            )
        }
        SectionCard(title = "Dock") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Show dock on external display",
                    color = PhoneOnSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = dockEnabled,
                    onCheckedChange = {
                        scope.launch {
                            prefs.setBool(PreferencesRepository.Keys.DOCK_ENABLED, it)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = PhonePrimary,
                        checkedThumbColor = PhonePrimaryBright,
                        uncheckedTrackColor = PhoneSurfaceElevated,
                        uncheckedThumbColor = PhoneOnSurfaceDim,
                    ),
                )
            }
        }
        SectionCard(title = "Dock contents") {
            ActionRow(
                label = "Manage dock apps (multi-select)",
                value = "${dockCfg.items.size} items",
                onClick = onPickDock,
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                label = "Manage dock folders",
                value = "${dockCfg.items.count { it is com.portalpad.app.data.DockItem.Folder }} folders",
                onClick = onOpenFolderManager,
            )
        }
        SectionCard(title = "External Display Appearance") {
            Text(
                "Customize the look and behavior of the dock, the top window bar, folder windows, and the widget overlay shown on the external display.",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                label = "Dock Customization",
                value = "Layout & style",
                onClick = onOpenDockCustomization,
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                label = "Top Bar Customization",
                value = "Position & style",
                onClick = onOpenTopBarCustomization,
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                label = "Folder Window Customization",
                value = "Background & labels",
                onClick = onOpenFolderWindowCustomization,
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                label = "Widget Overlay",
                value = "Widgets & background",
                onClick = onOpenWidgetOverlayCustomization,
            )
        }
        SectionCard(title = "App search") {
            ToggleRowWithDesc(
                label = "Use phone keyboard for dock search",
                desc = "When on, the dock app-search opens the phone keyboard relay so you type on your phone (reliable). When off, you type directly on the external-display search field. Voice search works either way.",
                key = PreferencesRepository.Keys.SEARCH_KEYBOARD_ON_PHONE,
                default = true,
            )
        }
        SectionCard(title = "Audio", tightSpacing = true) {
            val audioStatus by app.audioRouter.status.collectAsState()
            val selectedAudioMode by prefs.audioOutputMode.collectAsState(initial = AudioOutputMode.AUTO)
            LaunchedEffect(Unit) {
                app.audioRouter.refreshSnapshotOnly()
            }
            Text(
                "Forces the selected media audio output as soon as an external display connects. " +
                    "In Auto mode, audio plays on Bluetooth if connected (and shifts automatically if Bluetooth connects mid-session), " +
                    "then External display audio, and falls back to Phone audio last.",
                color = PhoneOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            WorkspaceAudioStatusCard(status = audioStatus)
            AudioOutputMode.entries.forEach { mode ->
                val selected = selectedAudioMode == mode
                val modeIcon = when (mode) {
                    AudioOutputMode.AUTO -> Icons.Default.GraphicEq
                    AudioOutputMode.BLUETOOTH -> Icons.Default.BluetoothAudio
                    AudioOutputMode.EXTERNAL_DISPLAY -> Icons.Default.Tv
                    AudioOutputMode.PHONE -> Icons.Default.SpeakerPhone
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (selected) PhonePrimary.copy(alpha = 0.16f) else PhoneSurfaceElevated.copy(alpha = 0.7f),
                        )
                        .border(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) PhonePrimary else PhonePrimaryDim.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(14.dp),
                        )
                        .clickable {
                            app.injector.buzz(longPress = false)
                            scope.launch {
                                prefs.setAudioOutputMode(mode)
                                if (audioStatus.externalDisplayActive) {
                                    app.audioRouter.reapplyRouting("ui-pick:${mode.id}")
                                }
                            }
                        }
                        .testTag("workspace_audio_mode_${mode.id}")
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = modeIcon,
                        contentDescription = null,
                        tint = if (selected) PhonePrimaryBright else PhoneOnSurfaceMuted,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = mode.title,
                            color = if (selected) PhoneOnSurface else PhoneOnSurface.copy(alpha = 0.92f),
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = mode.description,
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (selected) "Selected" else "Not selected",
                        tint = if (selected) PhonePrimaryBright else PhoneOnSurfaceDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            RequirementBadge(Requirement.ROOT_OR_SHIZUKU)
        }
        var showResetConfirm by remember { mutableStateOf(false) }
        ResetToDefaultsButton("Reset Workspace to Defaults") { showResetConfirm = true }
        if (showResetConfirm) {
            ResetConfirmDialog(
                title = "Reset Workspace to defaults?",
                message = "This resets the dock layout & style, top bar, folder windows, widget overlay background, video forwarding, audio routing, app-search, and wallpaper to their defaults. Your dock apps, folders, and widgets are kept.",
                onConfirm = {
                    showResetConfirm = false
                    scope.launch {
                        // Reset dock appearance/layout/behavior but PRESERVE items + folders.
                        val keptItems = dockCfg.items
                        prefs.setDockConfig(DockConfig().copy(items = keptItems))
                        prefs.setTopBarConfig(com.portalpad.app.data.TopBarConfig())
                        prefs.setFolderWindowConfig(com.portalpad.app.data.FolderWindowConfig())
                        // Widget overlay: reset the backdrop style but KEEP the
                        // added widgets (mirrors the dock-items rule — clearing
                        // them would also orphan their system bindings).
                        prefs.setWidgetOverlayConfig(
                            com.portalpad.app.data.WidgetOverlayConfig()
                                .copy(widgets = widgetOverlayCfg.widgets),
                        )
                        prefs.setBool(PreferencesRepository.Keys.DOCK_ENABLED, true)
                        prefs.setBool(PreferencesRepository.Keys.SEARCH_KEYBOARD_ON_PHONE, true)
                        prefs.setBool(PreferencesRepository.Keys.FORWARD_VIDEO_ON_CONNECT, true)
                        prefs.setAudioOutputMode(AudioOutputMode.AUTO)
                        prefs.resetHomeWallpapers()
                    }
                },
                onCancel = { showResetConfirm = false },
            )
        }
    }
}

@Composable
private fun WorkspaceAudioStatusCard(status: AudioStatusSnapshot) {
    val activeIcon = when (status.activeCategory) {
        AudioSinkCategory.BLUETOOTH -> Icons.Default.BluetoothAudio
        AudioSinkCategory.EXTERNAL_DISPLAY -> Icons.Default.Tv
        AudioSinkCategory.PHONE -> Icons.Default.SpeakerPhone
        null -> Icons.Default.GraphicEq
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PhoneSurfaceElevated)
            .border(1.dp, PhonePrimaryDim.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .testTag("workspace_audio_status_card")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = activeIcon,
                contentDescription = "Active audio route",
                tint = if (status.externalDisplayActive) PhonePrimaryBright else PhoneOnSurfaceMuted,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (status.externalDisplayActive) "Active media output" else "Audio route status",
                    color = PhoneOnSurfaceDim,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = status.activeRouteLabel,
                    color = PhoneOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (status.forcedByPrivilege) {
                Text(
                    text = "Enforced",
                    color = PhonePrimaryBright,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(PhonePrimary.copy(alpha = 0.22f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AudioSinkChip(
                label = status.bluetoothName ?: "Bluetooth off",
                available = status.hasBluetooth,
                active = status.externalDisplayActive && status.activeCategory == AudioSinkCategory.BLUETOOTH,
                modifier = Modifier.weight(1f),
            )
            AudioSinkChip(
                label = status.externalDisplayAudioName ?: "Display audio off",
                available = status.hasExternalDisplayAudio,
                active = status.externalDisplayActive && status.activeCategory == AudioSinkCategory.EXTERNAL_DISPLAY,
                modifier = Modifier.weight(1f),
            )
            AudioSinkChip(
                label = status.phoneAudioName ?: "Phone speaker",
                available = status.hasPhoneAudio,
                active = status.externalDisplayActive && status.activeCategory == AudioSinkCategory.PHONE,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AudioSinkChip(
    label: String,
    available: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        active -> PhonePrimary.copy(alpha = 0.24f)
        available -> PhoneSurface
        else -> PhoneSurface.copy(alpha = 0.55f)
    }
    val border = when {
        active -> PhonePrimary
        available -> PhonePrimaryDim.copy(alpha = 0.5f)
        else -> PhoneOnSurfaceDim.copy(alpha = 0.25f)
    }
    val fg = when {
        active -> PhonePrimaryBright
        available -> PhoneOnSurfaceMuted
        else -> PhoneOnSurfaceDim
    }
    Text(
        text = label,
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/** Dock Customization sub-page: layout + style, with a pinned bottom Back. */
@Composable
private fun DockCustomizationPage(onBack: () -> Unit) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val dockCfg by prefs.dockConfig.collectAsState(initial = DockConfig())
    val externalDisplayId by app.externalDisplayId.collectAsState()

    // Preview isolation: pin the dock visible on the external display while this page is
    // open (and the top bar's zone highlight stays off), so edits preview cleanly.
    androidx.compose.runtime.DisposableEffect(Unit) {
        app.setDockCustomizationActive(true)
        onDispose { app.setDockCustomizationActive(false) }
    }

    Box(Modifier.fillMaxSize().background(PhoneBackground)) {
        PageScrollChevrons(scrollState)
        PageScrollbar(scrollState)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
                .padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Dock Customization",
                color = PhoneOnSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            // External-display preview status hint (mirrors the folder-window page).
            Text(
                if (externalDisplayId == null)
                    "Connect your external display to preview the dock there."
                else
                    "Previewing your dock on the external display — adjust the style below to see it update live.",
                color = if (externalDisplayId != null) PhoneOnSurfaceMuted else PhoneWarning,
                style = MaterialTheme.typography.bodySmall,
            )
            SectionCard(title = "Visibility") {
                Text(
                    if (dockCfg.autoHideAfterSec == 0) "Always visible" else "Auto-hide after: ${dockCfg.autoHideAfterSec}s",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = dockCfg.autoHideAfterSec.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setDockConfig(dockCfg.copy(autoHideAfterSec = n.toInt())) } },
                    valueRange = 0f..30f, steps = 29, colors = sliderColors(),
                )
                ToggleRowWithDesc(
                    label = "Always show dock on wallpaper",
                    desc = "In desktop mode, when nothing is open (wallpaper " +
                        "showing), keep the dock pinned instead of auto-hiding — so you " +
                        "don't have to summon it to launch your first app. The moment a " +
                        "window opens, the dock returns to the auto-hide timer above. " +
                        "Minimized windows still count as wallpaper. Only applies in " +
                        "desktop mode.",
                    key = PreferencesRepository.Keys.ALWAYS_SHOW_DOCK_ON_WALLPAPER,
                    default = true,
                )
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            "Hide dock while scrolling",
                            color = PhoneOnSurface,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Hide the dock when scrolling down inside an app or on the wallpaper screen, and bring it back when scrolling up.",
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            leadInBold("Default: On"),
                            color = PhoneOnSurfaceDim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = dockCfg.hideOnScroll,
                        onCheckedChange = { enabled ->
                            scope.launch { prefs.setDockConfig(dockCfg.copy(hideOnScroll = enabled)) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = PhonePrimary,
                            checkedThumbColor = PhonePrimaryBright,
                            uncheckedTrackColor = PhoneSurfaceElevated,
                            uncheckedThumbColor = PhoneOnSurfaceDim,
                        ),
                        modifier = Modifier.testTag("dock_hide_on_scroll_switch"),
                    )
                }
            }
            SectionCard(title = "Reveal zone") {
                Text(
                    "When the dock is auto-hidden, move the cursor into this band at the dock's edge to bring it back.",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Text("Detection zone thickness: ${dockCfg.revealZoneHeightPx}px", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = dockCfg.revealZoneHeightPx.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setDockConfig(dockCfg.copy(revealZoneHeightPx = n.toInt())) } },
                    valueRange = 1f..120f, steps = 0, colors = sliderColors(),
                )
                Text("Detection zone width: ${dockCfg.revealZoneWidthPct}%", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = dockCfg.revealZoneWidthPct.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setDockConfig(dockCfg.copy(revealZoneWidthPct = n.toInt())) } },
                    valueRange = 10f..100f, steps = 89, colors = sliderColors(),
                )
                Text(
                    "The detection zone is highlighted on the external display while this page is open, so you can see what you're adjusting. It disappears when you go back.",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Text("Highlight color", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                ColorPointEditor("Zone highlight", dockCfg.zoneHighlightColor) { c ->
                    scope.launch { prefs.setDockConfig(dockCfg.copy(zoneHighlightColor = c)) }
                }
            }
            CollapsibleCard(title = "Dock layout") {
                Text(
                    "Dock size is controlled only here — it is independent of the Display DPI setting.",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            "Show dock app titles",
                            color = PhoneOnSurface,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Turn off to hide titles under dock icons, slimming down the dock height and compacting the clock and status icons for extra screen space.",
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = dockCfg.showLabels,
                        onCheckedChange = { show ->
                            scope.launch { prefs.setDockConfig(dockCfg.copy(showLabels = show)) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = PhonePrimary,
                            checkedThumbColor = PhonePrimaryBright,
                            uncheckedTrackColor = PhoneSurfaceElevated,
                            uncheckedThumbColor = PhoneOnSurfaceDim,
                        ),
                        modifier = Modifier.testTag("dock_show_labels_switch"),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("Icon size: ${dockCfg.iconSizeDp} dp", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = dockCfg.iconSizeDp.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setDockConfig(dockCfg.copy(iconSizeDp = n.toInt())) } },
                    valueRange = 48f..120f, colors = sliderColors(),
                )
                Text(
                    when {
                        !dockCfg.showLabels -> "App titles: hidden (compact dock & status chrome)"
                        dockCfg.labelSizeSp == 0 -> "Labels: hidden"
                        else -> "Label text size: ${dockCfg.labelSizeSp} sp"
                    },
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                )
                if (dockCfg.showLabels) {
                    Slider(
                        value = dockCfg.labelSizeSp.toFloat(),
                        onValueChange = { n -> scope.launch { prefs.setDockConfig(dockCfg.copy(labelSizeSp = n.toInt())) } },
                        valueRange = 0f..18f, colors = sliderColors(),
                    )
                }
                Text("Dock width: ${dockCfg.dockWidthPct}% of display", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = dockCfg.dockWidthPct.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setDockConfig(dockCfg.copy(dockWidthPct = n.toInt())) } },
                    valueRange = 40f..100f, colors = sliderColors(),
                )
                // Reset to defaults across all dock customization sections above
                // (visibility, reveal zone, and layout) — keeps style, clock, apps.
                androidx.compose.material3.TextButton(
                    onClick = {
                        scope.launch {
                            prefs.setDockConfig(
                                dockCfg.copy(
                                    showLabels = true,
                                    iconSizeDp = 60,
                                    labelSizeSp = 13,
                                    dockWidthPct = 92,
                                    autoHideAfterSec = 7,
                                    revealZoneHeightPx = 3,
                                    revealZoneWidthPct = 50,
                                    zoneHighlightColor = 0x66FFC83A,
                                ),
                            )
                        }
                    },
                ) {
                    Text("Reset to defaults")
                }
            }
            SectionCard(title = "Dock style") {
                BarStyleControls(
                    style = dockCfg.style,
                    onChange = { s -> scope.launch { prefs.setDockConfig(dockCfg.copy(style = s)) } },
                )
                MatchAllSurfacesButton(sourceLabel = "dock", sourceStyle = dockCfg.style)
            }
            SectionCard(title = "Clock") {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                // Live sample of the current format selection.
                val sample = remember(dockCfg.clockTimeFormat, dockCfg.clockDateFormat) {
                    val is24 = when (dockCfg.clockTimeFormat) {
                        com.portalpad.app.data.ClockTimeFormat.H24 -> true
                        com.portalpad.app.data.ClockTimeFormat.H12 -> false
                        com.portalpad.app.data.ClockTimeFormat.AUTO -> android.text.format.DateFormat.is24HourFormat(ctx)
                    }
                    val tf = java.text.SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", java.util.Locale.getDefault())
                    val df: java.text.DateFormat = when (dockCfg.clockDateFormat) {
                        com.portalpad.app.data.ClockDateFormat.MDY -> java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.getDefault())
                        com.portalpad.app.data.ClockDateFormat.DMY -> java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                        com.portalpad.app.data.ClockDateFormat.YMD -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        com.portalpad.app.data.ClockDateFormat.AUTO -> android.text.format.DateFormat.getDateFormat(ctx)
                    }
                    val d = java.util.Date()
                    "${tf.format(d)}  |  ${df.format(d)}"
                }
                Text("Preview: $sample", color = PhoneOnSurface, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("Time format", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                LabeledDropdown(
                    current = dockCfg.clockTimeFormat,
                    options = listOf(
                        com.portalpad.app.data.ClockTimeFormat.AUTO to "Auto (follow system)",
                        com.portalpad.app.data.ClockTimeFormat.H24 to "24-hour (13:30)",
                        com.portalpad.app.data.ClockTimeFormat.H12 to "12-hour (1:30 PM)",
                    ),
                    onSelect = { v -> scope.launch { prefs.setDockConfig(dockCfg.copy(clockTimeFormat = v)) } },
                    contentDescription = "Choose time format",
                )
                Spacer(Modifier.height(8.dp))
                Text("Date format", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                LabeledDropdown(
                    current = dockCfg.clockDateFormat,
                    options = listOf(
                        com.portalpad.app.data.ClockDateFormat.AUTO to "Auto (follow system)",
                        com.portalpad.app.data.ClockDateFormat.MDY to "MM/DD/YYYY",
                        com.portalpad.app.data.ClockDateFormat.DMY to "DD/MM/YYYY",
                        com.portalpad.app.data.ClockDateFormat.YMD to "YYYY-MM-DD",
                    ),
                    onSelect = { v -> scope.launch { prefs.setDockConfig(dockCfg.copy(clockDateFormat = v)) } },
                    contentDescription = "Choose date format",
                )
            }
            // Main reset for the whole Dock Customization page — restores dock
            // layout, style, and clock to defaults, while KEEPING your dock apps
            // and folders.
            var showDockResetConfirm by remember { mutableStateOf(false) }
            ResetToDefaultsButton("Reset Dock Customization to Defaults") { showDockResetConfirm = true }
            if (showDockResetConfirm) {
                ResetConfirmDialog(
                    title = "Reset dock customization to defaults?",
                    message = "This resets the dock layout, style, and clock to their defaults. Your dock apps and folders are kept.",
                    onConfirm = {
                        showDockResetConfirm = false
                        scope.launch {
                            val keptItems = dockCfg.items
                            prefs.setDockConfig(com.portalpad.app.data.DockConfig().copy(items = keptItems))
                        }
                    },
                    onCancel = { showDockResetConfirm = false },
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PhoneSurfaceElevated)
                .border(1.dp, PhoneAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .clickable { onBack() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("\u2190 Back", color = PhoneOnSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Top Bar Customization sub-page: position/width/zone/duration + style, with a pinned bottom Back. */
@Composable
private fun TopBarCustomizationPage(onBack: () -> Unit) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val topBarCfg by prefs.topBarConfig.collectAsState(initial = com.portalpad.app.data.TopBarConfig())
    val externalDisplayId by app.externalDisplayId.collectAsState()

    // Show the detection-zone highlight on the external display while this page is open;
    // hide it on leave. Preview-only.
    androidx.compose.runtime.DisposableEffect(Unit) {
        app.setZonePreviewActive(true)
        onDispose { app.setZonePreviewActive(false) }
    }

    Box(Modifier.fillMaxSize().background(PhoneBackground)) {
        PageScrollChevrons(scrollState)
        PageScrollbar(scrollState)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
                .padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Top Bar Customization",
                color = PhoneOnSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            // External-display preview status hint (mirrors the folder-window page).
            Text(
                if (externalDisplayId == null)
                    "Connect your external display to preview the top bar there."
                else
                    "Previewing the top bar on the external display — adjust the style below to see it update live.",
                color = if (externalDisplayId != null) PhoneOnSurfaceMuted else PhoneWarning,
                style = MaterialTheme.typography.bodySmall,
            )
            SectionCard(title = "Visibility") {
                Text(
                    if (topBarCfg.autoHideAfterSec == 0) "Always visible" else "Auto-hide after: ${topBarCfg.autoHideAfterSec}s",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = topBarCfg.autoHideAfterSec.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setTopBarConfig(topBarCfg.copy(autoHideAfterSec = n.toInt())) } },
                    valueRange = 0f..30f, steps = 29, colors = sliderColors(),
                )
            }
            SectionCard(title = "Reveal zone") {
                Text(
                    "When the top bar is auto-hidden, move the cursor to the top edge of the display to bring it back.",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Text("Detection zone height: ${topBarCfg.revealZoneHeightPx}px", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = topBarCfg.revealZoneHeightPx.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setTopBarConfig(topBarCfg.copy(revealZoneHeightPx = n.toInt())) } },
                    valueRange = 1f..120f, steps = 0, colors = sliderColors(),
                )
                Text("Detection zone width: ${topBarCfg.revealZoneWidthPct}%", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = topBarCfg.revealZoneWidthPct.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setTopBarConfig(topBarCfg.copy(revealZoneWidthPct = n.toInt())) } },
                    valueRange = 10f..100f, steps = 89, colors = sliderColors(),
                )
                Text(
                    "The detection zone is highlighted on the external display while this page is open, so you can see what you're adjusting. It disappears when you go back.",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Text("Highlight color", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                ColorPointEditor("Zone highlight", topBarCfg.zoneHighlightColor) { c ->
                    scope.launch { prefs.setTopBarConfig(topBarCfg.copy(zoneHighlightColor = c)) }
                }
            }
            CollapsibleCard(title = "Position & size") {
                Text("Position", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val positions = listOf(
                        com.portalpad.app.data.TopBarPosition.LEFT to "Left",
                        com.portalpad.app.data.TopBarPosition.CENTER to "Center",
                        com.portalpad.app.data.TopBarPosition.RIGHT to "Right",
                    )
                    positions.forEach { (pos, label) ->
                        val selected = topBarCfg.position == pos
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) PhoneAccent else PhoneSurfaceElevated)
                                .clickable { scope.launch { prefs.setTopBarConfig(topBarCfg.copy(position = pos)) } }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = if (selected) PhoneBackground else PhoneOnSurface, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text("Width: ${topBarCfg.widthPct}% of display", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = topBarCfg.widthPct.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setTopBarConfig(topBarCfg.copy(widthPct = n.toInt())) } },
                    valueRange = 30f..100f, steps = 69, colors = sliderColors(),
                )
                Text("Bar size: ${topBarCfg.sizePct}% (icons + height)", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = topBarCfg.sizePct.coerceIn(60, 160).toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setTopBarConfig(topBarCfg.copy(sizePct = n.toInt())) } },
                    valueRange = 60f..160f, steps = 99, colors = sliderColors(),
                )
                // Reset to defaults across all top-bar customization sections above
                // (visibility, reveal zone, position & size) — keeps the top-bar style.
                androidx.compose.material3.TextButton(
                    onClick = {
                        scope.launch {
                            prefs.setTopBarConfig(
                                topBarCfg.copy(
                                    position = com.portalpad.app.data.TopBarPosition.CENTER,
                                    widthPct = 70,
                                    revealZoneHeightPx = 3,
                                    revealZoneWidthPct = 50,
                                    zoneHighlightColor = 0x66FFC83A,
                                    autoHideAfterSec = 0,
                                ),
                            )
                        }
                    },
                ) {
                    Text("Reset to defaults")
                }
            }
            CollapsibleCard(title = "Top bar style") {
                BarStyleControls(
                    style = topBarCfg.style,
                    onChange = { s -> scope.launch { prefs.setTopBarConfig(topBarCfg.copy(style = s)) } },
                )
                MatchAllSurfacesButton(sourceLabel = "top bar", sourceStyle = topBarCfg.style)
            }
            // Main reset for the whole Top Bar Customization page — restores
            // position/size, detection zone, and top-bar style to defaults.
            var showTopBarResetConfirm by remember { mutableStateOf(false) }
            ResetToDefaultsButton("Reset Top Bar Customization to Defaults") { showTopBarResetConfirm = true }
            if (showTopBarResetConfirm) {
                ResetConfirmDialog(
                    title = "Reset top bar customization to defaults?",
                    message = "This resets the top bar position, size, detection zone, and style to their defaults.",
                    onConfirm = {
                        showTopBarResetConfirm = false
                        scope.launch {
                            prefs.setTopBarConfig(com.portalpad.app.data.TopBarConfig())
                        }
                    },
                    onCancel = { showTopBarResetConfirm = false },
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PhoneSurfaceElevated)
                .border(1.dp, PhoneAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .clickable { onBack() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("\u2190 Back", color = PhoneOnSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Folder Window Customization sub-page (global): background gradient + app-label
 *  color for all folder windows. Pinned bottom Back. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FolderWindowCustomizationPage(onBack: () -> Unit) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val fwCfg by prefs.folderWindowConfig.collectAsState(initial = com.portalpad.app.data.FolderWindowConfig())
    val dockCfg by prefs.dockConfig.collectAsState(initial = com.portalpad.app.data.DockConfig())
    val hasFolder = dockCfg.items.any { it is com.portalpad.app.data.DockItem.Folder }
    val externalDisplayId by app.externalDisplayId.collectAsState()

    // Preview the live folder-window style on the external display while this page is open
    // (opens the first folder there). Cleared on leave.
    androidx.compose.runtime.DisposableEffect(Unit) {
        app.setFolderWindowPreviewActive(true)
        onDispose { app.setFolderWindowPreviewActive(false) }
    }

    Box(Modifier.fillMaxSize().background(PhoneBackground)) {
        PageScrollChevrons(scrollState)
        PageScrollbar(scrollState)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
                .padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Folder Window Customization",
                color = PhoneOnSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Applies to all folder windows (the popup shown when you open a dock folder).",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
            // External-display preview status hint.
            val previewHint = when {
                externalDisplayId == null -> "Connect your external display to preview the folder window there."
                !hasFolder -> "Create a folder first to preview the folder window on your external display."
                else -> "Previewing your first folder on the external display — adjust the style below to see it update live."
            }
            Text(
                previewHint,
                color = if (hasFolder && externalDisplayId != null) PhoneOnSurfaceMuted else PhoneWarning,
                style = MaterialTheme.typography.bodySmall,
            )
            SectionCard(title = "Background gradient") {
                BarStyleControls(
                    style = fwCfg.background,
                    onChange = { s -> scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(background = s)) } },
                )
                MatchAllSurfacesButton(sourceLabel = "folder window", sourceStyle = fwCfg.background)
            }
            SectionCard(title = "App label color") {
                ColorPointEditor("Label color", fwCfg.labelColor) { c ->
                    scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(labelColor = c)) }
                }
            }
            CollapsibleCard(title = "Folder window layout") {
                // Lightweight sub-collapsibles so the (long) folder card breaks into
                // Grid / Window position / Labels instead of one tall scroll.
                val sub: @Composable (String, @Composable () -> Unit) -> Unit = { subTitle, subContent ->
                    var open by remember { mutableStateOf(false) }
                    val rot by animateFloatAsState(if (open) 180f else 0f, label = "fwSub")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { open = !open }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(subTitle, color = PhoneAccent, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = if (open) "Collapse" else "Expand",
                            tint = PhoneOnSurfaceMuted,
                            modifier = Modifier.rotate(rot),
                        )
                    }
                    if (open) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { subContent() }
                    }
                    HorizontalDivider(color = PhoneSurfaceElevated, thickness = 1.dp)
                }
                sub("Grid") {
                Text(
                    "Apps per row: ${fwCfg.columns}",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = fwCfg.columns.coerceIn(1, 12).toFloat(),
                    onValueChange = { v -> scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(columns = v.toInt().coerceIn(1, 12))) } },
                    valueRange = 1f..12f,
                    steps = 10,
                    colors = sliderColors(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Rows before scrolling: ${fwCfg.maxRows}",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = fwCfg.maxRows.coerceIn(1, 12).toFloat(),
                    onValueChange = { v -> scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(maxRows = v.toInt().coerceIn(1, 12))) } },
                    valueRange = 1f..12f,
                    steps = 10,
                    colors = sliderColors(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Icon size: ${fwCfg.tileSizeDp}dp",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = fwCfg.tileSizeDp.coerceIn(40, 120).toFloat(),
                    onValueChange = { v -> scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(tileSizeDp = v.toInt().coerceIn(40, 120))) } },
                    valueRange = 40f..120f,
                    colors = sliderColors(),
                )
                Text(
                    "More columns / larger icons widen the window; it's capped to fit the display. Rows set how many show before the grid scrolls.",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(14.dp))
                // Icon alignment within the window (matters for a partial last row).
                Text("Icon alignment", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        com.portalpad.app.data.FolderGridAlign.LEFT to "Left",
                        com.portalpad.app.data.FolderGridAlign.CENTER to "Center",
                    ).forEach { (a, label) ->
                        val sel = fwCfg.gridAlign == a
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (sel) PhoneAccent else PhoneSurfaceElevated)
                                .clickable { scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(gridAlign = a)) } }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = if (sel) PhoneBackground else PhoneOnSurface, style = MaterialTheme.typography.bodyMedium) }
                    }
                }

                }
                sub("Window position") {
                // Horizontal then vertical placement on the display.
                Text("Window position", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        com.portalpad.app.data.FolderWindowPosition.LEFT to "Left",
                        com.portalpad.app.data.FolderWindowPosition.CENTER to "Center",
                        com.portalpad.app.data.FolderWindowPosition.RIGHT to "Right",
                    ).forEach { (p, label) ->
                        val sel = fwCfg.position == p
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (sel) PhoneAccent else PhoneSurfaceElevated)
                                .clickable { scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(position = p)) } }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = if (sel) PhoneBackground else PhoneOnSurface, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        com.portalpad.app.data.FolderWindowVPosition.TOP to "Top",
                        com.portalpad.app.data.FolderWindowVPosition.CENTER to "Center",
                        com.portalpad.app.data.FolderWindowVPosition.BOTTOM to "Bottom",
                    ).forEach { (p, label) ->
                        val sel = fwCfg.vPosition == p
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (sel) PhoneAccent else PhoneSurfaceElevated)
                                .clickable { scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(vPosition = p)) } }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = if (sel) PhoneBackground else PhoneOnSurface, style = MaterialTheme.typography.bodyMedium) }
                    }
                }

                }
                sub("Labels") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Show app names", color = PhoneOnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(
                        checked = fwCfg.showLabels,
                        onCheckedChange = { on -> scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(showLabels = on)) } },
                    )
                }
                if (fwCfg.showLabels) {
                    Text("Label size: ${fwCfg.labelSizeSp}sp", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = fwCfg.labelSizeSp.coerceIn(8, 20).toFloat(),
                        onValueChange = { v -> scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(labelSizeSp = v.toInt().coerceIn(8, 20))) } },
                        valueRange = 8f..20f,
                        colors = sliderColors(),
                    )
                }

                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.TextButton(
                    onClick = {
                        scope.launch {
                            val d = com.portalpad.app.data.FolderWindowConfig()
                            // Reset only the grid/layout fields; keep background + label color.
                            prefs.setFolderWindowConfig(
                                fwCfg.copy(
                                    columns = d.columns, maxRows = d.maxRows, tileSizeDp = d.tileSizeDp,
                                    gridAlign = d.gridAlign, showLabels = d.showLabels,
                                    labelSizeSp = d.labelSizeSp, position = d.position,
                                ),
                            )
                        }
                    },
                ) { Text("Reset grid layout", color = PhonePrimaryBright) }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PhoneSurfaceElevated)
                .border(1.dp, PhoneAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .clickable { onBack() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("\u2190 Back", color = PhoneOnSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ─────────────────────────── Tab 4: Display ───────────────────────────

@Composable
private fun DisplayTab(onOpenDisplaySettings: () -> Unit) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val serviceRunning by app.serviceRunning.collectAsState()
    val externalDisplayId by app.externalDisplayId.collectAsState()
    val context = LocalContext.current
    val physicalExternalDisplayId by app.physicalExternalDisplayId.collectAsState()
    val detectedDisplayId = rememberLiveExternalDisplayId()
    val labelDisplayId = physicalExternalDisplayId ?: externalDisplayId ?: detectedDisplayId
    val displayLabel = remember(labelDisplayId) {
        labelDisplayId?.let { id ->
            val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager
            val display = dm.getDisplay(id)
            if (display != null) {
                val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
                "${display.name.ifBlank { "External" }} (${metrics.widthPixels}×${metrics.heightPixels})"
            } else null
        }
    }

    TabBody {
        SectionCard(title = "Connected external display") {
            val displayConnected = labelDisplayId != null
            val displaySettingsAvailable = serviceRunning && displayConnected
            com.portalpad.app.ui.trackpad.DisplaySelectorPill(
                label = displayLabel ?: "No display connected",
                onClick = onOpenDisplaySettings,
                enabled = displaySettingsAvailable,
            )
            val statusMessage = when {
                !serviceRunning && !displayConnected ->
                    "Start the PortalPad service and connect an external display to access display settings."
                serviceRunning && !displayConnected ->
                    "Connect an external display to access display settings."
                !serviceRunning ->
                    "Start the PortalPad service to apply display changes live."
                else ->
                    "Tap the display above to open its settings."
            }
            var statusIsSingleLine by remember(statusMessage) { mutableStateOf(true) }
            Text(
                statusMessage,
                color = if (displaySettingsAvailable) {
                    PhoneSuccess.copy(alpha = 0.88f)
                } else {
                    PhoneOnSurfaceMuted
                },
                style = MaterialTheme.typography.bodySmall,
                textAlign = if (statusIsSingleLine) TextAlign.Center else TextAlign.Start,
                onTextLayout = { result ->
                    statusIsSingleLine = result.lineCount == 1
                },
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider(
                color = PhonePrimaryDim.copy(alpha = 0.45f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
            val forceSeparateDisplay by prefs.forceSeparateDisplay.collectAsState(initial = false)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        "Force separate display",
                        color = PhoneOnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Lock Pixel external display preference to Separate Display and automatically switch out of Mirror when connected.",
                        color = PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = forceSeparateDisplay,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            prefs.setForceSeparateDisplay(enabled)
                            if (enabled) {
                                val backend = app.activeBoundBackend
                                if (backend != null && backend.isReady) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        backend.setExternalDisplayConnectionPreference(
                                            com.portalpad.app.service.BoundShellBackend.DISPLAY_PREF_SEPARATE,
                                        )
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("display_tab_force_separate_switch"),
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = PhonePrimary,
                        checkedThumbColor = PhonePrimaryBright,
                        uncheckedTrackColor = PhoneSurfaceElevated,
                        uncheckedThumbColor = PhoneOnSurfaceDim,
                    ),
                )
            }
        }
        SectionCard(title = "Mirroring keep-awake") {
            Text(
                "Choose how your phone screen stays awake during display mirroring so screen timeout doesn't turn off the external display.",
                color = PhoneOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            val keepAwakeMode by prefs.mirrorKeepAwakeMode.collectAsState(initial = MirrorKeepAwakeMode.OFF)
            var keepAwakeExpanded by remember { mutableStateOf(false) }
            com.portalpad.app.ui.common.AttachedDropdownBox(
                expanded = keepAwakeExpanded,
                onDismiss = { keepAwakeExpanded = false },
                modifier = Modifier.fillMaxWidth(),
                anchor = { opensUp ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PhoneSurfaceElevated)
                            .border(1.dp, PhonePrimaryDim, RoundedCornerShape(10.dp))
                            .clickable { keepAwakeExpanded = true }
                            .padding(horizontal = 14.dp, vertical = 14.dp)
                            .testTag("display_tab_keep_awake_dropdown"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            keepAwakeMode.title,
                            color = PhoneOnSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (opensUp) Icons.Filled.ArrowDropUp
                            else Icons.Filled.ArrowDropDown,
                            contentDescription = "Open mirroring keep-awake menu",
                            tint = PhoneOnSurfaceMuted,
                        )
                    }
                },
            ) {
                MirrorKeepAwakeMode.entries.forEachIndexed { i, mode ->
                    if (i > 0) HorizontalDivider(color = PhonePrimaryDim.copy(alpha = 0.5f))
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(mode.title, color = PhoneOnSurface)
                                Text(
                                    mode.description,
                                    color = PhoneOnSurfaceMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        onClick = {
                            keepAwakeExpanded = false
                            scope.launch { prefs.setMirrorKeepAwakeMode(mode) }
                        },
                        modifier = Modifier.testTag("display_tab_keep_awake_${mode.id}"),
                    )
                }
            }
            Text(
                keepAwakeMode.description,
                color = PhoneOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SectionCard(title = "Lock screen") {
            Text(
                "Control whether PortalPad's interface can appear over your phone's lock screen.",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
            ToggleRowWithDesc(
                label = "Show controls over the lock screen",
                desc = "Let the PortalPad controls (trackpad / remote / air mouse) open over your phone's lock screen, so you can drive the external display without unlocking. Leaving PortalPad to other apps still requires unlocking.",
                key = PreferencesRepository.Keys.ALLOW_LOCKSCREEN,
                default = true,
            )
        }
        SectionCard(title = "App orientation") {
            Text(
                "Locks PortalPad's phone screens to a fixed orientation so they don't auto-rotate. Affects only PortalPad — never apps shown on the external display. Pick Auto-rotate if you use a tablet.",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            val orientationFlow = remember {
                prefs.string(PreferencesRepository.Keys.FORCE_ORIENTATION, OrientationLock.DEFAULT)
            }
            val orientation by orientationFlow.collectAsState(initial = OrientationLock.DEFAULT)
            var expanded by remember { mutableStateOf(false) }
            com.portalpad.app.ui.common.AttachedDropdownBox(
                expanded = expanded,
                onDismiss = { expanded = false },
                modifier = Modifier.fillMaxWidth(),
                anchor = { opensUp ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PhoneSurfaceElevated)
                            .border(1.dp, PhonePrimaryDim, RoundedCornerShape(10.dp))
                            .clickable { expanded = true }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            OrientationLock.labelFor(orientation),
                            color = PhoneOnSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (opensUp) Icons.Filled.ArrowDropUp
                            else Icons.Filled.ArrowDropDown,
                            contentDescription = "Open orientation menu",
                            tint = PhoneOnSurfaceMuted,
                        )
                    }
                },
            ) {
                OrientationLock.OPTIONS.forEachIndexed { i, (value, label) ->
                    if (i > 0) HorizontalDivider(color = PhonePrimaryDim.copy(alpha = 0.5f))
                    DropdownMenuItem(
                        text = { Text(label, color = PhoneOnSurface) },
                        onClick = {
                            expanded = false
                            scope.launch { prefs.setString(PreferencesRepository.Keys.FORCE_ORIENTATION, value) }
                        },
                    )
                }
            }
            Text(
                "Changes apply when you return to a PortalPad screen.",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ─────────────────────────── Tab 5: System ───────────────────────────

@Composable
private fun SystemTab(
    onAuthorizeShizuku: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onPickBackupFolder: () -> Unit,
    onBackupNow: () -> Unit,
    onRestoreFromFile: () -> Unit,
    onScheduleBackup: (dayOfWeek: Int, hour: Int) -> Unit,
) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val shizukuStatus by app.shizuku.status.collectAsState()
    val accessMode by prefs.accessMode.collectAsState(initial = AccessMode.NONE)

    val overlayGranted = rememberOnResume { android.provider.Settings.canDrawOverlays(it) }
    val notifAccess = rememberOnResume { app.media.hasNotificationAccess }

    val shizukuReady = shizukuStatus == ShizukuManager.Status.READY
    val rootReady = app.root.isReady
    val shizukuUserServiceReady by app.shizukuClickBackend.readyFlow.collectAsState()
    val rootUserServiceReady by app.rootClickBackend.readyFlow.collectAsState()

    // Popup state — which chip the user just tapped that isn't ready
    var notReadyPopup by remember { mutableStateOf<AccessMode?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    TabBody {
        SectionCard(title = "Privilege source") {
            Text(
                "How PortalPad performs elevated operations like launching apps on external displays, external-display / AR-glasses click routing, IME policy, and granting Android permissions.",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("Note: ") }
                    append("Root runs a privileged service (via libsu) with full parity to Shizuku — trusted virtual display, keyboard-on-external, IME policy, and in-process input injection. This path is confirmed working. Shizuku is the most broadly tested option, so it remains a reliable alternative.")
                },
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
            AccessModeSelector(
                accessMode = accessMode,
                shizukuReady = shizukuReady,
                rootReady = rootReady,
                onSelect = { mode ->
                    // Always save the preference. Help popup only appears when the
                    // mode isn't ready yet — even then it's just guidance, the
                    // selection is already persisted.
                    scope.launch { prefs.setAccessMode(mode) }
                    when (mode) {
                        AccessMode.NONE -> { /* nothing to set up */ }
                        AccessMode.SHIZUKU -> {
                            // Bind the UserService if Shizuku is ready. If not,
                            // show the helper popup tailored to the current state.
                            app.shizuku.refresh()
                            if (app.shizuku.isReady) {
                                app.shizukuClickBackend.bind()
                            } else {
                                notReadyPopup = mode
                            }
                        }
                        AccessMode.ROOT -> {
                            app.root.refresh()
                            if (app.root.isReady) {
                                app.root.grantInjectEvents()
                                app.rootClickBackend.bind()
                            }
                            // Always show the Root popup: when root isn't granted
                            // it guides Magisk/Shizuku; when it IS granted it notes
                            // that some features may still want Shizuku and offers
                            // to authorize it too.
                            notReadyPopup = mode
                        }
                    }
                },
            )
            val statusValue = when (accessMode) {
                AccessMode.NONE -> "None — select Shizuku or Root above"
                AccessMode.SHIZUKU -> when {
                    shizukuUserServiceReady -> "Shizuku — Full features available"
                    shizukuReady -> "Shizuku — Binding service…"
                    else -> "Shizuku — Not authorized"
                }
                AccessMode.ROOT -> when {
                    rootUserServiceReady -> "Root — Full features available"
                    rootReady -> "Root — Starting service…"
                    else -> "Root — Not granted"
                }
            }
            val statusOk = when (accessMode) {
                AccessMode.NONE -> false
                AccessMode.SHIZUKU -> shizukuReady
                AccessMode.ROOT -> rootReady
            }
            StatusRow(label = "Status", value = statusValue, ok = statusOk)
            if (accessMode == AccessMode.ROOT) {
                Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("Note: ") }
                        append("Root is confirmed working — the privileged service, virtual display, and input all run with full parity to Shizuku. Shizuku remains the most broadly tested path, so you can switch to it anytime if you prefer.")
                    },
                    color = PhoneOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, start = 20.dp),
                )
            }
        }

        SectionCard(
            title = "Floating bubble",
            pill = { PermissionPill("Overlay") },
        ) {
            Text(
                "Show a draggable bubble when you leave the app, so you can return to the trackpad with one tap.",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            ToggleRow(label = "Show floating bubble when backgrounded", key = PreferencesRepository.Keys.FLOATING_BUBBLE, default = true)
            FixRow(
                label = "Overlay permission",
                value = if (overlayGranted.value) "Granted" else "Tap to grant",
                onClick = onOpenOverlaySettings,
                needsFix = !overlayGranted.value,
            )
        }

        SectionCard(
            title = "Media controls",
            pill = { PermissionPill("Notification access") },
        ) {
            Text(
                "Play/pause, next, previous and FF/rew. Volume buttons need no permission. The rest works best when PortalPad has Notification Access — that's how it talks to the active media session.",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            FixRow(
                label = "Notification access",
                value = if (notifAccess.value) "Granted" else "Tap to grant",
                onClick = onOpenNotificationListenerSettings,
                needsFix = !notifAccess.value,
            )
        }

        // (Duplicate "Accessibility service" card that used to live here has
        // been removed — the canonical Accessibility card above already
        // exposes the same toggle and now also holds the "Keep accessibility
        // service alive" pref + auto-disable timer.)

        SectionCard(title = "Startup & timeout") {
            ToggleRowWithDesc(
                label = "Start PortalPad on boot",
                desc = "Auto-start the foreground service when your phone finishes booting. Notification permission may be required on Android 13+.",
                key = PreferencesRepository.Keys.START_ON_BOOT,
                default = false,
            )
            AutoDisableRow()
            RestoreLastSessionRow()
            ToggleRowWithDesc(
                label = "Offer to restore on reconnect",
                desc = "When you open the trackpad and there's a saved session, ask whether to restore it. Turn off to never be prompted — the \"Restore session\" button above still works manually.",
                key = PreferencesRepository.Keys.OFFER_RESTORE_ON_RECONNECT,
                default = true,
            )
        }

        // (Diagnostics crash-summary card removed — same information is
        // available through the Logs button at the top right, which
        // shows full logcat including any crash stack traces.)

        BackupRestoreSection(
            onPickBackupFolder = onPickBackupFolder,
            onBackupNow = onBackupNow,
            onRestoreFromFile = onRestoreFromFile,
            onScheduleBackup = onScheduleBackup,
        )

        FactoryResetRow()
    }

    // ─── Popup: chosen privilege source isn't ready ────────────────────
    // Tailored to the current sub-state so the primary action is always useful.
    notReadyPopup?.let { mode ->
        // Read Shizuku status FRESHLY when the popup opens, so on first selection
        // we land on the correct branch (e.g. "Authorize") instead of a stale
        // UNKNOWN that wrongly showed the "Open Shizuku" path until reopened.
        val freshShizukuStatus = remember(mode) {
            if (mode == AccessMode.SHIZUKU) app.shizuku.freshStatus() else shizukuStatus
        }
        val (title, msg, primaryLabel, primaryAction) = when (mode) {
            AccessMode.SHIZUKU -> when (freshShizukuStatus) {
                ShizukuManager.Status.NOT_INSTALLED -> Quad(
                    "Shizuku not installed",
                    "Shizuku is a separate app that grants PortalPad the privileges needed for external displays / AR glasses, IME policy, and launching apps on external displays. Install the recommended GitHub build (adds start-on-boot and a watchdog so it keeps running), then come back.",
                    "Open in GitHub",
                    {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/thedjchi/Shizuku"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { ctx.startActivity(intent) }
                        notReadyPopup = null
                    },
                )
                ShizukuManager.Status.NOT_RUNNING -> Quad(
                    "Shizuku not running",
                    "Shizuku is installed but its service isn't running. Open Shizuku, then start the service via ADB (instructions inside the app) or with wireless debugging. PortalPad will pick it up automatically when you return.",
                    "Open Shizuku",
                    {
                        val pm = ctx.packageManager
                        val launchIntent = pm.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        launchIntent?.let {
                            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { ctx.startActivity(it) }
                        }
                        notReadyPopup = null
                    },
                )
                ShizukuManager.Status.NEEDS_PERMISSION -> Quad(
                    "Authorize PortalPad",
                    "Shizuku is running. Tap Authorize to bring up the Android permission dialog so PortalPad can use it.",
                    "Authorize",
                    {
                        app.shizuku.requestPermission()
                        notReadyPopup = null
                    },
                )
                else -> Quad(
                    "Shizuku status unknown",
                    "Couldn't determine Shizuku's state. Try reopening Shizuku, then come back.",
                    "OK",
                    { notReadyPopup = null },
                )
            }
            AccessMode.ROOT -> if (rootReady) Quad(
                "Root active",
                "PortalPad is using root, with full parity to Shizuku — confirmed working. " +
                    "Shizuku is the most broadly tested path, so if you'd like you can also " +
                    "authorize it and PortalPad will use whichever you prefer.",
                "OK",
                { notReadyPopup = null },
            ) else Quad(
                "Root not granted",
                "PortalPad couldn't get superuser access. In the Magisk app, make sure " +
                    "Superuser is enabled and that PortalPad is allowed (open Magisk → Superuser, " +
                    "grant PortalPad). Then tap Request. If you'd rather not use root, you can use " +
                    "Shizuku instead.",
                "Request",
                {
                    Thread {
                        runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() }
                        app.root.refresh()
                        if (app.root.isReady) {
                            app.root.grantInjectEvents()
                            app.rootClickBackend.bind()
                        }
                    }.start()
                    notReadyPopup = null
                },
            )
            AccessMode.NONE -> Quad("", "", "", { notReadyPopup = null })
        }
        if (title.isNotEmpty()) {
            // For ROOT, offer a secondary "Authorize Shizuku" action so the user
            // can fall back to (or supplement with) Shizuku directly from here —
            // whether root is unavailable or just doesn't cover a feature.
            val authorizeShizuku: (() -> Unit)? = if (mode == AccessMode.ROOT) {
                {
                    // Use the SYNCHRONOUS status read — refresh() is async, so
                    // reading status.value on the next line was stale (UNKNOWN),
                    // which made this button appear to do nothing. freshStatus()
                    // returns the current state immediately so we branch right.
                    when (app.shizuku.freshStatus()) {
                        ShizukuManager.Status.NOT_INSTALLED -> {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/thedjchi/Shizuku"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { ctx.startActivity(intent) }
                        }
                        ShizukuManager.Status.READY -> {
                            // Already authorized — bind and switch the privilege
                            // source to Shizuku (flip the dropdown), since the
                            // user chose to use it.
                            app.shizukuClickBackend.bind()
                            scope.launch { prefs.setAccessMode(AccessMode.SHIZUKU) }
                        }
                        ShizukuManager.Status.NOT_RUNNING, ShizukuManager.Status.UNKNOWN -> {
                            val pm = ctx.packageManager
                            pm.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let {
                                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { ctx.startActivity(it) }
                            }
                        }
                        else -> {
                            app.shizuku.requestPermission()
                        }
                    }
                    notReadyPopup = null
                }
            } else null

            androidx.compose.material3.AlertDialog(
                modifier = Modifier.border(
                    1.dp,
                    com.portalpad.app.ui.theme.PhonePrimaryDim,
                    androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                ),
                onDismissRequest = { notReadyPopup = null },
                title = { Text(title) },
                text = { Text(msg) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = primaryAction) {
                        Text(primaryLabel)
                    }
                },
                dismissButton = {
                    androidx.compose.foundation.layout.Row {
                        if (authorizeShizuku != null) {
                            val shizukuLabel = if (app.shizuku.freshStatus() == ShizukuManager.Status.READY)
                                "Enable Shizuku" else "Authorize Shizuku"
                            androidx.compose.material3.TextButton(onClick = authorizeShizuku) {
                                Text(shizukuLabel)
                            }
                        }
                        androidx.compose.material3.TextButton(onClick = { notReadyPopup = null }) {
                            Text("Cancel")
                        }
                    }
                },
            )
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/**
 * Backup & Restore card. Lets the user pick a folder (any folder — Drive-synced,
 * Dropbox-synced, local, SD card), run a manual backup, restore from a JSON file
 * picked anywhere on the device, and schedule periodic backups.
 */
@Composable
private fun BackupRestoreSection(
    onPickBackupFolder: () -> Unit,
    onBackupNow: () -> Unit,
    onRestoreFromFile: () -> Unit,
    onScheduleBackup: (dayOfWeek: Int, hour: Int) -> Unit,
) {
    val app = PortalPadApp.instance
    val folderUri by app.prefs.rawDataStore.data.collectAsState(initial = null)
    val folderUriStr = folderUri?.get(PreferencesRepository.Keys.BACKUP_FOLDER_URI)
    val lastBackupMs = folderUri?.get(PreferencesRepository.Keys.BACKUP_LAST_SUCCESS_MS) ?: 0L
    val frequency = folderUri?.get(PreferencesRepository.Keys.BACKUP_FREQUENCY) ?: "off"
    val backupDay = folderUri?.get(PreferencesRepository.Keys.BACKUP_DAY) ?: 0
    val backupHour = folderUri?.get(PreferencesRepository.Keys.BACKUP_HOUR) ?: 3

    SectionCard(title = "Backup & restore") {
        Text(
            "Saves every PortalPad setting and dock layout as a JSON file. If you haven't picked a folder, backups land in Documents/PortalPad/ — visible in any file manager and synced by the Google Drive app.",
            color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
        )

        val folderLabel = if (folderUriStr != null) {
            val decoded = runCatching { android.net.Uri.decode(folderUriStr) }.getOrDefault(folderUriStr)
            decoded.substringAfterLast("/")
        } else "Documents/PortalPad (default)"
        FixRow(
            label = "Backup folder",
            value = folderLabel,
            onClick = onPickBackupFolder,
            needsFix = false,  // Default folder works fine; tap to override
        )

        if (lastBackupMs > 0) {
            StatusRow(label = "Last backup", value = formatTimeAgo(System.currentTimeMillis() - lastBackupMs), ok = true)
        } else {
            StatusRow(label = "Last backup", value = "Never", ok = false)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .weight(1f).height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PhonePrimary)
                    .clickable { onBackupNow() },
                contentAlignment = Alignment.Center,
            ) {
                Text("Back up now", color = PhoneOnSurface, fontWeight = FontWeight.SemiBold)
            }
            Box(
                Modifier
                    .weight(1f).height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PhoneSurfaceElevated)
                    .clickable { onRestoreFromFile() },
                contentAlignment = Alignment.Center,
            ) {
                Text("Restore", color = PhoneOnSurface, fontWeight = FontWeight.SemiBold)
            }
        }

        // Schedule on/off + day + hour
        Text(
            "Scheduled backups",
            color = PhoneOnSurface, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )

        val scheduleOn = frequency == "scheduled"
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (scheduleOn) "Scheduled" else "Off",
                color = PhoneOnSurface, modifier = Modifier.weight(1f),
            )
            Switch(
                checked = scheduleOn,
                onCheckedChange = { newOn ->
                    if (newOn) onScheduleBackup(backupDay, backupHour)
                    else onScheduleBackup(-1, 0)   // -1 = off
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = PhonePrimary,
                    checkedThumbColor = PhonePrimaryBright,
                    uncheckedTrackColor = PhoneSurfaceElevated,
                    uncheckedThumbColor = PhoneOnSurfaceDim,
                ),
            )
        }

        if (scheduleOn) {
            // Day-of-week selector
            Text(
                "Day",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            DaySelector(
                selected = backupDay,
                onSelect = { onScheduleBackup(it, backupHour) },
            )

            // Hour-of-day selector
            Text(
                "Time: ${formatHour(backupHour)}",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = backupHour.toFloat(),
                onValueChange = { onScheduleBackup(backupDay, it.toInt()) },
                valueRange = 0f..23f,
                steps = 22,
                colors = sliderColors(),
            )
        }
    }
}

/** Day-of-week chips: Every day / Mon / Tue / Wed / Thu / Fri / Sat / Sun. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DaySelector(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(
        0 to "Every",
        1 to "Mon",
        2 to "Tue",
        3 to "Wed",
        4 to "Thu",
        5 to "Fri",
        6 to "Sat",
        7 to "Sun",
    )
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEach { (id, lbl) ->
            val isSel = selected == id
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSel) PhonePrimary else PhoneSurfaceElevated)
                    .clickable { onSelect(id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    lbl,
                    color = if (isSel) PhoneOnSurface else PhoneOnSurfaceMuted,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun formatHour(h: Int): String {
    val twelve = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    val ampm = if (h < 12) "AM" else "PM"
    return "${twelve}:00 $ampm"
}

private fun formatTimeAgo(deltaMs: Long): String {
    val seconds = deltaMs / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "$seconds seconds ago"
        minutes < 60 -> "$minutes minute${if (minutes == 1L) "" else "s"} ago"
        hours < 24 -> "$hours hour${if (hours == 1L) "" else "s"} ago"
        days < 30 -> "$days day${if (days == 1L) "" else "s"} ago"
        else -> "More than a month ago"
    }
}

/**
 * Actionable "fix this permission" row. When [needsFix] is true the row pulses
 * amber and is clickable; when granted, it's a quiet green status.
 */
@Composable
private fun FixRow(label: String, value: String, onClick: () -> Unit, needsFix: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (needsFix) PhoneAccent.copy(alpha = 0.12f) else PhoneSurfaceElevated.copy(alpha = 0.5f))
            .clickable(enabled = needsFix) { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (needsFix) PhoneAccent else PhoneSuccess),
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = PhoneOnSurface, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                color = if (needsFix) PhoneAccent else PhoneOnSurfaceMuted,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (needsFix) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        if (needsFix) {
            Text("Fix", color = PhoneAccent, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Small pill rendering the permission requirement next to a section title. */
@Composable
private fun PermissionPill(label: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PhoneAccent.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label, color = PhoneAccent,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Reads a value once and again every time the host activity resumes. Lets a
 * Compose UI react when the user comes back from a system Settings screen
 * (overlay permission, accessibility) where they may have changed things.
 */
@Composable
private fun <T> rememberOnResume(read: (android.content.Context) -> T): State<T> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(read(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = read(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

// ─────────────────────────── Shared composables ───────────────────────────

/**
 * True scale-to-fit container: measures [content] at its natural size, then
 * uniformly scales it down (never up past 1x) so the whole thing fits the
 * available height without scrolling or clipping. Used by the Start page, which
 * should size to the display rather than scroll. Content is given the full width
 * at its natural height first, then scaled around the top-center so it stays put.
 *
 * Tradeoff: on very small screens or large font scales, tall content shrinks
 * (text included) rather than scrolling. That's the intended "fit" behavior.
 */
@Composable
private fun ScaleToFit(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.SubcomposeLayout(modifier.fillMaxSize()) { constraints ->
        val availH = constraints.maxHeight
        val availW = constraints.maxWidth
        val widthBounded = availW != Constraints.Infinity

        // Pass 1: measure at the available width to learn the natural height.
        val firstConstraints = Constraints(
            minWidth = 0,
            maxWidth = if (widthBounded) availW else Constraints.Infinity,
            minHeight = 0,
            maxHeight = Constraints.Infinity,
        )
        val firstPass = subcompose("content", content).map { it.measure(firstConstraints) }
        val contentW = firstPass.maxOfOrNull { it.width } ?: 0
        val naturalH = firstPass.maxOfOrNull { it.height } ?: 0

        // Scale factor: shrink only if taller than the available space; never enlarge.
        val scale = if (naturalH > 0 && availH != Constraints.Infinity)
            minOf(1f, availH.toFloat() / naturalH.toFloat()) else 1f

        val layoutW = if (widthBounded) availW else contentW
        val layoutH = if (availH != Constraints.Infinity) availH else naturalH

        // When we have to shrink, a UNIFORM down-scale would also shrink the width,
        // leaving side gaps. To keep the content edge-to-edge, re-measure it at a
        // width inflated by 1/scale, then place it scaled from the TOP-LEFT — so the
        // scaled-down result spans the full available width. (A pure visual column;
        // re-composing it for measurement has no side effects.)
        val placeables = if (scale < 1f && widthBounded) {
            val wideW = kotlin.math.ceil(availW / scale).toInt().coerceAtLeast(availW)
            val wideConstraints = Constraints(
                minWidth = 0, maxWidth = wideW, minHeight = 0, maxHeight = Constraints.Infinity,
            )
            subcompose("content-wide", content).map { it.measure(wideConstraints) }
        } else {
            firstPass
        }

        layout(layoutW, layoutH) {
            placeables.forEach { placeable ->
                placeable.placeRelativeWithLayer(0, 0) {
                    scaleX = scale
                    scaleY = scale
                    // Top-left origin: the (over-wide) content scales down to fill
                    // from the left edge to the right edge, anchored at the top.
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                }
            }
        }
    }
}

@Composable
private fun TabBody(
    spacing: androidx.compose.ui.unit.Dp = 14.dp,
    scrollToTopSignal: Int = 0,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal > 0) scrollState.animateScrollTo(0)
    }
    val thumbColor = PhoneOnSurfaceMuted.copy(alpha = 0.5f)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        // Subtle always-visible scrollbar on the right edge. Only paints a thumb
        // when the content actually overflows (maxValue > 0) — short screens that
        // fit show nothing. Tells the user "there's more below" without the
        // confusion of an invisible scroll region. NOT used on the Remote /
        // Trackpad / Air Mouse control surfaces (those don't use TabBody).
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    val max = scrollState.maxValue
                    if (max > 0) {
                        // Reserve a zone per end so the thumb stops clear of the
                        // overlaid PageScrollChevrons (SH).
                        val chevZone = 26.dp.toPx()
                        val trackTop = chevZone
                        val trackBottom = size.height - chevZone
                        val trackH = (trackBottom - trackTop).coerceAtLeast(1f)
                        val viewport = size.height
                        // Thumb height proportional to visible/total; clamped to a
                        // usable minimum so it's always grabbable-looking.
                        val total = viewport + max
                        val thumbH = (trackH * (viewport / total)).coerceIn(28.dp.toPx(), trackH)
                        val frac = scrollState.value.toFloat() / max.toFloat()
                        val thumbY = trackTop + (trackH - thumbH) * frac
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
        PageScrollChevrons(scrollState)
    }
}

/**
 * Copies the [sourceStyle]'s gradient COLORS (colorA, colorB, gradientType) to
 * the other two customizable surfaces (dock / top bar / folder window), so they
 * all match in one tap. Each surface keeps its OWN corner radius (their shapes
 * differ), so only the colors + gradient type are propagated. Confirms first.
 */
@Composable
private fun MatchAllSurfacesButton(
    sourceLabel: String,
    sourceStyle: com.portalpad.app.data.BarStyle,
) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    androidx.compose.material3.TextButton(onClick = { confirm = true }) {
        Text("Match all surfaces to this")
    }
    if (confirm) {
        ResetConfirmDialog(
            title = "Match all surfaces to the $sourceLabel?",
            message = "This copies the $sourceLabel's gradient colors and type to the dock, top bar, and folder window so they all match. Each keeps its own corner radius. You can still adjust each one afterward.",
            onConfirm = {
                confirm = false
                scope.launch {
                    fun apply(s: com.portalpad.app.data.BarStyle) = s.copy(
                        colorA = sourceStyle.colorA,
                        colorB = sourceStyle.colorB,
                        gradientType = sourceStyle.gradientType,
                    )
                    val dock = prefs.dockConfig.first()
                    prefs.setDockConfig(dock.copy(style = apply(dock.style)))
                    val top = prefs.topBarConfig.first()
                    prefs.setTopBarConfig(top.copy(style = apply(top.style)))
                    val fw = prefs.folderWindowConfig.first()
                    prefs.setFolderWindowConfig(fw.copy(background = apply(fw.background)))
                }
            },
            onCancel = { confirm = false },
        )
    }
}

/** Widget Overlay sub-page (Workspace ▸ External Display Appearance): the
 *  feature's MANAGEMENT surface. Adding/placing widgets happens on the external
 *  display itself (summon the layer, tap "+ Add"); this page is list/remove plus
 *  the backdrop style — and the phone-side recovery path when a widget renders
 *  broken on the external display. */
@Composable
private fun WidgetOverlayCustomizationPage(onBack: () -> Unit) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val cfg by prefs.widgetOverlayConfig.collectAsState(
        initial = com.portalpad.app.data.WidgetOverlayConfig(),
    )
    val externalDisplayId by app.externalDisplayId.collectAsState()

    // Live preview: summon the real layer on the external display while this
    // page is open (PFS collects the flag), dismiss on leave — same pattern as
    // the folder-window preview. Known quirk (shared with that preview): a
    // layer the user had open BEFORE entering the page is dismissed on leave.
    androidx.compose.runtime.DisposableEffect(Unit) {
        app.setWidgetOverlayPreviewActive(true)
        onDispose { app.setWidgetOverlayPreviewActive(false) }
    }

    // Hoisted so the scrollbar overlay below can read position/overflow — the
    // page rolled its own scroll (not TabBody) and had NO visible scrollbar,
    // so a long widget list scrolled invisibly (field report).
    val woScrollState = rememberScrollState()
    val woThumbColor = PhoneOnSurfaceMuted.copy(alpha = 0.5f)
    Box(Modifier.fillMaxSize().background(PhoneBackground)) {
        PageScrollChevrons(woScrollState)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(woScrollState)
                .padding(16.dp)
                .padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Widget Overlay",
                color = PhoneOnSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "A summonable layer of Android app widgets on the external display. " +
                    "Assign \"Widget Overlay\" to the Home or Back button, then press it " +
                    "to show or hide the layer. Add widgets on the external display " +
                    "itself with its \"+ Add\" chip.",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (externalDisplayId == null)
                    "Connect your external display to preview the widget overlay there."
                else
                    "Previewing on your external display — background and widget changes apply live.",
                color = if (externalDisplayId == null) PhoneWarning else PhoneOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            SectionCard(title = "Background") {
                Text(
                    "Backdrop behind the widget grid. Widgets draw their own backgrounds on top of it.",
                    color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                BarStyleControls(
                    style = cfg.background,
                    showCornerRadius = false, // meaningless on a full-screen scrim
                    defaultStyle = com.portalpad.app.data.WidgetOverlayConfig().background,
                    onChange = { s ->
                        scope.launch { prefs.setWidgetOverlayConfig(cfg.copy(background = s)) }
                    },
                )
            }
            SectionCard(
                title = "Widgets (${cfg.widgets.size}/${com.portalpad.app.data.MAX_OVERLAY_WIDGETS})",
            ) {
                if (cfg.widgets.isEmpty()) {
                    Text(
                        "No widgets added yet. Summon the overlay on your external display and tap \"+ Add\".",
                        color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    // ── Sort control (top-right of the list). "Last added" is
                    // free: config preserves insertion order. ──
                    var sortAlpha by remember { mutableStateOf(false) }
                    var sortMenuOpen by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Sort by", color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(6.dp))
                        com.portalpad.app.ui.common.AttachedDropdownBox(
                            expanded = sortMenuOpen,
                            onDismiss = { sortMenuOpen = false },
                            anchor = { opensUp ->
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(PhoneSurfaceElevated)
                                        .border(1.dp, PhonePrimaryDim, RoundedCornerShape(10.dp))
                                        .clickable { sortMenuOpen = true }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(if (sortAlpha) "Alphabetically" else "Last added",
                                        color = PhonePrimaryBright)
                                    Icon(
                                        if (opensUp) Icons.Filled.ArrowDropUp
                                        else Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        tint = PhonePrimaryBright,
                                    )
                                }
                            },
                        ) {
                            com.portalpad.app.ui.common.CompactMenuRow(
                                label = "Last added",
                                selected = !sortAlpha,
                                selectedColor = PhonePrimaryBright,
                                normalColor = PhoneOnSurface,
                            ) { sortAlpha = false; sortMenuOpen = false }
                            HorizontalDivider(color = PhonePrimaryDim.copy(alpha = 0.5f))
                            com.portalpad.app.ui.common.CompactMenuRow(
                                label = "Alphabetically",
                                selected = sortAlpha,
                                selectedColor = PhonePrimaryBright,
                                normalColor = PhoneOnSurface,
                            ) { sortAlpha = true; sortMenuOpen = false }
                        }
                    }
                    // ── App-group accordion (same model as the pickers): app
                    // header left-aligned, Nova-style centered widget cards
                    // inside, per-resolution status as the card subtitle,
                    // Remove beneath. ──
                    val awmHere = remember { android.appwidget.AppWidgetManager.getInstance(ctx) }
                    val pmHere = remember { ctx.packageManager }
                    data class WsEntry(
                        val w: com.portalpad.app.data.OverlayWidget,
                        val info: android.appwidget.AppWidgetProviderInfo?,
                        val appLabel: String,
                        val addIndex: Int,
                    )
                    val entriesAll = remember(cfg) {
                        cfg.widgets.mapIndexed { idx, w ->
                            val info = runCatching { awmHere.getAppWidgetInfo(w.appWidgetId) }.getOrNull()
                            val pkg = w.provider.substringBefore("/")
                            val appLabel = runCatching {
                                pmHere.getApplicationInfo(pkg, 0).loadLabel(pmHere).toString()
                            }.getOrDefault(pkg)
                            WsEntry(w, info, appLabel, idx)
                        }
                    }
                    val groupsWs = remember(entriesAll, sortAlpha) {
                        val grouped = entriesAll.groupBy { it.appLabel }
                        if (sortAlpha) {
                            grouped.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                                .mapValues { (_, v) -> v.sortedBy { it.w.label.lowercase() } }
                        } else {
                            grouped.entries
                                .sortedByDescending { (_, v) -> v.maxOf { it.addIndex } }
                                .associate { (k, v) ->
                                    k to v.sortedByDescending { it.addIndex }
                                }
                        }
                    }
                    var expandedWsApps by remember { mutableStateOf(setOf<String>()) }
                    groupsWs.entries.forEachIndexed { gi, (appName, entries) ->
                        if (gi > 0) {
                            Box(
                                Modifier.fillMaxWidth().height(1.dp)
                                    .background(PhoneOnSurfaceMuted.copy(alpha = 0.16f)),
                            )
                        }
                        val expandedWs = appName in expandedWsApps
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedWsApps =
                                        if (expandedWs) expandedWsApps - appName
                                        else expandedWsApps + appName
                                }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val icon = remember(appName) {
                                runCatching {
                                    pmHere.getApplicationIcon(
                                        entries.first().w.provider.substringBefore("/"),
                                    ).toBitmap().asImageBitmap()
                                }.getOrNull()
                            }
                            if (icon != null) {
                                Image(icon, contentDescription = null,
                                    modifier = Modifier.size(30.dp))
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(appName, color = PhoneOnSurface,
                                fontWeight = FontWeight.SemiBold, maxLines = 1,
                                modifier = Modifier.weight(1f))
                            Text("${entries.size}", color = PhoneOnSurfaceMuted,
                                style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(4.dp))
                            val chevRot by androidx.compose.animation.core.animateFloatAsState(
                                targetValue = if (expandedWs) 0f else -90f,
                                label = "wsChevron",
                            )
                            Icon(
                                Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = PhoneOnSurfaceMuted,
                                modifier = Modifier.size(22.dp).rotate(chevRot),
                            )
                        }
                        if (expandedWs) {
                            entries.forEach { entry ->
                                val w = entry.w
                                // One line per resolution: "Name (WxH): SxS
                                // cells (state)". "cells" not pixels — cells
                                // are fixed 70dp on every resolution, and the
                                // unit matches the picker. Aspect buckets:
                                // Portrait <1, Standard ≤2.2 (16:9/16:10),
                                // Ultrawide ≤3.0 (21:9), Super Ultrawide >3.0
                                // (32:9 — 3840×1080 is 3.56, previously
                                // mislabeled plain Ultrawide). Raw WxH always
                                // prints, so odd resolutions are never wrong,
                                // just less friendly.
                                val status = if (w.placements.isEmpty()) {
                                    "not placed on any resolution yet"
                                } else {
                                    w.placements.entries.joinToString("\n") { (res, pl) ->
                                        val parts = res.split("x")
                                        val rw = parts.getOrNull(0)?.toIntOrNull()
                                        val rh = parts.getOrNull(1)?.toIntOrNull()
                                        val name = if (rw != null && rh != null && rh > 0) {
                                            val ratio = rw.toFloat() / rh
                                            when {
                                                ratio < 1f -> "Portrait"
                                                ratio <= 2.2f -> "Standard"
                                                ratio <= 3.0f -> "Ultrawide"
                                                else -> "Super Ultrawide"
                                            } + " (${rw}×$rh)"
                                        } else res
                                        val state = when {
                                            pl.hidden -> "hidden"
                                            pl.col == com.portalpad.app.data.Placement.NO_FIT ->
                                                "doesn't fit"
                                            else -> "shown"
                                        }
                                        "$name: ${pl.spanW}×${pl.spanH} cells ($state)"
                                    }
                                }
                                // Cells parenthetical on the widget NAME (a
                                // property of the widget, not its app group) —
                                // same defaultSpan math as the picker; omitted
                                // for placeholder widgets whose provider is gone.
                                val cellsSuffix = entry.info?.let { pi ->
                                    "(${com.portalpad.app.service.WidgetHostManager.defaultSpan(pi.minWidth)}×" +
                                        "${com.portalpad.app.service.WidgetHostManager.defaultSpan(pi.minHeight)} cells)"
                                }
                                com.portalpad.app.ui.common.WidgetPreviewCard(
                                    info = entry.info,
                                    name = w.label,
                                    nameSuffix = cellsSuffix,
                                    subtitle = status,
                                    subtitleMaxLines = 6,
                                    nameColor = PhoneOnSurface,
                                    subtitleColor = PhoneOnSurfaceMuted,
                                    trailing = {
                                        androidx.compose.material3.TextButton(onClick = {
                                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                // Release the system binding, then drop from config.
                                                com.portalpad.app.service.WidgetHostManager
                                                    .deleteId(ctx, w.appWidgetId)
                                                val cur = prefs.widgetOverlayConfig.first()
                                                prefs.setWidgetOverlayConfig(
                                                    cur.copy(widgets = cur.widgets.filterNot { it.id == w.id }),
                                                )
                                            }
                                        }) { Text("Remove", color = PhoneWarning) }
                                    },
                                )
                            }
                        }
                    }

                    androidx.compose.material3.TextButton(onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val cur = prefs.widgetOverlayConfig.first()
                            cur.widgets.forEach {
                                com.portalpad.app.service.WidgetHostManager
                                    .deleteId(ctx, it.appWidgetId)
                            }
                            prefs.setWidgetOverlayConfig(cur.copy(widgets = emptyList()))
                        }
                    }) { Text("Remove all widgets", color = PhoneWarning) }
                }
            }
        }
        // Overflow-only scrollbar, same presentation as TabBody's: paints a
        // thumb only when the content actually scrolls, so short lists show
        // nothing and long widget lists stop scrolling invisibly.
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    val max = woScrollState.maxValue
                    if (max > 0) {
                        val chevZone = 26.dp.toPx()  // clear the overlaid chevrons (SH)
                        val trackTop = chevZone
                        val trackBottom = size.height - chevZone
                        val trackH = (trackBottom - trackTop).coerceAtLeast(1f)
                        val viewport = size.height
                        val total = viewport + max
                        val thumbH = (trackH * (viewport / total)).coerceIn(28.dp.toPx(), trackH)
                        val frac = woScrollState.value.toFloat() / max.toFloat()
                        val thumbY = trackTop + (trackH - thumbH) * frac
                        val w = 4.dp.toPx()
                        val x = size.width - w - 3.dp.toPx()
                        drawRoundRect(
                            color = woThumbColor,
                            topLeft = androidx.compose.ui.geometry.Offset(x, thumbY),
                            size = androidx.compose.ui.geometry.Size(w, thumbH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f),
                        )
                    }
                },
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PhoneSurfaceElevated)
                .border(1.dp, PhoneAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .clickable { onBack() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("\u2190 Back", color = PhoneOnSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BarStyleControls(
    style: com.portalpad.app.data.BarStyle,
    // Corner radius is meaningless for a full-screen scrim (widget overlay);
    // that page hides the control. Everything else keeps the default = true.
    showCornerRadius: Boolean = true,
    // What "Reset to defaults" restores — pages whose surface has a non-BarStyle()
    // default (the widget overlay's dim scrim) pass their own.
    defaultStyle: com.portalpad.app.data.BarStyle = com.portalpad.app.data.BarStyle(),
    onChange: (com.portalpad.app.data.BarStyle) -> Unit,
) {
    var expanded by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    // Collapsible header so each customization page stays scannable: a tap target
    // with the section name + a live swatch (when collapsed) that expands the full
    // editor. The swatch promotes to the large preview once open.
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Background gradient",
            color = PhoneAccent,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (!expanded) {
            Box(
                Modifier
                    .width(56.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(com.portalpad.app.ui.common.toComposeBrush(style))
                    .border(1.dp, PhoneSurfaceElevated, RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            if (expanded) "▾" else "▸",
            color = PhoneAccent,
            style = MaterialTheme.typography.titleMedium,
        )
    }

    if (expanded) {
        // Large live preview — honors type, geometry, and balance via the same
        // builder every surface uses, so this is exactly what they render.
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(style.cornerRadiusDp.dp))
                .background(com.portalpad.app.ui.common.toComposeBrush(style))
                .border(1.dp, PhoneSurfaceElevated, RoundedCornerShape(style.cornerRadiusDp.dp)),
        )

        // Gradient type selector.
        Text("Gradient type", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.portalpad.app.data.BarGradientType.values().forEach { t ->
                val selected = style.gradientType == t
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) PhoneAccent else PhoneSurfaceElevated)
                        .clickable { onChange(style.copy(gradientType = t)) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        t.name.lowercase().replaceFirstChar { it.uppercase() },
                        color = if (selected) PhoneBackground else PhoneOnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // Per-type geometry, revealed contextually.
        when (style.gradientType) {
            com.portalpad.app.data.BarGradientType.LINEAR -> {
                // 8 compass steps (45°) — matched by both the Compose surfaces and
                // the GradientDrawable top bar (it only supports 8 orientations).
                val deg = (((style.angleDeg % 360) + 360) % 360)
                val arrow = when (deg / 45) {
                    0 -> "↓"; 1 -> "↘"; 2 -> "→"; 3 -> "↗"; 4 -> "↑"; 5 -> "↖"; 6 -> "←"; else -> "↙"
                }
                Text("Direction: $arrow  $deg°", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = (deg / 45).toFloat(),
                    onValueChange = { n -> onChange(style.copy(angleDeg = ((n + 0.5f).toInt() % 8) * 45)) },
                    valueRange = 0f..7f,
                    steps = 6,
                    colors = sliderColors(),
                )
            }
            com.portalpad.app.data.BarGradientType.RADIAL -> {
                Text("Center X: ${style.centerXPct}%", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = style.centerXPct.coerceIn(0, 100).toFloat(),
                    onValueChange = { n -> onChange(style.copy(centerXPct = n.toInt().coerceIn(0, 100))) },
                    valueRange = 0f..100f, steps = 0, colors = sliderColors(),
                )
                Text("Center Y: ${style.centerYPct}%", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = style.centerYPct.coerceIn(0, 100).toFloat(),
                    onValueChange = { n -> onChange(style.copy(centerYPct = n.toInt().coerceIn(0, 100))) },
                    valueRange = 0f..100f, steps = 0, colors = sliderColors(),
                )
                Text("Spread: ${style.radiusPct}%  (50 = original)", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = style.radiusPct.coerceIn(10, 150).toFloat(),
                    onValueChange = { n -> onChange(style.copy(radiusPct = n.toInt().coerceIn(10, 150))) },
                    valueRange = 10f..150f, steps = 0, colors = sliderColors(),
                )
            }
            com.portalpad.app.data.BarGradientType.CONICAL -> {
                Text("Center X: ${style.centerXPct}%", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = style.centerXPct.coerceIn(0, 100).toFloat(),
                    onValueChange = { n -> onChange(style.copy(centerXPct = n.toInt().coerceIn(0, 100))) },
                    valueRange = 0f..100f, steps = 0, colors = sliderColors(),
                )
                Text("Center Y: ${style.centerYPct}%", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = style.centerYPct.coerceIn(0, 100).toFloat(),
                    onValueChange = { n -> onChange(style.copy(centerYPct = n.toInt().coerceIn(0, 100))) },
                    valueRange = 0f..100f, steps = 0, colors = sliderColors(),
                )
            }
        }

        // The two color points.
        ColorPointEditor("Color point A", style.colorA) { onChange(style.copy(colorA = it)) }
        ColorPointEditor("Color point B", style.colorB) { onChange(style.copy(colorB = it)) }

        // Flip A/B — swap the two gradient stops (reverses the gradient direction).
        androidx.compose.material3.TextButton(
            onClick = { onChange(style.copy(colorA = style.colorB, colorB = style.colorA)) },
        ) {
            Text("Flip color points (A ⇄ B)")
        }

        // Color balance — where the A↔B transition sits (50% = even). All types.
        Text(
            "Color balance: ${style.midpointPct}%  (50 = even)",
            color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = style.midpointPct.coerceIn(5, 95).toFloat(),
            onValueChange = { n -> onChange(style.copy(midpointPct = n.toInt().coerceIn(5, 95))) },
            valueRange = 5f..95f,
            steps = 0,
            colors = sliderColors(),
        )

        if (showCornerRadius) {
            Text(
                "Corner radius: ${style.cornerRadiusDp}dp",
                color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = style.cornerRadiusDp.toFloat(),
                onValueChange = { n -> onChange(style.copy(cornerRadiusDp = n.toInt())) },
                valueRange = 0f..40f,
                steps = 0,
                colors = sliderColors(),
            )
        }
        androidx.compose.material3.TextButton(
            onClick = { onChange(defaultStyle) },
        ) {
            Text("Reset to defaults")
        }
    }
}

/**
 * Edits a single ARGB color: a tappable swatch that expands a 2D color picker
 * (saturation/value square + hue strip) plus an opacity slider. [argb] is
 * 0xAARRGGBB as a Long; [onChange] returns the new Long.
 */
@Composable
private fun ColorPointEditor(label: String, argb: Long, onChange: (Long) -> Unit) {
    val a = ((argb shr 24) and 0xFF).toInt()
    val r = ((argb shr 16) and 0xFF).toInt()
    val g = ((argb shr 8) and 0xFF).toInt()
    val b = (argb and 0xFF).toInt()
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(r, g, b, hsv)
    var showDialog by androidx.compose.runtime.remember { mutableStateOf(false) }

    fun rebuild(h: Float, s: Float, v: Float, alpha: Int): Long {
        val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
        val rr = (rgb shr 16) and 0xFF
        val gg = (rgb shr 8) and 0xFF
        val bb = rgb and 0xFF
        return ((alpha.toLong() and 0xFF) shl 24) or
            ((rr.toLong()) shl 16) or ((gg.toLong()) shl 8) or (bb.toLong())
    }

    // Compact page row: swatch + label + opacity %, tap to open the picker popup.
    // The full editor (SV square, hue, hex/RGB, opacity) lives in the dialog so it
    // doesn't eat vertical space on the settings page.
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { showDialog = true }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(argb.toInt()))
                .border(1.dp, PhoneSurfaceElevated, RoundedCornerShape(7.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = PhoneOnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${(a * 100 / 255)}%", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        Text("\u203A", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodyLarge)
    }

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.94f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(PhoneBackground)
                    .border(1.dp, PhoneSurfaceElevated, RoundedCornerShape(18.dp)),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    // Header: live swatch + label + Done.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color(argb.toInt()))
                                .border(1.dp, PhoneSurfaceElevated, RoundedCornerShape(7.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            label,
                            color = PhoneOnSurface,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "Done",
                            color = PhoneAccent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showDialog = false }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    // Saturation/Value square for the current hue.
                    SVPicker(
                        hue = hsv[0], sat = hsv[1], value = hsv[2],
                        onChange = { s, v -> onChange(rebuild(hsv[0], s, v, a)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    // Hue strip.
                    HueStrip(hue = hsv[0], onChange = { h -> onChange(rebuild(h, hsv[1], hsv[2], a)) })
                    Spacer(Modifier.height(10.dp))
                    // Manual entry: hex (#RRGGBB) + R/G/B. Two-way synced with the picker;
                    // alpha stays controlled by the opacity slider below.
                    ColorCodeFields(
                        r = r, g = g, b = b,
                        onRgb = { nr, ng, nb ->
                            onChange(((a.toLong() and 0xFF) shl 24) or (nr.toLong() shl 16) or (ng.toLong() shl 8) or nb.toLong())
                        },
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("Opacity: ${(a * 100 / 255)}%", color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = a.toFloat(), onValueChange = { onChange(rebuild(hsv[0], hsv[1], hsv[2], it.toInt())) },
                        valueRange = 0f..255f, colors = sliderColors(),
                    )
                }
            }
        }
    }
}

/**
 * Hex (#RRGGBB) + R/G/B numeric entry, synced to the current color. Each field
 * shows the current value; valid edits call [onRgb] with the new 0–255 channels.
 * Invalid/partial input doesn't apply (the field just holds what you typed until
 * it parses), so typing never makes the picker jump.
 */
@Composable
private fun ColorCodeFields(r: Int, g: Int, b: Int, onRgb: (Int, Int, Int) -> Unit) {
    val hexNow = String.format("#%02X%02X%02X", r, g, b)
    // Local editable text seeded from the current color; resynced when the color
    // changes from elsewhere (picker drag) via the key on the current value.
    var hexText by androidx.compose.runtime.remember(hexNow) { mutableStateOf(hexNow) }

    androidx.compose.material3.OutlinedTextField(
        value = hexText,
        onValueChange = { txt ->
            hexText = txt
            val cleaned = txt.trim().removePrefix("#")
            if (cleaned.length == 6 && cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                val v = cleaned.toLong(16)
                onRgb(((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt())
            }
        },
        label = { Text("Hex") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = PhoneOnSurface, unfocusedTextColor = PhoneOnSurface,
            focusedBorderColor = PhoneAccent, unfocusedBorderColor = PhoneSurfaceElevated,
            focusedLabelColor = PhoneAccent, unfocusedLabelColor = PhoneOnSurfaceMuted,
            cursorColor = PhoneAccent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val chan: @Composable (String, Int, (Int) -> Unit) -> Unit = { lbl, cur, set ->
            var t by androidx.compose.runtime.remember(cur) { mutableStateOf(cur.toString()) }
            androidx.compose.material3.OutlinedTextField(
                value = t,
                onValueChange = { txt ->
                    t = txt.filter { it.isDigit() }.take(3)
                    val n = t.toIntOrNull()
                    if (n != null && n in 0..255) set(n)
                },
                label = { Text(lbl) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PhoneOnSurface, unfocusedTextColor = PhoneOnSurface,
                    focusedBorderColor = PhoneAccent, unfocusedBorderColor = PhoneSurfaceElevated,
                    focusedLabelColor = PhoneAccent, unfocusedLabelColor = PhoneOnSurfaceMuted,
                    cursorColor = PhoneAccent,
                ),
                modifier = Modifier.weight(1f),
            )
        }
        chan("R", r) { onRgb(it, g, b) }
        chan("G", g) { onRgb(r, it, b) }
        chan("B", b) { onRgb(r, g, it) }
    }
}

/** Saturation (x) / Value (y) square for a fixed [hue]; reports new s,v on drag. */
@Composable
private fun SVPicker(hue: Float, sat: Float, value: Float, onChange: (Float, Float) -> Unit) {
    var sizePx by androidx.compose.runtime.remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, PhoneSurfaceElevated, RoundedCornerShape(10.dp))
            .onGloballyPositioned { sizePx = androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()) }
            .pointerInput(hue) {
                detectDragGestures { change, _ ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val s = (change.position.x / w).coerceIn(0f, 1f)
                    val v = (1f - change.position.y / h).coerceIn(0f, 1f)
                    onChange(s, v)
                }
            }
            .pointerInput(hue) {
                detectTapGestures { pos ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    onChange((pos.x / w).coerceIn(0f, 1f), (1f - pos.y / h).coerceIn(0f, 1f))
                }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
            // White → hue across X.
            drawRect(brush = Brush.horizontalGradient(listOf(Color.White, hueColor)))
            // Transparent → black down Y.
            drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            // Selection handle.
            val hx = sat * size.width
            val hy = (1f - value) * size.height
            drawCircle(Color.White, radius = 9f, center = androidx.compose.ui.geometry.Offset(hx, hy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
            drawCircle(Color.Black, radius = 12f, center = androidx.compose.ui.geometry.Offset(hx, hy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
        }
    }
}

/** Horizontal hue strip (0..360); reports new hue on tap/drag. */
@Composable
private fun HueStrip(hue: Float, onChange: (Float) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, PhoneSurfaceElevated, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    onChange((change.position.x / w).coerceIn(0f, 1f) * 360f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    onChange((pos.x / w).coerceIn(0f, 1f) * 360f)
                }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val hueColors = (0..360 step 30).map {
                Color(android.graphics.Color.HSVToColor(floatArrayOf(it.toFloat(), 1f, 1f)))
            }
            drawRect(brush = Brush.horizontalGradient(hueColors))
            // Position marker.
            val mx = (hue / 360f) * size.width
            drawLine(Color.White, start = androidx.compose.ui.geometry.Offset(mx, 0f), end = androidx.compose.ui.geometry.Offset(mx, size.height), strokeWidth = 3f)
            drawLine(Color.Black, start = androidx.compose.ui.geometry.Offset(mx, 0f), end = androidx.compose.ui.geometry.Offset(mx, size.height), strokeWidth = 1f)
        }
    }
}

@Composable
private fun ResetToDefaultsButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PhoneSurfaceElevated)
            .border(1.dp, PhoneDanger.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = PhoneDanger, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ResetConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        modifier = Modifier.border(
            1.dp,
            com.portalpad.app.ui.theme.PhonePrimaryDim,
            androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        ),
        onDismissRequest = onCancel,
        title = { Text(title, color = PhoneOnSurface) },
        text = { Text(message, color = PhoneOnSurfaceMuted) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text("Reset", color = PhoneDanger)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text("Cancel", color = PhoneOnSurface)
            }
        },
        containerColor = PhoneSurface,
    )
}

@Composable
private fun <T> LabeledDropdown(
    current: T,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    contentDescription: String = "Choose",
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == current }?.second ?: ""
    // Shared attached scaffold (SH's app-wide dropdown language). This ONE
    // helper carries the Dock / top bar / folder window / widget overlay
    // customization dropdowns, so migrating it converts them all.
    com.portalpad.app.ui.common.AttachedDropdownBox(
        expanded = expanded,
        onDismiss = { expanded = false },
        modifier = Modifier.fillMaxWidth(),
        anchor = { opensUp ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PhoneSurfaceElevated)
                    .border(1.dp, PhonePrimaryDim, RoundedCornerShape(12.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    currentLabel,
                    color = PhoneOnSurface,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (opensUp) Icons.Filled.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = contentDescription,
                    tint = PhoneOnSurfaceMuted,
                )
            }
        },
    ) {
        options.forEachIndexed { i, (value, label) ->
            if (i > 0) HorizontalDivider(color = PhonePrimaryDim.copy(alpha = 0.5f))
            DropdownMenuItem(
                text = {
                    Text(
                        label,
                        color = if (value == current) PhoneAccent else PhoneOnSurface,
                        fontWeight = if (value == current) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                onClick = { expanded = false; onSelect(value) },
            )
        }
    }
}

// Status strip shown under the System mirror toggle. Two facets, both derived from the
// single mirror setting and always inverse (one active, one inactive), so the strip reads
// as an at-a-glance summary of the mode's trade-off. Shared by both Display-pipeline cards
// (Start page + remote) so they can't drift; dimmed when no external display is attached.
@Composable
private fun MirrorStatusStrip(mirrorOn: Boolean, connected: Boolean) {
    // A single small gap drives every internal space (top pad = between facets = above
    // footnote = bottom pad) so they can't drift.
    val gap = 6.dp
    // Bleed the tinted band + hairlines out to the card edges, past SectionCard's 16.dp
    // content padding; the inner content keeps that 16.dp so the text still lines up with
    // the rest of the card.
    val bleedToCardEdge = Modifier.layout { measurable, constraints ->
        val extra = 16.dp.roundToPx() * 2
        val placeable = measurable.measure(
            constraints.copy(maxWidth = constraints.maxWidth + extra),
        )
        layout(placeable.width - extra, placeable.height) {
            placeable.place(-extra / 2, 0)
        }
    }
    Column(
        bleedToCardEdge
            .background(PhoneSurfaceVariant)
            .alpha(if (connected) 1f else 0.55f),
    ) {
        HorizontalDivider(color = PhoneSurfaceElevated, thickness = 1.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = gap),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            MirrorFacet(
                "DRM protected video",
                if (mirrorOn) "Supported" else "Blocked",
                active = mirrorOn,
                asterisk = mirrorOn,
            )
            MirrorFacet(
                "Display tuning",
                if (mirrorOn) "Off" else "Available",
                active = !mirrorOn,
                asterisk = false,
            )
            if (mirrorOn) {
                Text(
                    "* Not all DRM content plays, even when marked Supported.",
                    color = PhoneOnSurfaceDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        HorizontalDivider(color = PhoneSurfaceElevated, thickness = 1.dp)
    }
}

// One facet of the mirror status strip: a state dot (filled mint = active, hollow dim
// ring = inactive), a muted label, and a bold value. Colour is active/inactive, never
// good/bad — each mode is a trade-off, not a fault.
@Composable
private fun MirrorFacet(label: String, value: String, active: Boolean, asterisk: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .then(
                    if (active) {
                        Modifier.background(PhoneSuccess)
                    } else {
                        Modifier.border(1.5.dp, PhoneOnSurfaceDim, CircleShape)
                    },
                ),
        )
        Spacer(Modifier.width(7.dp))
        Text(label, color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(6.dp))
        Text(
            value + if (asterisk) "*" else "",
            color = if (active) PhoneSuccess else PhoneOnSurfaceDim,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    pill: @Composable (() -> Unit)? = null,
    centeredTitleNoDot: Boolean = false,
    tightSpacing: Boolean = false,
    titleDivider: Boolean = false,
    disabled: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Box {
    Card(
        colors = CardDefaults.cardColors(containerColor = PhoneSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
            .then(if (disabled) Modifier.alpha(0.4f) else Modifier),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(if (tightSpacing) 5.dp else 10.dp)) {
            if (title.isNotEmpty()) {
                if (centeredTitleNoDot) {
                    Text(
                        title, color = PhoneOnSurface, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title, color = PhoneOnSurface, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        pill?.invoke()
                    }
                }
            }
            if (titleDivider && title.isNotEmpty()) {
                HorizontalDivider(color = PhoneSurfaceElevated, thickness = 1.dp)
            }
            content()
        }
    }
        // When disabled, overlay a transparent catcher that consumes all touch
        // so the (dimmed) controls can't be interacted with. Saved values are
        // untouched — flipping back re-enables them exactly as they were.
        if (disabled) {
            androidx.compose.foundation.layout.Box(
                Modifier.matchParentSize().clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) {},
            )
        }
    }
}

/**
 * Like [SectionCard] but the title row is a tap target that expands/collapses the
 * body, with a chevron that flips when open. Default-collapsed so the heavy
 * customization cards (dock layout, top-bar position/size/style, folder grid) stay
 * scannable until the user drills in. Same [ColumnScope] content slot as
 * [SectionCard], so swapping one for the other is mechanical.
 */
@Composable
private fun CollapsibleCard(
    title: String,
    defaultExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    val rot by animateFloatAsState(if (expanded) 180f else 0f, label = "cardChev")
    Card(
        colors = CardDefaults.cardColors(containerColor = PhoneSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    color = PhoneAccent,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = PhoneAccent,
                    modifier = Modifier.rotate(rot),
                )
            }
            if (expanded) { content() }
        }
    }
}

@Composable
private fun ActionRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = PhoneOnSurface, style = MaterialTheme.typography.bodyLarge)
            Text(value, color = PhoneAccent, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** One labeled row with a dropdown to pick the [GestureAction] for a swipe
 *  direction. */
@Composable
private fun GestureActionRow(
    label: String,
    selected: com.portalpad.app.data.GestureAction,
    target: String,
    appEntry: com.portalpad.app.data.AppEntry? = null,
    onPick: (com.portalpad.app.data.GestureAction) -> Unit,
) {
    val ctx = LocalContext.current
    val rowScope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    val launchesApp = selected == com.portalpad.app.data.GestureAction.LAUNCH_APP && appEntry != null
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = PhoneOnSurface,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.width(110.dp),
        )
        com.portalpad.app.ui.common.AttachedDropdownBox(
            expanded = expanded,
            onDismiss = { expanded = false },
            modifier = Modifier.weight(1f),
            anchor = { opensUp ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PhoneSurfaceElevated)
                    .border(1.dp, PhonePrimaryDim, RoundedCornerShape(10.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // When this direction launches an app/activity, show the app icon
                // + app label (+ activity name) so it's clear which app it is.
                if (launchesApp) {
                    com.portalpad.app.ui.common.AssignedActionLabel(
                        entry = appEntry, fallback = selected.label,
                        iconSizeDp = 28, modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        selected.label,
                        color = PhoneOnSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Icon(
                    if (opensUp) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                    contentDescription = "Choose action",
                    tint = PhoneOnSurfaceMuted,
                )
            }
            },
        ) {
                com.portalpad.app.data.GestureAction.entries.forEachIndexed { gi, action ->
                    if (gi > 0) HorizontalDivider(color = PhonePrimaryDim.copy(alpha = 0.5f))
                    DropdownMenuItem(
                        text = {
                            Text(
                                action.label,
                                color = if (action == selected) PhoneAccent else PhoneOnSurface,
                                fontWeight = if (action == selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            expanded = false
                            if (action == com.portalpad.app.data.GestureAction.LAUNCH_APP) {
                                // Open the app picker; it writes the gesture app
                                // pref (and sets this direction to LAUNCH_APP).
                                com.portalpad.app.ui.dock.launchDockPickerOnPhone(ctx, target)
                            } else {
                                onPick(action)
                            }
                        },
                    )
                }
                HorizontalDivider(color = PhonePrimaryDim.copy(alpha = 0.5f))
                // Widget Overlay: FIRST-CLASS item. It technically existed
                // behind "Launch app…" → app picker → Widget Overlay row, but
                // that's undiscoverable (field report: "u didnt add widget
                // overlay for the three finger swipes"). One tap writes the
                // sentinel app + LAUNCH_APP; the swipe then toggles the layer.
                run {
                    val isCurrent = selected == com.portalpad.app.data.GestureAction.LAUNCH_APP &&
                        appEntry?.isWidgetOverlay == true
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Widget Overlay",
                                color = if (isCurrent) PhoneAccent else PhoneOnSurface,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            expanded = false
                            rowScope.launch {
                                val prefs = PortalPadApp.instance.prefs
                                val entry = com.portalpad.app.data.AppEntry.widgetOverlayEntry()
                                when (target) {
                                    "gesture_up" -> prefs.setGestureAppUp(entry)
                                    "gesture_down" -> prefs.setGestureAppDown(entry)
                                    "gesture_left" -> prefs.setGestureAppLeft(entry)
                                    "gesture_right" -> prefs.setGestureAppRight(entry)
                                }
                            }
                            onPick(com.portalpad.app.data.GestureAction.LAUNCH_APP)
                        },
                    )
                }
            }
    }
}

/** The trackpad-surface controls, shared by the Trackpad interface and the Air
 *  Mouse interface (which also uses the surface for taps/scroll). Order: tap,
 *  finger dots, speeds, invert scroll, edge-scroll strip + side. */
@Composable
private fun TrackpadSurfaceControls() {
    ToggleRowWithDesc(
        label = "Tap to click",
        desc = "ON: a quick tap on the trackpad surface fires a left click. OFF: trackpad surface is motion-only — use the dedicated Left / Right click buttons. Turn OFF if you frequently get accidental clicks while positioning the cursor.",
        key = PreferencesRepository.Keys.TAP_TO_CLICK,
        default = true,
    )
    ToggleRowWithDesc(
        label = "Show finger dots",
        desc = "Render a visible dot under each finger touching the trackpad surface.",
        key = PreferencesRepository.Keys.SHOW_FINGER_DOTS,
        default = false,
    )
    SpeedSlider(
        label = "Cursor speed",
        desc = "How fast the cursor moves relative to your finger.",
        key = PreferencesRepository.Keys.CURSOR_SPEED,
        default = 1.0f,
        range = 0.4f..2.5f,
    )
    SpeedSlider(
        label = "Cursor smoothing",
        desc = "Eases the cursor toward your finger to glide over occasional dropped frames (most noticeable on the wide ultrawide canvas). Off by default — no added latency. Higher = smoother but slightly laggier.",
        key = PreferencesRepository.Keys.CURSOR_SMOOTHING,
        default = 0f,
        range = 0f..0.9f,
    )
    SpeedSlider(
        label = "Pointer acceleration",
        desc = "How much the cursor speeds up when you move your finger faster, like a real mouse — slow moves stay precise, quick flicks cover more screen. Set to 0 for a constant speed.",
        key = PreferencesRepository.Keys.POINTER_ACCEL,
        default = 1.0f,
        range = 0f..2f,
    )
    SpeedSlider(
        label = "Scroll speed",
        desc = "How far content scrolls per swipe on the trackpad or edge strip.",
        key = PreferencesRepository.Keys.SCROLL_SPEED,
        default = 1.0f,
        range = 0.4f..3.0f,
    )
    ToggleRowWithDesc(
        label = "Invert scroll direction",
        desc = "Reverse the scroll direction (natural vs. traditional).",
        key = PreferencesRepository.Keys.INVERT_SCROLL,
        default = false,
    )
    ToggleRowWithDesc(
        label = "Edge scroll strip",
        desc = "Show a scroll strip (marked with horizontal lines) along one edge of the trackpad. Drag up/down on it to scroll, without needing the two-finger scroll gesture. Two-finger scroll still works everywhere else on the trackpad.",
        key = PreferencesRepository.Keys.EDGE_SCROLL,
        default = true,
    )
    EdgeScrollSideRadio()
}

/** Radio Left/Right chooser for which side the edge-scroll strip sits on.
 *  Shown under the "Edge scroll strip" toggle; only meaningful when it's on. */
@Composable
private fun EdgeScrollSideRadio() {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val side by app.prefs.string(PreferencesRepository.Keys.EDGE_SCROLL_SIDE, "right")
        .collectAsState(initial = "right")

    @Composable
    fun option(label: String, value: String) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { scope.launch { app.prefs.setString(PreferencesRepository.Keys.EDGE_SCROLL_SIDE, value) } }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.RadioButton(
                selected = side == value,
                onClick = { scope.launch { app.prefs.setString(PreferencesRepository.Keys.EDGE_SCROLL_SIDE, value) } },
                colors = androidx.compose.material3.RadioButtonDefaults.colors(
                    selectedColor = PhonePrimary,
                    unselectedColor = PhoneOnSurfaceMuted,
                ),
            )
            Spacer(Modifier.width(4.dp))
            Text(label, color = PhoneOnSurface, style = MaterialTheme.typography.bodyMedium)
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Strip side:", color = PhoneOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(end = 4.dp),
        )
        option("Left", "left")
        option("Right", "right")
    }
}

/** A small pill button with a leading store/source logo (GitHub, Play, F-Droid)
 *  and a label, used in the Resources section. The logo is a vector drawable;
 *  Play Store keeps its own colors, so the icon is drawn untinted. */
@Composable
private fun ResourceLinkButton(iconRes: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(PhoneSurfaceElevated)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = PhoneOnSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Open a web URL in the browser. */
private fun openUrl(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Open an app's Play Store page, falling back to the web URL if the Play
 *  app isn't present. */
private fun openStore(ctx: android.content.Context, packageName: String) {
    val market = android.net.Uri.parse("market://details?id=$packageName")
    val web = android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, market)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(intent) }
    catch (_: android.content.ActivityNotFoundException) {
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, web)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    key: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
    default: Boolean = false,
) {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val value by app.prefs.bool(key, default).collectAsState(initial = default)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = PhoneOnSurface, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = value,
            onCheckedChange = { scope.launch { app.prefs.setBool(key, it) } },
            colors = SwitchDefaults.colors(
                checkedTrackColor = PhonePrimary,
                checkedThumbColor = PhonePrimaryBright,
                uncheckedTrackColor = PhoneSurfaceElevated,
                uncheckedThumbColor = PhoneOnSurfaceDim,
            ),
        )
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = PhonePrimaryBright,
    activeTrackColor = PhonePrimary,
    inactiveTrackColor = PhoneSurfaceElevated,
)

/**
 * Auto-disable timeout selector. 0 = never. Built as labeled chips so the user
 * can see the options at a glance — slider would be ambiguous for a setting
 * where "1 minute" and "5 minutes" matter precisely.
 */
@Composable
private fun RestoreLastSessionRow() {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val session by app.prefs.lastSession.collectAsState(initial = com.portalpad.app.data.SavedSession())
    val externalDisplayId by app.externalDisplayId.collectAsState()
    // Observe Shizuku status so the button reacts live when privilege drops/returns.
    val shizukuStatus by app.shizuku.status.collectAsState()
    // Privileged launches need Shizuku OR Root ready (matches hasLaunchPrivilege).
    val accessReady = shizukuStatus == com.portalpad.app.shizuku.ShizukuManager.Status.READY || app.root.isReady
    val count = session.windows.size

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "Restore last session",
            color = PhoneOnSurface, style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Reopens the apps that were on your external display, arranged where they were. Works in desktop-windows mode (it remembers your window layout); in single-app mode there's no multi-window layout to save. App contents aren't restored (the system closes apps when the display disconnects) — this brings back your layout.",
            color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        val canRestore = count > 0 && externalDisplayId != null && accessReady
        val subtitle = when {
            count == 0 -> "No saved session yet."
            !accessReady -> "$count app${if (count == 1) "" else "s"} saved — connect Shizuku or Root to restore."
            externalDisplayId == null -> "$count app${if (count == 1) "" else "s"} saved — connect a display to restore."
            else -> "$count app${if (count == 1) "" else "s"} saved."
        }
        Text(subtitle, color = PhoneAccent, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (canRestore) PhonePrimary else PhoneSurfaceElevated)
                .then(
                    if (canRestore) Modifier.clickable {
                        val displayId = externalDisplayId ?: return@clickable
                        android.widget.Toast.makeText(ctx, "Restoring $count app${if (count == 1) "" else "s"}…", android.widget.Toast.LENGTH_SHORT).show()
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
                                val disp = dm?.getDisplay(displayId)
                                val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") disp?.getRealMetrics(it) }
                                val w = m.widthPixels.coerceAtLeast(1).let { if (it <= 1) 1920 else it }
                                val h = m.heightPixels.coerceAtLeast(1).let { if (it <= 1) 1080 else it }
                                runCatching { app.freeform.restoreSession(session, displayId, w, h) }
                            }
                        }
                    } else Modifier
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                "Restore session",
                color = if (canRestore) PhonePrimaryBright else PhoneOnSurfaceDim,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AutoDisableRow() {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val raw by app.prefs.rawDataStore.data.collectAsState(initial = null)
    val current = raw?.get(PreferencesRepository.Keys.AUTO_DISABLE_AFTER_MIN) ?: 0

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "Auto-disable after no display",
            color = PhoneOnSurface, style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Stops the service automatically if no external display has been connected for the chosen duration. Saves battery if you forget to disable manually.",
            color = PhoneOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        val options = listOf(0 to "Never", 5 to "5 min", 15 to "15 min", 60 to "1 hour")
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (mins, label) ->
                val sel = current == mins
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (sel) PhonePrimary else PhoneSurfaceElevated)
                        .clickable {
                            scope.launch {
                                app.prefs.rawDataStore.edit {
                                    it[PreferencesRepository.Keys.AUTO_DISABLE_AFTER_MIN] = mins
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        label,
                        color = if (sel) PhoneOnSurface else PhoneOnSurfaceMuted,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessModeSelector(
    accessMode: AccessMode,
    shizukuReady: Boolean,
    rootReady: Boolean,
    onSelect: (AccessMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (accessMode) {
        AccessMode.NONE -> "None"
        AccessMode.SHIZUKU -> "Shizuku"
        AccessMode.ROOT -> "Root"
    }
    com.portalpad.app.ui.common.AttachedDropdownBox(
        expanded = expanded,
        onDismiss = { expanded = false },
        modifier = Modifier.fillMaxWidth(),
        anchor = { opensUp ->
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(PhoneSurfaceElevated)
                .border(1.dp, PhonePrimaryDim, RoundedCornerShape(10.dp))
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = PhoneOnSurface,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (opensUp) Icons.Filled.ArrowDropUp
                else Icons.Filled.ArrowDropDown,
                contentDescription = "Open privilege source menu",
                tint = PhoneOnSurfaceMuted,
            )
        }
        },
    ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("None", color = PhoneOnSurface)
                        Text(
                            "No privileged access. Driving an external display needs Shizuku or Root.",
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                onClick = { expanded = false; onSelect(AccessMode.NONE) },
            )
            HorizontalDivider(
                color = PhoneOnSurfaceDim.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 3.dp),
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Shizuku", color = PhoneOnSurface)
                        Text(
                            "No root needed. Requires the Shizuku app installed and authorized, then it creates the display and injects input.",
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                onClick = { expanded = false; onSelect(AccessMode.SHIZUKU) },
            )
            HorizontalDivider(
                color = PhoneOnSurfaceDim.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 3.dp),
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Root", color = PhoneOnSurface)
                        Text(
                            "For rooted devices. Runs a privileged service (su) that creates the display and injects input — full parity with Shizuku.",
                            color = PhoneOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                onClick = { expanded = false; onSelect(AccessMode.ROOT) },
            )
    }
}

/**
 * "Reset to factory defaults" row + confirmation dialog. Clears the entire
 * DataStore (every pref, dock config, all backed-up state) AND wipes on-disk
 * state (custom wallpapers, screenshots, saved logs), stops the foreground
 * service, then schedules an automatic relaunch and kills the process so it
 * restarts clean with defaults everywhere. While the reset runs, a spinning
 * PortalPad logo overlay is shown so the user sees progress instead of being
 * dropped to the home screen.
 */
@Composable
private fun FactoryResetRow() {
    val scope = rememberCoroutineScope()
    var showConfirm by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    // 0..1 progress + a label for the current step, driven through the reset.
    var resetProgress by remember { mutableFloatStateOf(0f) }
    var resetStep by remember { mutableStateOf("Starting…") }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PhoneAccent.copy(alpha = 0.12f))
            .clickable { showConfirm = true }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Reset to factory defaults", color = PhoneAccent, fontWeight = FontWeight.SemiBold)
            Text(
                "Clears everything — dock items, folders, all toggles, custom wallpapers, screenshots and logs. The app restarts fresh.",
                color = PhoneOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showConfirm) {
        androidx.compose.material3.AlertDialog(
            modifier = Modifier.border(
                1.dp,
                com.portalpad.app.ui.theme.PhonePrimaryDim,
                androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            ),
            onDismissRequest = { showConfirm = false },
            containerColor = PhoneSurfaceElevated,
            title = { Text("Reset everything?", color = PhoneOnSurface) },
            text = {
                Text(
                    "This clears every PortalPad preference, the dock and all folders, plus custom wallpapers, screenshots and saved logs. The app will restart automatically. This can't be undone.",
                    color = PhoneOnSurfaceMuted,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showConfirm = false
                        resetting = true
                        scope.launch {
                            val app = PortalPadApp.instance
                            // 1) Clear DataStore preferences (every key).
                            resetStep = "Clearing settings…"; resetProgress = 0.15f
                            runCatching { app.prefs.clearAllForFactoryReset() }
                            kotlinx.coroutines.delay(250)
                            // 2) Wipe on-disk state DataStore doesn't cover:
                            //    custom wallpapers, screenshots, saved logs.
                            resetStep = "Removing wallpapers, screenshots & logs…"; resetProgress = 0.45f
                            runCatching {
                                java.io.File(app.filesDir, "wallpapers").deleteRecursively()
                                app.getExternalFilesDir(null)?.let { ext ->
                                    java.io.File(ext, "screenshots").deleteRecursively()
                                    java.io.File(ext, "logs").deleteRecursively()
                                }
                            }
                            kotlinx.coroutines.delay(250)
                            // 3) Stop the foreground service so it doesn't
                            //    re-create state from the now-cleared prefs.
                            resetStep = "Stopping services…"; resetProgress = 0.7f
                            runCatching {
                                app.stopService(
                                    android.content.Intent(
                                        app, com.portalpad.app.service.PortalPadForegroundService::class.java,
                                    ),
                                )
                            }
                            // 4) Unbind privileged backends cleanly.
                            runCatching { app.shizukuClickBackend.unbind() }
                            runCatching { app.rootClickBackend.unbind() }
                            kotlinx.coroutines.delay(250)
                            // 5) Schedule an automatic relaunch of the launcher
                            //    activity ~250ms out and kill the process. The
                            //    alarm brings the app back so the user lands in a
                            //    fresh PortalPad, not the home screen.
                            resetStep = "Restarting PortalPad…"; resetProgress = 1f
                            kotlinx.coroutines.delay(500)
                            runCatching {
                                val launch = app.packageManager
                                    .getLaunchIntentForPackage(app.packageName)
                                    ?.addFlags(
                                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                                    )
                                if (launch != null) {
                                    val pi = android.app.PendingIntent.getActivity(
                                        app, 0, launch,
                                        android.app.PendingIntent.FLAG_CANCEL_CURRENT or
                                            android.app.PendingIntent.FLAG_IMMUTABLE,
                                    )
                                    val am = app.getSystemService(android.content.Context.ALARM_SERVICE)
                                        as android.app.AlarmManager
                                    am.set(
                                        android.app.AlarmManager.RTC,
                                        System.currentTimeMillis() + 250,
                                        pi,
                                    )
                                }
                            }
                            kotlinx.coroutines.delay(150)
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    },
                ) { Text("Reset", color = PhoneAccent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel", color = PhoneOnSurfaceMuted)
                }
            },
        )
    }

    // Full-screen spinning-logo overlay shown while the reset runs (until the
    // process is killed). Covers the work so the user sees progress.
    if (resetting) {
        val transition = rememberInfiniteTransition(label = "reset-spin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(
                    durationMillis = 900,
                    easing = androidx.compose.animation.core.LinearEasing,
                ),
            ),
            label = "reset-angle",
        )
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(PhoneBackground.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 40.dp),
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(
                            com.portalpad.app.R.drawable.ic_launcher_foreground,
                        ),
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .graphicsLayer { rotationZ = angle },
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "Resetting PortalPad",
                        color = PhoneOnSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    // Live step description — says exactly what's happening.
                    Text(
                        resetStep,
                        color = PhoneOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(20.dp))
                    // Thin determinate bar that fills through the real steps.
                    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = resetProgress,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 350),
                        label = "reset-progress",
                    )
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = PhonePrimaryBright,
                        trackColor = PhoneSurfaceElevated,
                    )
                }
            }
        }
    }
}
