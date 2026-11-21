package io.github.ra5on.titanos.agent.figure

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Die arbeitende Figur des KI-Agenten: drei flache Punkte, die sich im Raum
// bewegen – Zahl für Zahl aus web/src/lib/agent-figures.ts übernommen. Eine
// Figur gibt für eine Zeit die Orte der drei Punkte in halben Breiten der
// Figur (-1 … 1, Mitte 0) und eine Tiefe (-1 hinten … 1 vorn). Alle drei
// Punkte haben immer dieselbe Größe und eine Farbe; die Tiefe zeigt sich
// nur in der Bewegung und darin, was was verdeckt.

data class Spot(val x: Double, val y: Double, val z: Double)

private typealias Vec = DoubleArray
private typealias Phase = (Double) -> List<Vec>

/** Durchmesser jedes Punktes als Anteil der Breite der Figur. */
const val SPHERE = 0.24
/** So weit geht die Mitte eines Punktes höchstens von der Mitte der Figur. */
const val REACH = 1 - SPHERE

private const val TAU = PI * 2
private const val THIRD = TAU / 3

private fun v(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)

private fun rotX(p: Vec, a: Double): Vec {
    val c = cos(a); val s = sin(a)
    return v(p[0], p[1] * c - p[2] * s, p[1] * s + p[2] * c)
}
private fun rotY(p: Vec, a: Double): Vec {
    val c = cos(a); val s = sin(a)
    return v(p[0] * c + p[2] * s, p[1], -p[0] * s + p[2] * c)
}
private fun rotZ(p: Vec, a: Double): Vec {
    val c = cos(a); val s = sin(a)
    return v(p[0] * c - p[1] * s, p[0] * s + p[1] * c, p[2])
}
private inline fun each(f: (Int) -> Vec): List<Vec> = listOf(f(0), f(1), f(2))

/** Ein Ring, der vor- und zurückkippt und sich dreht. */
private val ring: Phase = { t ->
    each { n ->
        val a = t * 1.9 + n * THIRD
        rotZ(rotX(v(cos(a) * 0.62, 0.0, sin(a) * 0.62), 1.05 + sin(t * 0.5) * 0.3), t * 0.4)
    }
}

/** Eine liegende Acht (Lemniskate von Gerono), die um die Senkrechte schwingt. */
private val eight: Phase = { t ->
    each { n ->
        val u = t * 1.7 + n * THIRD
        rotX(rotY(v(sin(u) * 0.66, sin(2 * u) * 0.3, cos(u) * 0.34), sin(t * 0.45) * 0.7), 0.3 + sin(t * 0.33) * 0.15)
    }
}

/** Eine Schraube: die Punkte steigen um die Senkrechte, die Schraube hebt und senkt sich. */
private val helix: Phase = { t ->
    each { n ->
        val a = t * 2.2 + n * 2.1
        rotX(v(cos(a) * 0.55, (n - 1) * 0.36 + sin(t * 0.8) * 0.14, sin(a) * 0.55), 0.2)
    }
}

/** Ein gleichseitiges Dreieck, das sich überschlägt. */
private val tumble: Phase = { t ->
    each { n ->
        val a = n * THIRD + PI / 2
        rotZ(rotY(rotX(v(cos(a) * 0.6, sin(a) * 0.6, 0.0), t * 1.6), sin(t * 0.7) * 0.25), t * 0.5)
    }
}

/** Zusammen und auseinander: die Punkte laufen ein, bis sie sich berühren, und fliegen wieder aus. */
private val gather: Phase = { t ->
    each { n ->
        val k = (0.5 - 0.5 * cos(t * 1.5)).pow(1.5)
        val r = (2 * SPHERE) / sqrt(3.0) + 0.42 * k
        val a = t * 1.4 + n * THIRD
        rotY(rotX(v(cos(a) * r, sin(a) * r, 0.0), sin(t * 0.7) * 0.6), cos(t * 0.6) * 0.5)
    }
}

