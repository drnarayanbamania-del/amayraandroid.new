package com.amayra.maya.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPackageAliasesTest {
    @Test
    fun `whatsapp resolves personal and business packages`() {
        assertEquals(setOf("com.whatsapp", "com.whatsapp.w4b"), AppPackageAliases.packagesFor("WhatsApp"))
        assertTrue(AppPackageAliases.packagesFor("whatsapp").contains("com.whatsapp"))
    }

    @Test
    fun `unknown or empty app has no package fallback`() {
        assertTrue(AppPackageAliases.packagesFor("Signal").isEmpty())
        assertTrue(AppPackageAliases.packagesFor("").isEmpty())
    }

    @Test
    fun `known aliases are case insensitive`() {
        assertEquals(setOf("com.google.android.youtube"), AppPackageAliases.packagesFor("YOUTUBE"))
    }
}
