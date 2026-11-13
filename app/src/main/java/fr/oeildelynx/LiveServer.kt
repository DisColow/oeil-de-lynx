package fr.oeildelynx

import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serveur du direct, hébergé par le téléphone caméra. Les spectateurs se connectent directement
 * (Wi-Fi local, IPv6 ou port redirigé par la box) : aucune image ne transite par un serveur tiers.
 *
 * Chaque spectateur a son propre thread d’envoi et ne garde que la dernière image : si sa connexion
 * est lente, les images intermédiaires sont sautées au lieu de s’accumuler en mémoire.
 */
/** Accès aux clips de la caméra pour la galerie distante. */
internal interface LiveGallery {
    /** Clips récents, chacun décrit par `id`, `name`, `start` (ms), `dur` (ms), `size` (octets). */
    fun list(): List<Map<String, String>>
    fun thumbnail(id: Long): ByteArray?
    fun open(id: Long): InputStream?
    /** Taille réelle du fichier en octets (0 si inconnue). */
    fun size(id: Long): Long = 0
}

internal class LiveServer(
    private val port: Int,
    private val secretProvider: () -> ByteArray?,
    private val helloProvider: () -> Map<String, String>,
    private val onViewersChanged: (Int) -> Unit,
    /** Commande d’un spectateur (ex. `op=exposure&value=2`), appelée hors du thread principal. */
    private val onRequest: (Map<String, String>) -> Unit = {},
    private val gallery: LiveGallery? = null,
    /** Vrai dès qu’au moins un spectateur veut le son : le micro du direct ne tourne que dans ce cas. */
    private val onAudioDemandChanged: (Boolean) -> Unit = {}
) {
    @Volatile private var audioDemand = false

    fun hasAudioListeners() = viewers.any { it.wantsAudio }

    /** Morceau de son µ-law à diffuser aux spectateurs qui l’écoutent. */
    fun publishAudio(chunk: ByteArray) = viewers.forEach { if (it.wantsAudio) it.offerAudio(chunk) }

    @Synchronized private fun refreshAudioDemand() {
        val demand = hasAudioListeners()
        if (demand != audioDemand) { audioDemand = demand; onAudioDemandChanged(demand) }
    }
    private val gallerySockets = CopyOnWriteArrayList<Socket>()
    private var serverSocket: ServerSocket? = null
    private val viewers = CopyOnWriteArrayList<Viewer>()
    private val pendingHandshakes = AtomicInteger()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var running = false
    @Volatile private var lastStatus: Map<String, String> = emptyMap()

    /** Spectateurs qui regardent l’image (les abonnés aux seules alertes ne comptent pas). */
    val viewerCount get() = viewers.count { !it.alertsOnly }
    fun hasViewers() = viewers.any { !it.alertsOnly }

    /** Ouvre le port d’écoute (IPv4 et IPv6). Renvoie faux si le port est déjà pris. */
    fun start(): Boolean {
        if (running) return true
        val socket = try {
            ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(port), 8) }
        } catch (error: IOException) {
            Log.e(TAG, "Live server cannot listen on $port", error); return false
        }
        serverSocket = socket
        running = true
        Thread({ acceptLoop(socket) }, "lynx-live-accept").start()
        // Un envoi bloqué (réseau coupé sans fermeture TCP) est interrompu en fermant le socket.
        watchdog.scheduleWithFixedDelay({
            val now = SystemClock.elapsedRealtime()
            viewers.forEach { viewer -> if (viewer.sendingSince != 0L && now - viewer.sendingSince > SEND_TIMEOUT_MS) viewer.close() }
        }, 5, 5, TimeUnit.SECONDS)
        Log.i(TAG, "Live server listening on port $port")
        return true
    }

    fun stop(sayGoodbye: Boolean) {
        running = false
        try { serverSocket?.close() } catch (_: IOException) { }
        serverSocket = null
        viewers.forEach { if (sayGoodbye) it.goodbye() else it.close() }
        gallerySockets.forEach { try { it.close() } catch (_: IOException) { } }
        watchdog.shutdownNow()
    }

    /** Déconnecte tous les spectateurs (ex. après un changement de clé d’appairage). */
    fun disconnectAll() = viewers.forEach { it.close() }

    fun publishFrame(jpeg: ByteArray, rotationDegrees: Int) {
        if (!hasViewers()) return
        val payload = ByteArray(jpeg.size + 2)
        payload[0] = (rotationDegrees shr 8).toByte(); payload[1] = rotationDegrees.toByte()
        System.arraycopy(jpeg, 0, payload, 2, jpeg.size)
        viewers.forEach { if (!it.alertsOnly) it.offerFrame(payload) }
    }

    fun publishStatus(status: Map<String, String>) {
        lastStatus = status
        val payload = LiveProtocol.encodeFields(status).toByteArray(Charsets.UTF_8)
        viewers.forEach { it.offerStatus(payload) }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            if (pendingHandshakes.get() >= MAX_PENDING || viewers.size >= MAX_VIEWERS + MAX_ALERT_LISTENERS) {
                Log.w(TAG, "Live connection refused: too many viewers")
                try { socket.close() } catch (_: IOException) { }
                continue
            }
            pendingHandshakes.incrementAndGet()
            Thread({ handle(socket) }, "lynx-live-viewer").start()
        }
    }

    private fun handle(socket: Socket) {
        val viewer: Viewer
        try {
            val secret = secretProvider() ?: throw IOException("Aucun appairage")
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val channel = LiveProtocol.serverHandshake(
                BufferedInputStream(socket.getInputStream()), BufferedOutputStream(socket.getOutputStream(), 64 * 1024), secret
            )
            val auth = channel.receive()
            if (auth.type != LiveProtocol.TYPE_AUTH) throw IOException("Authentification attendue")
            // Depuis 1.5.1 : `name=…&mode=alerts|live` ; avant : le nom seul.
            val text = String(auth.payload, Charsets.UTF_8)
            val fields = if (text.contains('=')) LiveProtocol.decodeFields(text) else mapOf("name" to text)
            if (fields["mode"] == "gallery") {
                if (gallery == null || gallerySockets.size >= MAX_GALLERY) throw IOException("Galerie indisponible")
                channel.send(LiveProtocol.TYPE_HELLO, LiveProtocol.encodeFields(helloProvider()).toByteArray(Charsets.UTF_8))
                pendingHandshakes.decrementAndGet()
                serveGallery(socket, channel, gallery)
                return
            }
            val alertsOnly = fields["mode"] == "alerts"
            val sameKind = viewers.count { it.alertsOnly == alertsOnly }
            if (sameKind >= if (alertsOnly) MAX_ALERT_LISTENERS else MAX_VIEWERS) throw IOException("Trop de spectateurs")
            channel.send(LiveProtocol.TYPE_HELLO, LiveProtocol.encodeFields(helloProvider()).toByteArray(Charsets.UTF_8))
            viewer = Viewer(socket, channel, fields["name"].orEmpty().take(60), alertsOnly)
            viewer.wantsAudio = !alertsOnly && fields["audio"] == "1"
        } catch (error: Exception) {
            // Mauvaise clé, scanner de ports ou connexion coupée : rien n’est envoyé en clair.
            Log.w(TAG, "Live handshake rejected from ${socket.inetAddress?.hostAddress}: ${error.message}")
            try { socket.close() } catch (_: IOException) { }
            pendingHandshakes.decrementAndGet()
            return
        }
        pendingHandshakes.decrementAndGet()
        if (!running) { viewer.close(); return }
        viewers += viewer
        Log.i(TAG, "Live viewer connected: ${viewer.name} from ${socket.inetAddress?.hostAddress}")
        onViewersChanged(viewerCount)
        refreshAudioDemand()
        if (lastStatus.isNotEmpty()) viewer.offerStatus(LiveProtocol.encodeFields(lastStatus).toByteArray(Charsets.UTF_8))
        // Les envois sont surveillés par le chien de garde ; les lectures attendent sans limite les commandes.
        try { socket.soTimeout = 0 } catch (_: IOException) { }
        Thread({ viewer.readLoop() }, "lynx-live-reader").start()
        try { viewer.sendLoop() } finally {
            viewer.close()
            viewers -= viewer
            Log.i(TAG, "Live viewer disconnected: ${viewer.name}")
            onViewersChanged(viewerCount)
            refreshAudioDemand()
        }
    }

    private inner class Viewer(private val socket: Socket, private val channel: SecureChannel, val name: String, val alertsOnly: Boolean) {
        // Abonné aux alertes : connexion longue durée, signe de vie espacé pour ménager batterie et forfait.
        private val keepaliveMs = if (alertsOnly) ALERT_KEEPALIVE_MS else KEEPALIVE_MS
        private val lock = Object()
        private var frame: ByteArray? = null
        private var status: ByteArray? = null
        private var bye = false
        // Son : petite file (≈ 1 s) ; au-delà, les morceaux les plus anciens sont jetés pour rester en direct.
        private val audio = ArrayDeque<ByteArray>()
        @Volatile var wantsAudio = false
        @Volatile var sendingSince = 0L

        fun offerFrame(payload: ByteArray) = synchronized(lock) { frame = payload; lock.notifyAll() }
        fun offerStatus(payload: ByteArray) = synchronized(lock) { status = payload; lock.notifyAll() }
        fun goodbye() = synchronized(lock) { bye = true; lock.notifyAll() }
        fun offerAudio(chunk: ByteArray) = synchronized(lock) {
            audio.addLast(chunk)
            while (audio.size > MAX_AUDIO_CHUNKS) audio.removeFirst()
            lock.notifyAll()
        }

        fun sendLoop() {
            try {
                while (true) {
                    var nextFrame: ByteArray?; var nextStatus: ByteArray?; var sayBye: Boolean
                    val sounds: List<ByteArray>
                    synchronized(lock) {
                        if (frame == null && status == null && !bye && audio.isEmpty()) lock.wait(keepaliveMs)
                        nextFrame = frame; nextStatus = status; sayBye = bye
                        frame = null; status = null
                        sounds = audio.toList(); audio.clear()
                    }
                    if (!running && !sayBye) return
                    sendingSince = SystemClock.elapsedRealtime()
                    if (sayBye) { channel.send(LiveProtocol.TYPE_BYE); return }
                    nextStatus?.let { channel.send(LiveProtocol.TYPE_STATUS, it) }
                    // Le son passe avant l’image : une coupure s’entend plus qu’une image sautée.
                    sounds.forEach { channel.send(LiveProtocol.TYPE_AUDIO, it) }
                    val image = nextFrame
                    if (image != null) channel.send(LiveProtocol.TYPE_FRAME, image)
                    else if (nextStatus == null && sounds.isEmpty()) channel.send(LiveProtocol.TYPE_KEEPALIVE)
                    sendingSince = 0L
                }
            } catch (_: Exception) {
                // Spectateur parti ou réseau coupé.
            }
        }

        /** Commandes du spectateur (anciennes versions : rien n’arrive, le thread attend la fermeture). */
        fun readLoop() {
            try {
                while (true) {
                    val record = channel.receive()
                    if (record.type == LiveProtocol.TYPE_REQUEST) {
                        val request = LiveProtocol.decodeFields(String(record.payload, Charsets.UTF_8))
                        // Son activé ou coupé par ce spectateur : géré ici, propre à sa connexion.
                        if (request["op"] == "audio") {
                            wantsAudio = !alertsOnly && request["value"] == "1"
                            if (!wantsAudio) synchronized(lock) { audio.clear() }
                            refreshAudioDemand()
                            continue
                        }
                        try { onRequest(request) } catch (error: Exception) { Log.w(TAG, "Live request failed", error) }
                    }
                }
            } catch (_: Exception) { close() }
        }

        fun close() { try { socket.close() } catch (_: IOException) { } }
    }

    /** Galerie distante : une requête à la fois (liste, miniature, fichier en morceaux). */
    private fun serveGallery(socket: Socket, channel: SecureChannel, gallery: LiveGallery) {
        gallerySockets += socket
        Log.i(TAG, "Remote gallery opened from ${socket.inetAddress?.hostAddress}")
        try {
            socket.soTimeout = GALLERY_IDLE_MS
            while (running) {
                val record = channel.receive()
                if (record.type != LiveProtocol.TYPE_REQUEST) continue
                val request = LiveProtocol.decodeFields(String(record.payload, Charsets.UTF_8))
                val id = request["id"]?.toLongOrNull()
                when (request["op"]) {
                    "list" -> channel.send(LiveProtocol.TYPE_LIST,
                        gallery.list().joinToString("\n") { LiveProtocol.encodeFields(it) }.toByteArray(Charsets.UTF_8))
                    "thumb" -> channel.send(LiveProtocol.TYPE_THUMB, id?.let { gallery.thumbnail(it) } ?: ByteArray(0))
                    "file" -> {
                        val input = id?.let { gallery.open(it) }
                        if (input == null) { channel.send(LiveProtocol.TYPE_ERROR, "Vidéo introuvable".toByteArray()); continue }
                        // Taille lue sur le fichier : celle de la médiathèque peut valoir 0 pour un clip récent.
                        channel.send(LiveProtocol.TYPE_FILE_SIZE, gallery.size(id).toString().toByteArray())
                        input.use {
                            val buffer = ByteArray(CHUNK_BYTES)
                            while (true) {
                                val read = it.read(buffer)
                                if (read < 0) break
                                if (read > 0) channel.send(LiveProtocol.TYPE_CHUNK, buffer.copyOf(read))
                            }
                        }
                        channel.send(LiveProtocol.TYPE_END)
                    }
                    else -> channel.send(LiveProtocol.TYPE_ERROR, "Requête inconnue".toByteArray())
                }
            }
        } catch (_: Exception) {
            // Spectateur parti ou inactif trop longtemps.
        } finally {
            gallerySockets -= socket
            try { socket.close() } catch (_: IOException) { }
        }
    }

    companion object {
        private const val TAG = "LynxLive"
        private const val MAX_VIEWERS = 3
        private const val MAX_ALERT_LISTENERS = 5
        private const val MAX_GALLERY = 2
        private const val MAX_AUDIO_CHUNKS = 16
        private const val GALLERY_IDLE_MS = 120_000
        // Morceaux de 64 ko : progression fluide côté spectateur, même sur un Wi-Fi rapide.
        private const val CHUNK_BYTES = 64 * 1024
        private const val ALERT_KEEPALIVE_MS = 25_000L
        private const val MAX_PENDING = 4
        private const val HANDSHAKE_TIMEOUT_MS = 6_000
        private const val KEEPALIVE_MS = 2_000L
        private const val SEND_TIMEOUT_MS = 15_000L
    }
}