/** Ein kleines Planetensystem: einer in der Mitte, einer auf enger, einer auf weiter Bahn. */
private val planets: Phase = { t ->
    val spin = t * 0.25
    val sun = v(sin(t * 0.7) * 0.06, cos(t * 0.9) * 0.05, 0.0)
    fun orbit(r: Double, a: Double, tilt: Double) = rotZ(rotX(v(cos(a) * r, 0.0, sin(a) * r), tilt), spin)
    val inner = orbit(0.45, t * 2.6, 0.75)
    val outer = orbit(0.66, -t * 1.5 + 2, 1.15)
    listOf(v(sun[0] + inner[0], sun[1] + inner[1], inner[2]), sun, v(sun[0] + outer[0], sun[1] + outer[1], outer[2]))
}

/** Die Phasen der Choreografie; der Ring zuerst, damit das Standbild (Zeit 0) drei Punkte gut auseinander zeigt. */
private val PHASES: List<Phase> = listOf(ring, eight, helix, tumble, gather, planets)

/** Länge eines Blocks in Sekunden: jede Phase einmal, 3,5 bis 4,5 s. */
const val BLOCK = 24.0
/** Die letzten Sekunden jeder Phase gleiten in die nächste. */
const val BLEND = 1.0
private val LENGTHS = doubleArrayOf(3.5, 4.5, 4.0, 3.5, 4.5, 4.0)

