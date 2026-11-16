package com.apusapp.utils

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object VaultManager {

    private const val VAULT_DIR_NAME = "secure_vault"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val VAULT_KEY_ALIAS = "ApusVaultMasterKey_AES256"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val METADATA_PREFS = "apus_vault_metadata"

    private fun getMetadataPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(METADATA_PREFS, Context.MODE_PRIVATE)
    }

    fun getVaultDir(context: Context): File {
        val dir = File(context.filesDir, VAULT_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val noMedia = File(dir, ".nomedia")
        if (!noMedia.exists()) {
            try {
                noMedia.createNewFile()
            } catch (_: Exception) {}
        }
        return dir
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        if (keyStore.containsAlias(VAULT_KEY_ALIAS)) {
            val entry = keyStore.getEntry(VAULT_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val keyGenSpec = KeyGenParameterSpec.Builder(
            VAULT_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }

    fun getVaultFiles(context: Context): List<File> {
        val dir = getVaultDir(context)
        return dir.listFiles { file ->
            file.isFile && (file.extension.equals("enc", ignoreCase = true) || file.extension.equals("pdf", ignoreCase = true))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun getOriginalFileName(context: Context, vaultFile: File): String {
        val stored = getMetadataPrefs(context).getString("orig_name_" + vaultFile.name, null)
        if (!stored.isNullOrBlank()) return stored

        return if (vaultFile.name.endsWith(".enc", ignoreCase = true)) {
            vaultFile.name.removeSuffix(".enc")
        } else {
            vaultFile.name
        }
    }

    fun getOriginalFileName(vaultFile: File): String {
        return if (vaultFile.name.endsWith(".enc", ignoreCase = true)) {
            vaultFile.name.removeSuffix(".enc")
        } else {
            vaultFile.name
        }
    }

    fun getOriginalFolderPath(context: Context, vaultFile: File): String? {
        return getMetadataPrefs(context).getString("orig_folder_" + vaultFile.name, null)
    }

    /**
     * Checks whether a file is stored in Vault or is a temporary decrypted vault preview.
     * Vault files must never be recorded in Recent Files, reading progress, or gallery.
     */
    fun isVaultFile(context: Context, file: File?): Boolean {
        if (file == null) return false
        val name = file.name
        if (name.startsWith("vault_preview_", ignoreCase = true)) return true
        if (name.endsWith(".enc", ignoreCase = true)) return true
        val path = file.absolutePath
        val vaultDir = getVaultDir(context).absolutePath
        if (path.startsWith(vaultDir)) return true
        if (file.parentFile?.name.equals(VAULT_DIR_NAME, ignoreCase = true)) return true
        return false
    }

    /**
     * Checks whether a path string points to a vault file or temporary vault preview.
     */
    fun isVaultPath(context: Context, path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (path.contains("vault_preview_", ignoreCase = true)) return true
        if (path.endsWith(".enc", ignoreCase = true)) return true
        val vaultDir = getVaultDir(context).absolutePath
        if (path.startsWith(vaultDir)) return true
        return false
    }

    /**
     * Encrypts and moves source file into secure vault, saving its original location so it can be restored to the same folder.
     * Enforces strict AES-GCM encryption with NO unencrypted fallback to guarantee privacy.
     */
    fun moveToVault(context: Context, sourceFile: File, originalFolderOverride: String? = null): Boolean {
        var destFile: File? = null
        return try {
            val vaultDir = getVaultDir(context)
            val baseName = if (sourceFile.name.endsWith(".pdf", ignoreCase = true)) sourceFile.name else "${sourceFile.name}.pdf"
            var target = File(vaultDir, "$baseName.enc")
            if (target.exists()) {
                target = File(vaultDir, "${sourceFile.nameWithoutExtension}_${System.currentTimeMillis()}.pdf.enc")
            }
            destFile = target

            val origFolder = when {
                !originalFolderOverride.isNullOrBlank() -> originalFolderOverride
                sourceFile.parentFile != null && !sourceFile.absolutePath.startsWith(context.cacheDir.absolutePath) -> sourceFile.parentFile?.absolutePath
                else -> StorageLocationManager.getTargetDirectory(context).absolutePath
            }

            val secretKey = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv

            FileOutputStream(target).use { out ->
                // Write IV first
                out.write(iv)
                FileInputStream(sourceFile).use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        val outputChunk = cipher.update(buffer, 0, bytesRead)
                        if (outputChunk != null) out.write(outputChunk)
                    }
                    val finalChunk = cipher.doFinal()
                    if (finalChunk != null) out.write(finalChunk)
                }
            }

            // Record original folder path and file name for exact restore to the same directory
            getMetadataPrefs(context).edit()
                .putString("orig_folder_" + target.name, origFolder)
                .putString("orig_name_" + target.name, sourceFile.name)
                .apply()

            RecentFilesManager.removeFile(context, sourceFile)
            sourceFile.delete()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            // Strictly delete any incomplete encrypted file; never fallback to unencrypted plain copy
            try {
                destFile?.let { if (it.exists()) it.delete() }
            } catch (_: Exception) {}
            false
        }
    }

    /**
     * Deletes all temporary decrypted vault previews from cache directory to prevent disk leakage.
     */
    fun clearVaultCache(context: Context) {
        try {
            val cache = context.cacheDir
            cache.listFiles { f ->
                f.isFile && f.name.startsWith("vault_preview_", ignoreCase = true)
            }?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    /**
     * Decrypts encrypted vault file into a temporary cache PDF for viewing.
     */
    fun decryptVaultFileToTemp(context: Context, vaultFile: File): File? {
        return try {
            if (!vaultFile.name.endsWith(".enc", ignoreCase = true)) {
                return vaultFile
            }

            val secretKey = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)

            val tempFile = File(context.cacheDir, "vault_preview_${System.currentTimeMillis()}.pdf")

            FileInputStream(vaultFile).use { input ->
                val iv = ByteArray(GCM_IV_LENGTH)
                val readIv = input.read(iv)
                if (readIv != GCM_IV_LENGTH) throw IllegalStateException("Invalid IV length in encrypted vault file")

                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

                FileOutputStream(tempFile).use { out ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        val outputChunk = cipher.update(buffer, 0, bytesRead)
                        if (outputChunk != null) out.write(outputChunk)
                    }
                    val finalChunk = cipher.doFinal()
                    if (finalChunk != null) out.write(finalChunk)
                }
            }
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Decrypts and restores vault file directly back into its original folder where it came from.
     */
    fun restoreFromVault(context: Context, vaultFile: File): File? {
        return try {
            val decryptedTemp = decryptVaultFileToTemp(context, vaultFile) ?: return null

            val originalName = getOriginalFileName(context, vaultFile)
            val originalFolderPath = getOriginalFolderPath(context, vaultFile)

            val targetDir = if (!originalFolderPath.isNullOrBlank()) {
                val f = File(originalFolderPath)
                if (!f.exists()) f.mkdirs()
                if (f.exists() && f.canWrite()) f else StorageLocationManager.getTargetDirectory(context)
            } else {
                StorageLocationManager.getTargetDirectory(context)
            }

            var destFile = File(targetDir, originalName)
            var count = 1
            val baseName = originalName.removeSuffix(".pdf")
            while (destFile.exists()) {
                destFile = File(targetDir, "${baseName}_$count.pdf")
                count++
            }

            FileInputStream(decryptedTemp).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf("application/pdf"), null)
            RecentFilesManager.recordFile(context, destFile)

            decryptedTemp.delete()
            vaultFile.delete()

            getMetadataPrefs(context).edit()
                .remove("orig_folder_" + vaultFile.name)
                .remove("orig_name_" + vaultFile.name)
                .apply()

            destFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deleteFromVault(context: Context, vaultFile: File): Boolean {
        return try {
            getMetadataPrefs(context).edit()
                .remove("orig_folder_" + vaultFile.name)
                .remove("orig_name_" + vaultFile.name)
                .apply()
            vaultFile.delete()
        } catch (_: Exception) {
            false
        }
    }

    fun deleteFromVault(vaultFile: File): Boolean {
        return try {
            vaultFile.delete()
        } catch (_: Exception) {
            false
        }
    }
}
