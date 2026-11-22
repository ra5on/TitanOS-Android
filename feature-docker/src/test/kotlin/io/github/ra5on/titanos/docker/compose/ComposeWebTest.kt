package io.github.ra5on.titanos.docker.compose

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vergleich mit der Logik des Webs: compose-web.json entsteht, indem
 * web/src/lib/compose/{template,casaos,hardware,service,lan}.ts per node
 * gegen dieselben Vorlagen laufen (Skript im Bericht, Erzeugung mit
 * node 24 und der yaml-Bibliothek des Webs). Verglichen wird der Inhalt der
 * Compose-Dateien als Baum (Werte, Reihenfolge der Schlüssel), nicht Zeichen
 * für Zeichen: Einrückung und Anführungszeichen schreibt snakeyaml-engine
 * teils anders als die yaml-Bibliothek des Webs.
 */
class ComposeWebTest {
    private val data: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("compose-web.json")!!.readText(),
    ).jsonObject

    private fun JsonElement.str() = jsonPrimitive.content

    /** JSON → einfacher Wert wie YamlDoc.plain (ganze Zahlen als Long). */
    private fun plainOf(e: JsonElement): Any? = when (e) {
        JsonNull -> null
        is JsonObject -> LinkedHashMap<String, Any?>().apply { e.forEach { (k, v) -> put(k, plainOf(v)) } }
        is JsonArray -> e.map { plainOf(it) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.longOrNull != null -> e.longOrNull
            else -> e.doubleOrNull
        }
    }

    /**
     * Baumvergleich mit Reihenfolge der Schlüssel (Zahlen als Double, damit 1 und 1.0 gleich sind).
     * Schlüssel, die ganze Zahlen sind („8554“), stellt JavaScript in Objekten nach vorn und sortiert
     * sie; die Datei selbst behält ihre Reihenfolge. Hier dieselbe Ordnung wie in JavaScript.
     */
    private fun norm(v: Any?): Any? = when (v) {
        is Map<*, *> -> {
            val index = Regex("^(0|[1-9][0-9]{0,8})$")
            val (ints, rest) = v.entries.partition { index.matches(it.key.toString()) }
            (ints.sortedBy { it.key.toString().toLong() } + rest).map { (k, x) -> k.toString() to norm(x) }
        }
        is List<*> -> v.map { norm(it) }
        is Number -> v.toDouble()
        else -> v
    }

    private fun assertTree(message: String, expected: Any?, actual: Any?) = assertEquals(message, norm(expected), norm(actual))

    private fun pairs(e: JsonElement) = e.jsonArray.map { Pair(it.jsonObject["name"]!!.str(), it.jsonObject["value"]!!.str()) }

    private fun serviceForm(o: JsonObject) = ServiceForm(
        image = o["image"]!!.str(),
        restart = o["restart"]!!.str(),
        network = o["network"]!!.str(),
        privileged = o["privileged"]!!.jsonPrimitive.booleanOrNull == true,
        hardware = o["hardware"]!!.jsonArray.map { it.str() },
        webui = o["webui"]!!.str(),
        publicUrl = o["publicUrl"]!!.str(),
        paths = o["paths"]!!.jsonArray.map { p -> p.jsonObject.let { PathRow(it["host"]!!.str(), it["container"]!!.str(), it["readOnly"]!!.jsonPrimitive.booleanOrNull == true, it["raw"]?.let(::plainOf)) } },
        ports = o["ports"]!!.jsonArray.map { p -> p.jsonObject.let { PortRow(it["host"]!!.str(), it["container"]!!.str(), it["protocol"]!!.str(), it["raw"]?.let(::plainOf)) } },
        env = pairs(o["env"]!!),
        labels = pairs(o["labels"]!!),
        devices = o["devices"]!!.jsonArray.map { d -> d.jsonObject.let { DeviceRow(it["value"]!!.str(), it["raw"]?.let(::plainOf)) } },
        cpuset = o["cpuset"]!!.str(),
        memLimit = o["memLimit"]!!.str(),
    )

    @Test
    fun serviceFormMatchesWeb() {
        for (e in data["edits"]!!.jsonArray.map { it.jsonObject }) {
            val id = e["id"]!!.str()
            val doc = YamlDoc.parse(e["input"]!!.str())
            val service = e["service"]!!.str()
            assertEquals(id, e["services"]!!.jsonArray.map { it.str() }, serviceNames(doc))
            assertEquals("$id: lesen", serviceForm(e["before"]!!.jsonObject), readService(doc, service))
            writeService(doc, service, serviceForm(e["after"]!!.jsonObject))
            val written = doc.toString()
            assertTree("$id: geschrieben\n$written", plainOf(e["plain"]!!), YamlDoc.parse(written).toPlain())
        }
    }

    @Test
    fun installMatchesWeb() {
        for (s in data["scenarios"]!!.jsonArray.map { it.jsonObject }) {
            val id = s["id"]!!.str()
            val input = s["input"]!!.jsonObject
            var compose = input["compose"]!!.str()
            input["casa"]?.jsonObject?.let { casa ->
                val ctx = casa["ctx"]!!.jsonObject
                compose = fromCasaOS(compose, casa["appId"]!!.str(), CasaContext(ctx["timezone"]!!.str(), ctx["uid"]!!.jsonPrimitive.int, ctx["gid"]!!.jsonPrimitive.int, ctx["host"]!!.str()))
                assertTrue(isCasaOS(input["compose"]!!.str()))
                assertTree("$id: CasaOS\n$compose", plainOf(s["casaPlain"]!!), YamlDoc.parse(compose).toPlain())
            }
            val tpl = TemplateInfo(compose, "Europe/Berlin", 1000, 100, "/mnt/Data/appdata", listOf(8080 to "tcp"), listOf("/dev/ttyUSB0"))
            val shares = input["shares"]!!.jsonArray.map { ShareRef(it.jsonObject["name"]!!.str(), it.jsonObject["path"]!!.str()) }
            val used = input["used"]!!.jsonObject.mapValues { it.value.str() }
            val installed = input["installed"]!!.jsonArray.map { it.str() }
            var form = readTemplate(tpl, input["name"]!!.str(), shares, used)
            form = form.copy(vars = form.vars.map { if (it.origin == "generated") it.copy(value = "GEN_" + it.name) else it })

            val read = s["read"]!!.jsonObject
            assertEquals("$id: Hauptdienst", read["main"]!!.str(), form.main)
            assertEquals("$id: Risiken", read["risks"]!!.jsonArray.map { it.str() }, form.risks)
            assertEquals("$id: fehlende Geräte", read["missingDevices"]!!.jsonArray.map { it.str() }, form.missingDevices)
            assertEquals("$id: Images", read["images"]!!.jsonArray.map { ImageField(it.jsonObject["service"]!!.str(), it.jsonObject["image"]!!.str()) }, form.images)
            assertEquals("$id: Variablen", read["vars"]!!.jsonArray.map { v ->
                v.jsonObject.let { listOf(it["service"]!!.str(), it["name"]!!.str(), it["value"]!!.str(), it["origin"]!!.str(), it["secret"]!!.toString(), it["required"]!!.toString(), it["fill"]!!.str(), it["placeholder"]!!.str()) }
            }, form.vars.map { listOf(it.service, it.name, it.value, it.origin, it.secret.toString(), it.required.toString(), it.fill, it.placeholder) })
            assertEquals("$id: Pfade", read["paths"]!!.jsonArray.map { p ->
                p.jsonObject.let { listOf(it["service"]!!.str(), it["index"]!!.toString(), it["container"]!!.str(), it["template"]!!.str(), it["host"]!!.str(), it["wanted"]!!.toString(), (it["share"] as? JsonObject)?.get("name")?.str() ?: "") }
            }, form.paths.map { listOf(it.service, it.index.toString(), it.container, it.template, it.host, it.wanted.toString(), it.share?.name ?: "") })
            assertEquals("$id: Ports", read["ports"]!!.jsonArray.map { p ->
                p.jsonObject.let { listOf(it["service"]!!.str(), it["index"]!!.toString(), it["container"]!!.str(), it["protocol"]!!.str(), it["webui"]!!.toString(), it["templateHost"]!!.str(), (it["blockedBy"] as? JsonPrimitive)?.takeIf { b -> b.isString }?.content ?: "null", it["host"]!!.str()) }
            }, form.ports.map { listOf(it.service, it.index.toString(), it.container, it.protocol, it.webui.toString(), it.templateHost, it.blockedBy ?: "null", it.host) })
            assertEquals("$id: Label des ersten Pfads", read["paths"]!!.jsonArray.firstOrNull()?.jsonObject?.get("label")?.let(::plainOf), form.paths.firstOrNull()?.label)

            // Dieselben Änderungen wie gen.mjs.
            val edits = input["edits"]!!.jsonObject
            val vars = edits["vars"]?.jsonObject?.mapValues { it.value.str() } ?: emptyMap()
            form = form.copy(vars = form.vars.map { if (it.name in vars) it.copy(value = vars.getValue(it.name)) else it })
            edits["paths"]?.jsonArray?.forEach { p ->
                val i = p.jsonObject["i"]!!.jsonPrimitive.int
                form = form.copy(paths = form.paths.toMutableList().also { it[i] = it[i].copy(host = p.jsonObject["host"]!!.str(), touched = true) })
            }
            val images = edits["images"]?.jsonObject?.mapValues { it.value.str() } ?: emptyMap()
            form = form.copy(images = form.images.map { if (it.service in images) it.copy(image = images.getValue(it.service)) else it })
            edits["own"]?.jsonObject?.let { own ->
                form = form.copy(
                    own = OwnEntries(
                        vars = own["vars"]?.let(::pairs) ?: emptyList(),
                        paths = own["paths"]?.jsonArray?.map { OwnPath(it.jsonObject["host"]!!.str(), it.jsonObject["container"]!!.str()) } ?: emptyList(),
                        ports = own["ports"]?.jsonArray?.map { OwnPort(it.jsonObject["host"]!!.str(), it.jsonObject["container"]!!.str(), it.jsonObject["protocol"]!!.str()) } ?: emptyList(),
                        labels = own["labels"]?.let(::pairs) ?: emptyList(),
                        devices = own["devices"]?.jsonArray?.map { it.str() } ?: emptyList(),
                    ),
                )
            }
            edits["cpuset"]?.let { form = form.copy(cpuset = it.str()) }
            edits["memLimit"]?.let { form = form.copy(memLimit = it.str()) }
            edits["publicUrl"]?.let { form = form.copy(publicUrl = it.str()) }

            val problems = checkInstall(form, installed, used)
            val p = s["problems"]!!.jsonObject
            assertEquals("$id: Name", p["name"]!!.str(), problems.name)
            assertEquals("$id: Port-Probleme", p["ports"]!!.jsonArray.map { it.str() }, problems.ports)
            assertEquals("$id: Pfad-Probleme", p["paths"]!!.jsonArray.map { it.str() }, problems.paths)
            assertEquals("$id: eigene Variablen", p["ownVars"]!!.jsonArray.map { it.str() }, problems.ownVars)
            assertEquals("$id: fehlend", p["missing"]!!.jsonPrimitive.int, problems.missing)
            assertEquals("$id: blockiert", p["blocking"]!!.jsonPrimitive.booleanOrNull, problems.blocking)

            val (built, env) = buildInstall(tpl, form)
            val hardware = edits["hardware"]?.jsonObject?.mapValues { h -> h.value.jsonArray.map { it.str() } }
            val final = if (hardware != null) withHardware(built, hardware) else built
            assertTree("$id: Compose-Datei\n$final", plainOf(s["plain"]!!), YamlDoc.parse(final).toPlain())
            assertEquals("$id: Geheimnisse", s["env"]!!.jsonObject.mapValues { it.value.str() }, env)
            assertEquals("$id: Hardware", s["hardware"]!!.jsonObject.mapValues { h -> h.value.jsonArray.map { it.str() } }, hardwareSelections(final))
        }
    }

    @Test
    fun lanMatchesWeb() {
        val lan = data["lan"]!!.jsonObject
        val out = assignContainerLAN(lan["input"]!!.str(), "app", "home-lan", "192.168.1.50")
        assertTree("LAN\n$out", plainOf(lan["out"]!!), YamlDoc.parse(out).toPlain())
        val back = assignContainerLAN(out, "app", "", "")
        assertTree("LAN zurück\n$back", plainOf(lan["back"]!!), YamlDoc.parse(back).toPlain())
        val a = lan["assignment"]!!.jsonObject
        assertEquals(a["network"]!!.str() to a["address"]!!.str(), lanAssignment(out, "app"))
    }

    @Test
    fun keepsCommentsAnchorsAndLongLines() {
        val text = "# Kopf\nx-env: &env\n  TZ: Europe/Berlin\nservices:\n  a:\n    image: x # Image\n    environment: *env\n    command: \"" + "y".repeat(200) + "\"\n  b:\n    image: z\n    environment: *env\n"
        val doc = YamlDoc.parse(text)
        writeService(doc, "a", readService(doc, "a").copy(env = listOf(Pair("TZ", "UTC"))))
        val out = doc.toString()
        assertTrue(out, out.contains("# Kopf"))
        assertTrue(out, out.contains("# Image"))
        assertTrue(out, out.lines().any { it.length > 200 })
        // b verweist weiter auf den unveränderten Anker.
        assertEquals("Europe/Berlin", record(record(record(YamlDoc.parse(out).toPlain())["services"])["b"])["environment"].let { record(it)["TZ"] })
        assertEquals("UTC", record(record(record(YamlDoc.parse(out).toPlain())["services"])["a"])["environment"].let { record(it)["TZ"] })
    }

    @Test
    fun links() {
        assertEquals("http://192.168.1.5:8096/web", appURL("8096/web", "192.168.1.5"))
        assertEquals("https://nas:8443/", appURL("https://[host]:8443/", "nas"))
        assertEquals("", publicURL("https://user:pw@example.org"))
        assertEquals("", publicURL("javascript:alert(1)"))
        assertEquals("https://example.org/", publicURL("https://example.org"))
        assertEquals(true, validImage("nginx:1.27"))
        assertEquals(false, validImage("nginx:"))
        assertEquals("nginx:1.28", withImageTag("nginx:1.27", "1.28"))
    }
}
