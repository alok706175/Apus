package com.apusapp.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apusapp.utils.AppLockManager
import com.apusapp.utils.HapticManager
import com.apusapp.utils.VaultManager
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    onBack: () -> Unit,
    onOpenPdf: (File) -> Unit,
    onSetupLock: (() -> Unit)? = null
) {
    val context = LocalContext.current
    BackHandler {
        VaultManager.clearVaultCache(context)
        onBack()
    }

    var vaultFiles by remember { mutableStateOf(VaultManager.getVaultFiles(context)) }
    val isLockConfigured = remember { AppLockManager.isLockConfigured(context) }
    var fileToDelete by remember { mutableStateOf<File?>(null) }
    var fileToRestore by remember { mutableStateOf<File?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf<Set<File>>(emptySet()) }
    var showBatchRestoreDialog by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedFiles = emptySet()
    }

    fun refresh() {
        vaultFiles = VaultManager.getVaultFiles(context)
    }

    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val input = context.contentResolver.openInputStream(it)
                if (input != null) {
                    val temp = File(context.cacheDir, "import_${System.currentTimeMillis()}.pdf")
                    temp.outputStream().use { out -> input.copyTo(out) }
                    VaultManager.moveToVault(context, temp)
                    refresh()
                    HapticManager.success()
                    Toast.makeText(context, "Added file to Private Vault", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to import file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text(
                            text = "${selectedFiles.size} of ${vaultFiles.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.Shield,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Private Vault",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Hidden & Protected PDFs",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = {
                            isSelectionMode = false
                            selectedFiles = emptySet()
                        }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Exit Selection")
                        }
                    } else {
                        IconButton(onClick = {
                            VaultManager.clearVaultCache(context)
                            onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = {
                            selectedFiles = if (selectedFiles.size == vaultFiles.size) emptySet() else vaultFiles.toSet()
                        }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = "Select All")
                        }
                        if (selectedFiles.isNotEmpty()) {
                            IconButton(onClick = { showBatchRestoreDialog = true }) {
                                Icon(Icons.Outlined.Restore, contentDescription = "Restore Selected", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { showBatchDeleteDialog = true }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete Selected", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        if (vaultFiles.isNotEmpty()) {
                            IconButton(onClick = { isSelectionMode = true }) {
                                Icon(Icons.Outlined.Checklist, contentDescription = "Select Multiple")
                            }
                        }
                        IconButton(onClick = { importPicker.launch(arrayOf("application/pdf")) }) {
                            Icon(Icons.Outlined.Add, contentDescription = "Add PDF to Vault", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = {
                            VaultManager.clearVaultCache(context)
                            onBack()
                        }) {
                            Icon(Icons.Outlined.Lock, contentDescription = "Lock & Exit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (vaultFiles.isEmpty()) {
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
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Your Vault is Empty",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Files saved here are completely hidden from device gallery, WhatsApp, and file managers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = { importPicker.launch(arrayOf("application/pdf")) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add PDF to Vault")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!isLockConfigured) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Vault is Unprotected",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            text = "Set an app passcode or biometric lock to keep these private files safe.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (onSetupLock != null) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        FilledTonalButton(
                                            onClick = onSetupLock,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text("Set Lock", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        // Data Safety Warning Alert Banner
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Uninstall Warning & Data Safety",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        text = "Files are securely stored in private internal storage. Please restore your files before uninstalling Apus to prevent permanent loss.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "${vaultFiles.size} protected file${if (vaultFiles.size > 1) "s" else ""} in secure vault",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    items(vaultFiles, key = { it.absolutePath }) { file ->
                        val isSelected = selectedFiles.contains(file)
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface
                            ),
                            border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isSelectionMode) {
                                            selectedFiles = if (isSelected) selectedFiles - file else selectedFiles + file
                                        } else {
                                            val decryptedTemp = VaultManager.decryptVaultFileToTemp(context, file)
                                            if (decryptedTemp != null) {
                                                onOpenPdf(decryptedTemp)
                                            } else {
                                                Toast.makeText(context, "Could not decrypt vault file", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSelectionMode) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = {
                                            selectedFiles = if (isSelected) selectedFiles - file else selectedFiles + file
                                        },
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Outlined.PictureAsPdf,
                                            contentDescription = null,
                                            tint = Color(0xFFE53935),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = VaultManager.getOriginalFileName(context, file),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                            modifier = Modifier.padding(end = 6.dp)
                                        ) {
                                            Text(
                                                text = "AES-256",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                        Text(
                                            text = "${formatVaultFileSize(file.length())} • ${formatVaultDate(file.lastModified())}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = { fileToRestore = file },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Restore,
                                            contentDescription = "Restore to Original Folder",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { fileToDelete = file },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (fileToRestore != null) {
        AlertDialog(
            onDismissRequest = { fileToRestore = null },
            icon = {
                Icon(
                    Icons.Outlined.Restore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("Restore File?", fontWeight = FontWeight.Bold) },
            text = { Text("This will decrypt and restore \"${fileToRestore?.let { VaultManager.getOriginalFileName(it) }}\" back into your storage.") },
            confirmButton = {
                Button(
                    onClick = {
                        val restored = fileToRestore?.let { VaultManager.restoreFromVault(context, it) }
                        if (restored != null) {
                            Toast.makeText(context, "File restored successfully", Toast.LENGTH_SHORT).show()
                            refresh()
                        } else {
                            Toast.makeText(context, "Restore failed", Toast.LENGTH_SHORT).show()
                        }
                        fileToRestore = null
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToRestore = null }) { Text("Cancel") }
            }
        )
    }

    if (showBatchRestoreDialog && selectedFiles.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBatchRestoreDialog = false },
            icon = {
                Icon(Icons.Outlined.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            },
            title = { Text("Restore ${selectedFiles.size} Files?", fontWeight = FontWeight.Bold) },
            text = { Text("All selected files will be decrypted and restored directly back to their original folders.") },
            confirmButton = {
                Button(
                    onClick = {
                        var successCount = 0
                        selectedFiles.forEach { vf ->
                            if (VaultManager.restoreFromVault(context, vf) != null) {
                                successCount++
                            }
                        }
                        Toast.makeText(context, "Restored $successCount files to their original folders", Toast.LENGTH_SHORT).show()
                        selectedFiles = emptySet()
                        isSelectionMode = false
                        showBatchRestoreDialog = false
                        refresh()
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Restore All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchRestoreDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showBatchDeleteDialog && selectedFiles.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            icon = {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
            },
            title = { Text("Delete ${selectedFiles.size} Files?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to permanently delete the selected ${selectedFiles.size} files from Vault? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        selectedFiles.forEach { vf ->
                            VaultManager.deleteFromVault(context, vf)
                        }
                        Toast.makeText(context, "Deleted ${selectedFiles.size} files", Toast.LENGTH_SHORT).show()
                        selectedFiles = emptySet()
                        isSelectionMode = false
                        showBatchDeleteDialog = false
                        refresh()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete Permanently?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to permanently delete \"${fileToDelete?.let { VaultManager.getOriginalFileName(it) }}\"? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        fileToDelete?.let { VaultManager.deleteFromVault(it) }
                        refresh()
                        HapticManager.light()
                        Toast.makeText(context, "File deleted", Toast.LENGTH_SHORT).show()
                        fileToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

private fun formatVaultFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val df = DecimalFormat("#.#")
    return when {
        bytes >= 1024 * 1024 -> "${df.format(bytes / (1024.0 * 1024.0))} MB"
        bytes >= 1024 -> "${df.format(bytes / 1024.0)} KB"
        else -> "$bytes B"
    }
}

private fun formatVaultDate(timestamp: Long): String {
    return SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(timestamp))
}
