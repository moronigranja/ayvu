package io.github.moronigranja.ayvu.featureplayer.ui
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.moronigranja.ayvu.player.ChapterDisplay
import io.github.moronigranja.ayvu.player.DisplayBlock
import io.github.moronigranja.ayvu.player.DisplayKind
import io.github.moronigranja.ayvu.player.DisplayMode
import io.github.moronigranja.ayvu.player.PlaybackUiState
import io.github.moronigranja.ayvu.player.PlayerCommands
import io.github.moronigranja.ayvu.player.PlayerPhase
import io.github.moronigranja.ayvu.player.PlayerPosition
import io.github.moronigranja.ayvu.player.SleepTimer
import io.github.moronigranja.ayvu.player.TextPagination
import io.github.moronigranja.ayvu.player.chapterMenuLabel
import io.github.moronigranja.ayvu.ui.AyvuSpacing
import io.github.moronigranja.ayvu.ui.CoverageProgress
import io.github.moronigranja.ayvu.ui.EmptyState
import io.github.moronigranja.ayvu.ui.LoadingState
import io.github.moronigranja.ayvu.ui.PlayerCard
import io.github.moronigranja.ayvu.ui.SectionHeader
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
 * Page gestures (horizontal swipe or side-zone taps turn pages and dismiss
 * the chrome; a middle tap toggles the immersive chrome; a long press opens
 * the paragraph menu — Play from here / Copy text) and the bookmark menu
 * (add + jump) round it out. The chrome is shown briefly on entry, then
 * auto-hides to the immersive overlays. With a display language set in the
 * interleaved layout, long-pressing the text selects a word/phrase (drag to
 * extend) and offers a Translate/Copy card. Follow turns the page with the
 * ACTIVE sentence, not the passage start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    startAt: PlayerPosition? = null,
    /** Called right after the one-shot share-target play is dispatched; the host clears
     *  its navigation target here so no later composition can replay it (owner report
     *  2026-09-22). */
    onStartTargetPlayed: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // The projected chapter (original passages + the reader-owned translation
    // map — never interleaves the published list, so the bookmark/resume
    // invariant holds by construction).
    val chapterDisplay by viewModel.chapterDisplay.collectAsState()
    var chapterMenu by remember { mutableStateOf(false) }
    var bookmarkMenu by remember { mutableStateOf(false) }
    var voiceSheet by remember { mutableStateOf(false) }
    val engineVoice by viewModel.engineVoice.collectAsState()
    // Chrome (immersive) state: rememberSaveable so rotation keeps the choice.
    // On entry the chrome is SHOWN, then auto-hidden by the intro effect below.
    // `introDone`/`chromeTouched` are saved too, so a rotation neither re-runs
    // the intro (it runs once per reader entry) nor lets the timer fight a toggle
    // or a page turn the user just made.
    var immersive by rememberSaveable { mutableStateOf(false) }
    var introDone by rememberSaveable { mutableStateOf(false) }
    var chromeTouched by rememberSaveable { mutableStateOf(false) }
    // System-bar fade sync (unchanged): hold the chrome until the bars settle.
    // The insets hide/show is ANIMATED (~300ms), so switching the layout the
    // same frame reflows the body under a half-faded bar — the occasional
    // "top cut". Held in BOTH directions.
    var barsSettled by remember { mutableStateOf(true) }

    // Chrome show/hide; the fade hold runs in BOTH directions (top-cut fix).
    // [barsSettled] flips false SYNCHRONOUSLY (same frame as [immersive]) so
    // the layout holds its pre-toggle chrome while the system bars fade;
    // [showOverlays] (the full-bleed body) appears only once the bars have
    // fully left — no mid-fade reflow. The effect below re-settles after the
    // fade; the controller hide/show runs off the same [immersive].
    fun setImmersive(hidden: Boolean) {
        chromeTouched = true
        if (hidden != immersive) {
            barsSettled = false
            immersive = hidden
        }
    }

    fun toggleImmersive() = setImmersive(!immersive)

    // Intro: show the chrome for CHROME_INTRO_MS on entry, then hide it — unless
    // the user already toggled or turned a page (`chromeTouched`).
    LaunchedEffect(Unit) {
        if (introDone) return@LaunchedEffect
        delay(CHROME_INTRO_MS)
        introDone = true
        if (!chromeTouched) setImmersive(true)
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
    // A return to the foreground must never strand the reader on
    // "Opening book…". The drift effect above re-fires only when the service
    // state CHANGES, and an open dispatched while the app was stopped was
    // refused by startForegroundService (and its bounded retry gave up).
    // Re-dispatch here. The first ON_START of this composition is skipped: the
    // drift effect already dispatched that open, and two ACTION_OPENs at mount
    // would be one wasted command (the second would supersede the first).
    val currentPlayback by rememberUpdatedState(state)
    var sawFirstStart by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (sawFirstStart && currentPlayback.bookId != bookId && currentPlayback.failure == null) {
            viewModel.open(bookId)
        }
        sawFirstStart = true
    }
    // A share target (S3 "listen here") starts audio at the passage ONCE: the host clears
    // the target when this fires, so a re-entry (or a recreation that restored it) cannot
    // replay it — while a rotation *before* the dispatch still plays, because the target
    // is then still set.
    LaunchedEffect(startAt) {
        if (startAt != null) {
            viewModel.playPosition(bookId, startAt.chapterIndex, startAt.passageIndex)
            onStartTargetPlayed()
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
    // With the chapter loaded this plays from the VIEW's position; while it is
    // still loading (the open in flight) the VM remembers the press and
    // completes it against the presented position — dispatching a resume there
    // raced the open and could start at passage 0/0 (owner report 2026-09-17).
    val playFromView: () -> Unit = {
        viewModel.playFromView(state.chapterIndex, pageStartPassage.value)
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
                                            viewModel.openPosition(
                                                bookId,
                                                bookmark.chapterIndex,
                                                bookmark.passageIndex,
                                                bookmark.offsetSeconds,
                                            )
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
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    // Android 15+ edge-to-edge: with the system bars hidden the
                    // display-cutout inset is the only thing left between the page
                    // (and the floating title overlay) and the camera hole. Gated on
                    // showOverlays — not immersive — so it flips with the rest of the
                    // immersive body and never reflows mid bar-fade (top-cut fix #125).
                    .then(if (showOverlays) Modifier.displayCutoutPadding() else Modifier),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            ) {
                when {
                    state.failure != null -> EmptyState(state.failure!!)
                    // An open is in flight and there is nothing to render yet:
                    // the holder still carries the previous book (or the
                    // process default with bookId == null), so the text is not
                    // absent, it is not here yet. Without this branch the
                    // IDLE-empty case below shows "Tap play…" over every cold
                    // start and every drift recovery (#130), while the open
                    // command is still doing its disk I/O.
                    state.chapterPassages.isEmpty() && state.bookId != bookId -> LoadingState("Opening book…", Modifier.fillMaxSize())
                    state.chapterPassages.isEmpty() && state.phase == PlayerPhase.IDLE ->
                        EmptyState("Tap play to start listening from this book.")
                    else ->
                        PaginatedChapter(
                            state = state,
                            display = chapterDisplay,
                            bookId = bookId,
                            viewModel = viewModel,
                            pageStartPassage = pageStartPassage,
                            readingPassage = viewModel.readingPassageFor(bookId, state.chapterIndex),
                            immersive = showOverlays,
                            onToggleImmersive = ::toggleImmersive,
                            onHideChrome = { setImmersive(true) },
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
                    // Same source as the in-body footer: the visible page's
                    // first passage, book-wide (pageStartPassage is published by
                    // PaginatedChapter for play-from-view).
                    val passageLabel =
                        PlaybackUiState.passageIndicatorLabel(
                            chapterPrefix(state) + pageStartPassage.value,
                            state.bookPassageCount,
                        )
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
                    // Read-in-language (decisions #114): the picker's own
                    // sections — "Show translation in" (+ mode) and "Read
                    // aloud in" — render above the voice rows; the pack
                    // download row shows until staged.
                    io.github.moronigranja.ayvu.ui.ReadInLanguagePicker(
                        state = translate,
                        onSelect = {
                            viewModel.setTranslateTarget(it)
                            voiceSheet = false
                        },
                        onDownload = viewModel::downloadTranslatePack,
                        // A display change re-projects the text — the sheet
                        // stays open so the mode toggle can follow; it does
                        // NOT dispatch a session rebuild (no changeVoice).
                        onDisplaySelect = { viewModel.setDisplayTarget(it) },
                        onModeSelect = { viewModel.setDisplayMode(it) },
                        onTranslateVoiceSelect = viewModel::setTranslateVoice,
                        onDownloadVoice = viewModel::downloadVoicePacks,
                    )
                    io.github.moronigranja.ayvu.ui.EngineVoicePicker(
                        state = engineVoice,
                        voiceLabel = "Voice",
                        onEngineSelect = viewModel::setEngine,
                        onSelect = viewModel::selectVoice,
                        onUseDefault = viewModel::useDefaultVoice,
                        onToggleFavorite = viewModel::toggleFavorite,
                        onPreview = viewModel::previewVoice,
                        onStopPreview = viewModel::stopPreview,
                        onDownload = viewModel::downloadVoicePacks,
                        // Missing packs leave the reader for the Settings
                        // pane (decisions #166 follow-up) — the sheet closes
                        // itself, the host navigates.
                        onOpenSettings = {
                            voiceSheet = false
                            onOpenSettings()
                        },
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
 * Gestures: horizontal swipe or side-zone taps turn pages and dismiss the
 * chrome; a middle tap toggles the immersive chrome (both ways); a long
 * press opens the paragraph context menu (Play from here / Copy text, G2).
 * A selected word/phrase (selection mode) suppresses the page gestures: a
 * page tap dismisses the selection instead of toggling. Follow turns
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
    display: ChapterDisplayState,
    bookId: String,
    viewModel: ReaderViewModel,
    pageStartPassage: MutableState<Int>,
    /** The stored reading place's chapter-local passage when it belongs to the
     * rendered chapter — the reader's own restore target (distinct from the
     * audio position the service publishes). */
    readingPassage: Int? = null,
    immersive: Boolean = false,
    onToggleImmersive: () -> Unit = {},
    /** Hide the chrome — a page turn dismisses it (swipe / side-zone tap / chapter jump). */
    onHideChrome: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (display.blocks.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    val pressedColor = MaterialTheme.colorScheme.surfaceVariant
    // Differentiation of translations and placeholders — colour + paragraph
    // indent, never font size (the line grid is a uniform pitch sampled from
    // lines 0→1, so a differently-pitched block would corrupt linesPerPage).
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
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

    // Selection mode: selection handles are offered only when a display
    // language is set AND the original + translation are shown together
    // (interleaved). A selection made in a translation-only layout cannot be
    // attributed to an original passage, so it is not offered.
    //
    // The selection is OUR own (Compose's SelectionContainer/Selection API is
    // internal): a page-local [TextRange], set by long-press + drag on the
    // body text, highlighted with a span and flanked by draggable handles.
    val selectionMode = display.displayTarget != null && display.displayMode == DisplayMode.INTERLEAVED
    var selection by remember { mutableStateOf<TextRange?>(null) }
    val selectionRef = rememberUpdatedState(selection)
    // The word the long-press picked — the anchor a drag extends from.
    var dragWord by remember { mutableStateOf<TextRange?>(null) }
    // The body Text's measured layout — the card and handles read the
    // selection's bounding boxes from it. Declared here (outside the page
    // `if`) so the overlay block below can read it.
    var pageLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val selectionColor = MaterialTheme.colorScheme.primaryContainer

    BoxWithConstraints(modifier = modifier) {
        val viewportHeight = constraints.maxHeight
        // Long-press timeout for the paragraph menu (G2) — platform timing,
        // captured once here, stable for the session.
        val longPressTimeoutMs = LocalViewConfiguration.current.longPressTimeoutMillis
        val pageWidth = (constraints.maxWidth - horizontalPadPx * 2).coerceAtLeast(1)
        val chapterTitle = state.chapters.getOrNull(state.chapterIndex).orEmpty()
        // The projected chapter: original blocks + the reader-owned
        // translation map (Pending/Unavailable render their placeholders).
        val blocks = display.blocks
        // A page turn, a re-projection or a mode switch invalidates any live
        // selection and lookup (offsets no longer name the same text).
        LaunchedEffect(page, blocks, selectionMode) {
            selection = null
            dragWord = null
            viewModel.clearWordLookup()
        }
        val chapterText = remember(blocks) { ChapterDisplay.join(blocks) }
        val passageOffsets = remember(blocks) { ChapterDisplay.offsets(blocks) }
        val firstBlockOfPassage =
            remember(blocks, state.chapterPassages.size) {
                ChapterDisplay.firstBlockOfPassage(blocks, state.chapterPassages.size)
            }
        // The block whose text is the utterance being narrated — the
        // read-along highlight anchor (F.5 highlight ownership).
        val highlightBlock = highlightBlockOf(display, state, firstBlockOfPassage, blocks)
        val activeSpan =
            highlightBlock?.let { (blockIndex, text) ->
                activeSentenceRange(passageOffsets, blockIndex, text, state.activeSentenceIndex)
            }
        val bodyLayout =
            remember(chapterText, pageWidth, bodyStyle, activeSpan, blocks, passageOffsets) {
                val annotated =
                    buildAnnotatedString {
                        append(chapterText)
                        // The full text carries the block styles EARLY so the
                        // measured layout's paragraph structure (the
                        // translation indent) IS the render's — pagination
                        // measures what the page draws.
                        applyBlockStyles(
                            blocks = blocks,
                            offsets = passageOffsets,
                            muted = mutedColor,
                            startOffset = 0,
                            rangeStart = 0,
                            rangeEnd = chapterText.length,
                        )
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
        // Chrome-toggle reflow (item 1): dropping the bottom PlayerCard grows
        // the viewport, so pages re-derive. Keep the reading place by
        // re-deriving the page from the top visible line of the OLD page
        // (geometry remembered across the toggle). Runs when the settled
        // layout actually changes (the toggle, or a rotation re-measure).
        var lastGeometry by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var lastBlocks by remember { mutableStateOf<List<DisplayBlock>?>(null) }
        // The identity of the page the reader is LOOKING at: its first
        // passage (the indicator's own value). Reflows re-anchor on THIS —
        // not on the audio passage, which can lag a hand-turned paused page.
        var anchorPassage by remember { mutableIntStateOf(0) }
        // Line each PASSAGE begins on — tap-mapping. Original-keyed: the line
        // of each passage's FIRST block (the Original in interleaved mode, the
        // translation in translated-only), so a tap and the long-press menu
        // keep naming the ORIGINAL passage.
        val passageStartLines =
            remember(bodyLayout, passageOffsets, firstBlockOfPassage, chapterText.length) {
                firstBlockOfPassage.map { blockIndex ->
                    val offset = passageOffsets.getOrNull(blockIndex) ?: 0
                    bodyLayout.getLineForOffset(offset.coerceAtMost(maxOf(0, chapterText.length - 1)))
                }
            }
        // Keep-together pagination (decisions #166 follow-up): a passage's
        // Original and its Translation must never be split across a page
        // break. Translation-passage groups feed TextPagination.layout — the
        // break lands after such a group, never inside it.
        val translationPassages =
            remember(blocks) { blocks.filter { it.kind != DisplayKind.Original }.map { it.passageIndex }.toSet() }
        val keepTogether =
            remember(translationPassages, passageStartLines, totalLines) {
                translationPassages.sorted().mapNotNull { p ->
                    val start = passageStartLines.getOrNull(p) ?: return@mapNotNull null
                    val end = passageStartLines.getOrNull(p + 1) ?: totalLines
                    if (end > start) start until end else null
                }
            }
        val pages =
            remember(bodyLayout, totalLines, firstPageLines, fullPageLines, keepTogether) {
                TextPagination.layout(totalLines, firstPageLines, fullPageLines, keepTogether)
            }
        val totalPages = pages.pageCount
        // Fires on ANY reflow — the chrome/geometry AND the projection (a
        // landing translation or a display-mode switch re-paginates the
        // chapter). The reading place is the PAGE's first passage: every
        // reflow re-anchors on the pre-reflow anchor passage's page. Keeping
        // a top LINE instead lets the page START drift backwards when the
        // geometry grows (the immersive toggle: a larger page starts
        // earlier, so the indicator dropped 3 passages backwards while the
        // line stayed put — the reported bug); keeping a page NUMBER drifts
        // dozens of passages on a mode switch.
        LaunchedEffect(immersive, viewportHeight, blocks) {
            val current = firstPageLines to fullPageLines
            val old = lastGeometry
            val blocksChanged = lastBlocks != null && lastBlocks != blocks
            if (blocksChanged || (old != null && old != current)) {
                val anchor =
                    firstBlockOfPassage.getOrNull(anchorPassage)?.let { anchorPassage }
                        ?: firstBlockOfPassage.getOrNull(state.passageIndex)?.let { state.passageIndex }
                val offset = anchor?.let { passageOffsets.getOrNull(firstBlockOfPassage[it]) }
                val line =
                    offset?.let {
                        bodyLayout.getLineForOffset(it.coerceAtMost(maxOf(0, chapterText.length - 1)))
                    }
                val targetPage = line?.let { pages.pageOf(it) }
                if (targetPage != null && targetPage != page) page = targetPage.coerceIn(0, totalPages - 1)
            }
            lastGeometry = current
            lastBlocks = blocks
        }
        // A newly PRESENTED passage (an open that restored the reading place, a
        // bookmark jump, a chapter turn, or a playback advance) must land on its
        // own page: the follow effects only track the ACTIVE sentence while
        // playing, so a READING open would otherwise show the chapter's FIRST
        // page — and the reading-place write below would then clobber the
        // restored place with it. Armed per presented position and re-applied
        // until pagination settles (chapter text lands progressively); a manual
        // page turn disarms it so the reader keeps their place.
        var pendingPassage by remember(state.chapterIndex) { mutableStateOf<Int?>(null) }
        var armedChapter by remember { mutableIntStateOf(-1) }
        // Read non-reactively: a reading-place write (this screen's own) must not
        // re-arm the snap and yank the page back to the audio position.
        val readingPassageRef = rememberUpdatedState(readingPassage)
        LaunchedEffect(state.chapterIndex, state.passageIndex, state.phase) {
            // The holder can still carry the PREVIOUS book for a frame (an open
            // in flight): that position belongs to another book and must never
            // be armed — or written below — as this book's reading place.
            if (state.bookId != bookId) {
                pendingPassage = null
                return@LaunchedEffect
            }
            // Only a READING presentation owns the page: the playback phases
            // (LOADING/PLAYING/PAUSED) keep the follow effects' page and must
            // never be yanked here (a pause must not move the view).
            if (state.phase != PlayerPhase.IDLE) return@LaunchedEffect
            val fresh = armedChapter != state.chapterIndex
            armedChapter = state.chapterIndex
            pendingPassage =
                if (fresh) readingPassageRef.value ?: state.passageIndex else state.passageIndex
        }
        LaunchedEffect(pendingPassage, totalPages, firstPageLines, fullPageLines, blocks) {
            val pending = pendingPassage ?: return@LaunchedEffect
            if (totalPages <= 0) return@LaunchedEffect
            val firstBlock = firstBlockOfPassage.getOrNull(pending) ?: return@LaunchedEffect
            val offset = passageOffsets.getOrNull(firstBlock) ?: return@LaunchedEffect
            val line = bodyLayout.getLineForOffset(offset.coerceAtMost(maxOf(0, chapterText.length - 1)))
            val target = pages.pageOf(line.coerceIn(0, maxOf(0, totalLines - 1))).coerceIn(0, totalPages - 1)
            if (target != page) page = target
            anchorPassage = pending
        }
        val range =
            remember(page, totalLines, pages) {
                val p = page.coerceIn(0, totalPages - 1)
                val start = pages.startLine(p)
                val end = if (p + 1 < totalPages) pages.startLine(p + 1) else totalLines
                if (start >= totalLines) {
                    start until start
                } else {
                    start until end.coerceAtMost(totalLines)
                }
            }
        // The gesture block's pointerInput keys (bookId/totalPages/blocks/immersive/
        // longPressTarget) do not include `page`: a manual or follow page turn leaves the
        // block holding the page geometry of the page it last (re)started on, so the
        // y→passage mapping named an earlier passage (owner report 2026-09-22; device-
        // measured shift −3 after one turn, −5 after two). Read the page-dependent inputs
        // through rememberUpdatedState — always the current page's, and no block restart
        // (a restart would cancel an in-flight gesture when follow turns the page).
        val liveRange = rememberUpdatedState(range)
        val livePassageStartLines = rememberUpdatedState(passageStartLines)
        val startChar = if (page <= 0 || range.isEmpty()) 0 else bodyLayout.multiParagraph.getLineStart(range.first)
        val endChar = if (range.isEmpty()) startChar else bodyLayout.multiParagraph.getLineEnd(range.last)
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
        // Track the page's first passage across every page change (follow,
        // manual turns, reflow snaps) — the next reflow's anchor — and record
        // the visible page as the book's reading place (the reopen target).
        // The asserted passage (an open's reading place / a bookmark jump) must
        // not be overwritten before the page shows it. "Shows it" is line-span
        // overlap, not equality: a page break can land mid-paragraph, so the
        // asserted passage is often on the page without being
        // `firstPassageOnPage`. Once it is on the page the assertion is
        // DROPPED — every later page change (playback follow, a reflow, a
        // manual turn) records the place again. Leaving it armed was the
        // freeze: the write stayed suppressed for a whole listen session, and
        // the snap effect re-snapped on every reflow (the immersive toggle).
        LaunchedEffect(page, firstPassageOnPage, state.chapterIndex, pendingPassage, totalLines) {
            // Stale-frame guard: the holder may still carry the previous book
            // (an open in flight) — never write its position to this book.
            if (state.bookId != bookId) return@LaunchedEffect
            val asserted = pendingPassage
            if (asserted != null) {
                // `totalLines` is a key so this re-evaluates once the chapter
                // text lands and pagination settles: the first frames of an
                // open run with an empty `range` (and page 0), so the line-span
                // test below can MISS the assertion — without a geometry key
                // the effect would never run again and the assertion would stay
                // armed for the whole session (the freeze it fixes).
                val firstLine = passageStartLines.getOrNull(asserted) ?: return@LaunchedEffect
                val endLine = passageStartLines.getOrNull(asserted + 1) ?: totalLines
                if (firstLine > range.last || endLine <= range.first) return@LaunchedEffect
                pendingPassage = null
            }
            // Anchor AFTER the guard: a not-yet-landed frame must not reset the
            // reflow anchor (the snap below still owns the page).
            anchorPassage = firstPassageOnPage
            viewModel.noteReadingPosition(state.chapterIndex, firstPassageOnPage)
        }

        // The page holding the ACTIVE sentence's first char — ONE shared
        // source for both follow effects (item 3). When the highlight is
        // suppressed (original audio in translated-only mode — no block
        // matches the utterance), the page-follow anchors on the passage's
        // FIRST block so playback still turns pages.
        fun activeSentencePage(): Int? {
            val anchor =
                activeSpan?.first
                    ?: passageOffsets.getOrNull(firstBlockOfPassage.getOrNull(state.passageIndex) ?: return null)
                    ?: return null
            val line = bodyLayout.getLineForOffset(anchor.coerceAtMost(maxOf(0, chapterText.length - 1)))
            return pages.pageOf(line.coerceAtMost(maxOf(0, totalLines - 1)))
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

        // Prefetch (F.2): every page entry fills the current + next page's
        // passages at display priority — the background decode lands the
        // translation blocks progressively (the keep-place effect above holds
        // the reading place while the page re-paginates). The service dedupes
        // by passage, so repeated entries are cheap.
        LaunchedEffect(bookId, state.chapterIndex, page, totalPages, pages, passageStartLines) {
            fun passagesIn(
                lineStart: Int,
                lineEnd: Int,
            ): List<Int> = passageStartLines.indices.filter { passageStartLines[it] in lineStart until lineEnd }
            val currentStart = pages.startLine(page)
            val currentEnd = if (page + 1 < totalPages) pages.startLine(page + 1) else totalLines
            val current =
                passagesIn(
                    currentStart,
                    currentEnd,
                )
            val next =
                if (page + 1 < totalPages) {
                    val start = pages.startLine(page + 1)
                    val end = if (page + 2 < totalPages) pages.startLine(page + 2) else totalLines
                    passagesIn(start, end)
                } else {
                    emptyList()
                }
            viewModel.prefetchChapter(bookId, state.chapterIndex, current + next)
        }

        val pageSlice = chapterText.substring(startChar.coerceIn(0, chapterText.length), endChar.coerceIn(startChar, chapterText.length))
        // Live loading dots (2-3 per second): a Pending block's placeholder
        // is one fixed-length line, so the animated replacement — dots added
        // one at a time, padded to the SAME length — never changes the wrap
        // or the line count (the measured full layout stays static).
        var pendingDots by remember { mutableIntStateOf(1) }
        val hasPendingOnPage =
            blocks.indices.any { i ->
                val b = blocks[i]
                if (b.kind != DisplayKind.Pending) {
                    false
                } else {
                    val bs = passageOffsets.getOrNull(i) ?: return@any false
                    bs < endChar && bs + ChapterDisplay.renderedLength(b) > startChar
                }
            }
        LaunchedEffect(blocks, startChar, endChar, hasPendingOnPage) {
            if (!hasPendingOnPage) return@LaunchedEffect
            while (true) {
                delay(PENDING_DOTS_TICK_MS)
                pendingDots = if (pendingDots >= PENDING_DOTS_MAX) 1 else pendingDots + 1
            }
        }
        val pendingDotsText =
            remember(pendingDots, blocks) {
                val length =
                    blocks.firstOrNull { it.kind == DisplayKind.Pending }?.let { ChapterDisplay.renderedLength(it) } ?: 0
                animatedPendingDots(pendingDots, length)
            }
        val pageText =
            remember(
                pageSlice,
                startChar,
                endChar,
                activeSpan,
                highlightColor,
                pressedPassage,
                pressedColor,
                blocks,
                passageOffsets,
                mutedColor,
                pendingDotsText,
                selection,
                selectionColor,
            ) {
                buildAnnotatedString {
                    // The page's text, rebuilt from the block chunks inside
                    // [startChar, endChar): non-pending content is the exact
                    // chapter substring (the same chars as [pageSlice], so
                    // the slice re-wraps identically); a FULLY-contained
                    // Pending block renders the animated dots instead —
                    // SAME total length: offsets are +2-separated ("\n\n"),
                    // so a chunk spans through the NEXT block's offset and
                    // the dots cover the block text with the separator
                    // appended verbatim. A mismatched length would shift
                    // every later style span past the text end.
                    for (i in blocks.indices) {
                        val block = blocks[i]
                        val bs = passageOffsets.getOrNull(i) ?: continue
                        // The chunk INCLUDES the "\n\n" separator: the next
                        // block's offset (the chapter end for the last).
                        val be = passageOffsets.getOrNull(i + 1) ?: chapterText.length
                        if (be <= startChar || bs >= endChar) continue
                        val from = maxOf(bs, startChar)
                        val to = minOf(be, endChar)
                        if (block.kind == DisplayKind.Pending && pendingDotsText.isNotEmpty() && from == bs && to == be) {
                            append(pendingDotsText)
                            append(chapterText.substring(bs + ChapterDisplay.renderedLength(block), be))
                        } else {
                            append(chapterText.substring(from, to))
                        }
                    }
                    // The page render carries the SAME block styles as the
                    // measured full layout (color + paragraph indent) — the
                    // slice re-wraps greedily like the measured chapter, so
                    // pagination measures what the page draws.
                    applyBlockStyles(
                        blocks = blocks,
                        offsets = passageOffsets,
                        muted = mutedColor,
                        startOffset = startChar,
                        rangeStart = startChar,
                        rangeEnd = endChar,
                    )
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
                    // Pressed keys the ORIGINAL passage index, so the pressed
                    // range is the passage's FIRST block through the start of
                    // the next passage (the whole displayed passage).
                    val pressed = pressedPassage
                    if (pressed != null) {
                        val passageStartBlock = firstBlockOfPassage.getOrNull(pressed) ?: return@buildAnnotatedString
                        val passageStart = passageOffsets.getOrNull(passageStartBlock) ?: return@buildAnnotatedString
                        val nextPassageBlock = firstBlockOfPassage.getOrNull(pressed + 1)
                        val passageEnd = nextPassageBlock?.let { passageOffsets.getOrNull(it) } ?: chapterText.length
                        val from = maxOf(passageStart, startChar)
                        val to = minOf(passageEnd, endChar)
                        if (from < to) {
                            addStyle(SpanStyle(background = pressedColor), from - startChar, to - startChar)
                        }
                    }
                    // Selection highlight (our own): a primary-container fill
                    // over the selected page-local range.
                    val sel = selection
                    if (sel != null) {
                        val sFrom = minOf(sel.start, sel.end).coerceIn(0, length)
                        val sTo = maxOf(sel.start, sel.end).coerceIn(0, length)
                        if (sFrom < sTo) {
                            addStyle(SpanStyle(background = selectionColor), sFrom, sTo)
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
            // A manual page turn takes over: the presented-passage snap must not
            // pull the view back (the reader keeps their place).
            pendingPassage = null
            if (phaseRef.value == PlayerPhase.PLAYING || phaseRef.value == PlayerPhase.LOADING) {
                viewModel.pause()
            }
        }

        // Derive the selection into a translatable snippet (pure; recomputed
        // each composition): the range must lie wholly inside ONE original
        // block — a range that spans the original/translation separator, or
        // sits in a translation/placeholder block, is not a translatable
        // original snippet (no Translate action; Copy still works from the
        // platform toolbar).
        val selectedSnippet: SelectedSnippet? =
            selection?.let { sel ->
                val s = minOf(sel.start, sel.end)
                val e = maxOf(sel.start, sel.end)
                if (s < 0 || e > pageText.length || s == e) {
                    null
                } else {
                    val gStart = startChar + s
                    val gEnd = startChar + e
                    val inOriginal =
                        blocks.indices.any { i ->
                            val bs = passageOffsets.getOrNull(i) ?: return@any false
                            val be = bs + ChapterDisplay.renderedLength(blocks[i])
                            blocks[i].kind == DisplayKind.Original && gStart >= bs && gEnd <= be
                        }
                    if (inOriginal) SelectedSnippet(pageText.text.substring(s, e).trim(), s, e) else null
                }
            }

        // The overlay clamps need the page area — captured before the Box so
        // the BoxScope receiver does not shadow the constraints receiver.
        val areaWidth = constraints.maxWidth
        val areaHeight = constraints.maxHeight

        Box(Modifier.fillMaxSize()) {
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
                        .pointerInput(state.bookId, totalPages, blocks, immersive, selectionMode, longPressTarget == null) {
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
                                val lines = liveRange.value
                                val globalLine = lines.first + lineInPage
                                val index = livePassageStartLines.value.indexOfLast { it <= globalLine }
                                return index.takeIf { it >= 0 && (!requirePositioned || state.positioned) }
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                // The selection live when this gesture started: a
                                // tap only DISMISSES an existing selection — a
                                // gesture that just created one (long-press) must
                                // not dismiss it on its own up.
                                val selectionAtDown = selectionRef.value
                                // Press feedback only for middle-zone touches — the
                                // zone whose tap toggles the chrome (B3).
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
                                //
                                // In selection mode the deadline race is skipped:
                                // the body Text's long-press-to-select gesture
                                // needs a plain await, and ANY consumed event means
                                // that gesture (or a handle/card) owns the pointer —
                                // abort quietly. A plain tap's first up is NOT
                                // consumed, so taps/swipes still reach this handler.
                                val longPressDeadline = down.uptimeMillis + longPressTimeoutMs
                                var timedOut = false
                                var aborted = false
                                var dragX = 0f
                                var paged = false
                                while (true) {
                                    val event =
                                        if (selectionMode) {
                                            awaitPointerEvent()
                                        } else {
                                            val remaining = longPressDeadline - SystemClock.uptimeMillis()
                                            if (remaining <= 0) {
                                                timedOut = true
                                                break
                                            }
                                            withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                                ?: run {
                                                    timedOut = true
                                                    break
                                                }
                                        }
                                    if (selectionMode && event.changes.any { it.isConsumed }) {
                                        aborted = true
                                        break
                                    }
                                    val change = event.changes.firstOrNull() ?: break
                                    if (change.positionChanged() && !change.isConsumed) {
                                        dragX += change.positionChange().x
                                        if (dragX > swipePx || dragX < -swipePx) {
                                            paged = true
                                            change.consume()
                                        }
                                    }
                                    if (event.changes.all { it.changedToUpIgnoreConsumed() }) break
                                }
                                if (aborted) {
                                    pressedPassage = null
                                    return@awaitEachGesture
                                }
                                // Long-press wins only if the deadline beat the
                                // up event — a completed swipe (paged) is never
                                // pre-empted by the deadline.
                                val longPressed = timedOut && !paged
                                if (longPressed) {
                                    // The menu fires at the deadline, finger still
                                    // down (anchor ready); the highlight clears —
                                    // the menu is its feedback. The menu targets
                                    // the passage under the finger with no
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
                                        if (event.changes.all { it.changedToUpIgnoreConsumed() }) break
                                    }
                                } else {
                                    pressedPassage = null
                                    if (paged) {
                                        val delta = if (dragX < 0f) 1 else -1
                                        when {
                                            page + delta < 0 -> {
                                                onHideChrome()
                                                viewModel.openChapter(bookId, -1)
                                            }
                                            page + delta > totalPages - 1 -> {
                                                onHideChrome()
                                                viewModel.openChapter(bookId, +1)
                                            }
                                            else -> {
                                                onHideChrome()
                                                stopPlaybackOnManualTurn()
                                                page = page + delta
                                            }
                                        }
                                    } else if (selectionMode && selectionRef.value != null) {
                                        if (selectionRef.value == selectionAtDown) {
                                            // A tap dismisses an EXISTING selection; it
                                            // never turns the page or toggles the chrome
                                            // while a selection is up.
                                            selection = null
                                            dragWord = null
                                            viewModel.clearWordLookup()
                                        }
                                        // else: this gesture just created/extended the
                                        // selection (long-press) — leave it.
                                    } else {
                                        val x = down.position.x
                                        when {
                                            x < pageWidthPx -> {
                                                onHideChrome()
                                                if (page <= 0) {
                                                    viewModel.openChapter(bookId, -1)
                                                } else {
                                                    stopPlaybackOnManualTurn()
                                                    page = page - 1
                                                }
                                            }
                                            x > pageWidthPx * 2f -> {
                                                onHideChrome()
                                                if (page >= totalPages - 1) {
                                                    viewModel.openChapter(bookId, +1)
                                                } else {
                                                    stopPlaybackOnManualTurn()
                                                    page = page + 1
                                                }
                                            }
                                            // Middle tap: toggle the immersive chrome
                                            // (item 2). The press highlight above
                                            // still gives feedback.
                                            else -> onToggleImmersive()
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
                    // The gesture reads the CURRENT page text through a ref: the
                    // pointerInput must NOT be keyed on `pageText` — the press
                    // highlight (pressedPassage) rebuilds it on the first down of
                    // every middle-zone touch, which restarted the block and
                    // cancelled the in-flight long-press before it could select.
                    val pageTextRef = rememberUpdatedState(pageText)
                    Text(
                        text = pageText,
                        style = bodyStyle,
                        modifier =
                            Modifier
                                .padding(horizontal = AyvuSpacing.LG)
                                .then(
                                    if (selectionMode) {
                                        Modifier.pointerInput(page, selectionMode) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { pos ->
                                                    val layout = pageLayout ?: return@detectDragGesturesAfterLongPress
                                                    val pg = pageTextRef.value
                                                    val off =
                                                        layout
                                                            .getOffsetForPosition(pos)
                                                            .coerceIn(0, pg.length)
                                                    val (ws, we) = wordRangeAt(pg.text, off)
                                                    dragWord = TextRange(ws, we)
                                                    selection = TextRange(ws, we)
                                                },
                                                onDrag = { change, _ ->
                                                    val layout = pageLayout ?: return@detectDragGesturesAfterLongPress
                                                    val word = dragWord ?: return@detectDragGesturesAfterLongPress
                                                    val pg = pageTextRef.value
                                                    change.consume()
                                                    val off =
                                                        layout
                                                            .getOffsetForPosition(change.position)
                                                            .coerceIn(0, pg.length)
                                                    val s = minOf(word.start, off)
                                                    val e = maxOf(word.end, off + 1).coerceAtMost(pg.length)
                                                    selection = TextRange(s, e)
                                                },
                                                onDragEnd = { },
                                                onDragCancel = {
                                                    selection = null
                                                    dragWord = null
                                                },
                                            )
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        onTextLayout = { pageLayout = it },
                    )
                }
                // Immersive hides the in-body footer — the overlay's minimal
                // player carries the same passage indicator (item 1).
                val passageLabel =
                    if (immersive) {
                        null
                    } else {
                        // The page's FIRST passage, book-wide — not the played one
                        // (item 4 follow-up): a manual page turn stops playback, so
                        // bookPassageIndex stays on the last played passage while
                        // the page moves, and the indicator went stale until
                        // playback resumed. firstPassageOnPage is the chapter-local
                        // value this composition already computed for play-from-view.
                        PlaybackUiState.passageIndicatorLabel(
                            chapterPrefix(state) + firstPassageOnPage,
                            state.bookPassageCount,
                        )
                    }
                if (passageLabel != null) {
                    // Pin the indicator to the viewport bottom. Pagination already
                    // reserves its band (bottomReservePx), but as the Column's last
                    // child in normal flow it floated up wherever a page filled
                    // fewer lines than the viewport allows — most visibly on a
                    // chapter's short last page.
                    Spacer(modifier = Modifier.weight(1f))
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
            // Selection overlay (word/phrase lookup): two draggable handles plus
            // the action card, anchored at the selection. Offered only in
            // selection mode with a measured layout. Everything sizes itself; the
            // clamps only keep it on-screen.
            val snippet = selectedSnippet
            val lookup by viewModel.wordLookup.collectAsState()
            val cardMaxWidth = 280.dp
            val cardMaxWidthPx = with(density) { cardMaxWidth.roundToPx() }
            val titleBlockPx =
                (if (page <= 0) titleHeightPx + titleGapPx else 0) +
                    (if (immersive) titleOverlayReservedPx else 0)
            val sel = selection
            val layout = pageLayout
            if (selectionMode && sel != null && layout != null && pageText.isNotEmpty()) {
                val lastIndex = (pageText.length - 1).coerceAtLeast(0)
                val selS = minOf(sel.start, sel.end).coerceIn(0, pageText.length)
                val selE = maxOf(sel.start, sel.end).coerceIn(0, pageText.length)
                val startBox = runCatching { layout.getBoundingBox(selS.coerceIn(0, lastIndex)) }.getOrNull()
                val endBox = runCatching { layout.getBoundingBox((selE - 1).coerceIn(0, lastIndex)) }.getOrNull()
                // Handles in Box coordinates (Text-local bounding box + the page's
                // horizontal padding and title block).
                val handles =
                    buildList {
                        startBox?.let { add(Triple(it.left, it.bottom, true)) }
                        endBox?.let { add(Triple(it.right, it.bottom, false)) }
                    }
                for ((hx, hy, isStart) in handles) {
                    SelectionHandle(
                        position = Offset(horizontalPadPx + hx, titleBlockPx + hy),
                        onDrag = { boxPos ->
                            val textLocal = Offset(boxPos.x - horizontalPadPx, boxPos.y - titleBlockPx)
                            val off = layout.getOffsetForPosition(textLocal).coerceIn(0, pageText.length)
                            val cur = selection
                            if (cur != null) {
                                val curS = minOf(cur.start, cur.end)
                                val curE = maxOf(cur.start, cur.end)
                                selection = if (isStart) TextRange(off, curE) else TextRange(curS, off)
                                dragWord = null
                            }
                        },
                    )
                }
                if (selS < selE) {
                    // Copy is always offered (the platform selection toolbar is
                    // not used — this card is the only action surface); Translate
                    // only when the snippet lies inside ONE original block.
                    val selText = pageText.text.substring(selS, selE).trim()
                    val translatable = snippet != null && snippet.text.isNotEmpty()
                    if (selText.isNotEmpty()) {
                        val xPx =
                            (horizontalPadPx + (endBox?.left ?: 0f).toInt())
                                .coerceIn(0, (areaWidth - cardMaxWidthPx).coerceAtLeast(0))
                        val yPx =
                            (titleBlockPx + (endBox?.bottom?.toInt() ?: 0) + with(density) { AyvuSpacing.XS.roundToPx() })
                                .coerceIn(0, (areaHeight - with(density) { 120.dp.roundToPx() }).coerceAtLeast(0))
                        SelectionChip(
                            selectedText = selText,
                            translatable = translatable,
                            lookup = if (translatable) lookup else WordLookup.None,
                            onTranslate = { if (translatable) viewModel.translateSelection(selText) },
                            onCopy = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("selection", selText)))
                                }
                                Toast.makeText(context, "Selection copied", Toast.LENGTH_SHORT).show()
                            },
                            onDismiss = {
                                selection = null
                                dragWord = null
                                viewModel.clearWordLookup()
                            },
                            modifier =
                                Modifier
                                    .offset { IntOffset(xPx, yPx) }
                                    .widthIn(max = cardMaxWidth),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Book-wide index of the current chapter's FIRST passage. `stateCopy` builds
 * [PlaybackUiState.bookPassageIndex] as the chapter prefix sum plus
 * `passageIndex`, so the prefix alone is the difference — no per-chapter
 * counts have to be published for the reader to convert a chapter-local
 * passage index into a book-wide one.
 */
private fun chapterPrefix(state: PlaybackUiState): Int = (state.bookPassageIndex - state.passageIndex).coerceAtLeast(0)

/**
 * The block whose text is the utterance being narrated — the read-along
 * highlight anchor (F.5 highlight ownership). Returns the block INDEX and
 * the TEXT to split into sentence spans:
 * - speech == display language → the TRANSLATION block (the anchors come
 *   from the translated render, so they match);
 * - original audio (or an audio-only translation with no displayed
 *   translation) → the passage's FIRST block when it is an Original
 *   (interleaved: today's behavior, the audio-only highlight drift is
 *   recorded degradation #101);
 * - translated-only mode with original audio → null: no displayed block
 *   matches the utterance, so the highlight is suppressed (page-follow
 *   stays anchored on the passage's first block).
 */
private fun highlightBlockOf(
    display: ChapterDisplayState,
    state: PlaybackUiState,
    firstBlockOfPassage: IntArray,
    blocks: List<DisplayBlock>,
): Pair<Int, String>? {
    val speech = display.speechTarget
    if (speech != null && speech == display.displayTarget) {
        for (i in blocks.indices) {
            val block = blocks[i]
            if (block.passageIndex == state.passageIndex && block.kind == DisplayKind.Translation) {
                return i to block.text
            }
        }
    }
    val firstBlockIndex = firstBlockOfPassage.getOrNull(state.passageIndex) ?: return null
    val first = blocks.getOrNull(firstBlockIndex) ?: return null
    return if (first.kind == DisplayKind.Original) firstBlockIndex to state.passageText else null
}

/**
 * Applies the per-block display styles (muted translation colour, paragraph
 * indent, the Pending dots and the Unavailable note — italic) to the
 * annotated builder, clipped to [rangeStart, rangeEnd) — the SAME rule for
 * the measured full layout and the page render, so pagination (fixed line
 * pitch) measures what the page draws. Color never changes measurement; the
 * paragraph indent does, which is why the measured bodyLayout carries it.
 */
internal fun AnnotatedString.Builder.applyBlockStyles(
    blocks: List<DisplayBlock>,
    offsets: IntArray,
    muted: Color,
    startOffset: Int,
    rangeStart: Int,
    rangeEnd: Int,
) {
    for (i in blocks.indices) {
        val block = blocks[i]
        val blockStart = offsets.getOrNull(i) ?: continue
        // Pending/Unavailable render placeholders, not their empty block text.
        val blockEnd = blockStart + ChapterDisplay.renderedLength(block)
        if (blockEnd <= rangeStart || blockStart >= rangeEnd) continue
        val from = maxOf(blockStart, rangeStart) - startOffset
        val to = minOf(blockEnd, rangeEnd) - startOffset
        if (from >= to) continue
        when (block.kind) {
            DisplayKind.Original -> Unit
            DisplayKind.Translation -> addStyle(SpanStyle(color = muted), from, to)
            DisplayKind.Pending -> addStyle(SpanStyle(color = muted), from, to)
            DisplayKind.Unavailable -> addStyle(SpanStyle(color = muted, fontStyle = FontStyle.Italic), from, to)
        }
    }
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

/** A translatable selection: the trimmed text plus its page-local char range
 * ([start], [end] exclusive) — the range is used to place the card. */
private data class SelectedSnippet(
    val text: String,
    val start: Int,
    val end: Int,
)

/** A draggable selection handle (our own — the platform handles are internal
 * to Compose). [position] is the handle's center in the page's Box
 * coordinates; [onDrag] receives the finger's position in the SAME space. */
@Composable
private fun SelectionHandle(
    position: Offset,
    onDrag: (Offset) -> Unit,
) {
    val onDragRef = rememberUpdatedState(onDrag)
    val posRef = rememberUpdatedState(position)
    val density = LocalDensity.current
    val radius = with(density) { 7.dp.toPx() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var acc by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier =
            Modifier
                .offset { IntOffset((position.x - radius).toInt(), (position.y - radius).toInt()) }
                .size(14.dp)
                .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            // Anchor the drag at the handle's start position (it
                            // moves as the selection changes, so the original
                            // must be frozen here — not read per frame).
                            origin = posRef.value
                            acc = Offset.Zero
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            acc += delta
                            onDragRef.value(origin + acc)
                        },
                    )
                },
    )
}

/** The word (letters/digits/apostrophe/hyphen run) containing [offset], as a
 * half-open [start, endExclusive). A press on a non-word char selects that
 * single char. */
private fun wordRangeAt(
    text: String,
    offset: Int,
): Pair<Int, Int> {
    if (text.isEmpty()) return 0 to 0
    val i = offset.coerceIn(0, text.length - 1)

    fun isWord(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'
    if (!isWord(text[i])) return i to (i + 1)
    var s = i
    while (s > 0 && isWord(text[s - 1])) s--
    var e = i + 1
    while (e < text.length && isWord(text[e])) e++
    return s to e
}

/** The selection action card (word/phrase lookup): a small surface anchored
 * at the selection. Morphs from [Translate]/[Copy] to a spinner to the
 * result. The word/phrase is translated one direction only — into the
 * book's display language (the caller passes [onTranslate]). */
@Composable
private fun SelectionChip(
    selectedText: String,
    /** True when the snippet lies inside ONE original block — Translate is
     *  offered only then; Copy is always offered. */
    translatable: Boolean,
    lookup: WordLookup,
    onTranslate: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(AyvuSpacing.SM)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selectedText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                }
            }
            if (!translatable) {
                Row { TextButton(onClick = onCopy) { Text("Copy") } }
            } else {
                when (lookup) {
                    WordLookup.None ->
                        Row {
                            TextButton(onClick = onTranslate) { Text("Translate") }
                            TextButton(onClick = onCopy) { Text("Copy") }
                        }
                    WordLookup.Loading ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "Translating…",
                                modifier = Modifier.padding(start = AyvuSpacing.SM),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    is WordLookup.Done -> Text(lookup.translation)
                    WordLookup.Unavailable ->
                        Text(
                            "Translation unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
        }
    }
}

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

/** The live-dots cycle: one new dot every ~350 ms (≈ 3/s), up to 8, then
 * restart — the Pending placeholder reads as "loading", not as a static
 * ellipsis row. */
private const val PENDING_DOTS_TICK_MS = 350L
private const val PENDING_DOTS_MAX = 8

/** The animated Pending placeholder: [count] dots added one at a time,
 * padded to the FIXED placeholder length — the rendered line never changes
 * its char count, so the wrap and the line grid stay identical to the
 * measured layout (a differently-long line would corrupt pagination). */
private fun animatedPendingDots(
    count: Int,
    length: Int,
): String = if (length <= 0) "" else ("⋯ ".repeat(count).trimEnd()).padEnd(length)

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

/** How long the chrome stays visible on reader entry before the intro
 * auto-hide (item 2) — long enough to read the title, short enough to feel
 * immersive by default. */
private const val CHROME_INTRO_MS = 3_000L
