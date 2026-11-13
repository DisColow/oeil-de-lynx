package fr.oeildelynx

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protocole du direct, sans serveur intermédiaire : le téléphone spectateur ouvre une connexion TCP
 * directement vers le téléphone caméra (réseau local, IPv6 ou port ouvert sur la box).
 *
 * Le secret partagé de 32 octets est transmis une seule fois, par le QR code d’appairage.
 * À chaque connexion, les deux téléphones échangent un nonce aléatoire puis dérivent deux clés
 * AES-256-GCM (une par sens) avec HMAC-SHA256. Toutes les données qui suivent l’échange de nonces
 * (authentification, images, état) sont chiffrées et authentifiées ; un tiers qui ne connaît pas
 * le secret ne peut ni regarder le flux ni se faire passer pour la caméra ou le spectateur.
 *
 * Code pur JVM (pas d’API Android) pour rester testable en test unitaire.
 */
internal object LiveProtocol {
    const val VERSION = 1
    const val DEFAULT_PORT = 47474
    const val KEY_BYTES = 32
    private const val NONCE_BYTES = 16
    private val MAGIC = byteArrayOf('L'.code.toByte(), 'Y'.code.toByte(), 'N'.code.toByte(), 'X'.code.toByte())

    /** Taille maximale d’un enregistrement : protège la mémoire contre un pair malveillant. */
    const val MAX_RECORD_BYTES = 4 * 1024 * 1024

    // Types d’enregistrements chiffrés.
    const val TYPE_AUTH = 0x01      // spectateur → caméra : nom du spectateur
    const val TYPE_HELLO = 0x02     // caméra → spectateur : nom, adresses connues
    const val TYPE_FRAME = 0x10     // caméra → spectateur : rotation (2 octets) + JPEG
    const val TYPE_STATUS = 0x11    // caméra → spectateur : état (enregistrement, caméra)
    const val TYPE_KEEPALIVE = 0x12 // caméra → spectateur : rien à envoyer, connexion vivante
    const val TYPE_BYE = 0x13       // caméra → spectateur : surveillance arrêtée
    // Depuis 1.7.0 : requêtes du spectateur (exposition, galerie distante).
    const val TYPE_REQUEST = 0x20   // spectateur → caméra : `op=…` + paramètres
    const val TYPE_LIST = 0x21      // caméra → spectateur : un clip par ligne (champs encodés)
    const val TYPE_THUMB = 0x22     // caméra → spectateur : miniature JPEG (vide si indisponible)
    const val TYPE_CHUNK = 0x23     // caméra → spectateur : morceau de fichier vidéo
    const val TYPE_END = 0x24       // caméra → spectateur : fin du fichier
    const val TYPE_ERROR = 0x25     // caméra → spectateur : message d’erreur
    // Depuis 1.9.0 : son du direct (G.711 µ-law, 16 kHz mono), envoyé aux spectateurs qui le demandent.
    const val TYPE_AUDIO = 0x14     // caméra → spectateur : échantillons µ-law

    const val AUDIO_SAMPLE_RATE = 16_000

    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    /** Côté spectateur : envoie son nonce, lit celui de la caméra. */
    fun clientHandshake(input: InputStream, output: OutputStream, key: ByteArray): SecureChannel {
        val clientNonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        writeHello(output, clientNonce)
        val serverNonce = readHello(input)
        return SecureChannel(
            input, output,
            sendKey = deriveKey(key, "lynx c2s", clientNonce, serverNonce),
            receiveKey = deriveKey(key, "lynx s2c", clientNonce, serverNonce)
        )
    }

    /** Côté caméra : lit le nonce du spectateur, répond avec le sien. */
    fun serverHandshake(input: InputStream, output: OutputStream, key: ByteArray): SecureChannel {
        val clientNonce = readHello(input)
        val serverNonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        writeHello(output, serverNonce)
        return SecureChannel(
            input, output,
            sendKey = deriveKey(key, "lynx s2c", clientNonce, serverNonce),
            receiveKey = deriveKey(key, "lynx c2s", clientNonce, serverNonce)
        )
    }

    private fun writeHello(output: OutputStream, nonce: ByteArray) {
        output.write(MAGIC); output.write(VERSION); output.write(nonce); output.flush()
    }

    private fun readHello(input: InputStream): ByteArray {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also { data.readFully(it) }
        if (!magic.contentEquals(MAGIC)) throw IOException("Ce n’est pas une caméra Œil de Lynx")
        val version = data.readUnsignedByte()
        if (version != VERSION) throw IOException("Version du direct incompatible ($version)")
        return ByteArray(NONCE_BYTES).also { data.readFully(it) }
    }

    internal fun deriveKey(secret: ByteArray, label: String, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(label.toByteArray(Charsets.UTF_8)); mac.update(clientNonce); mac.update(serverNonce)
        return mac.doFinal()
    }

    /** Petits dictionnaires texte `clé=valeur&…` pour HELLO, STATUS et le code d’appairage. */
    fun encodeFields(fields: Map<String, String>): String = fields.entries.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }

    fun decodeFields(text: String): Map<String, String> = text.split('&').filter { it.contains('=') }.associate { part ->
        val (key, value) = part.split('=', limit = 2)
        URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }
}

/**
 * Codec G.711 µ-law : 8 bits par échantillon au lieu de 16, soit 128 kbit/s à 16 kHz.
 * Sans dépendance ni latence d’encodage, et suffisant pour entendre ce qui se passe dans une pièce.
 */
internal object MuLaw {
    private const val BIAS = 0x84
    private const val CLIP = 32635

    fun encode(samples: ShortArray, count: Int = samples.size): ByteArray = ByteArray(count) { encode(samples[it].toInt()) }

