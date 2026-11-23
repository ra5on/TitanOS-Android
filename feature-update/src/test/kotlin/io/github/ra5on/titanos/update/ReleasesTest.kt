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
        // 1.01 ist Tag v1.1.0: angezeigt 1.01, versionCode 101 wie in build.gradle.kts.
        assertEquals("1.01" to 101, ReleaseSource.versionOf("v1.1.0"))
        assertEquals("1.10" to 110, ReleaseSource.versionOf("v1.10.0"))
    }

    /**
     * 1.00 → 1.01: so, wie release.yml das Release v1.1.0 baut (APK je App als
     * `<Präfix><versionName>.apk`, SHA256SUMS von `sha256sum`, keine Agent-App mehr).
     * Die 1.00 auf den Handys hat dieselbe Auswahl (pick/parse unverändert).
     */
    @Test
    fun update100To101FindsTheNewApkAndItsSum() = runTest {
        val sumApp = "1".repeat(64)
        val sumWatch = "2".repeat(64)
        val releases = """
            [
              {"tag_name":"v1.1.0","prerelease":false,"body":"**TitanOS für Android 1.01**","assets":[
                {"name":"TitanOS-Android-1.01.apk","browser_download_url":"https://h/a101.apk","size":91},
                {"name":"TitanOS-Watch-1.01.apk","browser_download_url":"https://h/w101.apk","size":21},
                {"name":"SHA256SUMS","browser_download_url":"https://h/s101"}]},
              {"tag_name":"v1.0.0","prerelease":false,"assets":[
                {"name":"TitanOS-Android-1.00.apk","browser_download_url":"https://h/a100.apk","size":90},
                {"name":"TitanOS-Agent-1.00.apk","browser_download_url":"https://h/g100.apk","size":40},
                {"name":"TitanOS-Watch-1.00.apk","browser_download_url":"https://h/w100.apk","size":20},
                {"name":"SHA256SUMS","browser_download_url":"https://h/s100"}]}
            ]
        """.trimIndent()
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body(releases).build())
            server.start()
            val all = ReleaseSource(OkHttpClient(), server.url("/repos/x/")).releases()
            val pick = ReleaseSource.pick(all, installedCode = 100, includeTest = false, kind = AppKind.TitanOS)!!
            assertEquals("v1.1.0", pick.tag)
            assertEquals("1.01", pick.versionName)
            assertEquals(101, pick.versionCode)
            assertEquals("https://h/a101.apk", pick.apks[AppKind.TitanOS])
            assertEquals(91L, pick.sizes[AppKind.TitanOS])
            assertEquals("https://h/s101", pick.sums)
            assertEquals("https://h/w101.apk", ReleaseSource.pick(all, 100, false, AppKind.Watch)!!.apks[AppKind.Watch])
            // Die Datei, die Installer lädt und in SHA256SUMS sucht, heißt wie im Release.
            assertEquals("TitanOS-Android-1.01.apk", "${AppKind.TitanOS.assetPrefix}${pick.versionName}.apk")
            // Schon 1.01 installiert: nichts. Eine Testfassung 0.03 (Code 3) sieht 1.01 auch ohne „Testfassungen“.
            assertNull(ReleaseSource.pick(all, 101, true, AppKind.TitanOS))
            assertEquals("v1.1.0", ReleaseSource.pick(all, 3, false, AppKind.TitanOS)!!.tag)
        }
        // SHA256SUMS genau so, wie `cd out && sha256sum *.apk > SHA256SUMS` sie schreibt.
        val sums = "$sumApp  TitanOS-Android-1.01.apk\n$sumWatch  TitanOS-Watch-1.01.apk\n"
        assertEquals(sumApp, ReleaseSource.sumIn(sums, "TitanOS-Android-1.01.apk"))
        assertEquals(sumWatch, ReleaseSource.sumIn(sums, "TitanOS-Watch-1.01.apk"))
        assertEquals(sumApp, ReleaseSource.sumIn("$sumApp *TitanOS-Android-1.01.apk", "TitanOS-Android-1.01.apk"))
        assertNull(ReleaseSource.sumIn(sums, "TitanOS-Agent-1.01.apk"))
        assertNull(ReleaseSource.sumIn(sums, "TitanOS-Android-1.00.apk"))
    }

    /** Die Agent-App ist ab 1.01 kein Ziel des Updates mehr; ihre Datei in alten Releases wird nicht beachtet. */
    @Test
    fun theAgentAppIsNoLongerUpdated() {
        assertEquals(listOf(AppKind.TitanOS, AppKind.Watch), AppKind.entries.toList())
        assertEquals(AppKind.TitanOS, AppKind.own(AppKind.OLD_AGENT_PACKAGE))
        assertEquals(AppKind.Watch, AppKind.own("io.github.ra5on.titanos.watch"))
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
            assertEquals("https://h/w100.apk", ReleaseSource.pick(all, 0, false, AppKind.Watch)!!.apks[AppKind.Watch])
            // Installiert ist 1.00: nichts Neueres.
            assertNull(ReleaseSource.pick(all, 100, true, AppKind.TitanOS))
        }
    }
}
