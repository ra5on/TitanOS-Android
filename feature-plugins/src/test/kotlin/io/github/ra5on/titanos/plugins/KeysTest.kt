package io.github.ra5on.titanos.plugins

import io.github.ra5on.titanos.admin.hasText
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Jeder feste Web-Schlüssel, den das Modul benutzt, steht in den erzeugten Texten (sonst stünde der Schlüssel selbst da). */
class KeysTest {
    @Test fun allLiteralKeysExist() {
        val key = Regex("""\bt(?:Or)?\("([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)"""")
        val used = File("src/main/kotlin").walk().filter { it.extension == "kt" }
            .flatMap { f -> key.findAll(f.readText()).map { it.groupValues[1] } }.toSortedSet()
        assertTrue("keine Schlüssel gefunden", used.size > 100)
        val missing = used.filterNot(::hasText)
        assertTrue("fehlende Schlüssel: $missing", missing.isEmpty())
    }

    @Test fun templateKeysExist() {
        val keys = buildList {
            for (s in CATEGORIES) add("plugins.category.$s")
            for (s in listOf("signature", "download", "checksum", "write", "stop", "sysext", "modules", "services", "health", "remove", "rollback")) add("plugins.step.$s")
            for (s in listOf("pending", "running", "done", "failed", "skipped")) add("plugins.stepState.$s")
            for (s in listOf("idle", "checking", "downloading", "ready", "current", "unavailable", "error", "environment", "sourceReady", "building")) add("plugins.driverState.$s")
            for (s in listOf("inactive", "Running", "Stopped", "NeedsLogin", "NeedsMachineAuth", "Starting", "NoState", "InUseOtherUser")) add("tailscale.states.$s")
            for (s in listOf("inactive", "Idle", "Connecting", "Connected", "NeedsLogin", "LoginFailed", "SessionExpired")) add("netbird.states.$s")
        }
        val missing = keys.filterNot(::hasText)
        assertTrue("fehlende Schlüssel: $missing", missing.isEmpty())
    }
}
