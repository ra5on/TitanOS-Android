package io.github.ra5on.titanos.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.ComputeEnv
import io.github.ra5on.titanos.compute.ConsoleRequest
import io.github.ra5on.titanos.compute.ProgramShell
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

/** Bildtests des Terminals: hell/dunkel bei 390 dp, Schrift 200 %, breit 1280 dp. Ohne Netz. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xhdpi")
class TerminalScreenshotTest {
    private val env = ComputeEnv(ComputeAccess(emptyList(), null), { "owner" }, { "alex" })

    @Before
    fun setUp() {
        Locale.setDefault(Locale.GERMANY)
        Consoles.closeAll()
        val nas = ConsoleRequest.nas().copy(title = "nas")
        Consoles.open(nas)
        Consoles.open(ConsoleRequest.container("abc", "immich · server"))
        Consoles.active = Consoles.sessions.first().id
        for (s in Consoles.sessions) s.openForTest()
        val emu = Consoles.sessions.first().emulator
        emu.feed("\u001b[90mroot-Shell auf dem NAS\u001b[0m\r\n")
        emu.feed("root@titan:~# ls --color /mnt\r\n\u001b[1;34mData\u001b[0m  \u001b[1;34mFast\u001b[0m  \u001b[32mbackup.sh\u001b[0m\r\n")
        emu.feed("root@titan:~# df -h /mnt/Data\r\nDateisystem  Größe Benutzt Verf. Verw%\r\n/dev/md0      1,8T    720G  1,1T   40%\r\n")
        emu.feed("root@titan:~# echo \u001b[38;5;208mGrüße\u001b[0m 漢字 ✓\r\nGrüße 漢字 ✓\r\nroot@titan:~# ")
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        Consoles.closeAll()
    }

    private fun shot(name: String) {
        for (dark in listOf(true, false)) {
            captureRoboImage("build/outputs/roborazzi/terminal-$name-${if (dark) "dunkel" else "hell"}.png") {
                TitanTheme(dark = dark) {
                    ProgramShell("Terminal", "terminal", "dark", emptyList(), "", {}, {}) {
                        Column(Modifier.fillMaxSize()) { Sessions(env, TerminalInfo(enabled = true, nasShell = true, idleMinutes = 15)) }
                    }
                }
            }
        }
    }

    @Test
    fun phone() = shot("390")

    @Test
    fun bigFont() {
        RuntimeEnvironment.setFontScale(2f)
        shot("390-schrift200")
    }

    @Test
    @Config(qualifiers = "de-w1280dp-h800dp-mdpi")
    fun wide() = shot("1280")
}
