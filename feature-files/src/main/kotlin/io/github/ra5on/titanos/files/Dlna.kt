package io.github.ra5on.titanos.files

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom
import kotlin.concurrent.thread

/** Ein Fernseher oder Lautsprecher im Heimnetz (UPnP MediaRenderer). */
data class Renderer(val name: String, val location: String, val control: String)

/**
 * Abspielen auf Fernseher und Lautsprechern im Heimnetz über DLNA/UPnP
 * (Inhaber 21.11.2026: kein Chromecast, keine Google-Dienste). Gefunden wird
 * per SSDP; das Gerät holt die Datei bei einem kleinen Server auf dem Handy,
 * der sie über die Verbindung der App vom NAS weiterreicht (der Fernseher
 * kennt weder Pin noch Sitzung). Jede Freigabe bekommt eine zufällige,
 * nicht erratbare Adresse, die mit dem Ende der Wiedergabe verfällt.
 */
object Dlna {
    private const val SSDP = "239.255.255.250"
    private const val TARGET = "urn:schemas-upnp-org:service:AVTransport:1"

    /** Sucht ein paar Sekunden nach Geräten mit AVTransport. */
    suspend fun discover(context: Context, seconds: Int = 4): List<Renderer> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("titanos-dlna")?.apply { setReferenceCounted(false); acquire() }
        val found = LinkedHashMap<String, Renderer>()
        try {
            MulticastSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.bind(java.net.InetSocketAddress(0))
                sock.soTimeout = 800
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $TARGET\r\n\r\n".toByteArray()
                val group = InetAddress.getByName(SSDP)
                repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
                val end = System.currentTimeMillis() + seconds * 1000L
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(p.data, 0, p.length)
                    val location = Regex("(?im)^LOCATION:\\s*(\\S+)").find(text)?.groupValues?.get(1) ?: continue
                    if (location in found) continue
                    describe(location)?.let { found[location] = it }
                }
            }
        } finally {
            lock?.release()
        }
        found.values.toList()
    }

    /** Liest die Gerätebeschreibung: Name und Steueradresse von AVTransport. */
    fun describe(location: String): Renderer? = runCatching {
        val xml = (URL(location).openConnection() as HttpURLConnection).run {
            connectTimeout = 2000; readTimeout = 2000
            inputStream.bufferedReader().use { it.readText() }
        }
        val name = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim() ?: location
        val service = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }
            .firstOrNull { it.contains("AVTransport") } ?: return null
        val control = Regex("<controlURL>(.*?)</controlURL>").find(service)?.groupValues?.get(1)?.trim() ?: return null
        Renderer(unescape(name), location, URL(URL(location), control).toString())
    }.getOrNull()

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Schickt eine AVTransport-Aktion (SOAP). */
    fun action(r: Renderer, name: String, args: String): Boolean = runCatching {
        val body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$name xmlns:u="$TARGET"><InstanceID>0</InstanceID>$args</u:$name></s:Body></s:Envelope>"""
        val c = URL(r.control).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 3000
        c.readTimeout = 5000
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPACTION", "\"$TARGET#$name\"")
        c.outputStream.use { it.write(body.toByteArray()) }
        val ok = c.responseCode in 200..299
        c.disconnect()
        ok
    }.getOrDefault(false)

    suspend fun play(r: Renderer, url: String, title: String, mime: String): Boolean = withContext(Dispatchers.IO) {
        val meta = escape(
            """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${escape(title)}</dc:title><upnp:class>${if (mime.startsWith("video")) "object.item.videoItem" else "object.item.audioItem.musicTrack"}</upnp:class><res protocolInfo="http-get:*:$mime:*">${escape(url)}</res></item></DIDL-Lite>""",
        )
        action(r, "Stop", "")
        action(r, "SetAVTransportURI", "<CurrentURI>${escape(url)}</CurrentURI><CurrentURIMetaData>$meta</CurrentURIMetaData>") &&
            action(r, "Play", "<Speed>1</Speed>")
    }

    suspend fun pause(r: Renderer) = withContext(Dispatchers.IO) { action(r, "Pause", "") }
    suspend fun resume(r: Renderer) = withContext(Dispatchers.IO) { action(r, "Play", "<Speed>1</Speed>") }
    suspend fun stop(r: Renderer) = withContext(Dispatchers.IO) { action(r, "Stop", "") }

    /** Die Adresse des Handys im Heimnetz (IPv4), unter der der Fernseher es erreicht. */
    fun localAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
}

/**
 * Kleiner HTTP-Server auf dem Handy für DLNA: gibt genau die freigegebenen
 * Dateien weiter (Range für Spulen), nur unter ihrer zufälligen Adresse,
 * nur GET/HEAD. Läuft, solange eine Freigabe besteht.
 */
object MediaRelay {
    private data class Share(val root: String, val path: String, val mime: String, val size: Long)

    private val shares = java.util.concurrent.ConcurrentHashMap<String, Share>()
    private var server: ServerSocket? = null
    private val random = SecureRandom()

    @Synchronized
    fun share(root: String, path: String, mime: String, size: Long): String? {
        val host = Dlna.localAddress() ?: return null
        val s = server ?: ServerSocket(0, 8).also { srv ->
            server = srv
            thread(name = "titanos-relay", isDaemon = true) { accept(srv) }
        }
        val token = ByteArray(18).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        shares.clear()
        shares[token] = Share(root, path, mime, size)
        val ext = Kinds.ext(path).let { if (it.isEmpty()) "" else ".$it" }
        return "http://$host:${s.localPort}/$token/media$ext"
    }

    @Synchronized
    fun stop() {
        shares.clear()
        runCatching { server?.close() }
        server = null
    }

    private fun accept(srv: ServerSocket) {
        while (!srv.isClosed) {
            val c = runCatching { srv.accept() }.getOrNull() ?: break
            thread(isDaemon = true) { runCatching { serve(c) }; runCatching { c.close() } }
        }
    }

    private fun serve(c: Socket) {
        c.soTimeout = 30_000
        val reader = BufferedReader(InputStreamReader(c.getInputStream()))
        val request = reader.readLine() ?: return
        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
        val (method, target) = request.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val out = c.getOutputStream()
        val token = target.trim('/').substringBefore('/')
        val share = shares[token]
        if (share == null || (method != "GET" && method != "HEAD")) return status(out, 404, "Not Found")
        val range = headers.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')?.trim()
        val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
        val to = range?.removePrefix("bytes=")?.substringAfter('-')?.toLongOrNull()
        val length = if (to != null) to - from + 1 else -1L
        val api = FilesRuntime.access()?.api ?: return status(out, 503, "Unavailable")
        val partial = range != null
        val total = share.size
        val end = (to ?: (total - 1)).coerceAtMost(total - 1)
        val head = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${share.mime}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
            append("transferMode.dlna.org: Streaming\r\ncontentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
            if (total > 0) {
                append("Content-Length: ${end - from + 1}\r\n")
                if (partial) append("Content-Range: bytes $from-$end/$total\r\n")
            }
            append("\r\n")
        }
        out.write(head.toByteArray())
        if (method == "HEAD") return out.flush()
        api.openRange(share.root, share.path, from, length).use { r ->
            r.body.byteStream().use { it.copyTo(out, 64 * 1024) }
        }
        out.flush()
    }

    private fun status(out: OutputStream, code: Int, text: String) {
        out.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }
}
