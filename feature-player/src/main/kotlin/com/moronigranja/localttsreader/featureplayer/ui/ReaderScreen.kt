package com.moronigranja.localttsreader.featureplayer.ui
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.moronigranja.localttsreader.player.PlaybackUiState
import com.moronigranja.localttsreader.player.PlayerCommands
import com.moronigranja.localttsreader.player.PlayerPhase
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.player.SleepTimer
import com.moronigranja.localttsreader.player.TextPagination
import com.moronigranja.localttsreader.player.chapterMenuLabel
import com.moronigranja.localttsreader.ui.AyvuSpacing
import com.moronigranja.localttsreader.ui.CoverageProgress
import com.moronigranja.localttsreader.ui.EmptyState
import com.moronigranja.localttsreader.ui.PlayerCard
import com.moronigranja.localttsreader.ui.SectionHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil

/**
 * The docked reader+player (decisions #29/#52): a real paginated book page —
 * the chapter's text flows and breaks exactly where it would overflow, every
 * chapter starts on a fresh page, there is NO scroll. The active sentence is
 * highlighted from the engine's anchors (#31) and playback turns pages
 * (only when the spoken passage leaves the current one). Opening a book does
 * NOT auto-play (decisions #52); the transport or the library play button
 * start audio. The shared player card docks below (play/pause, ±30s seek,
 * chapter skip); sleep timer + undo-skip stay in the top bar.
 * Page gestures (horizontal swipe or side-zone taps turn pages; a middle
 * double tap toggles the immersive chrome; a long press opens the paragraph
 * menu — Play from here / Copy text) and the bookmark menu (add + jump)
 * round it out. Follow turns the page with the ACTIVE sentence, not
 * the passage start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onClose: () -> Unit,
    startAt: PlayerPosition? = null,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var chapterMenu by remember { mutableStateOf(false) }
    var bookmarkMenu by remember { mutableStateOf(false) }
    var voiceSheet by remember { mutableStateOf(false) }
    val voiceSelector by viewModel.voiceSelector.collectAsState()
    // Immersive chrome (item 1): rememberSaveable so rotation keeps the
    // choice; the system bar controller re-hides the bars on the new window.
    var immersive by rememberSaveable { mutableStateOf(false) }
    // System-bar fade sync (item 1 follow-up): the insets hide/show is
    // ANIMATED (~300ms), so switching the layout the same frame reflows the
    // body under a half-faded bar — the occasional "top cut". Hold the
    // chrome until the bars settle, in BOTH directions.
    var barsSettled by remember { mutableStateOf(true) }

    // The toggle flips [barsSettled] false SYNCHRONOUSLY (same frame as
    // [immersive]) so the layout holds its pre-toggle chrome while the
    // system bars fade; [showOverlays] (the full-bleed body) appears only
    // once the bars have fully left — no mid-fade reflow under a half-faded
    // bar (the top-cut fix). The effect re-settles after the fade; the
    // controller hide/show runs off the same [immersive].
    fun toggleImmersive() {
        barsSettled = false
        immersive = !immersive
    }
    LaunchedEffect(immersive) {
        delay(SYSTEM_BARS_ANIM_MS)
        barsSettled = true
    }
    val showChrome = !immersive || !barsSettled
    val showOverlays = immersive && barsSettled
    // System bars: hide on enter, restore on exit/dispose. One swipe brings
    // them back transiently (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) — the
    // immersive page is never soft-locked without bars.
    val window = LocalContext.current.findActivity()?.window
    val insetsController = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
    DisposableEffect(immersive, window) {
        if (insetsController != null) {
            if (immersive) {
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            // The composable left the composition while immersive (or the
            // window rotated): never strand the user without bars.
            if (immersive) insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // System back returns to the library, same as the top-bar arrow (the
    // reader is a top-level destination, not an exit from the app).
    BackHandler { onClose() }

    // Opening positions the reader WITHOUT starting playback (decisions #52).
    // Re-open whenever the service's state no longer reflects the reader's
    // book (first open AND drift recovery: screen-off/on, process death, or a
    // media-session STOP reset underneath — open-bugs "empty-state
    // placeholder"). open() restores from the persisted resume row, so the
    // reading place recovers without restarting audio.
    LaunchedEffect(bookId, state.bookId) {
        if (state.bookId != bookId) {
            viewModel.open(bookId)
        }
    }
    // A share target (S3 "listen here") starts audio at the passage once.
    LaunchedEffect(startAt) {
        if (startAt != null) {
            viewModel.playPosition(bookId, startAt.chapterIndex, startAt.passageIndex)
        }
    }

    // Play starts from the top of the current visible page (item 3): the
    // page-start passage, published by PaginatedChapter from the page's
    // first line. Always the passage that BEGINS on the page, so follow
    // never snaps the view back to that passage's earlier start page.
    val pageStartPassage = remember { mutableIntStateOf(0) }
    // Play-from-view is shared by BOTH play buttons (the docked card and the
    // immersive overlay). The wrapper is remembered once, so it reads the
    // page-start passage through [pageStartPassage] (fresh state, not the
    // captured value); the ref indirection keeps the resume override current
    // for the delegated [PlayerCommands].
    val playFromView: () -> Unit = {
        if (state.chapterPassages.isNotEmpty()) {
            viewModel.playPosition(bookId, state.chapterIndex, pageStartPassage.value)
        } else {
            viewModel.resume()
        }
    }
    val playFromViewRef = rememberUpdatedState(playFromView)
    val readerCommands =
        remember(viewModel) {
            object : PlayerCommands by viewModel {
                override fun resume() = playFromViewRef.value()
            }
        }
    Scaffold(
        topBar = {
            // Immersive: no top bar at all — the book-title overlay draws in
            // its place (item 1). Held during the bar fade (barsSettled).
            if (showChrome) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .statusBarsPadding(),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(AyvuSpacing.XS),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Box {
                            TextButton(
                                onClick = { chapterMenu = true },
                                enabled = state.bookId != null && state.chapters.isNotEmpty(),
                            ) {
                                Text("Ch ${state.chapterIndex + 1}/${state.chapters.size}")
                            }
                            DropdownMenu(expanded = chapterMenu, onDismissRequest = { chapterMenu = false }) {
                                state.chapters.forEachIndexed { index, title ->
                                    DropdownMenuItem(
                                        text = { Text(chapterMenuLabel(index, title)) },
                                        onClick = {
                                            chapterMenu = false
                                            viewModel.openPosition(bookId, index, 0)
                                        },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { bookmarkMenu = true }, enabled = state.positioned) {
                                Icon(Icons.Filled.Bookmark, contentDescription = "Bookmarks")
                            }
                            DropdownMenu(expanded = bookmarkMenu, onDismissRequest = { bookmarkMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Bookmark this passage") },
                                    onClick = {
                                        bookmarkMenu = false
                                        viewModel.bookmark()
                                    },
                                )
                                state.bookmarks.forEach { bookmark ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                bookmark.label
                                                    ?: "Ch ${bookmark.chapterIndex + 1} · P ${bookmark.passageIndex + 1}",
                                            )
                                        },
                                        onClick = {
                                            bookmarkMenu = false
                                            viewModel.openPosition(bookId, bookmark.chapterIndex, bookmark.passageIndex)
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { voiceSheet = true }) {
                            Icon(Icons.Filled.RecordVoiceOver, contentDescription = "Change voice")
                        }
                        IconButton(onClick = { viewModel.undo() }, enabled = state.canUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                        }
                        TextButton(onClick = { viewModel.cycleSleep() }) {
                            Text(state.sleepLabel, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(
                        state.bookTitle.ifEmpty { "Reader" },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AyvuSpacing.LG, vertical = AyvuSpacing.SM),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        },
        // The shared app-wide player card (decisions #53): cover, progress,
        // times, −30s/◀Ch/play+spinner/Ch▶/+30s. Sleep timer + undo
        // stay in the top bar; the old transport row and footer are gone.
        // Immersive (item 1) drops BOTH bars: the empty top/bottom slots feed
        // no insets, so the body grows — the reflow is accepted (item 1) and
        // the slim title/player overlays below take their place. The chrome
        // is held during the bar fade (barsSettled) so the body never
        // reflows under a half-faded system bar (top-cut follow-up).
        bottomBar = { if (showChrome) PlayerCard(state, readerCommands) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            ) {
                when {
                    state.failure != null -> EmptyState(state.failure!!)
                    state.chapterPassages.isEmpty() && state.phase == PlayerPhase.IDLE ->
                        EmptyState("Tap play to start listening from this book.")
                    else ->
                        PaginatedChapter(
                            state = state,
                            bookId = bookId,
                            viewModel = viewModel,
                            pageStartPassage = pageStartPassage,
                            immersive = showOverlays,
                            onToggleImmersive = ::toggleImmersive,
                            modifier = Modifier.weight(1f),
                        )
                }
            }
            if (showOverlays) {
                // Top overlay: book title drawn OVER the page — semi-
                // transparent surface, labelLarge centered, SM margin. It
                // never feeds reservedPx: pagination is unchanged by it.
                Text(
                    text = state.bookTitle.ifEmpty { "Reader" },
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                            .padding(AyvuSpacing.SM),
                )
                // Bottom overlay: minimal player — play/pause, the thin
                // two-tone progress line (readFraction + pregen cushion, the
                // same segments as PlayerCard), and the item-4 passage
                // indicator (the same string as the regular footer).
                val loading = state.phase == PlayerPhase.LOADING
                val playing = state.phase == PlayerPhase.PLAYING || loading
                Row(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                            .padding(horizontal = AyvuSpacing.SM, vertical = AyvuSpacing.XS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (playing) viewModel.pause() else playFromView() }) {
                        Icon(
                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                        )
                    }
                    CoverageProgress(
                        coverageSpans = state.coverageSpans,
                        chapterMarks = state.chapterMarks,
                        playheadFraction = state.playheadFraction,
                        modifier = Modifier.weight(1f),
                    )
                    val passageLabel = PlaybackUiState.passageIndicatorLabel(state.bookPassageIndex, state.bookPassageCount)
                    Text(
                        text = passageLabel ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = AyvuSpacing.SM),
                    )
                }
            }
        }
    }
    // C2: the reader's voice surface — the same shared selector as Setup and
    // Settings. Selecting a voice persists it and rebuilds the active book at
    // the same playhead (A5); preview is one-at-a-time and narration-safe.
    if (voiceSheet) {
        val translate by viewModel.translateState.collectAsState()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { voiceSheet = false },
            confirmButton = {},
            title = { Text("Voice") },
            text = {
                androidx.compose.foundation.layout.Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                ) {
                    // Read-in-language (decisions #114): per-book target above
                    // the voice rows; the pack download row shows until staged.
                    com.moronigranja.localttsreader.ui.SectionHeader(
                        "Read in",
                        Modifier.padding(vertical = AyvuSpacing.SM),
                    )
                    com.moronigranja.localttsreader.ui.ReadInLanguagePicker(
                        state = translate,
                        onSelect = {
                            viewModel.setTranslateTarget(it)
                            voiceSheet = false
                        },
                        onDownload = viewModel::downloadTranslatePack,
                    )
                    com.moronigranja.localttsreader.ui.VoiceSelector(
                        state = voiceSelector,
                        onSelect = {
                            viewModel.selectVoice(it)
                            voiceSheet = false
                        },
                        onToggleFavorite = viewModel::toggleFavorite,
                        onPreview = viewModel::previewVoice,
                        onStopPreview = viewModel::stopPreview,
                        onDownload = { viewModel.downloadVoicePacks() },
                    )
                }
            },
        )
    }
}

private val PlaybackUiState.positioned: Boolean
    get() = bookId != null && (phase != PlayerPhase.IDLE || canUndo || passageText.isNotBlank())

private val PlaybackUiState.sleepLabel: String
    get() =
        when (sleepTimer) {
            SleepTimer.Off -> "Sleep: off"
            SleepTimer.EndOfChapter -> "Sleep: ch."
            is SleepTimer.Duration -> "Sleep: 30m"
        }

/**
 * One real book page (decisions #52): the chapter's text flows and breaks
 * exactly where it would overflow the viewport — no scroll — and a new
 * chapter always starts on a fresh page (its first page reserves headroom
 * for the chapter title). Pagination is measured from the fixed-line-height
 * chapter layout; pages are contiguous line ranges, so the rendered slice
 * re-wraps identically (greedy wrap breaks only depend on the line start).
 *
 * Gestures: horizontal swipe or side-zone taps turn pages; a middle double
 * tap toggles the immersive chrome (both ways); a long press opens the
 * paragraph context menu (Play from here / Copy text, G2). Follow turns
 * the page with the ACTIVE sentence — a long paragraph narrated across a
 * page break follows by sentence, and a manual page turn stops playback
 * (item 4). Follow acts only when the (chapter, passage, active sentence)
 * triple changes, so neither a chrome toggle nor a pause/turn can yank the
 * page. The three-way discrimination is tap vs long-press vs swipe
 * (decisions #96), defined against the B3 middle-zone press highlight.
 */