/** mulberry32 wie im Web (Int-Arithmetik wie Math.imul, >>> als ushr). */
private fun random(seed: Int): () -> Double {
    var a = seed
    return {
        a += 0x6d2b79f5
        var r = (a xor (a ushr 15)) * (1 or a)
        r = (r + (r xor (r ushr 7)) * (61 or r)) xor r
        ((r xor (r ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
    }
}

private fun shuffled(b: Int, rnd: () -> Double): IntArray {
    val order = IntArray(PHASES.size) { it }
    for (i in order.size - 1 downTo 1) {
        val j = floor(rnd() * (i + 1)).toInt()
        val x = order[i]; order[i] = order[j]; order[j] = x
    }
    if (b == 0) {
        val at = order.indexOf(0)
        val x = order[0]; order[0] = order[at]; order[at] = x
    }
    return order
}

/** Ein gut gemischter Startwert je Block (Finalizer von MurmurHash3). */
private fun seed(b: Int): () -> Double {
    var h = (b + 1) * 0x9e3779b1.toInt()
    h = (h xor (h ushr 16)) * 0x85ebca6b.toInt()
    h = (h xor (h ushr 13)) * 0xc2b2ae35.toInt()
    return random(h xor (h ushr 16))
}

private class Block(val order: IntArray, val lengths: DoubleArray)

private val blocks = HashMap<Int, Block>()

private fun block(b: Int): Block = synchronized(blocks) {
    blocks.getOrPut(b) {
        val rnd = seed(b)
        val order = shuffled(b, rnd)
        if (b != 0 && order[0] == shuffled(b - 1, seed(b - 1)).last()) {
            val x = order[0]; order[0] = order[1]; order[1] = x
        }
        val shift = floor(rnd() * LENGTHS.size).toInt()
        Block(order, DoubleArray(LENGTHS.size) { i -> LENGTHS[(i + shift) % LENGTHS.size] })
    }
}

/** Gleiten: langsam an beiden Enden (smoothstep). */
fun ease(k: Double): Double {
    val c = k.coerceIn(0.0, 1.0)
    return c * c * (3 - 2 * c)
}

private data class Segment(val phase: Int, val start: Double, val length: Double)

private fun segment(k: Int): Segment {
    val b = Math.floorDiv(k, PHASES.size)
    val i = k - b * PHASES.size
    val blk = block(b)
    var start = b * BLOCK
    for (n in 0 until i) start += blk.lengths[n]
    return Segment(blk.order[i], start, blk.lengths[i])
}

private val PERMS = listOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))

/**
 * Welcher Punkt seiner Phase jeder der drei Punkte im Abschnitt k folgt: bei
 * jedem Gleiten nehmen sie die nächsten Orte der neuen Phase, damit sich ihre
 * Wege nicht kreuzen. Hängt von jedem Gleiten davor ab, daher einmal von
 * vorn ausgerechnet und behalten.
 */
private val roles = arrayListOf(intArrayOf(0, 1, 2))

private fun rolesOf(k: Int): IntArray = synchronized(roles) {
    var n = roles.size
    while (n <= k) {
        val from = segment(n - 1)
        val to = segment(n)
        val at = to.start - BLEND
        val before = roles[n - 1]
        val all = PHASES[from.phase](at)
        val a = List(3) { all[before[it]] }
        val b = PHASES[to.phase](at)
        var best = PERMS[0]
        var least = Double.MAX_VALUE
        for (perm in PERMS) {
            var sum = 0.0
            for (i in 0 until 3) {
                val p = a[i]; val q = b[perm[i]]
                sum += hypot(hypot(p[0] - q[0], p[1] - q[1]), p[2] - q[2])
            }
            if (sum < least) { least = sum; best = perm }
        }
        roles.add(best)
        n++
    }
    roles[k]
}

data class PhaseAt(val now: Int, val next: Int, val glide: Double, val k: Int)

/** Welche Phase zur Zeit t läuft, welche danach kommt und wie weit das Gleiten ist. */
fun phaseAt(t: Double): PhaseAt {
    var k = maxOf(0, floor(t / BLOCK).toInt()) * PHASES.size
    var seg = segment(k)
    while (t >= seg.start + seg.length) seg = segment(++k)
    return PhaseAt(seg.phase, segment(k + 1).phase, ease((t - (seg.start + seg.length - BLEND)) / BLEND), k)
}

/** Die Choreografie: wo die drei Punkte zur Zeit t stehen (Sekunden ab 0). */
fun dance(time: Double): List<Spot> {
    val t = maxOf(0.0, time)
    val (now, next, glide, k) = phaseAt(t)
    val role = rolesOf(k)
    val all = PHASES[now](t)
    val a = List(3) { all[role[it]] }
    var b = a
    if (glide > 0) {
        val nextAll = PHASES[next](t)
        val r2 = rolesOf(k + 1)
        b = List(3) { nextAll[r2[it]] }
    }
    return List(3) { n ->
        val p = a[n]; val q = b[n]
        fun mix(i: Int) = p[i] + (q[i] - p[i]) * glide
        Spot(mix(0), mix(1), (mix(2) / REACH).coerceIn(-1.0, 1.0))
    }
}

/**
 * Was der Lauf gerade tut (BusyKind im Web): '' denkt, sonst Netz, Dateien,
 * Schreiben, Zuhause, Schalten, Code, System. Jede Art zeigt dieselben Punkte
 * im selben Tempo; nur das Wort daneben sagt, was geschieht.
 */
object BusyKind {
    const val NONE = ""
    const val WEB = "web"
    const val FILES = "files"
    const val WRITE = "write"
    const val HOME = "home"
    const val SWITCH = "switch"
    const val CODE = "code"
    const val SYSTEM = "system"

    private val write = Regex("^(file_write|files_write_text|document_(create|edit|write)|memory_save|skill_save)$")
    private val files = Regex("^(files?_|document_)")
    private val system = Regex("^(docker_|container_|app_|vm_|lxc_|storage_|system_|hardware_|backup_|logs_|shares_|updates?_|plugins?_|network_)")

    /** busyKind(tool) des Webs. */
    fun of(tool: String?): String = when {
        tool.isNullOrEmpty() -> NONE
        tool == "web_search" || tool == "web_read" -> WEB
        tool == "code_run" -> CODE
        tool == "ha_call" || tool == "ha_automation_save" -> SWITCH
        tool.startsWith("ha_") -> HOME
        write.containsMatchIn(tool) -> WRITE
        files.containsMatchIn(tool) -> FILES
        system.containsMatchIn(tool) -> SYSTEM
        else -> NONE
    }
}

/** Wo die Punkte nach [clock] Einheiten Bewegung stehen (figureAt: alle Arten gleich, Tempo 1). */
fun figureAt(@Suppress("UNUSED_PARAMETER") kind: String, clock: Double): List<Spot> = dance(clock)
