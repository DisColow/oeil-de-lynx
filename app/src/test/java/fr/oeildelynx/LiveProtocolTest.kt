package fr.oeildelynx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LiveProtocolTest {
    /** Lance un vrai couple de sockets locaux : la caméra côté serveur, le spectateur côté client. */
    private fun <T> withPair(serverKey: ByteArray, clientKey: ByteArray, server: (SecureChannel) -> Unit, client: (SecureChannel) -> T): T {
        ServerSocket(0).use { listener ->
            val pool = Executors.newSingleThreadExecutor()
            val serverTask = pool.submit {
                listener.accept().use { socket ->
                    socket.soTimeout = 3000
                    try { server(LiveProtocol.serverHandshake(socket.getInputStream(), socket.getOutputStream(), serverKey)) } catch (_: IOException) { }
                }
            }
            try {
                Socket("127.0.0.1", listener.localPort).use { socket ->
                    socket.soTimeout = 3000
                    return client(LiveProtocol.clientHandshake(socket.getInputStream(), socket.getOutputStream(), clientKey))
                }
            } finally {
                serverTask.get(5, TimeUnit.SECONDS)
                pool.shutdownNow()
            }
        }
    }

    @Test fun pairedPhonesExchangeEncryptedRecords() {
        val key = LiveProtocol.newKey()
        val jpeg = ByteArray(50_000) { (it * 31).toByte() }
        val received = withPair(key, key, server = { channel ->
            val auth = channel.receive()
            assertEquals(LiveProtocol.TYPE_AUTH, auth.type)
            channel.send(LiveProtocol.TYPE_HELLO, "n=Salon".toByteArray())
            channel.send(LiveProtocol.TYPE_FRAME, jpeg)
            channel.send(LiveProtocol.TYPE_BYE)
        }) { channel ->
            channel.send(LiveProtocol.TYPE_AUTH, "Pixel".toByteArray())
            listOf(channel.receive(), channel.receive(), channel.receive())
        }
        assertEquals(LiveProtocol.TYPE_HELLO, received[0].type)
        assertEquals("n=Salon", String(received[0].payload))
        assertEquals(LiveProtocol.TYPE_FRAME, received[1].type)
        assertArrayEquals(jpeg, received[1].payload)
        assertEquals(LiveProtocol.TYPE_BYE, received[2].type)
        assertEquals(0, received[2].payload.size)
    }

    @Test fun wrongKeyIsRejectedByCamera() {
        var serverError: Exception? = null
        withPair(LiveProtocol.newKey(), LiveProtocol.newKey(), server = { channel ->
            try { channel.receive(); fail("La caméra ne doit pas accepter une autre clé") } catch (error: IOException) { serverError = error }
        }) { channel -> channel.send(LiveProtocol.TYPE_AUTH, "Intrus".toByteArray()) }
        assertNotNull(serverError)
    }

    @Test fun tamperedRecordIsRejected() {
        val key = LiveProtocol.newKey()
        val sendKey = LiveProtocol.deriveKey(key, "lynx s2c", ByteArray(16), ByteArray(16))
        val wire = ByteArrayOutputStream()
        SecureChannel(ByteArrayInputStream(ByteArray(0)), wire, sendKey, sendKey).send(LiveProtocol.TYPE_FRAME, byteArrayOf(1, 2, 3))
        val bytes = wire.toByteArray().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try {
            SecureChannel(ByteArrayInputStream(bytes), ByteArrayOutputStream(), sendKey, sendKey).receive()
            fail("Un enregistrement modifié doit être refusé")
        } catch (_: IOException) { }
    }

    @Test fun replayedRecordIsRejected() {
        val key = ByteArray(32) { 7 }
        val wire = ByteArrayOutputStream()
        SecureChannel(ByteArrayInputStream(ByteArray(0)), wire, key, key).send(LiveProtocol.TYPE_KEEPALIVE)
        val once = wire.toByteArray()
        val reader = SecureChannel(ByteArrayInputStream(once + once), ByteArrayOutputStream(), key, key)
        assertEquals(LiveProtocol.TYPE_KEEPALIVE, reader.receive().type)
        try { reader.receive(); fail("Un enregistrement rejoué doit être refusé") } catch (_: IOException) { }
    }

    @Test fun nonLynxPeerIsRejected() {
        try {
            LiveProtocol.clientHandshake(ByteArrayInputStream("HTTP/1.1 200 OK\r\n\r\n".toByteArray()), ByteArrayOutputStream(), LiveProtocol.newKey())
            fail("Un autre service ne doit pas être pris pour une caméra")
        } catch (_: IOException) { }
    }

    @Test fun pairingCodeRoundTrip() {
        val code = PairingCode("abc-123", "Entrée & garage", LiveProtocol.newKey(),
            listOf("192.168.1.20:47474", "[2a01:e0a:1:2::5]:47474", "maison.freeboxos.fr:47474"))
        val text = code.encode()
        assertTrue(text.startsWith(PairingCode.PREFIX))
        assertEquals(code, PairingCode.parse(text))
    }

    @Test fun invalidPairingCodesAreIgnored() {
        assertNull(PairingCode.parse("https://example.com"))
        assertNull(PairingCode.parse(PairingCode.PREFIX + "v=1&id=x&k=AAAA&h="))
        assertNull(PairingCode.parse(PairingCode.PREFIX + "v=9&id=x&k=AAAA"))
    }

    @Test fun hostsAreParsed() {
        assertEquals("192.168.1.2" to 47474, PairingCode.parseHost("192.168.1.2:47474"))
        assertEquals("2a01:e0a::1" to 80, PairingCode.parseHost("[2a01:e0a::1]:80"))
        assertNull(PairingCode.parseHost("2a01:e0a::1"))
        assertNull(PairingCode.parseHost("hote:99999"))
        assertEquals("[2a01::1]:47474", PairingCode.formatHost("2a01::1", 47474))
    }

    @Test fun galleryClipIsDownloadedInChunks() {
        val key = LiveProtocol.newKey()
        val clip = ByteArray(600_000) { (it % 251).toByte() }
        ServerSocket(0).use { listener ->
            val pool = Executors.newSingleThreadExecutor()
            val camera = pool.submit {
                listener.accept().use { socket ->
                    val channel = LiveProtocol.serverHandshake(socket.getInputStream(), socket.getOutputStream(), key)
                    val request = LiveProtocol.decodeFields(String(channel.receive().payload))
                    assertEquals("file", request["op"]); assertEquals("42", request["id"])
                    clip.toList().chunked(256 * 1024).forEach { channel.send(LiveProtocol.TYPE_CHUNK, it.toByteArray()) }
                    channel.send(LiveProtocol.TYPE_END)
                }
            }
            Socket("127.0.0.1", listener.localPort).use { socket ->
                socket.soTimeout = 3000
                val channel = LiveProtocol.clientHandshake(socket.getInputStream(), socket.getOutputStream(), key)
                val output = ByteArrayOutputStream()
                var progress = 0L
                LiveClient.GallerySession(socket, channel).download("42", output) { progress = it }
                assertArrayEquals(clip, output.toByteArray())
                assertEquals(clip.size.toLong(), progress)
            }
            camera.get(5, TimeUnit.SECONDS); pool.shutdownNow()
        }
    }

    @Test fun mergedHostsPreferFreshAddresses() {
        assertEquals(listOf("b:1", "a:1", "c:1"), ViewerStore.mergeHosts(listOf("b:1", "a:1"), listOf("a:1", "c:1")))
    }
}
