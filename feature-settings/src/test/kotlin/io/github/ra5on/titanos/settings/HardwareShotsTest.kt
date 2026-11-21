package io.github.ra5on.titanos.settings

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ra5on.titanos.settings.shell.SettingsApp
import io.github.ra5on.titanos.settings.shell.SettingsNav
import io.github.ra5on.titanos.ui.guard.Guard
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Antworten eines nachgebauten NAS für die Seiten unter „Hardware und Energie“ und „Daten und Sicherung“. */
object HardwareFixtures {
    const val SYSTEM = """{"hostname":"titan","network":{"ipv4":"192.168.1.20/24"},"temperatures":[
        {"id":"coretemp-0","chip":"coretemp","label":"Package id 0","device":"coretemp-isa-0000","celsius":47.5,"criticalC":100},
        {"id":"nvme-0","chip":"nvme","label":"Composite","device":"nvme-pci-0100","celsius":38.9,"criticalC":84.85},
        {"id":"it87-1","chip":"it8728","label":"temp1","device":"it8728-isa-0a30","celsius":null,"criticalC":null}]}"""

    const val ALARMS = """{"heatShutdown":true,"muted":["nvme-0"],"future":1,"sensors":[
        {"id":"coretemp-0","limit":95,"own":true,"muted":false,"shutdown":true},
        {"id":"nvme-0","limit":80,"own":false,"muted":true,"shutdown":true},
        {"id":"it87-1","limit":null,"muted":false}]}"""

    const val FANS = """{"readOnly":false,"fans":[
        {"id":"it87/pwm1","chip":"it8728","label":"CPU-Lüfter","rpm":1200,"percent":45,"sensorC":47,
         "control":{"id":"it87/pwm1","mode":"curve","sensor":"coretemp-0","points":[{"celsius":35,"percent":30},{"celsius":50,"percent":45},{"celsius":65,"percent":75},{"celsius":80,"percent":100}],"min":30,"extra":"bleibt"}},
        {"id":"it87/pwm2","chip":"it8728","label":"Gehäuselüfter vorn","rpm":800,"percent":60,"control":{"id":"it87/pwm2","mode":"fixed","percent":60,"min":20}},
        {"id":"it87/pwm3","chip":"it8728","label":"Gehäuselüfter hinten","rpm":null,"percent":0,"control":{"id":"it87/pwm3","mode":"bios","min":20},"error":"pwm3: der Treiber erlaubt keine Regelung"}]}"""

    const val HARDWARE = """{"hardware":{"iommu":true,"pci":[
        {"address":"0000:01:00.0","vendor":"10de","device":"2204","vendorName":"NVIDIA","kind":"gpu","driver":"nvidia","iommuGroup":12,"primary":false,"companions":["0000:01:00.1"],"passable":true,"reason":""},
        {"address":"0000:00:02.0","vendor":"8086","device":"3e92","vendorName":"Intel","kind":"igpu","driver":"i915","iommuGroup":1,"primary":true,"companions":[],"passable":false,"reason":"primary"},
        {"address":"0000:03:00.0","vendor":"1e60","device":"2864","vendorName":"","kind":"npu","driver":"","iommuGroup":-1,"primary":false,"companions":[],"passable":false,"reason":"npu"}],
       "usb":[{"id":"0781:5583","name":"SanDisk Ultra Fit","port":"2-1","stick":true,"duplicate":false},
              {"id":"0627:0001","name":"","port":"1-1","stick":false,"duplicate":false},
              {"id":"1a86:7523","name":"Zigbee-Stick","port":"1-2","stick":false,"duplicate":false}]},
      "usedBy":{"0000:01:00.0":["Windows 11"],"1a86:7523":["homeassistant"]}}"""

    const val NVIDIA = """{"settings":{"0000:01:00.0":{"powerPercent":70,"later":true}},"problems":{}}"""

    const val INTEL = """{"mode":"auto","managed":true,"active":"","param":"","entries":{},"deferred":false,"restartNeeded":true,"writable":true,"problem":"",
      "gpus":[{"address":"0000:00:02.0","device":"3e92","revision":0,"platform":"coffeelake","name":"Coffee Lake","generation":"9","driver":"i915","renderNode":"renderD128","card":"card0",
        "applies":true,"submission":false,"submissionUsed":false,
        "guc":{"file":"i915/kbl_guc_70.1.1.bin","present":true,"loaded":"","version":"","state":"disabled","authenticated":false},
        "huc":{"file":"i915/kbl_huc_4.0.0.bin","present":true,"loaded":"i915/kbl_huc_4.0.0.bin","version":"4.0.0","state":"running","authenticated":true},
        "source":"debugfs","messages":["i915 0000:00:02.0: [drm] HuC authenticated"]}]}"""

