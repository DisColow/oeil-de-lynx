package fr.oeildelynx

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Client UPnP IGD minimal : demande à la box internet d’ouvrir un port TCP vers le téléphone caméra
 * et lit son adresse IPv4 publique. Tout se passe sur le réseau local, sans service externe.
 *
 * Les requêtes HTTP sont écrites à la main sur un socket TCP : la box ne parle qu’en HTTP clair,
 * et cela évite d’autoriser le trafic HTTP clair pour toute l’application.
 */
internal object LiveUpnp {
    data class Gateway(val controlUrl: String, val serviceType: String)

    /** Code d’erreur UPnP renvoyé par la box (ex. 718 = port déjà redirigé vers un autre appareil). */
    class UpnpError(val code: Int?, message: String) : IOException(message)

    private val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
        "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
        "urn:schemas-upnp-org:service:WANIPConnection:1",
        "urn:schemas-upnp-org:service:WANPPPConnection:1"
    )

    /** Cherche la box par SSDP puis lit sa description pour trouver le service de redirection de ports. */
    fun discover(timeoutMs: Int = 2500): Gateway? {
        val locations = linkedSetOf<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 400
            val group = InetAddress.getByName("239.255.255.250")
            for (target in SEARCH_TARGETS) {
                val request = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n"
                val bytes = request.toByteArray(Charsets.US_ASCII)
                socket.send(DatagramPacket(bytes, bytes.size, group, 1900))
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    parseLocation(String(packet.data, 0, packet.length, Charsets.US_ASCII))?.let { locations += it }
                } catch (_: SocketTimeoutException) {
                    if (locations.isNotEmpty()) break
                }
            }
        }
        for (location in locations) {
            try {
                val response = httpRequest(location, "GET", emptyMap(), null)
                parseGateway(response.body, location)?.let { return it }
            } catch (_: IOException) { }
        }
        return null
    }

    fun externalIp(gateway: Gateway): String? {
        val body = soap(gateway, "GetExternalIPAddress", emptyList()).body
        return xmlValue(body, "NewExternalIPAddress")?.takeIf { it.isNotBlank() }
    }

    /**
     * Ouvre `externalPort` sur la box vers `internalPort` du téléphone. Renvoie l’IP locale du téléphone
     * vue par la box (celle du socket utilisé pour lui parler).
     */
    fun addPortMapping(gateway: Gateway, externalPort: Int, internalPort: Int, leaseSeconds: Int): String {
        val localIp = localAddressTowards(gateway.controlUrl)
        val response = soap(gateway, "AddPortMapping", listOf(
            "NewRemoteHost" to "",
            "NewExternalPort" to externalPort.toString(),
            "NewProtocol" to "TCP",
            "NewInternalPort" to internalPort.toString(),
            "NewInternalClient" to localIp,
            "NewEnabled" to "1",
            "NewPortMappingDescription" to "Oeil de Lynx",
            "NewLeaseDuration" to leaseSeconds.toString()
        ))
        if (response.status != 200) throw UpnpError(xmlValue(response.body, "errorCode")?.toIntOrNull(), "La box a refusé la redirection (HTTP ${response.status})")
        return localIp
    }

    fun deletePortMapping(gateway: Gateway, externalPort: Int) {
        soap(gateway, "DeletePortMapping", listOf("NewRemoteHost" to "", "NewExternalPort" to externalPort.toString(), "NewProtocol" to "TCP"))
    }

    private fun soap(gateway: Gateway, action: String, arguments: List<Pair<String, String>>): HttpResponse {
        val args = arguments.joinToString("") { (name, value) -> "<$name>${escapeXml(value)}</$name>" }
        val body = "<?xml version=\"1.0\"?>\r\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"${gateway.serviceType}\">$args</u:$action></s:Body></s:Envelope>\r\n"
        return httpRequest(gateway.controlUrl, "POST", mapOf(
            "Content-Type" to "text/xml; charset=\"utf-8\"",
            "SOAPAction" to "\"${gateway.serviceType}#$action\""
        ), body.toByteArray(Charsets.UTF_8))
    }

    private fun localAddressTowards(url: String): String {
        val uri = URI(url)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, if (uri.port > 0) uri.port else 80), 3000)
            val address = socket.localAddress
            if (address !is Inet4Address) throw IOException("La box n’est pas joignable en IPv4")
            return address.hostAddress ?: throw IOException("Adresse locale inconnue")
        }
    }

    class HttpResponse(val status: Int, val body: String)

    private fun httpRequest(url: String, method: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        val uri = URI(url)
        if (uri.scheme != "http") throw IOException("Adresse UPnP non prise en charge : $url")
        val port = if (uri.port > 0) uri.port else 80
        val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, port), 3000)
            socket.soTimeout = 5000
            val request = StringBuilder("$method $path HTTP/1.0\r\nHost: ${uri.host}:$port\r\nConnection: close\r\n")
            headers.forEach { (name, value) -> request.append("$name: $value\r\n") }
            if (body != null) request.append("Content-Length: ${body.size}\r\n")
            request.append("\r\n")
            socket.getOutputStream().apply { write(request.toString().toByteArray(Charsets.US_ASCII)); body?.let { write(it) }; flush() }
            val raw = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            val input = socket.getInputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                raw.write(buffer, 0, read)
                if (raw.size() > 512 * 1024) throw IOException("Réponse de la box trop longue")
            }
            return parseHttpResponse(raw.toByteArray())
        }
    }

    internal fun parseHttpResponse(raw: ByteArray): HttpResponse {
        val text = String(raw, Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n")
        if (split < 0) throw IOException("Réponse HTTP incomplète")
        val head = text.substring(0, split)
        val status = head.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Statut HTTP invalide")
        var bodyBytes = raw.copyOfRange(split + 4, raw.size)
        if (Regex("(?im)^transfer-encoding:\\s*chunked").containsMatchIn(head)) bodyBytes = dechunk(bodyBytes)
        return HttpResponse(status, String(bodyBytes, Charsets.UTF_8))
    }

    private fun dechunk(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var position = 0
        while (position < data.size) {
            var lineEnd = position
            while (lineEnd + 1 < data.size && !(data[lineEnd] == '\r'.code.toByte() && data[lineEnd + 1] == '\n'.code.toByte())) lineEnd++
            val size = String(data, position, lineEnd - position, Charsets.US_ASCII).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = lineEnd + 2
            if (start + size > data.size) break
            out.write(data, start, size)
            position = start + size + 2
        }
        return out.toByteArray()
    }

    /** Extrait l’en-tête LOCATION d’une réponse SSDP. */
    internal fun parseLocation(response: String): String? =
        Regex("(?im)^location:\\s*(\\S+)\\s*$").find(response)?.groupValues?.get(1)

    /** Trouve le service WANIPConnection (ou WANPPPConnection) et résout son URL de contrôle. */
    internal fun parseGateway(xml: String, location: String): Gateway? {
        val base = xmlValue(xml, "URLBase")?.takeIf { it.isNotBlank() } ?: location
        val services = Regex("(?is)<service>(.*?)</service>").findAll(xml).map { it.groupValues[1] }.toList()
        for (wanted in listOf("WANIPConnection", "WANPPPConnection")) {
            for (service in services) {
                val type = xmlValue(service, "serviceType") ?: continue
                if (!type.contains(":service:$wanted:")) continue
                val control = xmlValue(service, "controlURL") ?: continue
                return Gateway(URI(base).resolve(control).toString(), type)
            }
        }
        return null
    }

    internal fun xmlValue(xml: String, tag: String): String? =
        Regex("(?is)<(?:[A-Za-z0-9_]+:)?$tag(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?$tag>").find(xml)?.groupValues?.get(1)?.trim()

    private fun escapeXml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Adresses IPv4 privées ou partagées (CGNAT) : la box elle-même est derrière un autre routeur. */
    fun isPrivateIpv4(address: String): Boolean {
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 || parts[0] == 127 || (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168) || (parts[0] == 100 && parts[1] in 64..127) || parts[0] == 0 ||
            (parts[0] == 169 && parts[1] == 254)
    }
}
