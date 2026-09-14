package com.moronigranja.localttsreader.featurelibrary

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moronigranja.localttsreader.player.PlayerPhase
import com.moronigranja.localttsreader.player.PregenJobState
import com.moronigranja.localttsreader.player.TodayStats
import com.moronigranja.localttsreader.player.formatBytes
import com.moronigranja.localttsreader.ui.AyvuMotion
import com.moronigranja.localttsreader.ui.AyvuSpacing
import com.moronigranja.localttsreader.ui.BookCover
import com.moronigranja.localttsreader.ui.ConfirmDialog
import com.moronigranja.localttsreader.ui.EmptyState
import com.moronigranja.localttsreader.ui.LabeledProgress
import com.moronigranja.localttsreader.ui.LocalReducedMotion
import com.moronigranja.localttsreader.ui.PlayerCard
import com.moronigranja.localttsreader.ui.ReadInLanguagePicker
import com.moronigranja.localttsreader.ui.SectionHeader
import com.moronigranja.localttsreader.ui.formatPercent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The library list + import flow (C5/C6). Pick ebooks via SAF, import them through
 * the Hilt-provided [LibraryViewModel], and see the result — with progress, a
 * failure dialog when anything failed, and a snackbar for clean successes.
 */

/** MIME types the SAF picker offers. mobi/azw have no registered MIME type, so
 *  providers report them as `application/octet-stream`; the importer still filters
 *  by extension, so stray picks surface as a typed "format not supported" failure. */
