package io.github.ra5on.titanos.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingUriTest {
    private val own = "io.github.ra5on.titanos"
    private val providers = mapOf(
        "io.github.ra5on.titanos.files" to own,
        "io.github.ra5on.titanos.documents" to own,
        "io.github.ra5on.titanos.agent.share" to "io.github.ra5on.titanos.agent",
        "com.android.providers.downloads.documents" to "com.android.providers.downloads",
        "media" to "com.android.providers.media.module",
    )
    private fun ok(scheme: String?, authority: String?) = IncomingUri.accepts(scheme, authority, own) { providers[it] }

    @Test
    fun onlyForeignContent() {
        assertTrue(ok("content", "com.android.providers.downloads.documents"))
        assertTrue(ok("content", "media"))
        // Unbekannter fremder Anbieter (Paketsichtbarkeit): gilt, solange der Name nicht nach der App aussieht.
        assertTrue(ok("content", "com.example.mail.fileprovider"))
        // Ein Anbieter der Agent-App ist fremd, auch mit ähnlichem Namen.
        assertTrue(ok("content", "io.github.ra5on.titanos.agent.share"))
    }

    @Test
    fun fileAndOtherSchemesAreRefused() {
        assertFalse(ok("file", null))
        assertFalse(ok("file", ""))
        assertFalse(ok("FILE", null))
        assertFalse(ok("android.resource", own))
        assertFalse(ok("https", "example.org"))
        assertFalse(ok(null, "media"))
        // ContentResolver kennt nur „content“ genau so; anders geschrieben öffnet er nichts.
        assertFalse(ok("CONTENT", "media"))
    }

    @Test
    fun ownProvidersAreRefused() {
        assertFalse(ok("content", "io.github.ra5on.titanos.files"))
        assertFalse(ok("content", "io.github.ra5on.titanos.documents"))
        assertFalse(ok("content", own))
        // Nicht aufzulösen, aber unter dem eigenen Namen: abgelehnt.
        assertFalse(ok("content", "io.github.ra5on.titanos.mail"))
        // Mit Nutzerkennung davor öffnet Android trotzdem den eigenen Anbieter.
        assertFalse(ok("content", "0@io.github.ra5on.titanos.files"))
        assertFalse(ok("content", "10@io.github.ra5on.titanos.mail"))
        assertFalse(ok("content", ""))
        assertFalse(ok("content", null))
    }
}
