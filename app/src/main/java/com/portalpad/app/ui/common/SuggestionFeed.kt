package com.portalpad.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.portalpad.app.service.PortalPadAccessibilityService

/**
 * Suggestion machinery shared by the FLOATING keyboard and the ON-DISPLAY
 * keyboard - the polling loop, the two-tier pick, and the list panel.
 *
 * EXTRACTED from the relay page's proven inline implementation
 * (KeyboardRelayActivity). The relay deliberately KEEPS its own copy for now:
 * that page's editor is the most regression-prone surface in the app and its
 * loop is verified on-device, so it migrates here only after these two new
 * consumers prove the extraction on-device. Behavior must match the relay's -
 * if a rule changes there, change it here too.
 */
object SuggestionFeed {

    /**
     * Poll the display app's own suggestion UI forever. Call from a
     * LaunchedEffect scoped to the keyboard being visible; cancellation ends it.
     *
     * Same rules as the relay loop: query-change resets the cadence (250ms hot,
     * 600ms idle), browser lists keep their native order (their ranking encodes
     * history/popularity signals ABC would destroy), everything else gets
     * SearchRank, and the list only clears after 4 consecutive empty reads -
     * a single empty poll is routine turbulence, not "no suggestions".
     */
    suspend fun run(
        displayId: () -> Int?,
        query: () -> String,
        onList: (List<PortalPadAccessibilityService.MirrorSuggestion>) -> Unit,
    ): Nothing {
        var lastQuery = ""
        var sinceChange = 0
        var emptyStreak = 0
        while (true) {
            val typed = query().trim()
            if (typed != lastQuery) {
                lastQuery = typed
                sinceChange = 0
                emptyStreak = 0
            } else {
                sinceChange++
            }
            val disp = displayId()
            val raw = if (disp == null) emptyList() else runCatching {
                PortalPadAccessibilityService.instance
                    ?.readSuggestions(disp, query = typed)
            }.getOrNull() ?: emptyList()
            // Filter the app's verbatim echo of the typed text (submit already
            // covers "search exactly this") and trim trailing separator dots.
            val processed = raw
                .map { it.copy(title = it.title.trimEnd(' ', '·', '–', '—').trim()) }
                .filter { it.title.isNotBlank() && !it.title.equals(typed, ignoreCase = true) }
                .distinctBy { it.title }
            val srcPkg = PortalPadAccessibilityService.instance?.lastSuggestionSourcePkg
            val ranked = if (PortalPadAccessibilityService.isBrowserPkg(srcPkg)) processed
            else SearchRank.rank(processed, typed) { it.title }
            if (ranked.isNotEmpty()) {
                emptyStreak = 0
                onList(ranked)
            } else {
                emptyStreak++
                if (emptyStreak >= 4) onList(emptyList())
            }
            kotlinx.coroutines.delay(if (sinceChange < 5) 250L else 600L)
        }
    }

    /**
     * Act on a picked suggestion. Tier 1 clicks the LIVE node when the app's
     * own dropdown still exists (preserves the row's exact semantics). Tier 2,
     * the expected path once that dropdown has died: SET_TEXT the row's target
     * into the retained field node and submit with a real Enter - URL rows
     * submit their ADDRESS (the field navigates), search rows their title.
     */
    suspend fun pick(
        s: PortalPadAccessibilityService.MirrorSuggestion,
        index: Int,
    ): Boolean {
        val svc = PortalPadAccessibilityService.instance
        val injector = com.portalpad.app.PortalPadApp.instance.injector
        var ok = runCatching {
            svc?.clickSuggestion(injector.displayId, s.title, index)
        }.getOrNull() == true
        if (!ok) {
            val submitText = s.url ?: s.title
            val set = runCatching { svc?.setFieldText(submitText) }.getOrNull() == true
            if (set) {
                injector.setSearchQuery(submitText)
                // Let the app's editor ingest the SET_TEXT before the submit key.
                kotlinx.coroutines.delay(120)
                injector.submitSearch()
                injector.pressKey(android.view.KeyEvent.KEYCODE_ENTER)
                ok = true
            }
        }
        android.util.Log.d(
            "PortalPadSuggest",
            "suggestion pick '${s.title}' idx=$index url=${s.url != null} ok=$ok",
        )
        if (ok) injector.buzz(longPress = false)
        return ok
    }

    /**
     * The list panel - header, rows (title + dimmed address line on navigate
     * rows), internal scroll past [listMaxHeight] with a proportional thumb.
     * Rows fire via tap gesture, which works both for thumbs (floating) and
     * injected cursor clicks (on-display). The panel root swallows stray
     * touches so a miss between rows can never fall through to whatever
     * dismissal layer sits behind it.
     */
    @Composable
    fun SuggestionListPanel(
        items: List<PortalPadAccessibilityService.MirrorSuggestion>,
        listMaxHeight: Dp,
        onPick: (Int, PortalPadAccessibilityService.MirrorSuggestion) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Text(
                "Suggestions · tap to open",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            val listState = rememberLazyListState()
            val listOverflow = listState.canScrollBackward || listState.canScrollForward
            if (listOverflow) {
                ScrollEdgeChevron(listState, up = true, color = MaterialTheme.colorScheme.primary)
            }
            Box {
                val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        // Chevron strips (2 x 16dp) live INSIDE the caller's
                        // height budget, so the panel never outgrows the space
                        // reserved for it (the on-display keyboard reserves an
                        // exact window slice).
                        .heightIn(max = maxOf(listMaxHeight - 32.dp, 60.dp))
                        .fillMaxWidth()
                        // Proportional thumb, drawn only while content overflows.
                        .drawWithContent {
                            drawContent()
                            val info = listState.layoutInfo
                            val total = info.totalItemsCount
                            if (total > 0 && info.visibleItemsInfo.isNotEmpty()) {
                                val visible = info.visibleItemsInfo.size.toFloat()
                                val frac = (visible / total).coerceIn(0.1f, 1f)
                                if (frac < 1f) {
                                    val firstIdx = info.visibleItemsInfo.first().index.toFloat()
                                    val progress = (firstIdx / (total - visible)).coerceIn(0f, 1f)
                                    val thumbH = size.height * frac
                                    val thumbY = (size.height - thumbH) * progress
                                    val w = 4.dp.toPx()
                                    drawRoundRect(
                                        color = thumbColor,
                                        topLeft = androidx.compose.ui.geometry.Offset(
                                            size.width - w - 3.dp.toPx(),
                                            thumbY,
                                        ),
                                        size = androidx.compose.ui.geometry.Size(w, thumbH),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
                                    )
                                }
                            }
                        },
                ) {
                    itemsIndexed(items) { i, s ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(s) { detectTapGestures { onPick(i, s) } }
                                .padding(start = 14.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
                        ) {
                            Text(
                                s.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (s.url != null) {
                                Text(
                                    s.url,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
            if (listOverflow) {
                ScrollEdgeChevron(listState, up = false, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