private val IMPORT_MIME_TYPES =
    arrayOf(
        "application/epub+zip",
        "text/plain",
        "text/markdown",
        "text/x-markdown",
        "application/octet-stream",
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: LibraryViewModel = viewModel(),
) {
    val library by viewModel.library.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val offline by viewModel.offline.collectAsState()
    val readProgress by viewModel.readProgress.collectAsState()
    val recent by viewModel.recent.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val stats by viewModel.todayStats.collectAsState()
    var query by remember { mutableStateOf("") }
    // F2: the field drives the VM filter, which re-emits the list reactively.
    LaunchedEffect(query) { viewModel.setQuery(query) }
    // Phase H: re-pin the TODAY window on resume — listening flushes land on
    // the back stack (Room flows re-emit) and the local day rolls at midnight.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshStats()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val fileLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isNotEmpty()) {
                uris.forEach(context::takeReadPermission)
                context.toEBookSources(uris).let(viewModel::import)
            }
        }

    // F3: folder import over a persisted SAF tree grant (root + one nested
    // level, capped at FolderScanPolicy.MAX_FILES files by the hostile-input
    // policy — see FolderScanPolicy/FolderScanner).
    val folderLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            uri?.let {
                context.takeReadPermission(it)
                viewModel.importFolder(it)
            }
        }
    var importMenuOpen by remember { mutableStateOf(false) }

    // The active session's book card (decisions #55/#56): it REPLACES the
    // top "Continue listening" row in place (no dock) and carries the row's
    // actions — overflow menu (pre-gen, delete offline, remove) + offline disk
    // usage — which the reader's docked card does not show.
    val positioned =
        playerState.bookId != null &&
            (playerState.phase != PlayerPhase.IDLE || playerState.canUndo || playerState.passageText.isNotBlank())
    val activeId = playerState.bookId
    val activeOffline = activeId?.let { offline[it] }
    val activeUsage = activeOffline?.usageBytes ?: 0L
    val activeEstimate = activeOffline?.estimateBytes ?: 0L
    val activeTitle =
        (
            (if (activeId != null) library.firstOrNull { it.book.id == activeId } else null)
                ?: (if (activeId != null) recent.firstOrNull { it.book.id == activeId } else null)
        )?.book?.title.orEmpty()
    // The active card REPLACES the book's row (and its progress bar), so it
    // must surface the live run itself — the menu swaps to "Stop generating".
    val activeJob by viewModel.pregenWork(activeId ?: "").collectAsState(PregenJobState())
    val activeRunning = activeJob.running
    var cardMenuOpen by remember { mutableStateOf(false) }
    var cardBudget by remember { mutableStateOf(false) }
    var cardConfirmRemove by remember { mutableStateOf(false) }
    var cardConfirmDeleteAudio by remember { mutableStateOf(false) }
    var cardReadInBookId by remember { mutableStateOf<String?>(null) }

    if (cardBudget) {
        PregenBudgetDialog(
            estimate = activeEstimate,
            onPick = { minutes ->
                cardBudget = false
                activeId?.let { viewModel.pregenerate(it, minutes) }
            },
            onDismiss = { cardBudget = false },
        )
    }
    if (cardConfirmRemove) {
        ConfirmDialog(
            title = "Remove from library?",
            text =
                "Removes \"$activeTitle\" with its progress, bookmarks and offline " +
                    "audio. You can re-import the file anytime.",
            confirmLabel = "Remove",
            onConfirm = {
                cardConfirmRemove = false
                activeId?.let(viewModel::removeBook)
            },
            onDismiss = { cardConfirmRemove = false },
        )
    }
    if (cardConfirmDeleteAudio) {
        ConfirmDialog(
            title = "Delete offline audio?",
            text = "Frees ${formatBytes(activeUsage)} for this book. It can be regenerated later.",
            confirmLabel = "Delete",
            onConfirm = {
                cardConfirmDeleteAudio = false
                activeId?.let(viewModel::deleteOffline)
            },
            onDismiss = { cardConfirmDeleteAudio = false },
        )
    }
    cardReadInBookId?.let { dialogBookId ->
        ReadInLanguageDialog(dialogBookId, viewModel, onDismiss = { cardReadInBookId = null })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ayvu library") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    Box {
                        TextButton(onClick = { importMenuOpen = true }) {
                            Text("Import")
                        }
                        DropdownMenu(
                            expanded = importMenuOpen,
                            onDismissRequest = { importMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Import files") },
                                onClick = {
                                    importMenuOpen = false
                                    fileLauncher.launch(IMPORT_MIME_TYPES)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Import folder") },
                                onClick = {
                                    importMenuOpen = false
                                    folderLauncher.launch(null)
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            // F2: local title/author search — always visible once the library
            // has anything; a blank query shows the full list.
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AyvuSpacing.LG, vertical = AyvuSpacing.SM),
                placeholder = { Text("Search title or author") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
            )

            if (library.isEmpty()) {
                EmptyState("No books yet — import your first ebook")
            } else {
                val recentIds = recent.map { it.book.id }.toSet()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(AyvuSpacing.LG),
                    verticalArrangement = Arrangement.spacedBy(AyvuSpacing.SM),
                ) {
                    // Phase H: the TODAY stats header card, above the
                    // continue-list (post-v1-plan Slice A — no other home
                    // restructure).
                    if (!stats.isEmpty) {
                        item(key = "today-stats") { TodayCard(stats) }
                    }
                    if (positioned || recentIds.isNotEmpty()) {
                        item { SectionHeader("Continue listening", Modifier.padding(top = AyvuSpacing.SM, bottom = AyvuSpacing.XS)) }
                    }
                    if (positioned && activeId != null) {
                        // The live player card replaces the top row and EXPANDS
                        // in place when the session starts (decisions #56).
                        item(key = "player-$activeId") {
                            AnimatedVisibility(
                                visible = true,
                                enter =
                                    if (LocalReducedMotion.current) {
                                        EnterTransition.None
                                    } else {
                                        expandVertically(expandFrom = Alignment.Top, animationSpec = tween(AyvuMotion.STANDARD_MS)) +
                                            fadeIn(tween(AyvuMotion.STANDARD_MS))
                                    },
                            ) {
                                PlayerCard(
                                    state = playerState,
                                    commands = viewModel.playerCommands,
                                    badge = {
                                        if (activeUsage > 0L) {
                                            Text(
                                                "${formatBytes(activeUsage)} offline",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onOpen = { activeId?.let(onOpenBook) },
                                    topRight = {
                                        Box {
                                            IconButton(onClick = { cardMenuOpen = true }) {
                                                Icon(Icons.Filled.MoreVert, contentDescription = "Book actions")
                                            }
                                            DropdownMenu(
                                                expanded = cardMenuOpen,
                                                onDismissRequest = { cardMenuOpen = false },
                                            ) {
                                                if (activeRunning) {
                                                    DropdownMenuItem(
                                                        text = { Text("Stop generating") },
                                                        onClick = {
                                                            cardMenuOpen = false
                                                            activeId?.let(viewModel::cancelPregen)
                                                        },
                                                    )
                                                } else {
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                buildString {
                                                                    append("Pre-generate")
                                                                    if (activeEstimate > 0L) append(" (≈${formatBytes(activeEstimate)})")
                                                                },
                                                            )
                                                        },
                                                        onClick = {
                                                            cardMenuOpen = false
                                                            cardBudget = true
                                                        },
                                                    )
                                                }
                                                if (activeUsage > 0L) {
                                                    DropdownMenuItem(
                                                        text = { Text("Delete offline audio") },
                                                        onClick = {
                                                            cardMenuOpen = false
                                                            cardConfirmDeleteAudio = true
                                                        },
                                                    )
                                                }
                                                DropdownMenuItem(
                                                    text = { Text("Read in language…") },
                                                    onClick = {
                                                        cardMenuOpen = false
                                                        activeId?.let { cardReadInBookId = it }
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Remove from library") },
                                                    onClick = {
                                                        cardMenuOpen = false
                                                        cardConfirmRemove = true
                                                    },
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    items(
                        recent.filterNot { it.book.id == activeId },
                        key = { it.book.id },
                    ) { entry ->
                        BookRow(
                            bookId = entry.book.id,
                            title = entry.book.title,
                            authors = entry.book.authors,
                            offline = offline[entry.book.id],
                            readFraction = readProgress[entry.book.id] ?: 0f,
                            onOpenBook = onOpenBook,
                            viewModel = viewModel,
                        )
                    }
                    if (recentIds.isNotEmpty()) {
                        item { SectionHeader("Library", Modifier.padding(top = AyvuSpacing.SM, bottom = AyvuSpacing.XS)) }
                    }
                    items(
                        searchResults.filterNot { it.book.id in recentIds || it.book.id == activeId },
                        key = { it.book.id },
                    ) { entry ->
                        BookRow(
                            bookId = entry.book.id,
                            title = entry.book.title,
                            authors = entry.book.authors,
                            offline = offline[entry.book.id],
                            readFraction = readProgress[entry.book.id] ?: 0f,
                            onOpenBook = onOpenBook,
                            viewModel = viewModel,
                        )
                    }
                    if (query.isNotBlank() && searchResults.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                EmptyState("No books match \"${query.trim()}\"")
                            }
                        }
                    }
                }
            }
        }
    }

    // F4: the ONE import overlay for every entry point (in-app picker, folder,
    // external ACTION_VIEW gateway, forwarded book-file shares).
    intakeOverlay(
        importState = importState,
        guidance = viewModel.intakeGuidance.collectAsState().value,
        onCancel = viewModel::cancelImport,
        onDismiss = viewModel::dismissIntake,
    )
}

/**
 * One library card with the offline pre-generation action (decisions #42) and
 * storage transparency (decisions #44): the row shows what a full pre-gen
 * costs (~estimate), how much is already on disk, and a one-tap delete that
 * cancels queued work first. A run's settlement refreshes the disk facts.
 */
@Composable
private fun BookRow(
    bookId: String,
    title: String,
    authors: List<String>,
    offline: LibraryViewModel.OfflineBook?,
    readFraction: Float,
    onOpenBook: (String) -> Unit,
    viewModel: LibraryViewModel,
) {
    // A6: the pre-generation row observes the core PregenJobState contract
    // (the app's WorkManager adapter maps WorkInfo under the hood).
    val job by viewModel.pregenWork(bookId).collectAsState(PregenJobState())
    val running = job.running
    val percent = job.percent
    val usage = offline?.usageBytes ?: 0L
    val estimate = offline?.estimateBytes ?: 0L

    // A settled run changed the tier: re-read usage + estimate (the job state
    // only carries the percent/error surface).
    LaunchedEffect(job.running, job.failed, job.error) {
        if (!job.running && (job.failed || percent > 0)) viewModel.refreshOffline()
    }
    // CR-1: a failed run exposes its typed reason (packs missing, synthesis
    // meltdown) instead of collapsing into a silent no-op success.
    val pregenError = job.error

    val cover = rememberCoverBitmap(viewModel, bookId)
    var menuOpen by remember { mutableStateOf(false) }
    var budgetDialog by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDeleteAudio by remember { mutableStateOf(false) }
    var readInBookId by remember { mutableStateOf<String?>(null) }

    if (budgetDialog) {
        PregenBudgetDialog(
            estimate = estimate,
            onPick = { minutes ->
                budgetDialog = false
                viewModel.pregenerate(bookId, minutes)
            },
            onDismiss = { budgetDialog = false },
        )
    }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onOpenBook(bookId) },
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(AyvuSpacing.MD),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(bitmap = cover, fallbackInitial = title, contentDescription = null)
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = AyvuSpacing.MD),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (authors.isNotEmpty()) {
                    Text(
                        text = authors.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (readFraction > 0f) {
                    LabeledProgress(
                        progress = readFraction,
                        label = formatPercent(readFraction),
                        modifier = Modifier.padding(top = AyvuSpacing.SM),
                    )
                }
                when {
                    running -> {
                        LabeledProgress(
                            progress = percent / 100f,
                            label = "$percent%",
                            modifier = Modifier.padding(top = AyvuSpacing.SM),
                        )
                    }
                    else -> {
                        if (pregenError != null) {
                            Text(
                                pregenError,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = AyvuSpacing.XS),
                            )
                        } else if (usage > 0L) {
                            Text(
                                "${formatBytes(usage)} offline",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = AyvuSpacing.XS),
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    TextButton(onClick = { viewModel.cancelPregen(bookId) }) {
                        Text("Stop")
                    }
                }
                IconButton(onClick = { viewModel.playBook(bookId) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Book actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (running) {
                            // #136: while a run is live the menu stops it — the
                            // plain "Pre-generate" tap was a KEEP no-op anyway.
                            DropdownMenuItem(
                                text = { Text("Stop generating") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.cancelPregen(bookId)
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        buildString {
                                            append("Pre-generate")
                                            if (estimate > 0L) append(" (≈${formatBytes(estimate)})")
                                        },
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    budgetDialog = true
                                },
                            )
                        }
                        if (usage > 0L) {
                            DropdownMenuItem(
                                text = { Text("Delete offline audio") },
                                onClick = {
                                    menuOpen = false
                                    confirmDeleteAudio = true
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Read in language…") },
                            onClick = {
                                menuOpen = false
                                readInBookId = bookId
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove from library") },
                            onClick = {
                                menuOpen = false
                                confirmRemove = true
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove from library?",
            text =
                "Removes \"$title\" with its progress, bookmarks and offline " +
                    "audio. You can re-import the file anytime.",
            confirmLabel = "Remove",
            onConfirm = {
                confirmRemove = false
                viewModel.removeBook(bookId)
            },
            onDismiss = { confirmRemove = false },
        )
    }
    if (confirmDeleteAudio) {
        ConfirmDialog(
            title = "Delete offline audio?",
            text = "Frees ${formatBytes(usage)} for this book. It can be regenerated later.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDeleteAudio = false
                viewModel.deleteOffline(bookId)
            },
            onDismiss = { confirmDeleteAudio = false },
        )
    }
    readInBookId?.let { dialogBookId ->
        ReadInLanguageDialog(dialogBookId, viewModel, onDismiss = { readInBookId = null })
    }
}

/** The shared per-book "Read in language" dialog (decisions #114): the same
 * picker the reader's voice sheet uses — Off + the active engine's target
 * languages, with the inline pack-download row until the pack is staged. */
@Composable
private fun ReadInLanguageDialog(
    bookId: String,
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.translateState(bookId).collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Read in language") },
        text = {
            ReadInLanguagePicker(
                state = state,
                onSelect = {
                    viewModel.setTranslateTarget(bookId, it)
                    onDismiss()
                },
                onDownload = { viewModel.downloadTranslatePack(bookId) },
            )
        },
        confirmButton = {},
    )
}

/** Decodes the book's extracted cover off the main thread; null while loading or absent. */
@Composable
private fun rememberCoverBitmap(
    viewModel: LibraryViewModel,
    bookId: String,
): ImageBitmap? {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bookId) {
        bitmap =
            withContext(Dispatchers.IO) {
                viewModel.cover(bookId)?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }
    }
    return bitmap
}

/** The pre-generation budget picker: listening-time presets, each with the
 * exact linear byte cost of the estimate model (1 min at the 24 kHz 16-bit
 * mono rate ≈ 2.88 MB; the whole-book estimate matches #44), plus a
 * free-form entry for arbitrary durations (Phase K item 3 — the backend
 * already accepts any listening-minute budget). */
@Composable
private fun PregenBudgetDialog(
    estimate: Long,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf("") }
    val parsed = parseListeningMinutes(custom)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pre-generate: how much listening time?") },
        text = {
            Column {
                Text(
                    "Synthesis runs in the background; you can keep reading or cancel anytime.",
                    style = MaterialTheme.typography.bodySmall,
                )
                PregenBudgetOption("30 min (≈${formatBytes(BYTES_PER_MINUTE * 30)})") { onPick(30) }
                PregenBudgetOption("1 h (≈${formatBytes(BYTES_PER_MINUTE * 60)})") { onPick(60) }
                PregenBudgetOption("2 h (≈${formatBytes(BYTES_PER_MINUTE * 120)})") { onPick(120) }
                PregenBudgetOption("3 h (≈${formatBytes(BYTES_PER_MINUTE * 180)})") { onPick(180) }
                PregenBudgetOption("Whole book (≈${formatBytes(estimate)})") { onPick(null) }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("Custom listening time") },
                    placeholder = { Text("45m, 1h30, 1.5h") },
                    singleLine = true,
                    isError = custom.isNotBlank() && parsed == null,
                    supportingText = {
                        when {
                            custom.isBlank() -> Unit
                            parsed != null -> Text("≈${formatBytes(BYTES_PER_MINUTE * parsed)} of audio")
                            else -> Text("Minutes or h/m units — try 45m, 1h30 or 1.5h")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { parsed?.let(onPick) }) { Text("Generate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PregenBudgetOption(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label)
    }
}

private const val BYTES_PER_MINUTE = 24_000L * 2 * 60

/**
 * The TODAY stats header card (Phase H, post-v1-plan Slice A): today's
 * read/listen minutes, the 7-day mini bar, and the consecutive-days streak.
 * On-device only — the data is the local activity_seconds table.
 */
@Composable
private fun TodayCard(stats: TodayStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AyvuSpacing.MD)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Today",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (stats.streakDays > 0) {
                    Text(
                        "${stats.streakDays}-day streak",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                "Read ${stats.today.readMinutes}m · Listened ${stats.today.listenedMinutes}m · " +
                    "${stats.today.totalMinutes}m total",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val maxSeconds = stats.week.maxOfOrNull { it.totalSeconds }?.coerceAtLeast(1L) ?: 1L
            Row(
                horizontalArrangement = Arrangement.spacedBy(AyvuSpacing.XS),
                modifier =
                    Modifier
                        .padding(top = AyvuSpacing.SM)
                        .fillMaxWidth()
                        .height(32.dp),
            ) {
                stats.week.forEach { day ->
                    // Each day is one bar whose fill height tracks the week's
                    // busiest day; today's bar uses the primary color.
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Bottom) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height((32.dp * (day.totalSeconds.toFloat() / maxSeconds)).coerceAtLeast(2.dp))
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (day.dayKey == stats.dayKey) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                    ),
                        )
                    }
                }
            }
        }
    }
}
