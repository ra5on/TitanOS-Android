package io.github.ra5on.titanos.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nur Kopplung und Anmeldung gehen ohne Sitzung; die Foto-Sicherung braucht sie. */
class AppLoginRoutesTest {
    @Test
    fun onlyPairingAndLoginGoWithoutSession() {
        for (p in listOf("/api/v1/app/pair/claim", "/api/v1/app/pair/wait", "/api/v1/app/challenge", "/api/v1/app/session")) assertTrue(p, p.isAppLogin())
        for (p in listOf("/api/v1/app/photos/check", "/api/v1/app/photos/uploads/abc/finish", "/api/v1/app/sessionx", "/api/v1/files/roots")) assertFalse(p, p.isAppLogin())
    }
}
