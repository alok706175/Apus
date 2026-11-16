package com.apusapp.utils

import com.apusapp.data.model.PageRange
import com.apusapp.data.model.SplitMode
import com.apusapp.data.model.SplitPdfConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPdfTest {

    @Test
    fun testPageRangeValidation() {
        val totalPages = 10
        val validRange = PageRange(fromPage = 1, toPage = 5)
        assertTrue(validRange.isValid(totalPages))
        assertEquals(5, validRange.pageCount)

        val invalidRangeOver = PageRange(fromPage = 5, toPage = 12)
        assertFalse(invalidRangeOver.isValid(totalPages))

        val invalidRangeReversed = PageRange(fromPage = 7, toPage = 3)
        assertFalse(invalidRangeReversed.isValid(totalPages))
    }

    @Test
    fun testSplitPdfConfigDefaultsAndFixedChunks() {
        val config = SplitPdfConfig(
            mode = SplitMode.FIXED_CHUNKS,
            chunkPageSize = 3
        )
        assertEquals(SplitMode.FIXED_CHUNKS, config.mode)
        assertEquals(3, config.chunkPageSize)

        // Test chunk count computation for 10 pages with chunk size 3: 4 chunks (1-3, 4-6, 7-9, 10)
        val totalPages = 10
        val chunkSize = config.chunkPageSize
        val expectedChunks = (totalPages + chunkSize - 1) / chunkSize
        assertEquals(4, expectedChunks)
    }

    @Test
    fun testSplitModeEntries() {
        val modes = SplitMode.entries
        assertTrue(modes.contains(SplitMode.CUSTOM_RANGES))
        assertTrue(modes.contains(SplitMode.EXTRACT_PAGES))
        assertTrue(modes.contains(SplitMode.FIXED_CHUNKS))
        assertTrue(modes.contains(SplitMode.BURST_ALL))
    }
}
