package com.agentdeck.hotel

import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Real loopback HTTPS through the shipped client. No provider or live hotel service is involved. */
class FleetClientTest {
    data class Request(val method: String, val path: String, val authorization: String?, val body: JSONObject)
    data class Reply(val code: Int, val body: String, val headers: String = "")
    private lateinit var server: ServerSocket
    private lateinit var serving: Thread
    private lateinit var client: FleetClient
    private val requests = LinkedBlockingQueue<Request>()
    private val replies = LinkedBlockingQueue<Reply>()
    @Volatile private var running = true

    @Before fun start() {
        running = true
        server = tls.serverSocketFactory.createServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        client = FleetClient("https://localhost:${server.localPort}", TOKEN, OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, trust).connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .followRedirects(true).build())
        serving = Thread {
            while (running) try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = DataInputStream(socket.getInputStream().buffered())
                    fun line(): String {
                        val bytes = ArrayList<Byte>()
                        while (true) {
                            val next = input.read()
                            if (next < 0) throw java.io.EOFException()
                            if (next == 10) break
                            if (next != 13) bytes.add(next.toByte())
                        }
                        return bytes.toByteArray().toString(Charsets.US_ASCII)
                    }
                    val first = line().split(' ')
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val text = line()
                        if (text.isEmpty()) break
                        headers[text.substringBefore(':').lowercase()] = text.substringAfter(':').trim()
                    }
                    val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                    input.readFully(body)
                    requests.add(Request(first[0], first[1], headers["authorization"],
                        if (body.isEmpty()) JSONObject() else JSONObject(body.toString(Charsets.UTF_8))))
                    val reply = replies.poll(5, TimeUnit.SECONDS) ?: error("Missing fixture reply")
                    val bytes = reply.body.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 ${reply.code} Fixture\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n${reply.headers}Connection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
                }
            } catch (_: IOException) { }
        }.apply { isDaemon = true; start() }
    }
    @After fun stop() { running = false; server.close(); serving.join(2000) }
    @Test fun devicePostsUseBearerHeaderAndTheVersionedContract() {
        replies.add(Reply(200, "{\"status\":\"active\"}"))
        assertEquals("active", client.post("/device/activate").getString("status"))
        val first = requests.poll(5, TimeUnit.SECONDS)!!
        assertEquals("POST", first.method)
        assertEquals("/api/v2/device/activate", first.path)
        assertEquals("Bearer $TOKEN", first.authorization)
        assertFalse(first.path.contains(TOKEN))
        replies.add(Reply(200, "{\"version\":2,\"config\":{}}"))
        assertEquals(2, client.get("/device/config").getInt("version"))
        assertEquals("GET", requests.poll(5, TimeUnit.SECONDS)!!.method)
    }
    @Test fun redirectsCannotForwardTheDeviceCredentialEvenIfInjectedClientAllowsRedirects() {
        replies.add(Reply(302, "{}", "Location: https://localhost:${server.localPort}/credential-target\r\n"))
        val failure = assertThrows(FleetHttpException::class.java) { client.post("/device/activate") }
        assertEquals(302, failure.status)
        assertNotNull(requests.poll(5, TimeUnit.SECONDS))
        assertNull(requests.poll(200, TimeUnit.MILLISECONDS))
    }
    @Test fun serverErrorBodiesNeverBecomeDiagnosticTextAndOversizedResponsesFailClosed() {
        replies.add(Reply(403, "{\"detail\":\"secret-marker private hotel text\"}"))
        val failure = assertThrows(FleetHttpException::class.java) { client.get("/device/config") }
        assertFalse(failure.message!!.contains("secret-marker"))
        requests.poll(5, TimeUnit.SECONDS)
        replies.add(Reply(200, "{\"padding\":\"" + "x".repeat(FleetProtocol.MAX_RESPONSE_BYTES) + "\"}"))
        assertThrows(IllegalStateException::class.java) { client.get("/device/config") }
    }
    companion object {
        private const val TOKEN = "fixture-only-device-credential-123456789012345"
        private lateinit var tls: SSLContext
        private lateinit var trust: X509TrustManager
        @JvmStatic @BeforeClass fun tls() {
            val directory = Files.createTempDirectory("fleet-https-fixture-")
            val path = directory.resolve("localhost.p12")
            val log = directory.resolve("keytool.log")
            try {
                val process = ProcessBuilder(System.getProperty("java.home") + "/bin/keytool", "-genkeypair",
                    "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                    "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                    "-keystore", path.toString(), "-storepass", "fixture-password", "-keypass", "fixture-password", "-noprompt")
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start()
                check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0)
                val keys = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(path).use { load(it, "fixture-password".toCharArray()) } }
                val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "fixture-password".toCharArray()) }
                val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
                trust = tm.trustManagers.single() as X509TrustManager
                tls = SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
            } finally { Files.deleteIfExists(path); Files.deleteIfExists(log); Files.deleteIfExists(directory) }
        }
    }
}