    const val REPORT = """{"generated":"2026-11-21T10:00:00Z","unbound":[
        {"bus":"pci","address":"0000:05:00.0","id":"10ec:8125","class":"020000","name":"Realtek RTL8125 2.5GbE","hint":"kernel","kernel":"5.9","newKernel":"6.12.9+deb13-amd64","newKernelFixes":true,"driver":"r8169"},
        {"bus":"pci","address":"0000:06:00.0","id":"1c00:3253","class":"070000","name":"PCIe Serial","hint":"plugin","plugins":[{"plugin":"serial-extra","title":"Serielle Karten","installed":false}]},
        {"bus":"usb","address":"1-4","id":"8087:0026","class":"e0","name":"Intel AX201 Bluetooth","hint":"driver_failed","driver":"btusb","kernel":"6.8","messages":["btusb 1-4:1.0: firmware load failed"]},
        {"bus":"pci","address":"0000:00:00.0","id":"8086:3e30","class":"060000","name":"Host Bridge","hint":"not_needed"},
        {"bus":"pci","address":"0000:07:00.0","id":"abcd:0001","class":"ff0000","name":"Unbekanntes Gerät","hint":"unclassified"}],
      "firmwareMissing":[{"file":"rtl_nic/rtl8125b-2.fw","device":"0000:05:00.0"}]}"""

    const val ENERGY = """{"settings":{"governor":"powersave","preference":"","platform":"","future":"x"},"readOnly":false,"drivers":["intel_pstate"],
      "controls":[{"key":"governor","choices":["performance","powersave"],"current":"powersave","mixed":false},
                  {"key":"preference","choices":["default","performance","balance_performance","balance_power","power"],"current":"balance_performance","mixed":true}]}"""

    const val SLEEP = """{"idleMinutes":30,"volumes":{"Daten":60},"disks":{"WD-ABC123456789XYZ":0},"available":true,"errors":[],"listed":true,
      "volumeStatus":[{"name":"Daten","override":60,"effective":60,"origin":"volume"},{"name":"Medien","override":null,"effective":30,"origin":"default"}],
      "diskStatus":[{"id":"WD-ABC123456789XYZ","name":"sda","model":"WDC WD40EFRX","serial":"WD-ABC123456789XYZ","sizeBytes":4000787030016,"volume":"Daten","override":0,"effective":0,"origin":"disk","state":"active",
          "since":"2026-11-20T08:15:00Z","wakes":[{"at":"2026-11-20T08:15:00Z","causes":[{"code":"journal"},{"code":"smb_session","detail":"alex"}]}],"wakesToday":2},
        {"id":"ST-XYZ","name":"sdb","model":"ST4000VN008","serial":"ZDH0XYZ","sizeBytes":4000787030016,"volume":"Medien","override":null,"effective":30,"origin":"default","state":"asleep","since":"2026-11-20T09:00:00Z"}],
      "unavailable":[{"name":"nvme0n1","model":"Samsung 980","sizeBytes":1000204886016,"reason":"nvme"}]}"""

    const val SCHEDULE = """{"settings":{"off":{"enabled":true,"days":[1,2,3,4,5],"time":"23:30","keep":1},"on":{"enabled":false,"days":[0,1,2,3,4,5,6],"time":"07:00"},"wakeOnLan":true,"extra":"bleibt"},
      "nextOff":"2026-11-21T22:30:00Z","rtc":true,"readOnly":false,
      "interfaces":[{"name":"enp1s0","mac":"52:54:00:e5:b9:49","supported":true,"enabled":true},{"name":"wlp2s0","mac":"aa:bb:cc:dd:ee:ff","supported":false,"enabled":false}]}"""

    const val BUTTON = """{"settings":{"action":"poweroff","later":2},"readOnly":false}"""

    const val UPS = """{"drivers":["usbhid-ups","nutdrv_qx","blazer_usb","blazer_ser","apcsmart","dummy-ups"],"passwordSet":false,
      "settings":{"mode":"local","driver":"usbhid-ups","port":"auto","serve":true,"remotePassword":"pw_beispiel","shutdownAfter":0,"future":"bleibt"},
      "status":{"mode":"local","connected":true,"onBattery":false,"lowBattery":false,"raw":"OL","charge":87,"runtime":2460,"load":23,"model":"APC Back-UPS 700","error":"","since":null,"shuttingDown":false}}"""

    const val MEMORY = """{"settings":{"cachePercent":20,"later":1},"readOnly":false,"budgetBytes":1572714905,"reserveBytes":2147483648,"inFlightBytes":0,
      "memory":{"totalBytes":31320178688,"availableBytes":10011058176,"dirtyBytes":10973184,"writebackBytes":0,"pressure":{"someAvg10":0.4,"fullAvg10":0.1}},"paused":false}"""

