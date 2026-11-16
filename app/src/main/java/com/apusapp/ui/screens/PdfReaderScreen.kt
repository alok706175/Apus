package com.apusapp.ui.screens

import androidx.compose.foundation.lazy.items

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path as ComposePath

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apusapp.data.UnlockPdfService
import com.apusapp.data.model.EncryptionStatus
import com.apusapp.ui.components.*
import com.apusapp.ui.theme.ApusCoral
import com.apusapp.utils.HapticManager
import com.apusapp.utils.PdfHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.roundToInt

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.FileProvider
import com.apusapp.utils.PdfSearchMatch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportRangeType {
    ALL_PAGES,
    CURRENT_PAGE,
    CUSTOM
}

enum class ReaderReadingMode(val label: String, val description: String) {
    NORMAL("Normal", "Default crisp white background"),
    NIGHT("Night (Invert)", "Dark background, white text for dark rooms"),
    SEPIA("Sepia (Warm Book)", "Warm amber tint like reading a real book"),
    EYE_COMFORT("Eye Comfort", "Reduced blue light and gentle contrast")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    file: File,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val activity = context as? Activity

    val handleBack: () -> Unit = {
        try {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } catch (_: Exception) {}
        onBack()
    }

    BackHandler { handleBack() }

    DisposableEffect(file) {
        onDispose {
            try {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } catch (_: Exception) {}
            if (com.apusapp.utils.VaultManager.isVaultFile(context, file)) {
                try {
                    if (file.name.startsWith("vault_preview_", ignoreCase = true) && file.exists()) {
                        file.delete()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    var activeFile by remember { mutableStateOf(file) }
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var fileDescriptor by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Password Protection State
    var isPasswordProtected by remember { mutableStateOf(false) }
    var enteredPassword by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isUnlocking by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    // Zoom & Pan State
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // UI & Search State
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchDetailedMatches by remember { mutableStateOf<List<PdfSearchMatch>>(emptyList()) }
    var currentSearchIndex by remember { mutableIntStateOf(0) }
    var isSearching by remember { mutableStateOf(false) }
    var isNightMode by remember { mutableStateOf(false) }
    var readingMode by remember { mutableStateOf(ReaderReadingMode.NORMAL) }
    var showReadingModeDialog by remember { mutableStateOf(false) }

    // 3-Dot Menu & Action Dialog States
    var showMenu by remember { mutableStateOf(false) }
    var showFileInfoDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSaveAsImagesDialog by remember { mutableStateOf(false) }
    var selectedImageFormat by remember { mutableStateOf("PNG") }
    var exportRangeType by remember { mutableStateOf(ExportRangeType.ALL_PAGES) }
    var customRangeInput by remember { mutableStateOf("") }
    var isSavingImages by remember { mutableStateOf(false) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var signatureText by remember { mutableStateOf("Jane Doe") }
    var appliedSignature by remember { mutableStateOf<String?>(null) }
    var showJumpToPageDialog by remember { mutableStateOf(false) }
    var showTextSelectionDialog by remember { mutableStateOf(false) }

    // Freehand Pen & Highlighter Annotation State
    var isDrawMode by remember { mutableStateOf(false) }
    var drawTool by remember { mutableStateOf("PEN") } // "PEN", "HIGHLIGHTER"
    var penColor by remember { mutableStateOf(Color(0xFFE53935)) } // Default red
    var highlighterColor by remember { mutableStateOf(Color(0xFFFFEB3B)) } // Default yellow
    val pageDrawings = remember { mutableStateMapOf<Int, MutableList<Pair<List<Offset>, Color>>>() }
    val pageHighlighters = remember { mutableStateMapOf<Int, MutableList<Pair<List<Offset>, Color>>>() }
    var currentDrawStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var isSavingAnnotations by remember { mutableStateOf(false) }
    var showSaveLocationDialogInReader by remember { mutableStateOf(false) }

    if (showSaveLocationDialogInReader) {
        com.apusapp.ui.components.SaveLocationSelectionDialog(
            currentLocation = com.apusapp.utils.StorageLocationManager.getLocation(context),
            languageViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
            onLocationSelected = {
                showSaveLocationDialogInReader = false
                saveCurrentFile(context, activeFile, appliedSignature) { savedFile ->
                    activeFile = savedFile
                }
            },
            onDismiss = { showSaveLocationDialogInReader = false }
        )
    }

    // Read Aloud (TTS), Book Mode, and Document Watermarking
    var tts by remember { mutableStateOf<android.speech.tts.TextToSpeech?>(null) }
    var isTtsActive by remember { mutableStateOf(false) }
    var isTtsSpeaking by remember { mutableStateOf(false) }
    var ttsCurrentPage by remember { mutableIntStateOf(1) }
    var isBookMode by remember { mutableStateOf(false) }
    var bookCurrentPage by remember { mutableIntStateOf(0) }
    var showWatermarkDialog by remember { mutableStateOf(false) }
    var watermarkInput by remember { mutableStateOf("CONFIDENTIAL") }

    val readingProgressPrefs = remember { context.getSharedPreferences("pdf_reading_progress", Context.MODE_PRIVATE) }
    val initialSavedPage = remember(activeFile.absolutePath) {
        readingProgressPrefs.getInt("page_" + activeFile.absolutePath, 0)
    }

    val bookmarksPrefs = remember { context.getSharedPreferences("pdf_bookmarks", Context.MODE_PRIVATE) }
    var bookmarkedPages by remember(activeFile.absolutePath) {
        val saved = bookmarksPrefs.getStringSet("bm_" + activeFile.absolutePath, emptySet()) ?: emptySet()
        mutableStateOf(saved.mapNotNull { it.toIntOrNull() }.toSet())
    }
    var showBookmarksDialog by remember { mutableStateOf(false) }

    val favorites by com.apusapp.utils.FavoritesManager.favoritesFlow.collectAsState()
    val isFavorited = remember(favorites, activeFile.absolutePath) { favorites.contains(activeFile.absolutePath) }

    // Table of Contents (PDF Outline Tree) State
    var showTocDialog by remember { mutableStateOf(false) }
    var outlineItems by remember { mutableStateOf<List<com.apusapp.utils.PdfOutlineItem>>(emptyList()) }
    var isLoadingToc by remember { mutableStateOf(false) }

    // Text-to-Speech (Read Aloud) Engine
    DisposableEffect(context) {
        var ttsInstance: android.speech.tts.TextToSpeech? = null
        ttsInstance = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                ttsInstance?.language = Locale.getDefault()
                tts = ttsInstance
            }
        }
        onDispose {
            try {
                ttsInstance?.stop()
                ttsInstance?.shutdown()
            } catch (_: Exception) {}
        }
    }

    fun speakPage(pageIdx1Based: Int) {
        ttsCurrentPage = pageIdx1Based
        scope.launch(Dispatchers.IO) {
            val text = com.apusapp.utils.PdfHelper.extractPageText(activeFile, pageIdx1Based)
            withContext(Dispatchers.Main) {
                if (text.isNotBlank()) {
                    isTtsSpeaking = true
                    tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "PDF_TTS_$pageIdx1Based")
                } else {
                    isTtsSpeaking = false
                    Toast.makeText(context, "No text detected on page $pageIdx1Based to read", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun readAloudCurrentPage() {
        if (isTtsSpeaking) {
            tts?.stop()
            isTtsSpeaking = false
            return
        }
        val curPageIdx = if (isBookMode) bookCurrentPage else listState.firstVisibleItemIndex
        isTtsActive = true
        speakPage(curPageIdx + 1)
    }

    fun loadAndShowToc() {
        showTocDialog = true
        isLoadingToc = true
        scope.launch(Dispatchers.IO) {
            val items = com.apusapp.utils.PdfHelper.extractPdfOutlines(activeFile)
            withContext(Dispatchers.Main) {
                outlineItems = items
                isLoadingToc = false
            }
        }
    }

    fun toggleBookmark(pageIndex: Int) {
        val updated = if (pageIndex in bookmarkedPages) {
            bookmarkedPages - pageIndex
        } else {
            bookmarkedPages + pageIndex
        }
        bookmarkedPages = updated
        bookmarksPrefs.edit().putStringSet("bm_" + activeFile.absolutePath, updated.map { it.toString() }.toSet()).apply()
        Toast.makeText(context, if (pageIndex in updated) "Page ${pageIndex + 1} Bookmarked" else "Bookmark Removed", Toast.LENGTH_SHORT).show()
    }
    var hasRestoredReadingPosition by remember(activeFile.absolutePath) { mutableStateOf(false) }

    LaunchedEffect(pageCount, isLoading) {
        if (!isLoading && pageCount > 0 && !hasRestoredReadingPosition) {
            hasRestoredReadingPosition = true
            if (initialSavedPage in 1 until pageCount) {
                listState.scrollToItem(initialSavedPage)
                bookCurrentPage = initialSavedPage
                Toast.makeText(context, "Resumed reading at Page ${initialSavedPage + 1}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex, isBookMode, bookCurrentPage) {
        if (com.apusapp.utils.VaultManager.isVaultFile(context, activeFile)) return@LaunchedEffect
        val curPage = if (isBookMode) bookCurrentPage else listState.firstVisibleItemIndex
        if (curPage in 0 until pageCount) {
            readingProgressPrefs.edit().putInt("page_" + activeFile.absolutePath, curPage).apply()
        }
    }

    LaunchedEffect(activeFile.absolutePath) {
        if (!com.apusapp.utils.VaultManager.isVaultFile(context, activeFile)) {
            com.apusapp.utils.RecentFilesManager.recordFile(context, activeFile)
        }
    }

    // Save as Launcher (ACTION_CREATE_DOCUMENT)
    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destinationUri ->
        if (destinationUri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                        FileInputStream(activeFile).use { input ->
                            input.copyTo(output)
                        }
                    }
                    if (!com.apusapp.utils.VaultManager.isVaultFile(context, activeFile)) {
                        com.apusapp.utils.RecentFilesManager.recordFile(context, activeFile)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Saved to selected location", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to save file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val pageCache = remember(activeFile.path) {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8 // Use 1/8th of available runtime memory
        object : android.util.LruCache<Int, Bitmap>(cacheSize) {
            override fun sizeOf(key: Int, value: Bitmap): Int {
                return value.byteCount / 1024
            }
        }
    }
    val renderLock = remember(activeFile.path) { Any() }

    // Load PDF Asynchronously
    LaunchedEffect(activeFile.path) {
        isLoading = true
        errorMessage = null
        withContext(Dispatchers.IO) {
            try {
                // Check if the file is encrypted with a user password
                val inspect = UnlockPdfService.inspectPdfFile(context, activeFile)
                if (inspect.encryptionStatus == EncryptionStatus.PASSWORD_PROTECTED) {
                    withContext(Dispatchers.Main) {
                        isPasswordProtected = true
                        isLoading = false
                    }
                    return@withContext
                }

                var pfd: ParcelFileDescriptor? = null
                // Attempt 1: Open direct file descriptor
                try {
                    if (activeFile.exists() && activeFile.canRead()) {
                        pfd = ParcelFileDescriptor.open(activeFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    }
                } catch (_: Exception) {}

                // Attempt 2: If direct open failed or restricted, make a local cache copy
                if (pfd == null) {
                    try {
                        val temp = File(context.cacheDir, "reader_cache_${System.currentTimeMillis()}.pdf")
                        FileInputStream(activeFile).use { input ->
                            FileOutputStream(temp).use { output ->
                                input.copyTo(output)
                            }
                        }
                        pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
                    } catch (_: Exception) {}
                }

                if (pfd == null) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "Cannot access PDF file descriptor."
                        isLoading = false
                    }
                    return@withContext
                }

                val renderer = PdfRenderer(pfd)
                withContext(Dispatchers.Main) {
                    fileDescriptor = pfd
                    pdfRenderer = renderer
                    pageCount = renderer.pageCount
                    isPasswordProtected = false
                    isLoading = false
                }
            } catch (e: SecurityException) {
                withContext(Dispatchers.Main) {
                    isPasswordProtected = true
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = e.localizedMessage ?: "Failed to open PDF document."
                    isLoading = false
                }
            }
        }
    }

    // Cleanup resources
    DisposableEffect(activeFile.path) {
        onDispose {
            synchronized(renderLock) {
                try {
                    pdfRenderer?.close()
                } catch (_: Exception) {}
                try {
                    fileDescriptor?.close()
                } catch (_: Exception) {}
                pageCache.evictAll()
            }
        }
    }

    // Perform Detailed Text Search
    LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        if (q.length >= 2) {
            isSearching = true
            val matches = withContext(Dispatchers.IO) {
                PdfHelper.searchDetailedMatchesInPdf(activeFile, q)
            }
            searchDetailedMatches = matches
            currentSearchIndex = 0
            if (matches.isNotEmpty()) {
                listState.animateScrollToItem(matches[0].pageIndex)
            }
            isSearching = false
        } else {
            searchDetailedMatches = emptyList()
            currentSearchIndex = 0
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                currentSearchIndex = 0
                            },
                            placeholder = {
                                Text(
                                    "Find in file...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            singleLine = true,
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isSearching) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else if (searchQuery.isNotBlank()) {
                                        val counterText = if (searchDetailedMatches.isNotEmpty()) {
                                            "${currentSearchIndex + 1}/${searchDetailedMatches.size}"
                                        } else {
                                            "0 matches"
                                        }
                                        Text(
                                            text = counterText,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (searchDetailedMatches.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                        IconButton(
                                            onClick = {
                                                searchQuery = ""
                                                searchDetailedMatches = emptyList()
                                                currentSearchIndex = 0
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Clear,
                                                contentDescription = "Clear search",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    } else {
                        Column {
                            Text(
                                text = activeFile.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (pageCount > 0) {
                                Text(
                                    text = "$pageCount ${if (pageCount == 1) "Page" else "Pages"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isSearchActive) {
                                isSearchActive = false
                                searchQuery = ""
                                searchDetailedMatches = emptyList()
                                currentSearchIndex = 0
                            } else {
                                handleBack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (isSearchActive) "Close Search" else "Back"
                        )
                    }
                },
                actions = {
                    if (isSearchActive) {
                        // Previous Match Button
                        IconButton(
                            onClick = {
                                if (searchDetailedMatches.isNotEmpty()) {
                                    currentSearchIndex = if (currentSearchIndex > 0) currentSearchIndex - 1 else searchDetailedMatches.size - 1
                                    val match = searchDetailedMatches[currentSearchIndex]
                                    scope.launch { listState.animateScrollToItem(match.pageIndex) }
                                }
                            },
                            enabled = searchDetailedMatches.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "Previous Match"
                            )
                        }
                        // Next Match Button
                        IconButton(
                            onClick = {
                                if (searchDetailedMatches.isNotEmpty()) {
                                    currentSearchIndex = (currentSearchIndex + 1) % searchDetailedMatches.size
                                    val match = searchDetailedMatches[currentSearchIndex]
                                    scope.launch { listState.animateScrollToItem(match.pageIndex) }
                                }
                            },
                            enabled = searchDetailedMatches.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Next Match"
                            )
                        }
                        // Close Search Button
                        IconButton(
                            onClick = {
                                isSearchActive = false
                                searchQuery = ""
                                searchDetailedMatches = emptyList()
                                currentSearchIndex = 0
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Exit Search"
                            )
                        }
                    } else {
                        // Search Button in Top Bar
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = "Search Document",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Bookmark Button in Top Bar
                        val currentActivePage = if (isBookMode) bookCurrentPage else listState.firstVisibleItemIndex
                        val isBookmarked = currentActivePage in bookmarkedPages
                        IconButton(onClick = { toggleBookmark(currentActivePage) }) {
                            Icon(
                                imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = "Bookmark Page",
                                tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Freehand Pen & Highlighter Toggle Button
                        IconButton(
                            onClick = {
                                HapticManager.light()
                                isDrawMode = !isDrawMode
                                if (isDrawMode) {
                                    Toast.makeText(context, "Draw Mode ON: Drag to draw notes or highlight text", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isDrawMode) Icons.Default.Edit else Icons.Outlined.Edit,
                                contentDescription = "Annotate PDF",
                                tint = if (isDrawMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // 1. Screen Rotation Button (Left side of More Menu button)
                        IconButton(
                            onClick = {
                                HapticManager.light()
                                activity?.let { act ->
                                    act.requestedOrientation = if (isLandscape) {
                                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                    } else {
                                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isLandscape) Icons.Outlined.ScreenLockPortrait else Icons.Outlined.ScreenRotation,
                                contentDescription = if (isLandscape) "Switch to Portrait" else "Switch to Landscape",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Read Aloud (TTS) Button
                        IconButton(
                            onClick = {
                                HapticManager.light()
                                readAloudCurrentPage()
                            }
                        ) {
                            Icon(
                                imageVector = if (isTtsSpeaking) Icons.Filled.VolumeUp else Icons.Outlined.VolumeUp,
                                contentDescription = if (isTtsSpeaking) "Stop Reading" else "Read Page Aloud",
                                tint = if (isTtsSpeaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Star / Favorite Toggle
                        IconButton(
                            onClick = {
                                HapticManager.light()
                                com.apusapp.utils.FavoritesManager.toggleFavorite(context, activeFile.absolutePath)
                                Toast.makeText(context, if (!isFavorited) "Added to Favorites ⭐" else "Removed from Favorites", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = if (isFavorited) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                contentDescription = if (isFavorited) "Favorited" else "Add to Favorites",
                                tint = if (isFavorited) com.apusapp.ui.theme.ApusCoral else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // 2. Three-vertical-dots More menu button (Right side)
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More Options",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            val menuScrollState = rememberScrollState()
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                scrollState = menuScrollState,
                                modifier = Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.7f).dp)
                            ) {
                                // Table of Contents Item
                                DropdownMenuItem(
                                    text = { Text("Table of Contents (Chapters)", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.MenuBook,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        loadAndShowToc()
                                    }
                                )

                                // Bookmarked Pages Dialog Item
                                DropdownMenuItem(
                                    text = { Text("Bookmarked Pages (${bookmarkedPages.size})", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Bookmark,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showBookmarksDialog = true
                                    }
                                )

                                // Read Aloud (TTS) Menu Item
                                DropdownMenuItem(
                                    text = { Text(if (isTtsSpeaking) "Stop Reading" else "Read Aloud (Text to Speech)", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isTtsSpeaking) Icons.Filled.VolumeUp else Icons.Outlined.VolumeUp,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        readAloudCurrentPage()
                                    }
                                )

                                // Add Page Numbers Tool
                                DropdownMenuItem(
                                    text = { Text("Add Page Numbers (1 of N)", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.FormatListNumbered,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        Toast.makeText(context, "Adding page numbers...", Toast.LENGTH_SHORT).show()
                                        scope.launch(Dispatchers.IO) {
                                            val res = com.apusapp.utils.PdfHelper.addPageNumbersToPdf(context, activeFile)
                                            withContext(Dispatchers.Main) {
                                                res.fold(
                                                    onSuccess = { Toast.makeText(context, "Saved numbered document: ${it.name}", Toast.LENGTH_LONG).show() },
                                                    onFailure = { Toast.makeText(context, "Failed: ${it.message}", Toast.LENGTH_SHORT).show() }
                                                )
                                            }
                                        }
                                    }
                                )

                                // 0. Select & Copy Text
                                DropdownMenuItem(
                                    text = { Text("Select & Copy Text", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.ContentCopy,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showTextSelectionDialog = true
                                    }
                                )

                                // Convert to B&W / Print-Friendly
                                DropdownMenuItem(
                                    text = { Text("B&W Print-Friendly (Ink Saver)", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.PrintDisabled,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        Toast.makeText(context, "Converting to B&W...", Toast.LENGTH_SHORT).show()
                                        scope.launch(Dispatchers.IO) {
                                            val res = com.apusapp.data.GrayscalePdfService.convertToGrayscale(context, activeFile)
                                            withContext(Dispatchers.Main) {
                                                res.fold(
                                                    onSuccess = { Toast.makeText(context, "Saved B&W document: ${it.name}", Toast.LENGTH_LONG).show() },
                                                    onFailure = { Toast.makeText(context, "Failed: ${it.message}", Toast.LENGTH_SHORT).show() }
                                                )
                                            }
                                        }
                                    }
                                )

                                // Export to Text (.txt)
                                DropdownMenuItem(
                                    text = { Text("Export to Text (.txt)", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        Toast.makeText(context, "Extracting text to .txt file...", Toast.LENGTH_SHORT).show()
                                        scope.launch(Dispatchers.IO) {
                                            val res = com.apusapp.data.PdfToTextService.extractAndSaveText(context, activeFile)
                                            withContext(Dispatchers.Main) {
                                                res.fold(
                                                    onSuccess = { (file, _) ->
                                                        Toast.makeText(context, "Text file exported: ${file.name}", Toast.LENGTH_LONG).show()
                                                    },
                                                    onFailure = { Toast.makeText(context, "Failed: ${it.message}", Toast.LENGTH_SHORT).show() }
                                                )
                                            }
                                        }
                                    }
                                )

                                // 1. Find in file
                                DropdownMenuItem(
                                    text = { Text("Find in file", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Search,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        isSearchActive = true
                                    }
                                )

                                // 2. Open with
                                DropdownMenuItem(
                                    text = { Text("Open with", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        openWith(context, activeFile)
                                    }
                                )

                                // 3. Save
                                DropdownMenuItem(
                                    text = { Text("Save", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Save,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        if (!com.apusapp.utils.StorageLocationManager.isFolderChosen(context)) {
                                            showSaveLocationDialogInReader = true
                                        } else {
                                            saveCurrentFile(context, activeFile, appliedSignature) { savedFile ->
                                                activeFile = savedFile
                                            }
                                        }
                                    }
                                )

                                // 4. Save as
                                DropdownMenuItem(
                                    text = { Text("Save as", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.SaveAs,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        val defaultCopyName = if (activeFile.name.endsWith(".pdf", ignoreCase = true)) {
                                            "${activeFile.nameWithoutExtension}_copy.pdf"
                                        } else {
                                            "${activeFile.name}_copy.pdf"
                                        }
                                        saveAsLauncher.launch(defaultCopyName)
                                    }
                                )

                                // 5. Print
                                DropdownMenuItem(
                                    text = { Text("Print", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Print,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        printPdf(context, activeFile)
                                    }
                                )

                                // 6. File info
                                DropdownMenuItem(
                                    text = { Text("File info", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showFileInfoDialog = true
                                    }
                                )

                                // 7. Share : update share option
                                DropdownMenuItem(
                                    text = { Text("Share", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Share,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        sharePdfFile(context, activeFile)
                                    }
                                )


                                // Previous App Options Retained:
                                DropdownMenuItem(
                                    text = { Text("Edit / Sign", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Draw,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showSignatureDialog = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Read Aloud (TTS)", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.VolumeUp,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        isTtsActive = true
                                        val p = if (isBookMode) bookCurrentPage + 1 else listState.firstVisibleItemIndex + 1
                                        speakPage(p)
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text(if (isBookMode) "Mode: Continuous Scroll" else "Mode: Book Flip View", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isBookMode) Icons.Outlined.ViewStream else Icons.Outlined.AutoStories,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        isBookMode = !isBookMode
                                        if (isBookMode) {
                                            bookCurrentPage = listState.firstVisibleItemIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                                        } else {
                                            scope.launch { listState.scrollToItem(bookCurrentPage) }
                                        }
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Add Text Watermark", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.BrandingWatermark,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showWatermarkDialog = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Add Page Numbers", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.FormatListNumbered,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        scope.launch(Dispatchers.IO) {
                                            val res = com.apusapp.utils.PdfHelper.addPageNumbersToPdf(context, activeFile)
                                            withContext(Dispatchers.Main) {
                                                res.fold(
                                                    onSuccess = { Toast.makeText(context, "Page numbers added successfully!", Toast.LENGTH_SHORT).show() },
                                                    onFailure = { Toast.makeText(context, "Failed: ${it.message}", Toast.LENGTH_SHORT).show() }
                                                )
                                            }
                                        }
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Save as Images", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Image,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showSaveAsImagesDialog = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Jump to Page", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.FindInPage,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showJumpToPageDialog = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Rename", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.EditNote,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showRenameDialog = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Reading Mode: ${readingMode.label}", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = when (readingMode) {
                                                ReaderReadingMode.NIGHT -> Icons.Default.DarkMode
                                                ReaderReadingMode.SEPIA -> Icons.Default.AutoStories
                                                ReaderReadingMode.EYE_COMFORT -> Icons.Default.Visibility
                                                else -> Icons.Default.LightMode
                                            },
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showReadingModeDialog = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showDeleteDialog = true
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isLoading) {
                // Loading Spinner
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Opening PDF...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (isPasswordProtected) {
                // Standard Password Prompt Dialog
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    try {
                        focusRequester.requestFocus()
                    } catch (_: Exception) {}
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(60.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Enter Password",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "This file is protected. Enter password to open.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }

                            OutlinedTextField(
                                value = enteredPassword,
                                onValueChange = {
                                    enteredPassword = it
                                    passwordError = null
                                },
                                label = { Text("Password") },
                                singleLine = true,
                                isError = passwordError != null,
                                supportingText = passwordError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    if (enteredPassword.isNotBlank() && !isUnlocking) {
                                        scope.launch {
                                            isUnlocking = true
                                            passwordError = null
                                            val result = UnlockPdfService.unlockAndStripSecurity(context, file, enteredPassword) {}
                                            result.fold(
                                                onSuccess = { unlockedFile ->
                                                    activeFile = unlockedFile
                                                    isPasswordProtected = false
                                                    isUnlocking = false
                                                },
                                                onFailure = {
                                                    isUnlocking = false
                                                    passwordError = "Incorrect password"
                                                }
                                            )
                                        }
                                    }
                                }),
                                trailingIcon = {
                                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                        Icon(
                                            imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = if (isPasswordVisible) "Hide password" else "Show password"
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onBack,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Cancel")
                                }

                                TactileButton(
                                    onClick = {
                                        if (enteredPassword.isNotBlank()) {
                                            scope.launch {
                                                isUnlocking = true
                                                passwordError = null
                                                val result = UnlockPdfService.unlockAndStripSecurity(context, file, enteredPassword) {}
                                                result.fold(
                                                    onSuccess = { unlockedFile ->
                                                        activeFile = unlockedFile
                                                        isPasswordProtected = false
                                                        isUnlocking = false
                                                    },
                                                    onFailure = {
                                                        isUnlocking = false
                                                        passwordError = "Incorrect password"
                                                    }
                                                )
                                            }
                                        } else {
                                            passwordError = "Please enter password"
                                        }
                                    },
                                    isLoading = isUnlocking,
                                    enabled = enteredPassword.isNotBlank(),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Open")
                                }
                            }
                        }
                    }
                }
            } else if (errorMessage != null) {
                // Error State
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.size(56.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Unable to Open PDF",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = errorMessage ?: "Unknown error",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = onBack,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Go Back")
                            }
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    val matchingPageSet = remember(searchDetailedMatches) {
                        searchDetailedMatches.map { it.pageIndex }.distinct()
                    }
                    val activeMatchPageIndex = if (searchDetailedMatches.isNotEmpty() && currentSearchIndex in searchDetailedMatches.indices) {
                        searchDetailedMatches[currentSearchIndex].pageIndex
                    } else -1

                    // Search Results Indicator Bar
                    if (isSearchActive && searchDetailedMatches.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                            tonalElevation = 2.dp
                        ) {
                            ScrollableTabRow(
                                selectedTabIndex = -1,
                                edgePadding = 16.dp,
                                divider = {},
                                containerColor = Color.Transparent,
                            ) {
                                matchingPageSet.forEach { pIdx ->
                                    val isCurrentPageMatch = pIdx == activeMatchPageIndex
                                    AssistChip(
                                        onClick = { 
                                            val firstMatchOnPageIdx = searchDetailedMatches.indexOfFirst { it.pageIndex == pIdx }
                                            if (firstMatchOnPageIdx >= 0) {
                                                currentSearchIndex = firstMatchOnPageIdx
                                            }
                                            scope.launch { listState.animateScrollToItem(pIdx) }
                                        },
                                        label = { Text("Page ${pIdx + 1}") },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = if (isCurrentPageMatch) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                            labelColor = if (isCurrentPageMatch) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        ),
                                        border = if (isCurrentPageMatch) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    } else if (isSearchActive && searchQuery.trim().length >= 2 && !isSearching && searchDetailedMatches.isEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "No matches found for \"$searchQuery\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(if (isNightMode) Color.Black else Color(0xFFF4F5F9))
                            .clipToBounds()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = { tapOffset ->
                                        if (scale > 1.15f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            scale = 2.5f
                                            val centerX = size.width / 2f
                                            val centerY = size.height / 2f
                                            offset = Offset(
                                                x = (centerX - tapOffset.x) * (2.5f - 1f),
                                                y = (centerY - tapOffset.y) * (2.5f - 1f)
                                            )
                                        }
                                    }
                                )
                            }
                            .pointerInput(Unit) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    val oldScale = scale
                                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                                    val maxOffsetX = (size.width * (newScale - 1f)) / 2f
                                    val maxOffsetY = (size.height * (newScale - 1f)) / 2f

                                    if (newScale <= 1.05f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        val scaleFactor = newScale / oldScale
                                        val focalAdjustX = centroid.x - (centroid.x - offset.x) * scaleFactor
                                        val focalAdjustY = centroid.y - (centroid.y - offset.y) * scaleFactor

                                        val newOffsetX = (focalAdjustX + pan.x * newScale).coerceIn(-maxOffsetX, maxOffsetX)
                                        val newOffsetY = (focalAdjustY + pan.y * newScale).coerceIn(-maxOffsetY, maxOffsetY)

                                        scale = newScale
                                        offset = Offset(newOffsetX, newOffsetY)
                                    }
                                }
                            },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                        ) {
                            if (isBookMode) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    PdfPageItem(
                                        renderer = pdfRenderer,
                                        pageIndex = bookCurrentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                                        isNightMode = isNightMode,
                                        readingMode = readingMode,
                                        pageCache = pageCache,
                                        renderLock = renderLock,
                                        signatureStamp = if (bookCurrentPage == (pageCount - 1)) appliedSignature else null,
                                        isHighlighted = (isSearchActive && bookCurrentPage == activeMatchPageIndex),
                                    )
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    userScrollEnabled = (!isDrawMode && scale <= 1.05f),
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(16.dp),
                                ) {
                                    items(
                                        count = pageCount,
                                        key = { it },
                                        contentType = { "pdf_page" }
                                    ) { index ->
                                        PdfPageItem(
                                            renderer = pdfRenderer,
                                            pageIndex = index,
                                            isNightMode = isNightMode,
                                            readingMode = readingMode,
                                            pageCache = pageCache,
                                            renderLock = renderLock,
                                            signatureStamp = if (index == (pageCount - 1)) appliedSignature else null,
                                            isHighlighted = (isSearchActive && index == activeMatchPageIndex),
                                        )
                                    }
                                }
                            }

                            // Freehand Drawing & Highlighter Canvas Overlay
                            val activeDrawPage = if (isBookMode) bookCurrentPage else listState.firstVisibleItemIndex
                            androidx.compose.foundation.Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (isDrawMode) {
                                            Modifier.pointerInput(drawTool, penColor, highlighterColor, activeDrawPage) {
                                                detectDragGestures(
                                                    onDragStart = { offset ->
                                                        if (drawTool == "ERASER") {
                                                            val eraseRadius = 60f
                                                            pageDrawings[activeDrawPage]?.removeAll { (pts, _) ->
                                                                pts.any { (it - offset).getDistance() < eraseRadius }
                                                            }
                                                            pageHighlighters[activeDrawPage]?.removeAll { (pts, _) ->
                                                                pts.any { (it - offset).getDistance() < eraseRadius }
                                                            }
                                                        } else {
                                                            currentDrawStroke = listOf(offset)
                                                        }
                                                    },
                                                    onDrag = { change, _ ->
                                                        change.consume()
                                                        if (drawTool == "ERASER") {
                                                            val eraseRadius = 60f
                                                            pageDrawings[activeDrawPage]?.removeAll { (pts, _) ->
                                                                pts.any { (it - change.position).getDistance() < eraseRadius }
                                                            }
                                                            pageHighlighters[activeDrawPage]?.removeAll { (pts, _) ->
                                                                pts.any { (it - change.position).getDistance() < eraseRadius }
                                                            }
                                                        } else {
                                                            currentDrawStroke = currentDrawStroke + change.position
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        if (drawTool != "ERASER" && currentDrawStroke.size > 1) {
                                                            if (drawTool == "HIGHLIGHTER") {
                                                                val list = pageHighlighters.getOrPut(activeDrawPage) { mutableListOf() }
                                                                list.add(Pair(currentDrawStroke, highlighterColor.copy(alpha = 0.35f)))
                                                            } else {
                                                                val list = pageDrawings.getOrPut(activeDrawPage) { mutableListOf() }
                                                                list.add(Pair(currentDrawStroke, penColor))
                                                            }
                                                            currentDrawStroke = emptyList()
                                                        }
                                                    }
                                                )
                                            }
                                        } else Modifier
                                    )
                            ) {
                                // 1. Render Highlighters
                                pageHighlighters[activeDrawPage]?.forEach { (points, color) ->
                                    if (points.size > 1) {
                                        val p = ComposePath()
                                        p.moveTo(points[0].x, points[0].y)
                                        for (i in 1 until points.size) p.lineTo(points[i].x, points[i].y)
                                        drawPath(path = p, color = color, style = Stroke(width = 24.dp.toPx(), cap = StrokeCap.Square))
                                    }
                                }

                                // 2. Render Pen Drawings
                                pageDrawings[activeDrawPage]?.forEach { (points, color) ->
                                    if (points.size > 1) {
                                        val p = ComposePath()
                                        p.moveTo(points[0].x, points[0].y)
                                        for (i in 1 until points.size) p.lineTo(points[i].x, points[i].y)
                                        drawPath(path = p, color = color, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                                    }
                                }

                                // 3. Render current active stroke
                                if (currentDrawStroke.size > 1) {
                                    val p = ComposePath()
                                    p.moveTo(currentDrawStroke[0].x, currentDrawStroke[0].y)
                                    for (i in 1 until currentDrawStroke.size) p.lineTo(currentDrawStroke[i].x, currentDrawStroke[i].y)
                                    if (drawTool == "HIGHLIGHTER") {
                                        drawPath(path = p, color = highlighterColor.copy(alpha = 0.35f), style = Stroke(width = 24.dp.toPx(), cap = StrokeCap.Square))
                                    } else {
                                        drawPath(path = p, color = penColor, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                                    }
                                }
                            }
                        }
                        
                        // Floating Drawing / Annotation Toolbar
                        if (isDrawMode) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shadowElevation = 10.dp,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 16.dp, vertical = 68.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Pen Tool
                                    FilterChip(
                                        selected = drawTool == "PEN",
                                        onClick = { drawTool = "PEN" },
                                        label = { Text("Pen") },
                                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    )

                                    // Highlighter Tool
                                    FilterChip(
                                        selected = drawTool == "HIGHLIGHTER",
                                        onClick = { drawTool = "HIGHLIGHTER" },
                                        label = { Text("Highlighter") },
                                        leadingIcon = { Icon(Icons.Outlined.BorderColor, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    )

                                    // Eraser Tool
                                    FilterChip(
                                        selected = drawTool == "ERASER",
                                        onClick = { drawTool = "ERASER" },
                                        label = { Text("Eraser") },
                                        leadingIcon = { Icon(Icons.Outlined.AutoFixNormal, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    )

                                    // Color selector indicator
                                    Surface(
                                        shape = CircleShape,
                                        color = if (drawTool == "PEN") penColor else highlighterColor,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable {
                                                if (drawTool == "PEN") {
                                                    penColor = when (penColor) {
                                                        Color(0xFFE53935) -> Color(0xFF1E88E5)
                                                        Color(0xFF1E88E5) -> Color(0xFF43A047)
                                                        Color(0xFF43A047) -> Color(0xFF212121)
                                                        else -> Color(0xFFE53935)
                                                    }
                                                } else {
                                                    highlighterColor = when (highlighterColor) {
                                                        Color(0xFFFFEB3B) -> Color(0xFF69F0AE)
                                                        Color(0xFF69F0AE) -> Color(0xFF40C4FF)
                                                        Color(0xFF40C4FF) -> Color(0xFFFF80AB)
                                                        else -> Color(0xFFFFEB3B)
                                                    }
                                                }
                                            }
                                    ) {}

                                    // Undo button
                                    IconButton(
                                        onClick = {
                                            val cur = if (isBookMode) bookCurrentPage else listState.firstVisibleItemIndex
                                            if (drawTool == "HIGHLIGHTER") {
                                                pageHighlighters[cur]?.removeLastOrNull()
                                            } else {
                                                pageDrawings[cur]?.removeLastOrNull()
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Outlined.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp))
                                    }

                                    // Clear Page button
                                    IconButton(
                                        onClick = {
                                            val cur = if (isBookMode) bookCurrentPage else listState.firstVisibleItemIndex
                                            pageDrawings[cur]?.clear()
                                            pageHighlighters[cur]?.clear()
                                            Toast.makeText(context, "Page annotations cleared", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Outlined.Delete, contentDescription = "Clear Page", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }

                                    // Save Annotations Permanently button
                                    IconButton(
                                        onClick = {
                                            isSavingAnnotations = true
                                            saveCurrentFileWithAnnotations(
                                                context = context,
                                                file = activeFile,
                                                appliedSignature = appliedSignature,
                                                pageDrawings = pageDrawings,
                                                pageHighlighters = pageHighlighters
                                            ) { savedFile ->
                                                activeFile = savedFile
                                                pageDrawings.clear()
                                                pageHighlighters.clear()
                                                isSavingAnnotations = false
                                                isDrawMode = false
                                                Toast.makeText(context, "Annotations saved permanently to PDF!", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Outlined.Save, contentDescription = "Save Annotations", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    }

                                    // Done / Close Draw Mode
                                    IconButton(
                                        onClick = { isDrawMode = false },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Outlined.Close, contentDescription = "Close Draw Mode", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }

                        // Bottom Controls Row: Page Indicator (Left) & Zoom Controller (Right)
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Page Indicator
                            if (pageCount > 0) {
                                val currentPage by remember {
                                    derivedStateOf { listState.firstVisibleItemIndex + 1 }
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                    shape = RoundedCornerShape(12.dp),
                                    shadowElevation = 4.dp,
                                    modifier = Modifier.clickable { showJumpToPageDialog = true }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = "$currentPage / $pageCount",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            } else {
                                Spacer(modifier = Modifier.size(1.dp))
                            }

                            // Quick Zoom Pill Controller
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                shape = RoundedCornerShape(16.dp),
                                shadowElevation = 4.dp,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                ) {
                                    TactileIconButton(
                                        onClick = {
                                            val newScale = (scale - 0.4f).coerceIn(1f, 5f)
                                            scale = newScale
                                            if (newScale <= 1.05f) offset = Offset.Zero
                                        },
                                        enabled = scale > 1f,
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(Icons.Default.Remove, contentDescription = "Zoom Out", modifier = Modifier.size(16.dp))
                                    }

                                    Surface(
                                        onClick = {
                                            scale = 1f
                                            offset = Offset.Zero
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (scale > 1.05f) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        modifier = Modifier.bounceClick(),
                                    ) {
                                        Text(
                                            text = "${(scale * 100).roundToInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (scale > 1.05f) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                        )
                                    }

                                    TactileIconButton(
                                        onClick = {
                                            scale = (scale + 0.4f).coerceIn(1f, 5f)
                                        },
                                        enabled = scale < 5f,
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "Zoom In", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

            }
        }
    }

    if (showReadingModeDialog) {
        AlertDialog(
            onDismissRequest = { showReadingModeDialog = false },
            title = {
                Text("Select Reading Mode", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderReadingMode.entries.forEach { mode ->
                        val isSelected = (readingMode == mode)
                        Surface(
                            onClick = {
                                readingMode = mode
                                isNightMode = (mode == ReaderReadingMode.NIGHT)
                                showReadingModeDialog = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when (mode) {
                                        ReaderReadingMode.NORMAL -> "☀️"
                                        ReaderReadingMode.NIGHT -> "🌙"
                                        ReaderReadingMode.SEPIA -> "📜"
                                        ReaderReadingMode.EYE_COMFORT -> "👓"
                                    },
                                    fontSize = 20.sp
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = mode.label,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = mode.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReadingModeDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // File Info Dialog
    if (showFileInfoDialog) {
        FileInfoDialog(
            file = activeFile,
            pageCount = pageCount,
            onDismiss = { showFileInfoDialog = false }
        )
    }

    // Table of Contents Dialog (PDF Outline Tree)
    if (showTocDialog) {
        AlertDialog(
            onDismissRequest = { showTocDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Table of Contents", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                if (isLoadingToc) {
                    Box(modifier = Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                    }
                } else if (outlineItems.isEmpty()) {
                    Text("This document does not contain an embedded Table of Contents / Outline tree.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                        items(outlineItems) { item ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clickable {
                                        showTocDialog = false
                                        val p = item.pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                                        if (isBookMode) {
                                            bookCurrentPage = p
                                        } else {
                                            scope.launch { listState.animateScrollToItem(p) }
                                        }
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp).padding(start = (item.depth * 14).dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (item.depth == 0) FontWeight.SemiBold else FontWeight.Normal),
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "p. ${item.pageIndex + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTocDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showBookmarksDialog) {
        AlertDialog(
            onDismissRequest = { showBookmarksDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Bookmarked Pages", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                if (bookmarkedPages.isEmpty()) {
                    Text("No pages bookmarked yet. Tap the bookmark icon in the top bar to bookmark the current page.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(bookmarkedPages.sorted().toList()) { pIndex ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    showBookmarksDialog = false
                                    if (isBookMode) {
                                        bookCurrentPage = pIndex
                                    } else {
                                        scope.launch { listState.animateScrollToItem(pIndex) }
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Page ${pIndex + 1}", fontWeight = FontWeight.SemiBold)
                                    IconButton(
                                        onClick = { toggleBookmark(pIndex) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Outlined.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBookmarksDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showTextSelectionDialog) {
        val curPageIdx = if (isBookMode) bookCurrentPage else listState.firstVisibleItemIndex
        var extractedText by remember { mutableStateOf("Extracting page text...") }
        var isExtracting by remember { mutableStateOf(true) }

        LaunchedEffect(curPageIdx) {
            withContext(Dispatchers.IO) {
                val txt = com.apusapp.utils.PdfHelper.extractPageText(activeFile, curPageIdx + 1)
                withContext(Dispatchers.Main) {
                    extractedText = if (txt.isBlank()) "No selectable text found on Page ${curPageIdx + 1}." else txt
                    isExtracting = false
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showTextSelectionDialog = false },
            icon = {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            },
            title = {
                Text("Page ${curPageIdx + 1} Selectable Text", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                    if (isExtracting) {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    } else {
                        Text(
                            text = "Long-press on text to select and copy, or use Copy All:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text = extractedText,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("PDF Text", extractedText))
                        Toast.makeText(context, "Page text copied to clipboard", Toast.LENGTH_SHORT).show()
                        showTextSelectionDialog = false
                    },
                    enabled = !isExtracting && extractedText.isNotBlank(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Copy All Text")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextSelectionDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Jump to Page Dialog
    if (showJumpToPageDialog && pageCount > 0) {
        val currentPageNumber = listState.firstVisibleItemIndex + 1
        JumpToPageDialog(
            currentPage = currentPageNumber,
            totalPages = pageCount,
            onJump = { targetPage ->
                scope.launch {
                    listState.scrollToItem((targetPage - 1).coerceIn(0, pageCount - 1))
                }
            },
            onDismiss = { showJumpToPageDialog = false }
        )
    }

    // Floating TTS Read Aloud Controller
    if (isTtsActive) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 8.dp,
            modifier = Modifier
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Outlined.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(
                    text = "Page $ttsCurrentPage",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = {
                        if (isTtsSpeaking) {
                            tts?.stop()
                            isTtsSpeaking = false
                        } else {
                            speakPage(ttsCurrentPage)
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isTtsSpeaking) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = {
                        if (ttsCurrentPage < pageCount) {
                            ttsCurrentPage++
                            speakPage(ttsCurrentPage)
                            if (isBookMode) bookCurrentPage = ttsCurrentPage - 1
                            else scope.launch { listState.animateScrollToItem(ttsCurrentPage - 1) }
                        }
                    },
                    enabled = ttsCurrentPage < pageCount,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next Page")
                }
                IconButton(
                    onClick = {
                        tts?.stop()
                        isTtsSpeaking = false
                        isTtsActive = false
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close TTS", modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    // Watermark Dialog
    if (showWatermarkDialog) {
        AlertDialog(
            onDismissRequest = { showWatermarkDialog = false },
            title = { Text("Add Text Watermark", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter watermark text to stamp across all pages:", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = watermarkInput,
                        onValueChange = { watermarkInput = it },
                        label = { Text("Watermark Text") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWatermarkDialog = false
                        scope.launch(Dispatchers.IO) {
                            val res = com.apusapp.utils.PdfHelper.addWatermarkToPdf(context, activeFile, watermarkInput)
                            withContext(Dispatchers.Main) {
                                res.fold(
                                    onSuccess = { Toast.makeText(context, "Watermark added: ${it.name}", Toast.LENGTH_SHORT).show() },
                                    onFailure = { Toast.makeText(context, "Failed: ${it.message}", Toast.LENGTH_SHORT).show() }
                                )
                            }
                        }
                    },
                    enabled = watermarkInput.isNotBlank()
                ) {
                    Text("Apply Watermark")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWatermarkDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Rename PDF Dialog
    if (showRenameDialog) {
        var newNameText by remember { mutableStateOf(activeFile.nameWithoutExtension) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename PDF", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a new file name:", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = newNameText,
                        onValueChange = { newNameText = it },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = newNameText.trim()
                        if (clean.isNotEmpty()) {
                            val newFileName = if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
                            val newTarget = File(activeFile.parentFile, newFileName)
                            if (activeFile.renameTo(newTarget)) {
                                activeFile = newTarget
                                Toast.makeText(context, "Renamed successfully", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Rename failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showRenameDialog = false
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Delete PDF Confirmation Dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Delete PDF?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${activeFile.name}\"? This action cannot be undone.",
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        if (activeFile.delete()) {
                            Toast.makeText(context, "File deleted", Toast.LENGTH_SHORT).show()
                        }
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Save as Images Dialog (JPG / JPEG / PNG)
    if (showSaveAsImagesDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSavingImages) showSaveAsImagesDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    tint = ApusCoral,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Save as Images", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Choose image format and export range:", style = MaterialTheme.typography.bodyMedium)

                    // Format Selector: JPG, JPEG, PNG
                    Text("Image Format", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, color = ApusCoral)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("PNG", "JPG", "JPEG").forEach { format ->
                            val isSelected = selectedImageFormat == format
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { selectedImageFormat = format },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) ApusCoral else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                ),
                                color = if (isSelected) ApusCoral.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = format,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) ApusCoral else MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }

                    // Page Range Selector
                    Text("Export Range", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, color = ApusCoral)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 1. All Pages
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { exportRangeType = ExportRangeType.ALL_PAGES },
                            shape = RoundedCornerShape(10.dp),
                            color = if (exportRangeType == ExportRangeType.ALL_PAGES) ApusCoral.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("All Pages ($pageCount ${if (pageCount == 1) "Page" else "Pages"})", style = MaterialTheme.typography.bodyMedium)
                                RadioButton(
                                    selected = exportRangeType == ExportRangeType.ALL_PAGES,
                                    onClick = { exportRangeType = ExportRangeType.ALL_PAGES },
                                    colors = RadioButtonDefaults.colors(selectedColor = ApusCoral)
                                )
                            }
                        }

                        // 2. Current Page Only
                        val currentPage = (listState.firstVisibleItemIndex + 1).coerceAtMost(pageCount.coerceAtLeast(1))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { exportRangeType = ExportRangeType.CURRENT_PAGE },
                            shape = RoundedCornerShape(10.dp),
                            color = if (exportRangeType == ExportRangeType.CURRENT_PAGE) ApusCoral.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Current Page Only (Page $currentPage)", style = MaterialTheme.typography.bodyMedium)
                                RadioButton(
                                    selected = exportRangeType == ExportRangeType.CURRENT_PAGE,
                                    onClick = { exportRangeType = ExportRangeType.CURRENT_PAGE },
                                    colors = RadioButtonDefaults.colors(selectedColor = ApusCoral)
                                )
                            }
                        }

                        // 3. Custom Range
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { exportRangeType = ExportRangeType.CUSTOM },
                            shape = RoundedCornerShape(10.dp),
                            color = if (exportRangeType == ExportRangeType.CUSTOM) ApusCoral.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Custom Range (e.g. 1-3, 5)", style = MaterialTheme.typography.bodyMedium)
                                    RadioButton(
                                        selected = exportRangeType == ExportRangeType.CUSTOM,
                                        onClick = { exportRangeType = ExportRangeType.CUSTOM },
                                        colors = RadioButtonDefaults.colors(selectedColor = ApusCoral)
                                    )
                                }

                                if (exportRangeType == ExportRangeType.CUSTOM) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = customRangeInput,
                                        onValueChange = { customRangeInput = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        placeholder = { Text("e.g. 1-3, 5 (Max $pageCount)", style = MaterialTheme.typography.bodySmall) },
                                        label = { Text("Pages (e.g. 1-3, 5)", style = MaterialTheme.typography.bodySmall) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = ApusCoral,
                                            focusedLabelColor = ApusCoral,
                                            cursorColor = ApusCoral
                                        ),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done)
                                    )
                                    val parsedCount = remember(customRangeInput, pageCount) {
                                        parseCustomPages(customRangeInput, pageCount).size
                                    }
                                    if (customRangeInput.isNotBlank()) {
                                        Text(
                                            text = if (parsedCount > 0) "$parsedCount page(s) will be exported" else "No valid pages in 1-$pageCount",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (parsedCount > 0) ApusCoral else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(top = 4.dp, start = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isSavingImages) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = ApusCoral)
                            Text("Exporting and saving images...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!isSavingImages) {
                            isSavingImages = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                    var pfd: ParcelFileDescriptor? = null
                                    try {
                                        pfd = ParcelFileDescriptor.open(activeFile, ParcelFileDescriptor.MODE_READ_ONLY)
                                    } catch (_: Exception) {}

                                    if (pfd == null) {
                                        val temp = File(context.cacheDir, "temp_render_${System.currentTimeMillis()}.pdf")
                                        FileInputStream(activeFile).use { input ->
                                            FileOutputStream(temp).use { output -> input.copyTo(output) }
                                        }
                                        pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
                                    }

                                    val nonNullPfd = pfd ?: throw IllegalStateException("Could not open file descriptor")
                                    val renderer = PdfRenderer(nonNullPfd)
                                    val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                                    val outputDir = File(picturesDir, "ApusPDF").apply { if (!exists()) mkdirs() }

                                    val baseName = activeFile.nameWithoutExtension
                                    val timestamp = System.currentTimeMillis()
                                    val ext = if (selectedImageFormat == "PNG") "png" else if (selectedImageFormat == "JPEG") "jpeg" else "jpg"
                                    val compressFormat = if (selectedImageFormat == "PNG") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG

                                    val pagesToExport = when (exportRangeType) {
                                        ExportRangeType.ALL_PAGES -> (0 until renderer.pageCount).toList()
                                        ExportRangeType.CURRENT_PAGE -> listOf(listState.firstVisibleItemIndex.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0)))
                                        ExportRangeType.CUSTOM -> {
                                            val custom = parseCustomPages(customRangeInput, renderer.pageCount)
                                            if (custom.isEmpty()) {
                                                withContext(Dispatchers.Main) {
                                                    isSavingImages = false
                                                    HapticManager.error()
                                                    Toast.makeText(context, "Please enter valid page numbers between 1 and ${renderer.pageCount}", Toast.LENGTH_LONG).show()
                                                }
                                                renderer.close()
                                                nonNullPfd.close()
                                                return@launch
                                            }
                                            custom
                                        }
                                    }
                                    val savedFiles = mutableListOf<String>()

                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        for (pageIdx in pagesToExport) {
                                            val page = renderer.openPage(pageIdx)
                                            val scaleFactor = 2
                                            val bitmap = Bitmap.createBitmap(page.width * scaleFactor, page.height * scaleFactor, Bitmap.Config.ARGB_8888)
                                            val canvas = android.graphics.Canvas(bitmap)
                                            canvas.drawColor(android.graphics.Color.WHITE)
                                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                                            val imgFileName = "${baseName}_page_${pageIdx + 1}_$timestamp.$ext"
                                            val contentValues = ContentValues().apply {
                                                put(MediaStore.MediaColumns.DISPLAY_NAME, imgFileName)
                                                put(MediaStore.MediaColumns.MIME_TYPE, if (selectedImageFormat == "PNG") "image/png" else "image/jpeg")
                                                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ApusPDF")
                                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                                            }
                                            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                                            if (uri != null) {
                                                context.contentResolver.openOutputStream(uri)?.use { out ->
                                                    bitmap.compress(compressFormat, 95, out)
                                                }
                                                contentValues.clear()
                                                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                                context.contentResolver.update(uri, contentValues, null, null)
                                                savedFiles.add("Pictures/ApusPDF/$imgFileName")
                                            }
                                            page.close()
                                            bitmap.recycle()
                                        }
                                    } else {
                                        for (pageIdx in pagesToExport) {
                                            val page = renderer.openPage(pageIdx)
                                            val scaleFactor = 2
                                            val bitmap = Bitmap.createBitmap(page.width * scaleFactor, page.height * scaleFactor, Bitmap.Config.ARGB_8888)
                                            val canvas = android.graphics.Canvas(bitmap)
                                            canvas.drawColor(android.graphics.Color.WHITE)
                                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                                            val outFile = File(outputDir, "${baseName}_page_${pageIdx + 1}_$timestamp.$ext")
                                            FileOutputStream(outFile).use { out ->
                                                bitmap.compress(compressFormat, 95, out)
                                            }
                                            savedFiles.add(outFile.absolutePath)
                                            page.close()
                                            bitmap.recycle()
                                        }
                                    }

                                    renderer.close()
                                    nonNullPfd.close()

                                    MediaScannerConnection.scanFile(
                                        context,
                                        savedFiles.toTypedArray(),
                                        arrayOf(if (selectedImageFormat == "PNG") "image/png" else "image/jpeg"),
                                        null
                                    )

                                    withContext(Dispatchers.Main) {
                                        isSavingImages = false
                                        showSaveAsImagesDialog = false
                                        HapticManager.success()
                                        Toast.makeText(context, "Saved ${savedFiles.size} image(s) to Pictures/ApusPDF", Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isSavingImages = false
                                        HapticManager.error()
                                        Toast.makeText(context, "Failed to export images: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isSavingImages,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ApusCoral)
                ) {
                    Text("Save Images")
                }
            },
            dismissButton = {
                if (!isSavingImages) {
                    TextButton(onClick = { showSaveAsImagesDialog = false }) {
                        Text("Cancel")
                    }
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Quick Sign / Edit Dialog
    if (showSignatureDialog) {
        AlertDialog(
            onDismissRequest = { showSignatureDialog = false },
            title = { Text("E-Signature Stamp", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Type signature text to place at the end of the document:")
                    OutlinedTextField(
                        value = signatureText,
                        onValueChange = { signatureText = it },
                        label = { Text("Full Name / Initials") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        appliedSignature = signatureText
                        showSignatureDialog = false
                        Toast.makeText(context, "Signature applied to document", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Apply Signature")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignatureDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
}

private fun sharePdfFile(context: Context, file: File) {
    try {
        if (!file.exists()) {
            Toast.makeText(context, "File does not exist or has been deleted", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share ${file.name} via...")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share PDF: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

private fun sharePdfFile(context: Context, filePath: String) {
    sharePdfFile(context, File(filePath))
}

private fun openWith(context: Context, file: File) {
    try {
        if (!file.exists()) {
            Toast.makeText(context, "File does not exist or has been deleted", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Open with")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "No compatible application found to open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun saveCurrentFile(
    context: Context,
    file: File,
    appliedSignature: String?,
    onFileSaved: (File) -> Unit,
) {
    try {
        if (!file.exists()) {
            Toast.makeText(context, "Cannot save: file not found", Toast.LENGTH_SHORT).show()
            return
        }
        val isCached = file.absolutePath.startsWith(context.cacheDir.absolutePath)
        val targetDir = if (isCached) {
            com.apusapp.utils.StorageLocationManager.getTargetDirectory(context)
        } else {
            file.parentFile ?: com.apusapp.utils.StorageLocationManager.getTargetDirectory(context)
        }

        val destFile = if (isCached) File(targetDir, file.name) else file

        if (!appliedSignature.isNullOrBlank()) {
            // Embed signature onto the document using PDFBox
            runCatching {
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { doc ->
                    val lastPage = doc.getPage(doc.numberOfPages - 1)
                    val contentStream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(
                        doc,
                        lastPage,
                        com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND,
                        true,
                        true
                    )
                    contentStream.use { cs ->
                        cs.beginText()
                        cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA_BOLD_OBLIQUE, 18f)
                        cs.setNonStrokingColor(0f, 51f / 255f, 102f / 255f)
                        cs.newLineAtOffset((lastPage.mediaBox.width - 220f).coerceAtLeast(20f), 50f)
                        cs.showText("Signed: $appliedSignature")
                        cs.endText()
                    }
                    doc.save(destFile)
                }
            }.onFailure {
                if (destFile != file) {
                    FileInputStream(file).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        } else if (destFile != file) {
            FileInputStream(file).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        }

        MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf("application/pdf"), null)
        com.apusapp.utils.RecentFilesManager.recordFile(context, destFile)
        onFileSaved(destFile)
        val msg = if (!appliedSignature.isNullOrBlank()) {
            "File saved with edits: ${destFile.name}"
        } else {
            "File saved: ${destFile.name}"
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to save file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun FileInfoDialog(
    file: File,
    pageCount: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var metadata by remember { mutableStateOf<com.apusapp.utils.PdfMetadata?>(null) }
    var showEditMeta by remember { mutableStateOf(false) }

    LaunchedEffect(file.absolutePath) {
        metadata = PdfHelper.getPdfMetadata(file)
    }

    val fileSizeFormatted = remember(file.length()) {
        try {
            Formatter.formatFileSize(context, file.length())
        } catch (_: Exception) {
            "${file.length() / 1024} KB"
        }
    }

    val lastModifiedFormatted = remember(file.lastModified()) {
        try {
            val date = Date(file.lastModified())
            SimpleDateFormat("MMM dd, yyyy  hh:mm a", Locale.getDefault()).format(date)
        } catch (_: Exception) {
            "Unknown"
        }
    }

    if (showEditMeta && metadata != null) {
        var editTitle by remember { mutableStateOf(metadata!!.title) }
        var editAuthor by remember { mutableStateOf(metadata!!.author) }
        var editSubject by remember { mutableStateOf(metadata!!.subject) }
        var editKeywords by remember { mutableStateOf(metadata!!.keywords) }
        var isSavingMeta by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isSavingMeta) showEditMeta = false },
            icon = {
                Icon(Icons.Outlined.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            title = { Text("Edit Document Metadata", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editAuthor,
                        onValueChange = { editAuthor = it },
                        label = { Text("Author") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editSubject,
                        onValueChange = { editSubject = it },
                        label = { Text("Subject") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editKeywords,
                        onValueChange = { editKeywords = it },
                        label = { Text("Keywords (comma separated)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isSavingMeta = true
                        scope.launch {
                            val res = PdfHelper.updatePdfMetadata(
                                file = file,
                                title = editTitle.trim(),
                                author = editAuthor.trim(),
                                subject = editSubject.trim(),
                                keywords = editKeywords.trim()
                            )
                            isSavingMeta = false
                            if (res.isSuccess) {
                                metadata = PdfHelper.getPdfMetadata(file)
                                showEditMeta = false
                                Toast.makeText(context, "Metadata updated successfully", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to update metadata", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isSavingMeta
                ) {
                    if (isSavingMeta) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditMeta = false }, enabled = !isSavingMeta) {
                    Text("Cancel")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "File info",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FileInfoField(label = "File Name", value = file.name)
                val effectivePageCount = if (pageCount > 0) pageCount else (metadata?.pageCount ?: 0)
                if (effectivePageCount > 0) {
                    FileInfoField(label = "Number of Pages", value = "$effectivePageCount ${if (effectivePageCount == 1) "Page" else "Pages"}")
                }
                FileInfoField(label = "File Size", value = fileSizeFormatted)
                metadata?.pdfVersion?.takeIf { it.isNotBlank() }?.let {
                    FileInfoField(label = "PDF Version", value = it)
                }
                if (metadata?.isEncrypted == true) {
                    FileInfoField(label = "Security", value = "Password Encrypted 🔒")
                }
                metadata?.title?.takeIf { it.isNotBlank() }?.let {
                    FileInfoField(label = "Title", value = it)
                }
                metadata?.author?.takeIf { it.isNotBlank() }?.let {
                    FileInfoField(label = "Author", value = it)
                }
                metadata?.subject?.takeIf { it.isNotBlank() }?.let {
                    FileInfoField(label = "Subject", value = it)
                }
                metadata?.keywords?.takeIf { it.isNotBlank() }?.let {
                    FileInfoField(label = "Keywords", value = it)
                }

                // Location with Copy Button
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Location",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = file.absolutePath,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 1.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("PDF Path", file.absolutePath)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Path copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy Path", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                FileInfoField(label = "Last Modified", value = lastModifiedFormatted)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (metadata != null && metadata?.isEncrypted == false) {
                    OutlinedButton(
                        onClick = { showEditMeta = true },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit Metadata")
                    }
                }
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Close")
                }
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun FileInfoField(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}

@Composable
fun AnnotationIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(38.dp)
            .bounceClick(scaleDownFactor = 0.90f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun PdfPageItem(
    renderer: PdfRenderer?,
    pageIndex: Int,
    isNightMode: Boolean,
    readingMode: ReaderReadingMode = ReaderReadingMode.NORMAL,
    pageCache: android.util.LruCache<Int, Bitmap>?,
    renderLock: Any?,
    signatureStamp: String? = null,
    isHighlighted: Boolean = false,
) {
    val context = LocalContext.current
    val cachedBitmap = remember(pageIndex) { pageCache?.get(pageIndex) }
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(cachedBitmap) }
    var renderError by remember(pageIndex) { mutableStateOf(false) }
    var aspectRatio by remember(pageIndex) {
        mutableFloatStateOf(
            if (cachedBitmap != null && cachedBitmap.height > 0) {
                cachedBitmap.width.toFloat() / cachedBitmap.height.toFloat()
            } else 0.707f
        )
    }
    
    val nightModeMatrix = remember {
        ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }
    val sepiaMatrix = remember {
        ColorMatrix(floatArrayOf(
            0.393f * 1.15f, 0.769f * 1.15f, 0.189f * 1.15f, 0f, 15f,
            0.349f * 1.05f, 0.686f * 1.05f, 0.168f * 1.05f, 0f, 10f,
            0.272f * 0.90f, 0.534f * 0.90f, 0.131f * 0.90f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
    }
    val eyeComfortMatrix = remember {
        ColorMatrix(floatArrayOf(
            1.0f, 0f, 0f, 0f, 0f,
            0f, 0.96f, 0f, 0f, 0f,
            0f, 0f, 0.82f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
    }

    LaunchedEffect(renderer, pageIndex) {
        val cached = pageCache?.get(pageIndex)
        if (cached != null) {
            bitmap = cached
            if (cached.height > 0) {
                aspectRatio = cached.width.toFloat() / cached.height.toFloat()
            }
            return@LaunchedEffect
        }
        if (renderer != null) {
            withContext(Dispatchers.IO) {
                try {
                    val displayMetrics = context.resources.displayMetrics
                    val screenWidthPx = displayMetrics.widthPixels
                    var calculatedRatio = 0.707f

                    val bmp = synchronized(renderLock ?: renderer) {
                        val page = renderer.openPage(pageIndex)
                        try {
                            calculatedRatio = page.width.toFloat() / page.height.toFloat()
                            // Adaptive target resolution scaled to device screen
                            // High-DPI crisp rendering: 2x base multiplier for razor sharp text when zooming
                            val scaleMultiplier = ((screenWidthPx.toFloat() / page.width.toFloat()) * 2.0f).coerceIn(1.5f, 3.2f)
                            val targetWidth = (page.width * scaleMultiplier).toInt().coerceIn(720, 2880)
                            val targetHeight = (page.height * scaleMultiplier).toInt().coerceIn(960, 4096)

                            val b = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                            b.eraseColor(android.graphics.Color.WHITE)
                            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            pageCache?.put(pageIndex, b)
                            b
                        } finally {
                            page.close()
                        }
                    }
                    bitmap = bmp
                    aspectRatio = calculatedRatio
                } catch (e: Exception) {
                    renderError = true
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        shape = RoundedCornerShape(12.dp),
        border = if (isHighlighted) BorderStroke(3.dp, Color(0xFFFFB300)) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = if (isHighlighted) 8.dp else 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (readingMode) {
                ReaderReadingMode.NIGHT -> Color.DarkGray
                ReaderReadingMode.SEPIA -> Color(0xFFF7F1E3)
                ReaderReadingMode.EYE_COMFORT -> Color(0xFFFBF8EE)
                ReaderReadingMode.NORMAL -> if (isNightMode) Color.DarkGray else Color.White
            }
        ),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (bitmap != null) {
                val colorFilter = when (readingMode) {
                    ReaderReadingMode.NIGHT -> ColorFilter.colorMatrix(nightModeMatrix)
                    ReaderReadingMode.SEPIA -> ColorFilter.colorMatrix(sepiaMatrix)
                    ReaderReadingMode.EYE_COMFORT -> ColorFilter.colorMatrix(eyeComfortMatrix)
                    ReaderReadingMode.NORMAL -> if (isNightMode) ColorFilter.colorMatrix(nightModeMatrix) else null
                }

                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                    colorFilter = colorFilter,
                )

                if (isHighlighted) {
                    Surface(
                        modifier = Modifier
                            .padding(10.dp)
                            .align(Alignment.TopCenter),
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFFFB300),
                        shadowElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Search Match on Page ${pageIndex + 1}",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.Black
                            )
                        }
                    }
                }
            } else if (renderError) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                        .background(if (isNightMode) Color.DarkGray else Color(0xFFEEEEEE)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Unable to render page ${pageIndex + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                        .background(if (isNightMode) Color.Black else Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }

            // Search Match Badge
            if (isHighlighted) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(topStart = 12.dp, bottomEnd = 12.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Match on Page ${pageIndex + 1}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // E-Signature Stamp Overlay
            if (signatureStamp != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 32.dp, end = 24.dp),
                    color = Color.Transparent,
                ) {
                    Text(
                        text = signatureStamp,
                        fontSize = 32.sp,
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.Cursive,
                        color = Color(0xFF003366),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF003366), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private fun parseCustomPages(input: String, totalPages: Int): List<Int> {
    if (input.isBlank()) return emptyList()
    val pages = mutableSetOf<Int>()
    val tokens = input.split(",", ";", " ", "\n")
    for (token in tokens) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) continue
        if (trimmed.contains("-")) {
            val parts = trimmed.split("-")
            if (parts.size == 2) {
                val start = parts[0].trim().toIntOrNull()
                val end = parts[1].trim().toIntOrNull()
                if (start != null && end != null) {
                    val min = minOf(start, end).coerceAtLeast(1)
                    val max = maxOf(start, end).coerceAtMost(totalPages)
                    for (p in min..max) {
                        pages.add(p - 1)
                    }
                }
            }
        } else {
            val page = trimmed.toIntOrNull()
            if (page != null && page in 1..totalPages) {
                pages.add(page - 1)
            }
        }
    }
    return pages.sorted()
}

@Composable
fun JumpToPageDialog(
    currentPage: Int,
    totalPages: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var pageInput by remember { mutableStateOf(currentPage.toString()) }
    var sliderValue by remember { mutableFloatStateOf(currentPage.toFloat()) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Jump to Page",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Enter a page number between 1 and $totalPages:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = pageInput,
                    onValueChange = { input ->
                        val clean = input.filter { it.isDigit() }
                        pageInput = clean
                        val num = clean.toIntOrNull()
                        if (num != null && num in 1..totalPages) {
                            sliderValue = num.toFloat()
                            isError = false
                        } else if (clean.isNotEmpty()) {
                            isError = true
                        }
                    },
                    label = { Text("Page Number") },
                    singleLine = true,
                    isError = isError,
                    supportingText = if (isError) {
                        { Text("Please enter 1 to $totalPages", color = MaterialTheme.colorScheme.error) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (totalPages > 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Or scrub with slider: Page ${sliderValue.roundToInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = sliderValue,
                            onValueChange = {
                                sliderValue = it
                                pageInput = it.roundToInt().toString()
                                isError = false
                            },
                            valueRange = 1f..totalPages.toFloat(),
                            steps = if (totalPages > 2) totalPages - 2 else 0,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val target = pageInput.toIntOrNull() ?: sliderValue.roundToInt()
                    if (target in 1..totalPages) {
                        onJump(target)
                        onDismiss()
                    }
                },
                enabled = !isError && (pageInput.toIntOrNull() in 1..totalPages || sliderValue.roundToInt() in 1..totalPages),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Go to Page")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

fun printPdf(context: Context, file: File) {
    try {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager
        if (printManager == null) {
            Toast.makeText(context, "Print service is not available on this device", Toast.LENGTH_SHORT).show()
            return
        }
        val printAdapter = object : android.print.PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: android.print.PrintAttributes?,
                newAttributes: android.print.PrintAttributes?,
                cancellationSignal: android.os.CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: android.os.Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return
                }
                val pdi = android.print.PrintDocumentInfo.Builder(file.name)
                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build()
                callback?.onLayoutFinished(pdi, true)
            }

            override fun onWrite(
                pages: Array<out android.print.PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: android.os.CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                try {
                    FileInputStream(file).use { input ->
                        FileOutputStream(destination?.fileDescriptor).use { output ->
                            input.copyTo(output)
                        }
                    }
                    callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                }
            }
        }
        printManager.print(
            file.nameWithoutExtension,
            printAdapter,
            android.print.PrintAttributes.Builder().build()
        )
    } catch (e: Exception) {
        Toast.makeText(context, "Error printing: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

private fun saveCurrentFileWithAnnotations(
    context: Context,
    file: File,
    appliedSignature: String?,
    pageDrawings: Map<Int, List<Pair<List<Offset>, Color>>>,
    pageHighlighters: Map<Int, List<Pair<List<Offset>, Color>>>,
    onFileSaved: (File) -> Unit,
) {
    try {
        if (!file.exists()) {
            Toast.makeText(context, "Cannot save: file not found", Toast.LENGTH_SHORT).show()
            return
        }
        val isCached = file.absolutePath.startsWith(context.cacheDir.absolutePath)
        val targetDir = if (isCached) {
            com.apusapp.utils.StorageLocationManager.getTargetDirectory(context)
        } else {
            file.parentFile ?: com.apusapp.utils.StorageLocationManager.getTargetDirectory(context)
        }
        val destFile = if (isCached) File(targetDir, file.name) else file
        val tempOutput = File(context.cacheDir, "temp_annot_${System.currentTimeMillis()}.pdf")

        com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { doc ->
            // 1. Signature stamp if present
            if (!appliedSignature.isNullOrBlank()) {
                val lastPage = doc.getPage(doc.numberOfPages - 1)
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(
                    doc,
                    lastPage,
                    com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND,
                    true,
                    true
                ).use { cs ->
                    cs.beginText()
                    cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA_BOLD_OBLIQUE, 18f)
                    cs.setNonStrokingColor(0f, 51f / 255f, 102f / 255f)
                    cs.newLineAtOffset((lastPage.mediaBox.width - 220f).coerceAtLeast(20f), 50f)
                    cs.showText("Signed: $appliedSignature")
                    cs.endText()
                }
            }

            // 2. Highlighters & Drawings stamp
            val modifiedPages = (pageDrawings.keys + pageHighlighters.keys).distinct()
            for (pIndex in modifiedPages) {
                if (pIndex in 0 until doc.numberOfPages) {
                    val page = doc.getPage(pIndex)
                    val pw = page.mediaBox.width
                    val ph = page.mediaBox.height

                    val bmpW = (pw * 1.5f).toInt().coerceAtLeast(400)
                    val bmpH = (ph * 1.5f).toInt().coerceAtLeast(400)
                    val annotBitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(annotBitmap)
                    val scaleX = bmpW.toFloat() / pw
                    val scaleY = bmpH.toFloat() / ph

                    // Calculate exact on-screen rendered dimensions for pixel-perfect coordinate mapping
                    val displayMetrics = context.resources.displayMetrics
                    val screenW = displayMetrics.widthPixels.toFloat()
                    val density = displayMetrics.density
                    val renderedPageW = (screenW - 32f * density).coerceAtLeast(300f)
                    val renderedPageH = renderedPageW * (ph / pw)
                    val offsetX = ((screenW - renderedPageW) / 2f).coerceAtLeast(0f)
                    val offsetY = 16f * density

                    fun mapPdfX(touchX: Float): Float {
                        val rel = (touchX - offsetX) / renderedPageW
                        return (rel.coerceIn(0f, 1f)) * bmpW
                    }
                    fun mapPdfY(touchY: Float): Float {
                        val rel = (touchY - offsetY) / renderedPageH
                        return (rel.coerceIn(0f, 1f)) * bmpH
                    }

                    // Draw Highlighters
                    pageHighlighters[pIndex]?.forEach { (points, color) ->
                        if (points.size > 1) {
                            val hlPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                this.color = android.graphics.Color.argb(
                                    85,
                                    (color.red * 255).toInt(),
                                    (color.green * 255).toInt(),
                                    (color.blue * 255).toInt()
                                )
                                style = android.graphics.Paint.Style.STROKE
                                strokeWidth = 24f * scaleX
                                strokeCap = android.graphics.Paint.Cap.ROUND
                                strokeJoin = android.graphics.Paint.Join.ROUND
                            }
                            val path = android.graphics.Path()
                            path.moveTo(mapPdfX(points[0].x), mapPdfY(points[0].y))
                            for (i in 1 until points.size) {
                                path.lineTo(mapPdfX(points[i].x), mapPdfY(points[i].y))
                            }
                            canvas.drawPath(path, hlPaint)
                        }
                    }

                    // Draw Pen Strokes
                    pageDrawings[pIndex]?.forEach { (points, color) ->
                        if (points.size > 1) {
                            val penPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                this.color = android.graphics.Color.argb(
                                    255,
                                    (color.red * 255).toInt(),
                                    (color.green * 255).toInt(),
                                    (color.blue * 255).toInt()
                                )
                                style = android.graphics.Paint.Style.STROKE
                                strokeWidth = 5f * scaleX
                                strokeCap = android.graphics.Paint.Cap.ROUND
                                strokeJoin = android.graphics.Paint.Join.ROUND
                            }
                            val path = android.graphics.Path()
                            path.moveTo(mapPdfX(points[0].x), mapPdfY(points[0].y))
                            for (i in 1 until points.size) {
                                path.lineTo(mapPdfX(points[i].x), mapPdfY(points[i].y))
                            }
                            canvas.drawPath(path, penPaint)
                        }
                    }

                    val pdImage = com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, annotBitmap)
                    com.tom_roush.pdfbox.pdmodel.PDPageContentStream(
                        doc,
                        page,
                        com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND,
                        true,
                        true
                    ).use { cs ->
                        cs.drawImage(pdImage, 0f, 0f, pw, ph)
                    }
                    annotBitmap.recycle()
                }
            }

            doc.save(tempOutput)
        }

        // Copy tempOutput to destFile
        tempOutput.inputStream().use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        tempOutput.delete()
        onFileSaved(destFile)
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to save annotations: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}
