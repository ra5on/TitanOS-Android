package io.github.ra5on.titanos.pim

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.pim.con.ConRoute
import io.github.ra5on.titanos.pim.con.ContactsScreen
import io.github.ra5on.titanos.pim.con.DuplicateSet
import io.github.ra5on.titanos.ui.theme.TitanTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone

/** Beispieldaten für die Bildtests von Kontakte (nie echte Daten). */
internal object ConFixtures {
    private val names = listOf(
        "Anna Albers", "Ben Okafor", "Clara Christ", "David Dietz", "Eva Engel", "Felix Fuchs", "Greta Graf", "Hanna Huber", "Ida Imhof",
        "Jonas Jung", "Kai Klein", "Lea Neumann", "Mia Maier", "Noah Nagel", "Olga Ott", "Paul Peters", "Sam Christensen", "Tom Tal",
    )
    val list = names.mapIndexed { i, n ->
        val last = n.substringAfter(' ')
        """{"id":"c$i.vcf","name":"$n","sortKey":"${last.lowercase()} ${n.substringBefore(' ').lowercase()}","org":"${if (i % 4 == 0) "Schule am Park" else ""}","etag":"e$i"${if (i == 16) ""","birthdaySoon":{"date":"2026-11-24","age":41}""" else ""}}"""
    }.joinToString(",", "[", "]")
    const val CONTACT = """{"id":"c16.vcf","etag":"e16","first":"Sam","last":"Christensen","name":"Sam Christensen","org":"Schule am Park",
        "phones":[{"type":"mobile","value":"+49 170 1234567"},{"type":"work","value":"+49 30 1234"}],
        "emails":[{"type":"home","value":"sam@example.org"}],
        "addresses":[{"type":"home","street":"Hauptstraße 1","city":"Berlin","postcode":"10115","region":"","country":"Deutschland"}],
        "birthday":"1985-11-24","notes":"Mag Tee, keinen Kaffee.","url":"https://sam.example"}"""
    const val OVERVIEW = """{"books":[{"id":"kontakte","name":"Kontakte","count":18}],"groups":[{"id":"familie.vcf","etag":"g1","name":"Familie","members":["c16.vcf","c11.vcf"]},{"id":"schule.vcf","etag":"g2","name":"Schule","members":["c0.vcf","c4.vcf","c8.vcf"]}],"duplicates":1}"""
    val routes = mapOf(
        "GET /api/v1/contacts" to list,
        "GET /api/v1/contacts/kontakte/" to CONTACT,
        "GET /api/v1/contacts/overview" to OVERVIEW,
        "GET /api/v1/contacts/duplicates" to """[{"book":"kontakte","ids":["c16.vcf","c2.vcf"],"matches":[{"kind":"email","value":"sam@example.org","ids":["c16.vcf","c2.vcf"]}]}]""",
        "GET /api/v1/contacts/suggestions" to """[{"name":"Rita Roth","addr":"rita@example.org","count":7},{"name":"","addr":"info@verein.example","count":3}]""",
        "GET /api/v1/preferences/contacts" to """{"me":"c11.vcf"}""",
    )
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp-xhdpi")
class ContactsScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nas: FakeNas

    @Before
    fun setUp() {
        Locale.setDefault(Locale.GERMANY)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        nas = FakeNas(ConFixtures.routes + CalFixtures.routes)
    }

    @After
    fun tearDown() = nas.close()

    private fun shot(name: String, dark: Boolean, route: ConRoute = ConRoute.Home) {
        rule.setContent {
            TitanTheme(dark = dark) { Box(Modifier.fillMaxSize()) { val c = nas.connection(); ContactsScreen(c, null, onClose = {}, onSyncSettings = {}, startRoute = route) } }
        }
        val until = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < until) { rule.mainClock.advanceTimeBy(100); Thread.sleep(50) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dunkel" else "hell"}.png")
    }

    @Test fun listDark() = shot("60-kontakte", true)
    @Test fun listLight() = shot("60-kontakte", false)
    @Test fun detailDark() = shot("61-kontakt", true, ConRoute.Detail("c16.vcf"))
    @Test fun detailLight() = shot("61-kontakt", false, ConRoute.Detail("c16.vcf"))
    @Test fun editDark() = shot("62-kontakt-bearbeiten", true, ConRoute.Edit("c16.vcf"))
    @Test fun editLight() = shot("62-kontakt-bearbeiten", false, ConRoute.Edit("c16.vcf"))
    @Test fun listsDark() = shot("63-listen", true, ConRoute.Lists)
    @Test fun listsLight() = shot("63-listen", false, ConRoute.Lists)
    @Test fun duplicatesLight() = shot("64-dubletten", false, ConRoute.Duplicates)
    @Test fun mergeDark() = shot("65-zusammenfuehren", true, ConRoute.Merge(DuplicateSet(ids = listOf("c16.vcf", "c2.vcf"))))
    @Test fun suggestionsLight() = shot("66-vorschlaege", false, ConRoute.Suggestions)
    @Test fun settingsDark() = shot("67-kontakte-einstellungen", true, ConRoute.Settings)
    @Test fun settingsLight() = shot("67-kontakte-einstellungen", false, ConRoute.Settings)
    @Test @Config(qualifiers = "de-w840dp-h900dp-mdpi") fun splitLight() = shot("68-kontakte-split", false, ConRoute.Detail("c16.vcf"))
    @Test @Config(qualifiers = "de-w1280dp-h800dp-mdpi") fun wideDark() = shot("69-kontakte-pc", true, ConRoute.Detail("c16.vcf"))
    @Test @Config(qualifiers = "de-w412dp-h915dp-xhdpi", fontScale = 2.0f) fun largeFont() = shot("70-kontakt-schrift-200", false, ConRoute.Detail("c16.vcf"))
}
