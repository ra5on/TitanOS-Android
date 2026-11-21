package io.github.ra5on.titanos

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ziehen und Ablegen im Dateimanager an einem gekoppelten Test-NAS
 * (scratchpad/zz_appserve_test.go): „Liste.txt“ markieren, dann auf den Ordner
 * „Weg“ ziehen; danach liegt sie dort (Auftrag am NAS) und nicht mehr in Docs.
 * Läuft nur von Hand: `./gradlew :app-titanos:connectedDebugAndroidTest`
 * unter der Emulator-Sperre, die App muss gekoppelt sein.
 */
@RunWith(AndroidJUnit4::class)
class DragDropTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val pkg = "io.github.ra5on.titanos"

    private fun tap(text: String) {
        val o = device.wait(Until.findObject(By.text(text)), 10_000) ?: device.wait(Until.findObject(By.desc(text)), 5_000)
        assertTrue("nicht gefunden: $text", o != null)
        o!!.click()
    }

    @Test
    fun dragFileOntoFolder() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 10_000)
        tap(if (device.hasObject(By.text("Dateien"))) "Dateien" else "Files")
        tap("Docs")
        val file = device.wait(Until.findObject(By.text("Liste.txt")), 10_000)!!
        val folder = device.wait(Until.findObject(By.text("Weg")), 10_000)!!
        // Markieren (langes Drücken), dann ziehen: am Touchscreen ziehen markierte Einträge.
        file.longClick()
        Thread.sleep(800)
        val from = file.visibleCenter
        val to = folder.visibleCenter
        device.drag(from.x, from.y, to.x, to.y, 120)
        // Nach dem Auftrag lädt der Ordner neu; die Datei ist weg.
        val gone = device.wait(Until.gone(By.text("Liste.txt")), 15_000)
        assertTrue("Liste.txt ist nach dem Ablegen auf „Weg“ noch in Docs", gone)
    }
}
