package fr.oeildelynx

import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
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
internal class LiveServer(
    private val port: Int,
    private val secretProvider: () -> ByteArray?,
    private val helloProvider: () -> Map<String, String>,
    private val onViewersChanged: (Int) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val viewers = CopyOnWriteArrayList<Viewer>()
    private val pendingHandshakes = AtomicInteger()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var running = false
    @Volatile private var lastStatus: Map<String, String> = emptyMap()

    val viewerCount get() = viewers.size
    fun hasViewers() = viewers.isNotEmpty()

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
        watchdog.shutdownNow()
    }

    /** Déconnecte tous les spectateurs (ex. après un changement de clé d’appairage). */
    fun disconnectAll() = viewers.forEach { it.close() }

    fun publishFrame(jpeg: ByteArray, rotationDegrees: Int) {
        if (viewers.isEmpty()) return
        val payload = ByteArray(jpeg.size + 2)
        payload[0] = (rotationDegrees shr 8).toByte(); payload[1] = rotationDegrees.toByte()
        System.arraycopy(jpeg, 0, payload, 2, jpeg.size)
        viewers.forEach { it.offerFrame(payload) }
    }

    fun publishStatus(status: Map<String, String>) {
        lastStatus = status
        val payload = LiveProtocol.encodeFields(status).toByteArray(Charsets.UTF_8)
        viewers.forEach { it.offerStatus(payload) }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            if (pendingHandshakes.get() >= MAX_PENDING || viewers.size >= MAX_VIEWERS) {
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
            val viewerName = String(auth.payload, Charsets.UTF_8).take(60)
            channel.send(LiveProtocol.TYPE_HELLO, LiveProtocol.encodeFields(helloProvider()).toByteArray(Charsets.UTF_8))
            viewer = Viewer(socket, channel, viewerName)
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
        onViewersChanged(viewers.size)
        if (lastStatus.isNotEmpty()) viewer.offerStatus(LiveProtocol.encodeFields(lastStatus).toByteArray(Charsets.UTF_8))
        try { viewer.sendLoop() } finally {
            viewer.close()
            viewers -= viewer
            Log.i(TAG, "Live viewer disconnected: ${viewer.name}")
            onViewersChanged(viewers.size)
        }
    }

    private inner class Viewer(private val socket: Socket, private val channel: SecureChannel, val name: String) {
        private val lock = Object()
        private var frame: ByteArray? = null
        private var status: ByteArray? = null
        private var bye = false
        @Volatile var sendingSince = 0L

        fun offerFrame(payload: ByteArray) = synchronized(lock) { frame = payload; lock.notifyAll() }
        fun offerStatus(payload: ByteArray) = synchronized(lock) { status = payload; lock.notifyAll() }
        fun goodbye() = synchronized(lock) { bye = true; lock.notifyAll() }

        fun sendLoop() {
            try {
                while (true) {
                    var nextFrame: ByteArray?; var nextStatus: ByteArray?; var sayBye: Boolean
                    synchronized(lock) {
                        if (frame == null && status == null && !bye) lock.wait(KEEPALIVE_MS)
                        nextFrame = frame; nextStatus = status; sayBye = bye
                        frame = null; status = null
                    }
                    if (!running && !sayBye) return
                    sendingSince = SystemClock.elapsedRealtime()
                    if (sayBye) { channel.send(LiveProtocol.TYPE_BYE); return }
                    nextStatus?.let { channel.send(LiveProtocol.TYPE_STATUS, it) }
                    val image = nextFrame
                    if (image != null) channel.send(LiveProtocol.TYPE_FRAME, image)
                    else if (nextStatus == null) channel.send(LiveProtocol.TYPE_KEEPALIVE)
                    sendingSince = 0L
                }
            } catch (_: Exception) {
                // Spectateur parti ou réseau coupé.
            }
        }

        fun close() { try { socket.close() } catch (_: IOException) { } }
    }

    companion object {
        private const val TAG = "LynxLive"
        private const val MAX_VIEWERS = 3
        private const val MAX_PENDING = 4
        private const val HANDSHAKE_TIMEOUT_MS = 6_000
        private const val KEEPALIVE_MS = 2_000L
        private const val SEND_TIMEOUT_MS = 15_000L
    }
}
