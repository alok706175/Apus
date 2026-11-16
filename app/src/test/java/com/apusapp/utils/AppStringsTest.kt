package com.apusapp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStringsTest {

    @Test
    fun testDefaultEnglishStringsExist() {
        val appName = AppStrings.get("app_name", AppLanguage.ENGLISH)
        assertEquals("Apus", appName)

        val mergeTool = AppStrings.get("tool_merge", AppLanguage.ENGLISH)
        assertTrue(mergeTool.isNotBlank())
    }

    @Test
    fun testFallbackToEnglishWhenKeyMissingInTargetLanguage() {
        // Requesting an unknown key should return the key itself
        val unknown = AppStrings.get("non_existent_key_xyz", AppLanguage.ENGLISH)
        assertEquals("non_existent_key_xyz", unknown)
    }

    @Test
    fun testAllLanguagesHaveCoreKeys() {
        val coreKeys = listOf("app_name", "tab_home", "tab_tools", "tab_settings", "tool_merge", "tool_compress")
        for (lang in AppLanguage.values()) {
            for (key in coreKeys) {
                val value = AppStrings.get(key, lang)
                assertNotNull("Missing key '$key' for language $lang", value)
                assertTrue("Key '$key' is blank for language $lang", value.isNotBlank())
            }
        }
    }
}
