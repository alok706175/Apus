package com.apusapp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VaultManagerTest {

    @Test
    fun testIsVaultPathDetection() {
        // Vault paths or preview filenames should be identified properly
        assertTrue(VaultManager.isVaultPath(null, "/data/user/0/com.apusapp/files/secure_vault/doc.pdf.enc"))
        assertTrue(VaultManager.isVaultPath(null, "vault_preview_1700000000.pdf"))
        assertTrue(VaultManager.isVaultPath(null, "my_document.pdf.enc"))
        
        // Regular files should not be flagged as vault files
        assertFalse(VaultManager.isVaultPath(null, "/storage/emulated/0/Documents/sample.pdf"))
        assertFalse(VaultManager.isVaultPath(null, ""))
        assertFalse(VaultManager.isVaultPath(null, null))
    }

    @Test
    fun testGetOriginalFileName() {
        val encFile = File("Invoice_2026.pdf.enc")
        val cleanName = VaultManager.getOriginalFileName(encFile)
        assertEquals("Invoice_2026.pdf", cleanName)

        val regularFile = File("Invoice_2026.pdf")
        assertEquals("Invoice_2026.pdf", VaultManager.getOriginalFileName(regularFile))
    }
}
