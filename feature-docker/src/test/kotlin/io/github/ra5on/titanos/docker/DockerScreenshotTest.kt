package io.github.ra5on.titanos.docker

import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.ComputeEnv
import io.github.ra5on.titanos.compute.ListDetail
import io.github.ra5on.titanos.compute.ProgramShell
import io.github.ra5on.titanos.compute.Section
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanTheme
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Bildtests der Docker-Seite mit Beispieldaten wie scripts/test-ui-docker-table.mjs im Web. Ohne Netz. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xhdpi")
class DockerScreenshotTest {
    private val access = ComputeAccess(emptyList(), null)
    private val env = ComputeEnv(access, { "owner" })

    private fun ct(name: String, app: String = name, service: String = name, image: String = "$name:latest", state: String = "running", cpu: Double = 1.2, mem: Long = 180_000_000, port: Int? = null, update: Boolean = false, host: Boolean = false) =
        DockerContainer(id = name, name = name, image = image, state = state, since = "2026-11-07T08:00:00Z", ports = listOfNotNull(port?.let { Port(it, it) }),
            app = app, service = service, managed = true, cpuPercent = cpu, memoryBytes = mem, webui = port?.toString().orEmpty(), updateAvailable = update, hostNetwork = host)

    private val status = DockerStatus(
        settings = DockerSettings(true, "Data"), running = true, version = "27.3",
        containers = listOf(
            ct("immich-server", "immich", "server", cpu = 4.1, mem = 1_400_000_000, port = 2283),
            ct("immich-ml", "immich", "ml", cpu = 0.4, mem = 300_000_000),
            ct("immich-db", "immich", "db", cpu = 0.2, mem = 120_000_000),
            ct("jellyfin", image = "jellyfin/jellyfin:10.10", cpu = 6.5, mem = 1_100_000_000, port = 8096, update = true),
            ct("vaultwarden", image = "vaultwarden/server:latest", state = "exited", port = 8081),
            ct("frigate", image = "ghcr.io/blakeblackshear/frigate:stable", state = "restarting", port = 5000, host = true),
        ),
        apps = listOf("immich", "jellyfin", "vaultwarden", "frigate"),
        updatesChecked = "2026-11-21T08:00:00Z",
    )

    @Before
    fun german() = Locale.setDefault(Locale.GERMANY)

    @After
    fun font() = RuntimeEnvironment.setFontScale(1f)

    private fun shot(name: String, detail: Boolean) {
        val state = DockerState(DockerApi(access)).also { it.status = status }
        val groups = groupsOf(status.containers)
        for (dark in listOf(true, false)) {
            captureRoboImage("build/outputs/roborazzi/docker-$name-${if (dark) "dunkel" else "hell"}.png") {
                TitanTheme(dark = dark) {
                    val sections = listOf(
                        Section("containers", "Container", TitanIcons.box), Section("images", "Images", TitanIcons.archive),
                        Section("volumes", "Volumes", TitanIcons.hdd), Section("networks", "Netzwerke", TitanIcons.globe),
                        Section("cleanup", "Aufräumen", TitanIcons.trash), Section("catalog", "Katalog", TitanIcons.grid),
                    )
                    ProgramShell("Docker", "docker", "blue", sections, "containers", {}, {}) {
                        ListDetail(detail, {}, list = { ContainerList(env, state, groups) {} }, detail = { GroupDetail(env, state, groups.first(), {}) })
                    }
                }
            }
        }
    }

    @Test
    fun list() = shot("liste-390", detail = false)

    @Test
    fun app() = shot("app-390", detail = true)

    @Test
    fun bigFont() {
        RuntimeEnvironment.setFontScale(2f)
        shot("liste-390-schrift200", detail = false)
    }

    @Test
    @Config(qualifiers = "de-w1280dp-h800dp-mdpi")
    fun wide() = shot("1280", detail = true)
}
