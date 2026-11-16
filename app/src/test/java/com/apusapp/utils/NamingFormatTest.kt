package com.apusapp.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class NamingFormatTest {

    @Test
    fun testNamingFormatFromId() {
        assertEquals(NamingFormat.TIMESTAMP_COMPACT, NamingFormat.fromId("timestamp_compact"))
        assertEquals(NamingFormat.TIMESTAMP_DETAILED, NamingFormat.fromId("timestamp_detailed"))
        assertEquals(NamingFormat.DATE_ONLY, NamingFormat.fromId("date_only"))
        // Fallback for unknown ID
        assertEquals(NamingFormat.TIMESTAMP_COMPACT, NamingFormat.fromId("unknown_format_xyz"))
    }
}
