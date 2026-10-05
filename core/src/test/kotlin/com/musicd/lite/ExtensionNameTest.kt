package com.musicd.lite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The page's first-run banner tells the user which extension to enable in
 * Roon by name. If that name and the one this server registers drift apart,
 * the one instruction a new install depends on points at nothing.
 */
class ExtensionNameTest {

    @Test
    fun theBannerNamesTheExtensionRoonLists() {
        // Gradle runs these from the module directory.
        val page = File("../app/src/main/assets/web/app.js")
        val text = page.readText()
        val named = Regex("click Enable on “([^”]+)”").findAll(text).map { it.groupValues[1] }.toList()
        assertNotNull(named.firstOrNull())
        for (n in named) assertEquals(EXTENSION_NAME, n)
    }
}