@Composable
private fun PaginatedChapter(
    state: PlaybackUiState,
    bookId: String,
    viewModel: ReaderViewModel,
    pageStartPassage: MutableState<Int>,
    immersive: Boolean = false,
    onToggleImmersive: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (state.chapterPassages.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    val pressedColor = MaterialTheme.colorScheme.surfaceVariant
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Measurement constant: pagination keys on the FIXED line-height contract.
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp)
    val titleStyle = MaterialTheme.typography.titleLarge
    val indicatorStyle = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    // Title gap and page margins are tokenized (decisions #94); the gap and
    // the rendered title padding MUST stay equal — both read AyvuSpacing.MD.
    val titleGapPx = with(density) { AyvuSpacing.MD.toPx() }.toInt()
    val horizontalPadPx = with(density) { AyvuSpacing.LG.toPx() }.toInt()
    var page by remember(state.chapterIndex) { mutableIntStateOf(0) }
    var pressedPassage by remember { mutableStateOf<Int?>(null) }
    // Middle-zone double-tap detection (item 2): the first tap of a pair
    // only records its time — a second tap inside the double-tap window
    // toggles the immersive chrome. A single middle tap does nothing.
    var lastMiddleTapAt by remember { mutableLongStateOf(0L) }
    // Long-press context menu (G2): the pressed passage + anchor; null =
    // closed. While open the page gestures are inert (the pointerInput block
    // is keyed on it) so an outside tap dismisses the menu, never turns the
    // page.
    var longPressTarget by remember { mutableStateOf<LongPressTarget?>(null) }
    // Follow dedupe (item 1): both follow effects re-fire whenever their
    // geometry keys (firstPageLines/fullPageLines/totalPages) change — the
    // immersive toggle re-derives them. The dedupe key is the (chapter,
    // passage, active sentence) triple: when it is unchanged the effects
    // early-return BEFORE any snap, so a chrome toggle (or a pause that
    // flips phase) never yanks the page back to the highlighted passage —
    // the geometry-restore effect keeps the reading place instead. A manual
    // page turn stops playback (item 4), so no grace timer is needed.
    var lastFollowKey by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }

    BoxWithConstraints(modifier = modifier) {
        val viewportHeight = constraints.maxHeight
        // Double-tap window for the immersive toggle (item 2): the platform
        // timings come from the composition's ViewConfiguration — capture
        // once here, they are stable for the session.
        val doubleTapMinMs = LocalViewConfiguration.current.doubleTapMinTimeMillis
        val doubleTapTimeoutMs = LocalViewConfiguration.current.doubleTapTimeoutMillis
        // Long-press timeout for the paragraph menu (G2) — platform timing,
        // same capture-once treatment as the double-tap constants.
        val longPressTimeoutMs = LocalViewConfiguration.current.longPressTimeoutMillis
        val pageWidth = (constraints.maxWidth - horizontalPadPx * 2).coerceAtLeast(1)
        val chapterTitle = state.chapters.getOrNull(state.chapterIndex).orEmpty()
        val chapterText =
            remember(state.chapterIndex, state.chapterPassages) {
                state.chapterPassages.joinToString("\n\n")
            }
        val passageOffsets = remember(state.chapterPassages) { computePassageOffsets(state.chapterPassages) }
        val activeSpan = activeSentenceRange(passageOffsets, state.passageIndex, state.passageText, state.activeSentenceIndex)
        val bodyLayout =
            remember(chapterText, pageWidth, bodyStyle, activeSpan) {
                val annotated =
                    buildAnnotatedString {
                        append(chapterText)
                        if (activeSpan != null) {
                            addStyle(SpanStyle(fontWeight = FontWeight.Bold), activeSpan.first, activeSpan.second)
                        }
                    }
                textMeasurer.measure(
                    text = annotated,
                    style = bodyStyle,
                    constraints = Constraints(maxWidth = pageWidth),
                )
            }
        val titleHeightPx =
            remember(chapterTitle, pageWidth, titleStyle) {
                if (chapterTitle.isBlank()) {
                    0
                } else {
                    textMeasurer
                        .measure(
                            text = AnnotatedString(chapterTitle),
                            style = titleStyle,
                            constraints = Constraints(maxWidth = pageWidth),
                        ).size.height
                }
            }
        // Real line pitch, measured from the layout itself: on some OEMs the
        // paint-level font scale differs from Density.fontScale (S22 @2.0×:
        // 148px pitch vs 107px from the sp conversion) — a parallel sp→px
        // computation silently over-filled pages, cropped the last line and
        // pushed the indicator out (#87/#95). Pagination keys on the measured
        // pitch so the two can never disagree.
        val lineHeightPx =
            if (bodyLayout.lineCount > 1) {
                (bodyLayout.multiParagraph.getLineTop(1) - bodyLayout.multiParagraph.getLineTop(0)).toInt()
            } else {
                (bodyLayout.multiParagraph.getLineBottom(0) - bodyLayout.multiParagraph.getLineTop(0)).toInt()
            }
        // Bottom-crop-safe reserve for the page indicator (decisions #94):
        // measured from a real layout of the indicator text (its rendered
        // height) plus the 2 * XS vertical padding, so the reserve matches
        // what renders — the #87 last-line-visible invariant holds by
        // construction at any font scale.
        val indicatorReservedPx =
            remember(pageWidth, indicatorStyle) {
                textMeasurer
                    .measure(
                        text = AnnotatedString("Passage 9999/9999 (100%)"),
                        style = indicatorStyle,
                        constraints = Constraints(maxWidth = pageWidth),
                    ).size.height
            } + 2 * with(density) { AyvuSpacing.XS.roundToPx() }

        // Conservative bottom reserve: one extra line of slack past the
        // measured footer (crop residual, open-bugs #29). OEM paint-scale
        // drift can under-cover the measured height; one line lost on the
        // last page beats a clipped line.
        val bottomReservePx = indicatorReservedPx + lineHeightPx

        // Immersive top reserve (top-cut follow-up): the book-title overlay
        // is drawn OVER the page — a body line that starts under its
        // translucent band has its glyph tops dimmed (the large chapter
        // title on page 0 is the worst offender: its taller glyphs rise well
        // into the band). In immersive the body reserves the MEASURED band
        // height so no first line ever sits under it; regular mode keeps the
        // floating overlay (pagination unchanged there).
        val overlayTitleStyle = MaterialTheme.typography.labelLarge
        val titleOverlayMeasuredPx =
            remember(pageWidth, state.bookTitle) {
                textMeasurer
                    .measure(
                        text = AnnotatedString(state.bookTitle.ifEmpty { "Reader" }),
                        style = overlayTitleStyle,
                        constraints = Constraints(maxWidth = pageWidth),
                    ).size.height
            }
        val titleOverlayReservedPx =
            if (immersive) titleOverlayMeasuredPx + 2 * with(density) { AyvuSpacing.SM.roundToPx() } else 0

        val totalLines = bodyLayout.lineCount
        val firstPageLines =
            TextPagination.linesPerPage(
                viewportHeight,
                lineHeightPx,
                reservedPx = titleHeightPx + titleGapPx + bottomReservePx + titleOverlayReservedPx,
            )
        val fullPageLines =
            TextPagination.linesPerPage(viewportHeight, lineHeightPx, reservedPx = bottomReservePx + titleOverlayReservedPx)
        val totalPages = TextPagination.totalPages(totalLines, firstPageLines, fullPageLines)
        // Chrome-toggle reflow (item 1): dropping the bottom PlayerCard grows
        // the viewport, so pages re-derive. Keep the reading place by
        // re-deriving the page from the top visible line of the OLD page
        // (geometry remembered across the toggle). Runs when the settled
        // layout actually changes (the toggle, or a rotation re-measure).
        var lastGeometry by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        LaunchedEffect(immersive, viewportHeight) {
            val current = firstPageLines to fullPageLines
            val old = lastGeometry
            if (old != null && old != current) {
                val line = TextPagination.pageStartLine(page, old.first, old.second)
                val targetPage = TextPagination.pageOf(line, current.first, current.second)
                if (targetPage != page) page = targetPage.coerceIn(0, totalPages - 1)
            }
            lastGeometry = current
        }
        val range =
            remember(page, totalLines, firstPageLines, fullPageLines) {
                val start = TextPagination.pageStartLine(page.coerceIn(0, totalPages - 1), firstPageLines, fullPageLines)
                if (start >= totalLines) {
                    start until start
                } else {
                    start until (start + if (page <= 0) firstPageLines else fullPageLines).coerceAtMost(totalLines)
                }
            }
        val startChar = if (page <= 0 || range.isEmpty()) 0 else bodyLayout.multiParagraph.getLineStart(range.first)
        val endChar = if (range.isEmpty()) startChar else bodyLayout.multiParagraph.getLineEnd(range.last)
        // Line each passage begins on — tap-mapping.
        val passageStartLines =
            remember(bodyLayout, passageOffsets, totalLines) {
                passageOffsets.map { bodyLayout.getLineForOffset(it.coerceAtMost(maxOf(0, chapterText.length - 1))) }
            }
        // The passage the current page starts at (item 3): the first passage
        // whose start line lies on the page, or the passage containing the
        // page's first line when the whole page sits inside one long
        // paragraph (no passage starts on it). Published to the parent so
        // the play action starts audio from the top of the visible page —
        // always the passage that BEGINS here, so follow never snaps the
        // view back to the passage's earlier start page.
        val firstPassageOnPage =
            passageStartLines
                .indexOfFirst { it in range.first..range.last }
                .takeIf { it >= 0 }
                ?: passageStartLines.indexOfLast { it <= range.first }.coerceAtLeast(0)
        SideEffect { pageStartPassage.value = firstPassageOnPage }

        // The page holding the ACTIVE sentence's first char — ONE shared
        // source for both follow effects (item 3). Null when there is no
        // active sentence to anchor on.
        fun activeSentencePage(): Int? {
            val span =
                activeSentenceRange(passageOffsets, state.passageIndex, state.passageText, state.activeSentenceIndex)
                    ?: return null
            val line = bodyLayout.getLineForOffset(span.first.coerceAtMost(maxOf(0, chapterText.length - 1)))
            return TextPagination.pageOf(line.coerceAtMost(maxOf(0, totalLines - 1)), firstPageLines, fullPageLines)
        }

        // Playback turns the page when the ACTIVE SENTENCE leaves it
        // (item 3). The effect re-fires on every geometry change (the
        // immersive toggle re-derives firstPageLines…) — dedupe on the
        // (chapter, passage, active sentence) triple: an unchanged triple
        // means the viewport only reflowed, and follow must not yank the
        // page. The claim is taken only when this effect actually acts, so
        // the paused effect below still runs on open/resume.
        LaunchedEffect(
            state.chapterIndex,
            state.passageIndex,
            state.activeSentenceIndex,
            state.phase,
            totalPages,
            firstPageLines,
            fullPageLines,
        ) {
            val key = Triple(state.chapterIndex, state.passageIndex, state.activeSentenceIndex)
            if (key == lastFollowKey) return@LaunchedEffect
            if (state.phase == PlayerPhase.PLAYING || state.phase == PlayerPhase.LOADING) {
                lastFollowKey = key
                val target = activeSentencePage() ?: return@LaunchedEffect
                if (target != page) page = target.coerceIn(0, totalPages - 1)
            }
        }
        // A chapter opened WITHOUT playback (boundary turn, share-open, resume)
        // shows the presented passage's page, not page one — the backward turn
        // lands on the previous chapter's LAST passage, so its ending page is
        // what the reader must open at. Chapter no-ops (book edges) change no
        // key here, so the page stays put. Shares the playing effect's dedupe:
        // a pause or chrome-toggle re-measure keeps the triple, so the page
        // must not move.
        LaunchedEffect(
            state.chapterIndex,
            state.passageIndex,
            state.activeSentenceIndex,
            firstPageLines,
            fullPageLines,
            totalPages,
        ) {
            if (state.phase != PlayerPhase.PLAYING && state.phase != PlayerPhase.LOADING) {
                val key = Triple(state.chapterIndex, state.passageIndex, state.activeSentenceIndex)
                if (key == lastFollowKey) return@LaunchedEffect
                lastFollowKey = key
                val targetPage = activeSentencePage() ?: return@LaunchedEffect
                if (targetPage != page) page = targetPage.coerceIn(0, totalPages - 1)
            }
        }

        val pageSlice = chapterText.substring(startChar.coerceIn(0, chapterText.length), endChar.coerceIn(startChar, chapterText.length))
        val pageText =
            remember(pageSlice, startChar, endChar, activeSpan, highlightColor, pressedPassage, pressedColor) {
                buildAnnotatedString {
                    append(pageSlice)
                    if (activeSpan != null) {
                        val from = maxOf(activeSpan.first, startChar)
                        val to = minOf(activeSpan.second, endChar)
                        if (from < to) {
                            addStyle(
                                SpanStyle(background = highlightColor, fontWeight = FontWeight.Bold),
                                from - startChar,
                                to - startChar,
                            )
                        }
                    }
                    // Pressed-passage feedback (decisions #94): surfaceVariant
                    // fill, no bold — distinct from the read-along highlight.
                    val pressed = pressedPassage
                    if (pressed != null) {
                        val passageStart = passageOffsets.getOrNull(pressed) ?: 0
                        val passageEnd = passageOffsets.getOrNull(pressed + 1) ?: chapterText.length
                        val from = maxOf(passageStart, startChar)
                        val to = minOf(passageEnd, endChar)
                        if (from < to) {
                            addStyle(SpanStyle(background = pressedColor), from - startChar, to - startChar)
                        }
                    }
                }
            }

        // Phase read fresh inside the pointer handler (item 4): the
        // pointerInput block does not restart when state.phase changes, so
        // it must read the CURRENT phase through a rememberUpdatedState ref.
        val phaseRef = rememberUpdatedState(state.phase)

        // A manual page turn stops playback (item 4) — only the IN-CHAPTER
        // paths call this; chapter-boundary turns go through openChapter,
        // which stops playback on its own. Pausing re-fires the paused
        // follow effect, which dedupes on the unchanged (chapter, passage,
        // active sentence) triple — the page never yanks back, and no grace
        // timer is needed.
        fun stopPlaybackOnManualTurn() {
            if (phaseRef.value == PlayerPhase.PLAYING || phaseRef.value == PlayerPhase.LOADING) {
                viewModel.pause()
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    // Immersive top-cut fix: the body content (page-0 chapter
                    // title included) must RENDER below the floating title
                    // overlay — the reserve math counts this offset, and the
                    // padding places it.
                    .padding(top = if (immersive) with(density) { titleOverlayReservedPx.toDp() } else 0.dp)
                    .clipToBounds()
                    .pointerInput(state.bookId, totalPages, state.chapterPassages, immersive, longPressTarget == null) {
                        // The context menu is open: the page is inert — an
                        // outside tap dismisses the menu (G2), it must not
                        // turn the page. Opening the menu restarts this block
                        // via the key above; this early-return keeps the
                        // gesture loop from consuming dismiss taps.
                        if (longPressTarget != null) return@pointerInput
                        // Passage under a y coordinate — the middle-third tap
                        // mapping, shared by the press highlight and the
                        // chrome-toggle tap. [requirePositioned] keeps the
                        // B3 highlight's "nothing loaded" guard; the long-press
                        // menu maps any real passage so a freshly opened,
                        // never-played book can still "Play from here" (G2).
                        val pageWidthPx = size.width / 3f
                        val swipePx = SWIPE_PAGE_THRESHOLD.toPx()

                        fun passageAt(
                            y: Float,
                            requirePositioned: Boolean,
                        ): Int? {
                            val topInset = if (immersive) titleOverlayReservedPx else 0
                            val titleBlock = (if (page <= 0) titleHeightPx + titleGapPx else 0) + topInset
                            val lineInPage = ((y - titleBlock).toInt() / lineHeightPx).coerceAtLeast(0)
                            val globalLine = range.first + lineInPage
                            val index = passageStartLines.indexOfLast { it <= globalLine }
                            return index.takeIf { it >= 0 && (!requirePositioned || state.positioned) }
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            // Press feedback only for middle-zone touches — the
                            // zone whose double tap toggles the chrome (B3).
                            if (down.position.x >= pageWidthPx && down.position.x <= pageWidthPx * 2f) {
                                pressedPassage = passageAt(down.position.y, requirePositioned = true)
                            }
                            // Long-press race (G2): the platform long-press
                            // deadline is measured from THIS down and captured
                            // once, so a jittery finger inside the touch slop
                            // cannot extend it (the detectTapGestures deadline
                            // model). The loop races each pointer await against
                            // the remaining time; the same loop keeps the
                            // swipe and quick-up paths unchanged.
                            val longPressDeadline = down.uptimeMillis + longPressTimeoutMs
                            var timedOut = false
                            var dragX = 0f
                            var paged = false
                            while (true) {
                                val remaining = longPressDeadline - SystemClock.uptimeMillis()
                                if (remaining <= 0) {
                                    timedOut = true
                                    break
                                }
                                val event =
                                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                        ?: run {
                                            timedOut = true
                                            break
                                        }
                                val change = event.changes.firstOrNull() ?: break
                                if (change.positionChanged()) {
                                    dragX += change.positionChange().x
                                    if (dragX > swipePx || dragX < -swipePx) {
                                        paged = true
                                        change.consume()
                                    }
                                }
                                if (event.changes.all { it.changedToUp() }) break
                            }
                            // Long-press wins only if the deadline beat the
                            // up event — a completed swipe (paged) is never
                            // pre-empted by the deadline.
                            val longPressed = timedOut && !paged
                            if (longPressed) {
                                // The menu fires at the deadline, finger still
                                // down (anchor ready); the highlight clears —
                                // the menu is its feedback. A middle-zone
                                // long-press breaks out BEFORE the tap logic,
                                // so it never records a tap time and cannot
                                // corrupt the double-tap window. The menu
                                // targets the passage under the finger with no
                                // positioned guard — "Play from here" works on
                                // an opened-but-never-played book (G2).
                                pressedPassage = null
                                val index = passageAt(down.position.y, requirePositioned = false)
                                if (index != null) {
                                    longPressTarget = LongPressTarget(index, down.position)
                                }
                                // Drain until every pointer lifts so the up is
                                // not re-consumed as a tap by the restarted
                                // block (the menu-open pointerInput restart may
                                // cancel this early — equally safe).
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.all { it.changedToUp() }) break
                                }
                            } else {
                                pressedPassage = null
                                if (paged) {
                                    val delta = if (dragX < 0f) 1 else -1
                                    when {
                                        page + delta < 0 -> viewModel.openChapter(bookId, -1)
                                        page + delta > totalPages - 1 -> viewModel.openChapter(bookId, +1)
                                        else -> {
                                            stopPlaybackOnManualTurn()
                                            page = page + delta
                                        }
                                    }
                                } else {
                                    val x = down.position.x
                                    when {
                                        x < pageWidthPx -> {
                                            if (page <= 0) {
                                                viewModel.openChapter(bookId, -1)
                                            } else {
                                                stopPlaybackOnManualTurn()
                                                page = page - 1
                                            }
                                        }
                                        x > pageWidthPx * 2f -> {
                                            if (page >= totalPages - 1) {
                                                viewModel.openChapter(bookId, +1)
                                            } else {
                                                stopPlaybackOnManualTurn()
                                                page = page + 1
                                            }
                                        }
                                        else -> {
                                            // Middle double tap: toggle the
                                            // immersive chrome (item 2). A single
                                            // middle tap does nothing; the press
                                            // highlight above still gives
                                            // feedback.
                                            val now = System.currentTimeMillis()
                                            val since = now - lastMiddleTapAt
                                            if (since >= doubleTapMinMs && since <= doubleTapTimeoutMs) {
                                                lastMiddleTapAt = 0L
                                                onToggleImmersive()
                                            } else {
                                                lastMiddleTapAt = now
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
        ) {
            if (page <= 0 && chapterTitle.isNotBlank()) {
                Text(
                    chapterTitle,
                    style = titleStyle,
                    modifier =
                        Modifier.padding(
                            bottom = AyvuSpacing.MD,
                            start = AyvuSpacing.LG,
                            end = AyvuSpacing.LG,
                        ),
                )
            }
            if (pageSlice.isNotBlank()) {
                Text(
                    text = pageText,
                    style = bodyStyle,
                    modifier = Modifier.padding(horizontal = AyvuSpacing.LG),
                )
            }
            // Immersive hides the in-body footer — the overlay's minimal
            // player carries the same passage indicator (item 1).
            val passageLabel =
                if (immersive) {
                    null
                } else {
                    PlaybackUiState.passageIndicatorLabel(
                        state.bookPassageIndex,
                        state.bookPassageCount,
                    )
                }
            if (passageLabel != null) {
                Text(
                    passageLabel,
                    style = indicatorStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = AyvuSpacing.XS),
                )
            }
// Long-press paragraph menu (G2): a popup anchored at the press point via
            // DropdownMenu's dedicated [offset] (window-relative to the page);
            // the page gestures are inert while it is open (the pointerInput
            // key) — an outside tap dismisses, never turns the page.
            val target = longPressTarget
            if (target != null) {
                val passage = state.chapterPassages.getOrNull(target.passageIndex)
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = { longPressTarget = null },
                    offset =
                        DpOffset(
                            with(density) { target.position.x.toDp() },
                            with(density) { target.position.y.toDp() },
                        ),
                ) {
                    DropdownMenuItem(
                        text = { Text("Play from here") },
                        onClick = {
                            longPressTarget = null
                            // Same position command as the bookmark jumps and
                            // play-from-view (item 3): starts at the PRESSED
                            // passage, not the narrated one (G2 acceptance).
                            if (passage != null) {
                                viewModel.playPosition(bookId, state.chapterIndex, target.passageIndex)
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy text") },
                        onClick = {
                            longPressTarget = null
                            if (passage != null) {
                                // setClipEntry is the suspend modern clipboard
                                // API (BOM 2026.06.01); ClipEntry wraps a
                                // native ClipData on Android.
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("passage", passage)))
                                }
                                Toast.makeText(context, "Passage copied", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Char offset of each passage's start in the joined chapter text. */
private fun computePassageOffsets(passages: List<String>): IntArray {
    val offsets = IntArray(passages.size)
    var acc = 0
    for ((index, passage) in passages.withIndex()) {
        offsets[index] = acc
        acc += passage.length + 2 // "\n\n"
    }
    return offsets
}

private data class SentenceSpan(
    val offset: Int,
    val length: Int,
)

/** The open long-press context menu (G2): the passage under the finger and
 * the press point the menu anchors at (page-content-local coordinates). */
private data class LongPressTarget(
    val passageIndex: Int,
    val position: Offset,
)

/** Char spans of the sentences, split after `.!?…` and following whitespace. */
private fun sentenceSpans(text: String): List<SentenceSpan> {
    val spans = mutableListOf<SentenceSpan>()
    var start = 0
    var index = 0
    while (index < text.length) {
        if (text[index] in ".!?…") {
            var end = index + 1
            while (end < text.length && text[end].isWhitespace()) end++
            spans += SentenceSpan(start, end - start)
            start = end
            index = end
        } else {
            index++
        }
    }
    if (start < text.length) spans += SentenceSpan(start, text.length - start)
    return spans
}

/** Global char range [start, endExclusive) of the active sentence in the joined chapter text. */
private fun activeSentenceRange(
    passageOffsets: IntArray,
    passageIndex: Int,
    passageText: String,
    activeSentenceIndex: Int,
): Pair<Int, Int>? {
    val passageOffset = passageOffsets.getOrNull(passageIndex) ?: return null
    val sentence = sentenceSpans(passageText).getOrNull(activeSentenceIndex) ?: return null
    val start = passageOffset + sentence.offset
    return start to (start + sentence.length)
}

/** Horizontal drag distance that turns a page. */
private val SWIPE_PAGE_THRESHOLD = 64.dp

/** Walks Context wrappers (ContextThemeWrapper, …) to the host [Activity] —
 * the immersive system-bar controller needs the window (item 1). First
 * WindowInsets use in the repo; LocalActivity-free by construction. */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** The system-bars hide/show fade duration — the chrome holds for this
 * long after a toggle so the body never reflows mid-fade (top-cut fix). */
private const val SYSTEM_BARS_ANIM_MS = 350L
