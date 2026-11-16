package com.apusapp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentFilesManagerTest {

    @Test
    fun testLegacyDelimitedParsing() {
        val legacyRaw = "/storage/emulated/0/doc1.pdf::1700000000;;/storage/emulated/0/doc2.pdf::1700001000"
        val entries = RecentFilesManager.parseEntries(legacyRaw)

        assertEquals(2, entries.size)
        assertEquals("/storage/emulated/0/doc1.pdf", entries[0].first)
        assertEquals(1700000000L, entries[0].second)
        assertEquals("/storage/emulated/0/doc2.pdf", entries[1].first)
        assertEquals(1700001000L, entries[1].second)
    }

    @Test
    fun testEmptyRawParsing() {
        val entries = RecentFilesManager.parseEntries("")
        assertTrue(entries.isEmpty())
    }
}