    fun encode(sample: Int): Byte {
        var pcm = sample
        val sign = if (pcm < 0) 0x80 else 0
        if (pcm < 0) pcm = -pcm
        if (pcm > CLIP) pcm = CLIP
        pcm += BIAS
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && pcm and mask == 0) { exponent--; mask = mask shr 1 }
        val mantissa = (pcm shr (exponent + 3)) and 0x0f
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    fun decode(data: ByteArray): ShortArray = ShortArray(data.size) { decode(data[it]) }

    fun decode(value: Byte): Short {
        val u = value.toInt().inv() and 0xff
        val sign = u and 0x80
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0f
        val magnitude = (((mantissa shl 3) + BIAS) shl exponent) - BIAS
        return (if (sign != 0) -magnitude else magnitude).toShort()
    }
}

/** Enregistrement déchiffré : type + contenu. */
internal class LiveRecord(val type: Int, val payload: ByteArray)

/**
 * Canal chiffré : chaque enregistrement est `longueur (4 octets) + AES-GCM(type + contenu)`.
 * Le nonce GCM est un compteur par sens ; la clé étant neuve à chaque connexion, il n’est jamais réutilisé,
 * et un enregistrement rejoué, supprimé ou réordonné fait échouer la vérification.
 */
internal class SecureChannel(input: InputStream, output: OutputStream, sendKey: ByteArray, receiveKey: ByteArray) {
    private val input = DataInputStream(input)
    private val output = DataOutputStream(output)
    private val sendKey = SecretKeySpec(sendKey, 0, 32, "AES")
    private val receiveKey = SecretKeySpec(receiveKey, 0, 32, "AES")
    private val sendCipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val receiveCipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var sendCounter = 0L
    private var receiveCounter = 0L

    @Synchronized fun send(type: Int, payload: ByteArray = ByteArray(0)) {
        sendCipher.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, nonce(sendCounter++)))
        val plain = ByteArray(payload.size + 1)
        plain[0] = type.toByte()
        System.arraycopy(payload, 0, plain, 1, payload.size)
        val encrypted = sendCipher.doFinal(plain)
        output.writeInt(encrypted.size)
        output.write(encrypted)
        output.flush()
    }

    /** Lit le prochain enregistrement ; lève une IOException si la connexion ou le déchiffrement échoue. */
    fun receive(): LiveRecord {
        val size = try { input.readInt() } catch (error: EOFException) { throw EOFException("Connexion fermée par l’autre téléphone") }
        if (size < 17 || size > LiveProtocol.MAX_RECORD_BYTES) throw IOException("Enregistrement invalide ($size octets)")
        val encrypted = ByteArray(size).also { input.readFully(it) }
        val plain = try {
            receiveCipher.init(Cipher.DECRYPT_MODE, receiveKey, GCMParameterSpec(128, nonce(receiveCounter++)))
            receiveCipher.doFinal(encrypted)
        } catch (error: Exception) {
            throw IOException("Données refusées : clé d’appairage incorrecte ou flux altéré", error)
        }
        return LiveRecord(plain[0].toInt() and 0xff, plain.copyOfRange(1, plain.size))
    }

    private fun nonce(counter: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
}

/**
 * Contenu du QR code d’appairage : identité de la caméra, secret partagé et adresses où la joindre.
 * Format : `oeildelynx://pair?v=1&id=…&n=…&k=…&h=hôte:port,[ipv6]:port`.
 */
internal data class PairingCode(val id: String, val name: String, val key: ByteArray, val hosts: List<String>) {
    fun encode(): String = PREFIX + LiveProtocol.encodeFields(linkedMapOf(
        "v" to LiveProtocol.VERSION.toString(),
        "id" to id,
        "n" to name,
        "k" to java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key),
        "h" to hosts.joinToString(",")
    ))

    override fun equals(other: Any?) = other is PairingCode && id == other.id && name == other.name &&
        key.contentEquals(other.key) && hosts == other.hosts
    override fun hashCode() = id.hashCode()

    companion object {
        const val PREFIX = "oeildelynx://pair?"

        /** Renvoie null si le texte n’est pas un code d’appairage valide. */
        fun parse(text: String): PairingCode? {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX)) return null
            return try {
                val fields = LiveProtocol.decodeFields(trimmed.removePrefix(PREFIX))
                if (fields["v"] != LiveProtocol.VERSION.toString()) return null
                val key = java.util.Base64.getUrlDecoder().decode(fields["k"] ?: return null)
                val id = fields["id"]?.takeIf { it.isNotBlank() } ?: return null
                if (key.size != LiveProtocol.KEY_BYTES) return null
                val hosts = fields["h"].orEmpty().split(',').map { it.trim() }.filter { parseHost(it) != null }
                PairingCode(id, fields["n"]?.takeIf { it.isNotBlank() } ?: "Caméra", key, hosts)
            } catch (_: IllegalArgumentException) { null }
        }

        /** Découpe `hôte:port` ou `[ipv6]:port`. */
        fun parseHost(entry: String): Pair<String, Int>? {
            val host: String; val portText: String
            if (entry.startsWith("[")) {
                val end = entry.indexOf("]:")
                if (end < 0) return null
                host = entry.substring(1, end); portText = entry.substring(end + 2)
            } else {
                val colon = entry.lastIndexOf(':')
                if (colon <= 0 || entry.indexOf(':') != colon) return null
                host = entry.substring(0, colon); portText = entry.substring(colon + 1)
            }
            val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return if (host.isBlank()) null else host to port
        }

        fun formatHost(host: String, port: Int) = if (host.contains(':')) "[$host]:$port" else "$host:$port"
    }
}
