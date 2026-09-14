package com.amayra.scanner

import com.amayra.scanner.ocr.OcrScript
import com.amayra.scanner.ocr.ScannerWordCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerLogicTest {

    @Test
    fun `counts words in normal text`() {
        assertEquals(5, ScannerWordCounter.count("The quick brown fox jumps"))
    }

    @Test
    fun `handles multiple whitespace and newlines`() {
        assertEquals(6, ScannerWordCounter.count("one  two\n\nthree\t four  five six"))
    }

    @Test
    fun `blank text counts as zero words`() {
        assertEquals(0, ScannerWordCounter.count(""))
        assertEquals(0, ScannerWordCounter.count("   \n\t "))
    }

    @Test
    fun `store word counter matches ocr word counter`() {
        assertEquals(
            ScannerWordCounter.count("a b c"),
            com.amayra.scanner.data.ScannerStore.countWords("a b c")
        )
    }

    @Test
    fun `script codes parse both ways`() {
        assertEquals(OcrScript.LATIN, OcrScript.fromCode("latin"))
        assertEquals(OcrScript.DEVANAGARI, OcrScript.fromCode("devanagari"))
        assertNull(OcrScript.fromCode("klingon"))
        assertNull(OcrScript.fromCode(null))
    }

    @Test
    fun `auto mode maps to no explicit script`() {
        // "auto" is a settings-level value, not a recognizer script.
        assertNull(OcrScript.fromCode("auto"))
    }

    @Test
    fun `translation languages match the spec list`() {
        val codes = com.amayra.scanner.data.TRANSLATE_LANGUAGES.map { it.first }
        assertTrue(
            codes.containsAll(listOf("en", "hi", "fr", "de", "es", "it", "zh", "ja"))
        )
        assertEquals(8, codes.size)
        assertEquals(codes.distinct().size, codes.size)
    }

    @Test
    fun `target language codes are valid translation tags`() {
        com.amayra.scanner.data.TRANSLATE_LANGUAGES.forEach { (code, label) ->
            assertTrue("language $code should have a label", label.isNotBlank())
        }
    }
}
