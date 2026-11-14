package fr.oeildelynx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Caméras appairées sur le téléphone spectateur. Chaque entrée est stockée sous forme de code d’appairage. */
internal object ViewerStore {
    private const val KEY = "paired_cameras"
    private fun prefs(context: Context) = context.getSharedPreferences("lynx_viewer", Context.MODE_PRIVATE)

    fun cameras(context: Context): List<PairingCode> =
        prefs(context).getString(KEY, "").orEmpty().lines().mapNotNull { PairingCode.parse(it) }

    fun find(context: Context, id: String) = cameras(context).firstOrNull { it.id == id }

    /** Ajoute ou remplace (même identifiant) une caméra. */
    fun save(context: Context, camera: PairingCode) {
        val list = cameras(context).filterNot { it.id == camera.id } + camera
        prefs(context).edit().putString(KEY, list.joinToString("\n") { it.encode() }).apply()
    }

    fun remove(context: Context, id: String) {
        val list = cameras(context).filterNot { it.id == id }
        prefs(context).edit().putString(KEY, list.joinToString("\n") { it.encode() }).apply()
        setAlerts(context, id, false)
    }

    private const val KEY_ALERTS = "alert_cameras"

    /** Caméras pour lesquelles ce téléphone reçoit une notification à chaque mouvement. */
    fun alertCameras(context: Context): Set<String> = prefs(context).getStringSet(KEY_ALERTS, emptySet()).orEmpty()

    fun setAlerts(context: Context, id: String, enabled: Boolean) {
        val ids = alertCameras(context).toMutableSet().apply { if (enabled) add(id) else remove(id) }
        prefs(context).edit().putStringSet(KEY_ALERTS, ids).apply()
    }

    /** Les adresses annoncées par la caméra passent devant ; les anciennes restent en secours. */
    fun mergeHosts(fresh: List<String>, previous: List<String>): List<String> = (fresh + previous).distinct().take(8)
}

/**
 * Connexion du téléphone spectateur vers la caméra. Essaie en parallèle toutes les adresses connues
 * (Wi-Fi local d’abord, puis Internet) et garde la première qui s’authentifie. Se reconnecte seul.
 */
