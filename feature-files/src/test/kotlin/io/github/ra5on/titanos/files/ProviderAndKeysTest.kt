package io.github.ra5on.titanos.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

class ProviderAndKeysTest {
    @Test
    fun documentIds() {
        assertEquals("share/Docs" to "Briefe/a.docx", NasDocumentsProvider.split("share/Docs|Briefe/a.docx"))
        assertEquals("share/Docs" to "", NasDocumentsProvider.split("share/Docs|"))
        assertEquals("share/Docs" to "a b|c.txt", NasDocumentsProvider.split("share/Docs|a b|c.txt"))
        assertEquals("share/Docs|x/y", NasDocumentsProvider.docId("share/Docs", NasDocumentsProvider.join("x", "y")))
        for (bad in listOf("ohne-trenner", "|nur-pfad", "share/Docs|a/../b")) {
            try {
                NasDocumentsProvider.split(bad)
                error("accepted $bad")
            } catch (_: FileNotFoundException) {
            }
        }
    }

    @Test
    fun widthOfPanesAndPrefsRoundTrip() {
        val p = FilePrefs().rememberFolder("share/Docs", "A", true)
        assertTrue(p.folders.single().path == "A")
        assertFalse(p.rememberFolder("share/Docs", "A", false).folders.isNotEmpty())
    }

    @Test
    fun otherNamesForOfficeAndDlnaParsing() {
        assertEquals("Bericht (2)", otherName("Bericht"))
        assertEquals("a.b (2).txt", otherName("a.b.txt"))
    }
}
