package io.github.ra5on.titanos.settings

import io.github.ra5on.titanos.ui.guard.GuardAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Konsolen sind nie in der Liste des Fingerabdrucks (Inhaber 21.11.2026). */
class GuardTest {
    @Test
    fun noConsoleAction() {
        val words = listOf("console", "konsole", "terminal", "shell", "vnc", "serial")
        for (a in GuardAction.entries) assertFalse(a.id, words.any { a.id.contains(it, ignoreCase = true) || a.name.contains(it, ignoreCase = true) })
    }

    @Test
    fun sensibleDefaults() {
        assertFalse(GuardAction.AppOpen.default)
        assertTrue(GuardAction.Power.default && GuardAction.Updates.default && GuardAction.Network.default && GuardAction.VolumeDelete.default)
    }
}
