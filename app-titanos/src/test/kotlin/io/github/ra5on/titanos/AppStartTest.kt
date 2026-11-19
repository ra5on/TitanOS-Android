package io.github.ra5on.titanos

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Startet die echte Activity ohne Kopplung: Willkommen; mit App-Link: „NAS koppeln“ (noch ohne claim). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-w412dp-h915dp")
class AppStartTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun withoutPairingTheWelcomeScreenShows() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("QR-Code scannen").assertExists()
            compose.onNodeWithText("Adresse von Hand eingeben").assertExists()
        }
    }

    @Test
    fun pairLinkOpensConfirmationWithoutClaiming() {
        val link = "titanos://pair?a=nas.invalid%21n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg&c=abc&v=1"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link), ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.onNodeWithText("NAS koppeln").assertExists()
            compose.onNodeWithText("Koppeln").assertExists()
            compose.onNodeWithText("nas.invalid").assertExists()
        }
    }
}