internal class LiveClient(
    private val context: Context,
    cameraId: String,
    private val listener: Listener,
    /** Vrai : connexion d’alerte (état seulement, aucune image), utilisée par [AlertService]. */
    private val alertsOnly: Boolean = false
) {
    private var mode = if (alertsOnly) "alerts" else "live"

    /** Rotation ajoutée par l’utilisateur (0, 90, 180, 270), si la caméra signale mal sa position. */
    @Volatile var extraRotation = 0

    /** Son du direct demandé à la caméra ; modifiable pendant la connexion. */
    @Volatile var audioEnabled = false
        set(value) {
            field = value
            if (!value) player?.flush()
            sendRequest(mapOf("op" to "audio", "value" to if (value) "1" else "0"))
        }
    @Volatile private var player: AudioPlayer? = null

    /**
     * Lecture du son sur son propre thread : l’écriture dans AudioTrack bloque, elle ne doit pas
     * retarder la réception des images. File courte : en cas de retard, les morceaux anciens sont jetés.
     */
    private class AudioPlayer {
        private val queue = java.util.concurrent.LinkedBlockingDeque<ShortArray>()
        @Volatile private var running = true
        private val track: android.media.AudioTrack? = try {
            val rate = LiveProtocol.AUDIO_SAMPLE_RATE
            val min = android.media.AudioTrack.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
            android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, rate / 2))
                .setTransferMode(android.media.AudioTrack.MODE_STREAM).build().also { it.play() }
        } catch (_: Exception) { null }

        init {
            Thread({
                while (running) {
                    val chunk = try { queue.poll(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
                    try { track?.write(chunk, 0, chunk.size) } catch (_: Exception) { }
                }
            }, "lynx-live-audio-out").start()
        }

        fun play(chunk: ShortArray) {
            queue.offer(chunk)
            while (queue.size > 8) queue.pollFirst() // ~0,5 s de retard au plus
        }

        fun flush() { queue.clear(); try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) { } }

        fun release() { running = false; queue.clear(); try { track?.stop(); track?.release() } catch (_: Exception) { } }
    }
    @Volatile private var activeChannel: SecureChannel? = null

    @Volatile private var talkRecord: android.media.AudioRecord? = null

    /** Vrai pendant que l’utilisateur parle vers la caméra. */
    val isTalking get() = talkRecord != null

    /**
     * Micro du spectateur vers le haut-parleur de la caméra (bouton « Parler » maintenu).
     * Le son reçu de la caméra est coupé pendant ce temps : sinon on s’entendrait en écho.
     * Renvoie faux si le direct n’est pas connecté ou si le micro est indisponible.
     */
    @android.annotation.SuppressLint("MissingPermission") // vérifiée par l’écran du direct avant l’appel
    fun startTalking(): Boolean {
        if (talkRecord != null) return true
        val channel = activeChannel ?: return false
        val rate = LiveProtocol.AUDIO_SAMPLE_RATE
        val min = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = try {
            android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
        } catch (_: Exception) { return false }
        if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        try { rec.startRecording() } catch (_: Exception) { rec.release(); return false }
        talkRecord = rec
        player?.flush()
        Thread({
            val samples = ShortArray(640) // 40 ms : faible latence
            while (talkRecord === rec) {
                val n = try { rec.read(samples, 0, samples.size) } catch (_: Exception) { -1 }
                if (n < 0) break
                if (n > 0) try { channel.send(LiveProtocol.TYPE_TALK, MuLaw.encode(samples, n)) } catch (_: IOException) { break }
            }
            try { rec.stop() } catch (_: Exception) { }
            rec.release()
        }, "lynx-talk-mic").start()
        return true
    }

    fun stopTalking() { talkRecord = null }

    /** Envoie une commande à la caméra (ex. exposition) si la connexion est ouverte. */
    fun sendRequest(fields: Map<String, String>) {
        val channel = activeChannel ?: return
        Thread {
            try { channel.send(LiveProtocol.TYPE_REQUEST, LiveProtocol.encodeFields(fields).toByteArray(Charsets.UTF_8)) } catch (_: IOException) { }
        }.start()
    }

    /** Connexion de galerie : requêtes/réponses synchrones (bloquant, hors du thread principal). */
    class GallerySession internal constructor(private val socket: Socket, private val channel: SecureChannel) : java.io.Closeable {
        @Synchronized fun request(fields: Map<String, String>): LiveRecord {
            channel.send(LiveProtocol.TYPE_REQUEST, LiveProtocol.encodeFields(fields).toByteArray(Charsets.UTF_8))
            val record = channel.receive()
            // Une caméra en version antérieure à 1.7.0 envoie le direct au lieu de répondre.
            if (record.type == LiveProtocol.TYPE_FRAME || record.type == LiveProtocol.TYPE_STATUS || record.type == LiveProtocol.TYPE_KEEPALIVE) {
                throw IOException("Mets à jour Œil de Lynx sur le téléphone caméra pour accéder à sa galerie.")
            }
            return record
        }

        /** Télécharge un clip morceau par morceau ; [onProgress] reçoit le nombre d’octets reçus. */
        @Synchronized fun download(id: String, output: java.io.OutputStream, onSize: (Long) -> Unit = {}, onProgress: (Long) -> Unit) {
            channel.send(LiveProtocol.TYPE_REQUEST, LiveProtocol.encodeFields(mapOf("op" to "file", "id" to id)).toByteArray(Charsets.UTF_8))
            var received = 0L
            while (true) {
                val record = channel.receive()
                when (record.type) {
                    LiveProtocol.TYPE_CHUNK -> { output.write(record.payload); received += record.payload.size; onProgress(received) }
                    LiveProtocol.TYPE_FILE_SIZE -> String(record.payload, Charsets.UTF_8).toLongOrNull()?.takeIf { it > 0 }?.let(onSize)
                    LiveProtocol.TYPE_END -> return
                    LiveProtocol.TYPE_ERROR -> throw IOException(String(record.payload, Charsets.UTF_8))
                }
            }
        }

        override fun close() { try { socket.close() } catch (_: IOException) { } }
    }

    fun openGallery(): GallerySession {
        mode = "gallery"
        val connected = connect()
        connected.socket.soTimeout = 60_000
        return GallerySession(connected.socket, connected.channel)
    }
    interface Listener {
        fun onState(text: String, connected: Boolean)
        fun onFrame(bitmap: Bitmap)
        fun onStatus(fields: Map<String, String>)
    }

    private class Connected(val socket: Socket, val channel: SecureChannel, val host: String, val hello: Map<String, String>)
    private class KeyRejected : IOException("Clé refusée")

    private var camera: PairingCode = ViewerStore.find(context, cameraId) ?: throw IllegalArgumentException("Caméra inconnue")
    /** Un drapeau par session : un ancien thread qui finit de se fermer ne reprend jamais la main. */
    private var session: AtomicBoolean? = null
    @Volatile private var socket: Socket? = null
    private var thread: Thread? = null

    fun start() {
        if (session?.get() == true) return
        val flag = AtomicBoolean(true)
        session = flag
        thread = Thread({ runLoop(flag) }, "lynx-live-client").apply { start() }
    }

    fun stop() {
        stopTalking()
        player?.release(); player = null
        session?.set(false)
        session = null
        try { socket?.close() } catch (_: IOException) { }
        thread?.interrupt()
    }

    private fun runLoop(flag: AtomicBoolean) {
        var failures = 0
        while (flag.get()) {
            listener.onState(if (failures == 0) "Connexion à ${camera.name}…" else "Nouvelle tentative…", false)
            var waitMs: Long
            var current: Socket? = null
            try {
                val connected = connect()
                current = connected.socket
                failures = 0
                socket = current
                activeChannel = connected.channel
                if (!flag.get()) break
                val route = if (isLocal(connected.socket.inetAddress)) "Wi-Fi local" else "Internet"
                listener.onState("En direct · $route", true)
                updateHosts(connected.hello)
                waitMs = readLoop(connected, flag)
            } catch (_: KeyRejected) {
                listener.onState("Clé refusée par la caméra : elle a été réinitialisée. Appaire à nouveau ce téléphone.", false)
                waitMs = 30_000
            } catch (error: Exception) {
                failures++
                if (!flag.get()) break
                Log.i(TAG, "Live connection failed: ${error.message}")
                val delay = minOf(2_000L * failures, 15_000L)
                listener.onState(
                    "Caméra injoignable. Vérifie qu’elle est en surveillance et connectée au Wi-Fi de la box. " +
                        "Nouvel essai dans ${delay / 1000} s.", false
                )
                waitMs = delay
            } finally {
                activeChannel = null
                current?.let { closeQuietly(it) }
                if (socket === current) socket = null
            }
            if (!flag.get()) break
            try { Thread.sleep(waitMs) } catch (_: InterruptedException) { if (!flag.get()) break }
        }
    }

    /** Lit les images jusqu’à la coupure ; renvoie le délai avant reconnexion. */
    private fun readLoop(connected: Connected, flag: AtomicBoolean): Long {
        var frameCount = 0
        var windowStart = System.currentTimeMillis()
        val route = if (isLocal(connected.socket.inetAddress)) "Wi-Fi local" else "Internet"
        while (flag.get()) {
            val record = try { connected.channel.receive() } catch (error: IOException) {
                if (flag.get()) listener.onState("Connexion perdue, reconnexion…", false)
                return 1_000
            }
            when (record.type) {
                LiveProtocol.TYPE_FRAME -> {
                    if (record.payload.size < 3) continue
                    val rotation = ((((record.payload[0].toInt() and 0xff) shl 8) or (record.payload[1].toInt() and 0xff)) + extraRotation) % 360
                    val decoded = BitmapFactory.decodeByteArray(record.payload, 2, record.payload.size - 2) ?: continue
                    val bitmap = if (rotation % 360 == 0) decoded else
                        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                            .also { if (it !== decoded) decoded.recycle() }
                    listener.onFrame(bitmap)
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - windowStart >= 3_000) {
                        val fps = frameCount * 1000.0 / (now - windowStart)
                        listener.onState("En direct · $route · ${"%.1f".format(fps)} img/s", true)
                        frameCount = 0; windowStart = now
                    }
                }
                LiveProtocol.TYPE_AUDIO -> if (audioEnabled && flag.get() && talkRecord == null) {
                    val out = player ?: AudioPlayer().also { player = it }
                    out.play(MuLaw.decode(record.payload))
                }
                LiveProtocol.TYPE_STATUS -> listener.onStatus(LiveProtocol.decodeFields(String(record.payload, Charsets.UTF_8)))
                LiveProtocol.TYPE_BYE -> {
                    listener.onState("Surveillance arrêtée sur la caméra. Le direct reprendra quand elle redémarrera.", false)
                    return 10_000
                }
            }
        }
        return 0
    }

    /** Course entre les adresses : la première authentifiée gagne, les autres sont fermées. */
    private fun connect(): Connected {
        val hosts = camera.hosts.mapNotNull { PairingCode.parseHost(it) }
        if (hosts.isEmpty()) throw IOException("Aucune adresse connue pour cette caméra")
        val pool = Executors.newFixedThreadPool(hosts.size)
        val completion = ExecutorCompletionService<Connected>(pool)
        hosts.forEachIndexed { index, (host, port) ->
            completion.submit {
                // Laisse un court avantage aux premières adresses (Wi-Fi local) pour préférer la route directe.
                Thread.sleep(index * 250L)
                attempt(host, port)
            }
        }
        var winner: Connected? = null
        var keyRejected = false
        var lastError: Throwable? = null
        try {
            repeat(hosts.size) {
                val future = completion.poll(CONNECT_TIMEOUT_MS + HANDSHAKE_TIMEOUT_MS + hosts.size * 250L, TimeUnit.MILLISECONDS) ?: return@repeat
                try {
                    val result = future.get()
                    if (winner == null) winner = result else closeQuietly(result.socket)
                } catch (error: Exception) {
                    val cause = error.cause ?: error
                    if (cause is KeyRejected) keyRejected = true
                    lastError = cause
                }
                if (winner != null) {
                    // Ferme en arrière-plan les connexions perdantes qui aboutiraient plus tard.
                    pool.submit {
                        while (true) {
                            val late = completion.poll((CONNECT_TIMEOUT_MS + HANDSHAKE_TIMEOUT_MS).toLong(), TimeUnit.MILLISECONDS) ?: break
                            try { closeQuietly(late.get().socket) } catch (_: Exception) { }
                        }
                    }
                    return winner!!
                }
            }
        } finally {
            pool.shutdown()
        }
        if (keyRejected) throw KeyRejected()
        throw IOException(lastError?.message ?: "Délai dépassé", lastError)
    }

    private fun attempt(host: String, port: Int): Connected {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val channel = LiveProtocol.clientHandshake(
                BufferedInputStream(socket.getInputStream(), 64 * 1024), BufferedOutputStream(socket.getOutputStream()), camera.key
            )
            // À partir d’ici, la caméra a répondu : une fermeture immédiate signifie qu’elle refuse notre clé.
            val auth = LiveProtocol.encodeFields(mapOf("name" to (Build.MODEL ?: "Spectateur"), "mode" to mode, "audio" to if (audioEnabled && mode == "live") "1" else "0"))
            channel.send(LiveProtocol.TYPE_AUTH, auth.toByteArray(Charsets.UTF_8))
            val hello = try { channel.receive() } catch (error: EOFException) { throw KeyRejected() } catch (error: java.net.SocketException) { throw KeyRejected() }
            if (hello.type != LiveProtocol.TYPE_HELLO) throw IOException("Réponse inattendue de la caméra")
            val fields = LiveProtocol.decodeFields(String(hello.payload, Charsets.UTF_8))
            if (fields["id"] != null && fields["id"] != camera.id) throw IOException("Une autre caméra répond à cette adresse")
            socket.soTimeout = if (alertsOnly) ALERT_READ_TIMEOUT_MS else READ_TIMEOUT_MS
            return Connected(socket, channel, PairingCode.formatHost(host, port), fields)
        } catch (error: Exception) {
            closeQuietly(socket)
            throw error
        }
    }

    /** Mémorise les adresses actuelles de la caméra (IP publique changée, nouvelle IP locale…). */
    private fun updateHosts(hello: Map<String, String>) {
        val fresh = hello["h"].orEmpty().split(',').map { it.trim() }.filter { PairingCode.parseHost(it) != null }
        val name = hello["n"]?.takeIf { it.isNotBlank() } ?: camera.name
        val updated = camera.copy(name = name, hosts = ViewerStore.mergeHosts(fresh, camera.hosts))
        if (updated != camera) { camera = updated; ViewerStore.save(context, updated) }
    }

    private fun isLocal(address: InetAddress?) = address != null &&
        (address.isSiteLocalAddress || address.isLinkLocalAddress || (address is Inet4Address && LiveUpnp.isPrivateIpv4(address.hostAddress ?: "")))

    private fun closeQuietly(socket: Socket) { try { socket.close() } catch (_: IOException) { } }

    companion object {
        private const val TAG = "LynxLiveClient"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val HANDSHAKE_TIMEOUT_MS = 6_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val ALERT_READ_TIMEOUT_MS = 70_000
    }
}
