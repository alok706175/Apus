package com.apusapp.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apusapp.utils.StorageLocationManager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class EditablePageItem(
    val originalPageIndex: Int,
    val rotation: Int = 0,
    val thumbnail: Bitmap? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReorderPagesScreen(
    initialPdfFile: File? = null,
    onNavigateBack: () -> Unit,
    onOpenPdf: (File) -> Unit
) {
    BackHandler { onNavigateBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeFile by remember { mutableStateOf(initialPdfFile) }
    var pageItems by remember { mutableStateOf<List<EditablePageItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var successFile by remember { mutableStateOf<File?>(null) }

    fun loadPages(file: File) {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val list = mutableListOf<EditablePageItem>()
            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            try {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(pfd)
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    // High-efficiency memory footprint (160px width, RGB_565 takes only ~60KB per page instead of ~500KB)
                    val w = 160
                    val h = ((page.height.toFloat() / page.width.toFloat()) * w).toInt().coerceIn(140, 240)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    list.add(EditablePageItem(originalPageIndex = i, rotation = 0, thumbnail = bitmap))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to load PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                try { renderer?.close() } catch (_: Exception) {}
                try { pfd?.close() } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                pageItems = list
                isLoading = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pageItems.forEach { it.thumbnail?.recycle() }
        }
    }

    LaunchedEffect(activeFile) {
        activeFile?.let { 
            pageItems.forEach { item -> item.thumbnail?.recycle() }
            loadPages(it) 
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val stream = context.contentResolver.openInputStream(it) ?: return@launch
                    val temp = File(context.cacheDir, "reorder_${System.currentTimeMillis()}.pdf")
                    FileOutputStream(temp).use { out -> stream.copyTo(out) }
                    withContext(Dispatchers.Main) {
                        activeFile = temp
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun saveReordered() {
        val file = activeFile ?: return
        if (pageItems.isEmpty()) return
        isSaving = true
        scope.launch(Dispatchers.IO) {
            try {
                val srcDoc = PDDocument.load(file)
                val targetDoc = PDDocument()
                try {
                    for (item in pageItems) {
                        val imported = targetDoc.importPage(srcDoc.getPage(item.originalPageIndex))
                        if (item.rotation != 0) {
                            imported.rotation = (imported.rotation + item.rotation) % 360
                        }
                    }
                    val tempOut = File(context.cacheDir, "reordered_${System.currentTimeMillis()}.pdf")
                    FileOutputStream(tempOut).use { targetDoc.save(it) }

                    val finalName = "${file.nameWithoutExtension}_organized.pdf"
                    val saved = StorageLocationManager.savePdfToStorage(context, tempOut, finalName)
                    tempOut.delete()

                    withContext(Dispatchers.Main) {
                        isSaving = false
                        successFile = saved
                    }
                } finally {
                    srcDoc.close()
                    targetDoc.close()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSaving = false
                    Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Organize & Reorder Pages", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (pageItems.isNotEmpty()) "${pageItems.size} pages" else "Select PDF to organize",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (activeFile != null) {
                        IconButton(onClick = { filePicker.launch(arrayOf("application/pdf")) }) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = "Change PDF")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (pageItems.isNotEmpty()) {
                Surface(
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { activeFile?.let { loadPages(it) } },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Reset")
                        }
                        Button(
                            onClick = { saveReordered() },
                            modifier = Modifier.weight(2f),
                            enabled = !isSaving && pageItems.isNotEmpty(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Saving...")
                            } else {
                                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Save Reordered PDF")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (activeFile == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(80.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Layers, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Select PDF to Organize", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Reorder pages, rotate sideways pages, or delete unwanted pages visually.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = { filePicker.launch(arrayOf("application/pdf")) }, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select PDF")
                    }
                }
            } else if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Loading page previews...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(pageItems, key = { _, item -> item.originalPageIndex }) { index, item ->
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                // Header: Page number + Delete
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(2.dp)
                                    ) {
                                        Text(
                                            text = "Page ${index + 1}",
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            if (pageItems.size > 1) {
                                                pageItems = pageItems.filterIndexed { i, _ -> i != index }
                                            } else {
                                                Toast.makeText(context, "Cannot delete all pages.", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Outlined.Delete, contentDescription = "Delete Page", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Page Thumbnail
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(150.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (item.thumbnail != null) {
                                        Image(
                                            bitmap = item.thumbnail.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .rotate(item.rotation.toFloat()),
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Action Buttons: Move Left, Rotate, Move Right
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Move Left / Up
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                val mutable = pageItems.toMutableList()
                                                val prev = mutable[index - 1]
                                                mutable[index - 1] = mutable[index]
                                                mutable[index] = prev
                                                pageItems = mutable
                                            }
                                        },
                                        enabled = index > 0,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Move Left", modifier = Modifier.size(18.dp))
                                    }

                                    // Rotate 90
                                    IconButton(
                                        onClick = {
                                            val mutable = pageItems.toMutableList()
                                            mutable[index] = mutable[index].copy(rotation = (mutable[index].rotation + 90) % 360)
                                            pageItems = mutable
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Outlined.RotateRight, contentDescription = "Rotate 90", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    }

                                    // Move Right / Down
                                    IconButton(
                                        onClick = {
                                            if (index < pageItems.size - 1) {
                                                val mutable = pageItems.toMutableList()
                                                val next = mutable[index + 1]
                                                mutable[index + 1] = mutable[index]
                                                mutable[index] = next
                                                pageItems = mutable
                                            }
                                        },
                                        enabled = index < pageItems.size - 1,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Move Right", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (successFile != null) {
        AlertDialog(
            onDismissRequest = { successFile = null },
            icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) },
            title = { Text("Pages Organized!", fontWeight = FontWeight.Bold) },
            text = { Text("Your reordered PDF has been saved successfully:\n${successFile?.name}") },
            confirmButton = {
                Button(
                    onClick = {
                        val f = successFile!!
                        successFile = null
                        onOpenPdf(f)
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Open PDF")
                }
            },
            dismissButton = {
                TextButton(onClick = { successFile = null }) {
                    Text("Done")
                }
            }
        )
    }
}

