package io.github.ra5on.titanos.update

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleasesTest {
    @Test
    fun tagsMapToTwoPartVersions() {
        assertEquals("0.02" to 2, ReleaseSource.versionOf("v0.2.0"))
        assertEquals("1.00" to 100, ReleaseSource.versionOf("v1.0.0"))
        assertEquals("1.12" to 112, ReleaseSource.versionOf("v1.12.0"))
        assertNull(ReleaseSource.versionOf("v0.1.0-probe"))
        assertNull(ReleaseSource.versionOf("v0.1.1"))
        assertNull(ReleaseSource.versionOf("v1.100.0"))
    }

    private val body = """
        [
          {"tag_name":"v0.3.0","prerelease":true,"body":"## Neu\n- **Widgets** und `Agent`\n- [Link](https://x)","assets":[
            {"name":"TitanOS-Android-0.03.apk","browser_download_url":"https://h/a3.apk","size":30},
            {"name":"TitanOS-Agent-0.03.apk","browser_download_url":"https://h/g3.apk","size":20},
            {"name":"SHA256SUMS","browser_download_url":"https://h/s3"}]},
          {"tag_name":"v0.2.0","prerelease":true,"assets":[
            {"name":"TitanOS-Android-0.02.apk","browser_download_url":"https://h/a2.apk"},
            {"name":"SHA256SUMS","browser_download_url":"https://h/s2"}]},
          {"tag_name":"v0.4.0","draft":true,"assets":[]},
          {"tag_name":"v0.1.0-probe","assets":[]}
        ]
    """.trimIndent()

    @Test
    fun picksTheNewestAboveTheInstalledOne() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body(body).build())
            server.start()
            val source = ReleaseSource(OkHttpClient(), server.url("/repos/x/"))
            val all = source.releases()
            assertEquals(listOf("v0.3.0", "v0.2.0"), all.map { it.tag })
            val pick = ReleaseSource.pick(all, installedCode = 2, includeTest = true, kind = AppKind.TitanOS)!!
            assertEquals("0.03", pick.versionName)
            assertEquals("https://h/a3.apk", pick.apks[AppKind.TitanOS])
            assertEquals("https://h/s3", pick.sums)
            assertEquals("• Widgets und Agent\n• Link", pick.notes.substringAfter("Neu\n"))
            // Ohne Testfassungen nichts; Agent nur, wo es eine Datei gibt.
            assertNull(ReleaseSource.pick(all, 2, includeTest = false, kind = AppKind.TitanOS))
            assertNull(ReleaseSource.pick(all, 3, includeTest = true, kind = AppKind.TitanOS))
            assertEquals("v0.3.0", ReleaseSource.pick(all, 0, true, AppKind.Agent)!!.tag)
            assertEquals("/repos/x/releases", server.takeRequest().url.encodedPath)
        }
    }

    /** App 1.00 (Tag v1.0.0) ist kein Vorab-Release mehr: 0.03 findet sie auch ohne „Testfassungen“, die Uhr ihre eigene Datei. */
    @Test
    fun firstFinalReleaseIsFoundWithoutTestVersions() = runTest {
        val final = """
            [
              {"tag_name":"v1.0.0","prerelease":false,"body":"Neu","assets":[
                {"name":"TitanOS-Android-1.00.apk","browser_download_url":"https://h/a100.apk","size":90},
                {"name":"TitanOS-Agent-1.00.apk","browser_download_url":"https://h/g100.apk","size":40},
                {"name":"TitanOS-Watch-1.00.apk","browser_download_url":"https://h/w100.apk","size":20},
                {"name":"SHA256SUMS","browser_download_url":"https://h/s100"}]},
              {"tag_name":"v0.3.0","prerelease":true,"assets":[
                {"name":"TitanOS-Android-0.03.apk","browser_download_url":"https://h/a3.apk"},
                {"name":"SHA256SUMS","browser_download_url":"https://h/s3"}]}
            ]
        """.trimIndent()
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body(final).build())
            server.start()
            val all = ReleaseSource(OkHttpClient(), server.url("/repos/x/")).releases()
            val pick = ReleaseSource.pick(all, installedCode = 3, includeTest = false, kind = AppKind.TitanOS)!!
            assertEquals("1.00", pick.versionName)
            assertEquals(100, pick.versionCode)
            assertEquals("https://h/a100.apk", pick.apks[AppKind.TitanOS])
            // Die Agent-App 1.00 trug vorher den Code 4; 100 liegt darüber.
            assertEquals("v1.0.0", ReleaseSource.pick(all, 4, false, AppKind.Agent)!!.tag)
            assertEquals("https://h/w100.apk", ReleaseSource.pick(all, 0, false, AppKind.Watch)!!.apks[AppKind.Watch])
            // Installiert ist 1.00: nichts Neueres.
            assertNull(ReleaseSource.pick(all, 100, true, AppKind.TitanOS))
        }
    }
}