    const val SYSDATA = """{"volume":"","resolved":"Daten","sleepKnown":true,
      "volumes":[{"name":"Daten","mounted":true,"sleeps":false},{"name":"Medien","mounted":true,"sleeps":true},{"name":"Alt","mounted":false,"sleeps":false}],
      "functions":[{"id":"config","choice":"","volume":"Daten","source":"common","enabled":true,"moving":false,"sizeBytes":1048576,"sizeAt":null},
        {"id":"logs","choice":"","volume":"Daten","source":"common","enabled":true,"moving":false,"sizeBytes":52428800,"sizeAt":null},
        {"id":"activity","choice":"Medien","volume":"Medien","source":"own","enabled":true,"moving":false,"sizeBytes":2097152,"sizeAt":null,"aside":["2026-11.jsonl.from-Daten"]},
        {"id":"notify","choice":"","volume":"Daten","source":"common","enabled":true,"moving":false,"sizeBytes":4096,"sizeAt":null},
        {"id":"smart","choice":"","volume":"Daten","source":"common","enabled":true,"moving":false,"sizeBytes":8192,"sizeAt":null},
        {"id":"monitoring","choice":"","volume":"Daten","source":"common","enabled":false,"moving":false,"sizeBytes":0,"sizeAt":null}]}"""

    const val CONFIG = """{"backup":{"settings":{"enabled":true,"volume":"","keep":14,"schedule":"","time":"","weekday":0,"pace":"change","encrypt":true},"target":"Daten","lastError":"",
      "backups":[{"time":"2026-11-21T08:00:00Z","sizeBytes":24576},{"time":"2026-11-20T08:00:00Z","sizeBytes":24000}]},"passwordSet":true}"""

    const val INDEX = """{"mounted":["Daten","Daten A"],"volumes":["Daten","Daten A"],"shares":[{"name":"Familie","volume":"Daten"},{"name":"Fotos","volume":"Daten A"}],
      "status":{"enabled":true,"volumes":["Daten"],"shares":[],"ocr":false,"time":"","later":"bleibt","ocrAvailable":true,
        "indexes":[{"volume":"Daten","state":"idle","files":12345,"known":true,"scanned":0,"updated":0,"bytes":73400320,"lastRun":"2026-11-21T03:00:00Z"},
                   {"volume":"Daten A","state":"running","files":0,"known":false,"scanned":1200,"updated":300,"bytes":0,"lastError":"pdf: kaputte Datei","truncated":true}]}}"""

    const val WEBAPPS = """{"apps":[{"id":"t-word","name":"T-Word","installed":true,"state":"ready","types":["docx","odt"],"default":true,"url":"/webapps/t-word/1.7.0/"},
      {"id":"t-pdf","name":"T-PDF","installed":true,"state":"ready","types":["pdf"],"default":true,"url":"/webapps/t-pdf/1.7.0/"}]}"""

    const val PREFS = """{"fileapps":{"odt":"editor","x-old":"webapp:weg"}}"""

    const val POWER = """{"pending":null,"preparing":null,"rebuilding":false}"""

    fun install(nas: FakeNas) {
        nas.on("GET system", SYSTEM)
        nas.on("GET notifications/temperatures", ALARMS)
        nas.on("GET system/fans", FANS)
        nas.on("GET hardware", HARDWARE)
        nas.on("GET hardware/nvidia", NVIDIA)
        nas.on("GET hardware/intel-gpu", INTEL)
        nas.on("GET hardware/report", REPORT)
        nas.on("GET system/energy", ENERGY)
        nas.on("GET storage/disk-sleep", SLEEP)
        nas.on("GET system/power-schedule", SCHEDULE)
        nas.on("GET system/power-button", BUTTON)
        nas.on("GET ups", UPS)
        nas.on("GET system/memory", MEMORY)
        nas.on("GET system/data", SYSDATA)
        nas.on("GET config-backup", CONFIG)
        nas.on("GET files/index", INDEX)
        nas.on("GET webapps", WEBAPPS)
        nas.on("GET preferences", PREFS)
        nas.on("GET system/power", POWER)
        nas.on("GET health", """{"ready":true}""")
        nas.on("GET network", """{"timezone":"Europe/Berlin","hostname":"titan"}""")
    }
}

