package io.github.ra5on.titanos

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.net.NasProfile
import io.github.ra5on.titanos.net.NasRegistry
import io.github.ra5on.titanos.net.Pairing
import io.github.ra5on.titanos.net.PinnedAddress
import io.github.ra5on.titanos.photos.SettingsStore
import io.github.ra5on.titanos.ui.theme.TitanTheme
import io.github.ra5on.titanos.widgets.WidgetNas
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Bildtests „Mehrere NAS“: Seitenmenü (ersetzt den Umschalter, PLAN 117), Seite „NAS und Konten“, Wahl des NAS
 * für ein Widget – hell und dunkel, 390 dp, 200 % Schrift und 1280 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xhdpi")
class NasScreenshotTest {
    private val app: TitanApp get() = ApplicationProvider.getApplicationContext()
    private lateinit var graph: AppGraph
    private val second = "n0a1b2c3d4e"

    @Before
    fun setUp() {
        Locale.setDefault(Locale.GERMANY)
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            app, androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build(),
        )
        for (name in listOf("nas", "pairing", "photos", "photos-$second")) app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        graph = AppGraph(app, NasRegistry.prefs(app))
        graph.registry.add(NasProfile(NasRegistry.LEGACY_ID, Pairing("zeus", "d1", listOf(PinnedAddress("192.168.1.5:8443", "pinA")), "nas"), label = "Zuhause"))
        graph.registry.add(NasProfile(second, Pairing("zeus", "d2", listOf(PinnedAddress("eltern.tail1234.ts.net:8443", "pinB")), "eltern-nas")))
        SettingsStore(app, NasRegistry.LEGACY_ID).save(SettingsStore(app, NasRegistry.LEGACY_ID).load().copy(enabled = true, root = "share/Fotos", rootName = "Fotos", dir = "Pixel 10"))
        graph.switchTo(NasRegistry.LEGACY_ID)
    }

    @After
    fun tearDown() {
        for (name in listOf("nas", "pairing", "photos", "photos-$second")) app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        for (dark in listOf(true, false)) {
            captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dunkel" else "hell"}.png") {
                TitanTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun big(name: String, content: @Composable () -> Unit) {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        try {
            shot(name, content)
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun n01_switcher() = shot("300-seitenmenue") {
        Box(Modifier.fillMaxSize()) { SideMenu(graph, {}, {}, {}, {}) }
    }

    @Test
    fun n02_list() = shot("301-nas-und-konten") { NasListPage(graph, {}, {}, {}) }

    @Test
    fun n03_list_big() = big("302-nas-und-konten-schrift-200") { NasListPage(graph, {}, {}, {}) }

    @Test
    fun n04_switcher_big() = big("303-seitenmenue-schrift-200") {
        Box(Modifier.fillMaxSize()) { SideMenu(graph, {}, {}, {}, {}) }
    }

    @Test
    fun n05_widget() = shot("304-widget-nas-wahl") {
        WidgetNasPage(listOf(WidgetNas(NasRegistry.LEGACY_ID, "Zuhause"), WidgetNas(second, "eltern-nas")), second, {}, {})
    }

    @Test
    fun n06_widget_big() = big("305-widget-nas-wahl-schrift-200") {
        WidgetNasPage(listOf(WidgetNas(NasRegistry.LEGACY_ID, "Zuhause"), WidgetNas(second, "eltern-nas")), null, {}, {})
    }

    @Test
    @Config(qualifiers = "de-w1280dp-h800dp-mdpi")
    fun n07_list_wide() = shot("306-nas-und-konten-breit") { NasListPage(graph, {}, {}, {}) }
}