/**
 * Hardware und Energie, Systemdaten und Dateisuche: jede Seite und die
 * wichtigen aufgeklappten Zustände und Dialoge; hell, dunkel, 200 %, breit.
 * Hohe Fenster, damit die ganze Seite im Bild ist.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h2600dp-hdpi", sdk = [36])
class HardwareShotsTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val shots = Shots(compose)

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        Guard.prompt = { _, _, done -> done(true) }
        HardwareFixtures.install(nas)
        nas.start()
    }

    @After
    fun stop() {
        Guard.prompt = null
        nas.stop()
        shots.assertClean()
    }

    private fun open(nav: SettingsNav, page: String, wait: String) {
        nav.openPage(page)
        shots.until { nas.requests.any { it.second == wait } }
        shots.settle(1200)
    }

    private fun click(text: String) {
        val exact = compose.onAllNodes(hasText(text), useUnmergedTree = true)
        val node = if (exact.fetchSemanticsNodes().isNotEmpty()) exact.onFirst() else compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).onFirst()
        node.apply { runCatching { performScrollTo() } }.performClick()
        shots.settle(900)
    }

    private fun app(dark: Boolean, role: String = "owner"): SettingsNav {
        val nav = SettingsNav()
        compose.mainClock.autoAdvance = false
        shots.show(dark) { SettingsApp(nas.env(role), onClose = {}, nav = nav) }
        return nav
    }

    /** Hardware, Temperaturen, Energie, Ein-/Ausschalter. */
    private fun partA(dark: Boolean) {
        val nav = app(dark)
        open(nav, "hardware", "hardware/report"); shots.capture("10-hardware", dark)
        click("Geräte ohne Treiber"); click("Grafikkarte · NVIDIA"); shots.capture("11-hardware-offen", dark)
        open(nav, "temperatures", "system/fans"); click("Sensoren, die melden"); click("CPU-Lüfter"); shots.capture("20-temperaturen", dark)
        open(nav, "energy", "system/power-schedule"); click("Zeitplan"); shots.capture("30-energie", dark)
        open(nav, "power-button", "system/power-button"); shots.capture("40-einschalter", dark)
    }

    /** USV, Arbeitsspeicher, Systemdaten, Dateisuche. */
    private fun partB(dark: Boolean) {
        val nav = app(dark)
        open(nav, "ups", "ups"); shots.capture("50-usv", dark)
        open(nav, "memory", "system/memory"); shots.capture("60-arbeitsspeicher", dark)
        open(nav, "systemdata", "config-backup"); click("Konfigurationssicherung"); shots.capture("70-systemdaten", dark)
        open(nav, "files", "files/index"); click("Ganze Volumes"); click("Wann indexiert wird"); shots.capture("80-dateisuche", dark)
    }

    /** Dialoge: Plattenruhe, Neustart, GuC-Rückfrage, Index entfernen. */
    private fun dialogs(dark: Boolean) {
        val nav = app(dark)
        open(nav, "energy", "storage/disk-sleep"); click("Ruhemodus einstellen …"); shots.capture("31-plattenruhe", dark); click("Schließen")
        nav.openPage("hardware"); shots.until { nas.requests.count { it.second == "hardware/intel-gpu" } >= 1 }; shots.settle(1200)
        click("Jetzt neu starten"); shots.capture("12-neustart", dark)
        click("Abbrechen")
        click("GuC-Submission und HuC"); click("Speichern"); shots.capture("13-guc-rueckfrage", dark)
        click("Abbrechen")
        open(nav, "files", "files/index"); click("Index löschen"); shots.capture("81-index-entfernen", dark)
    }

    @Test fun hellA() = partA(false)

    @Test fun hellB() = partB(false)

    @Test fun hellDialoge() = dialogs(false)

    @Test fun dunkelA() = partA(true)

    @Test fun dunkelB() = partB(true)

    @Test fun dunkelDialoge() = dialogs(true)

    @Test
    fun benutzer() {
        // Temperaturen ist die eine Seite hier, die auch Benutzer sehen: nur die Sensoren.
        val nav = app(false, role = "user")
        open(nav, "temperatures", "system")
        shots.capture("21-temperaturen-benutzer", false)
    }

    @Test
    @Config(qualifiers = "de-w1280dp-h1800dp-mdpi")
    fun breitA() { shots.prefix = "breit-"; partA(true) }

    @Test
    @Config(qualifiers = "de-w1280dp-h1800dp-mdpi")
    fun breitB() { shots.prefix = "breit-"; partB(true) }

    @Test
    @Config(qualifiers = "de-w390dp-h4000dp-mdpi")
    fun schrift200A() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        shots.prefix = "schrift200-"
        partA(false)
    }

    @Test
    @Config(qualifiers = "de-w390dp-h4000dp-mdpi")
    fun schrift200B() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        shots.prefix = "schrift200-"
        partB(false)
    }

    @Test
    @Config(qualifiers = "de-w390dp-h2000dp-mdpi")
    fun schrift200Dialoge() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        shots.prefix = "schrift200-"
        dialogs(false)
    }
}
