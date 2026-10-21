package space.einundzwanzig.meetup.data.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.DartBase64
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.core.crypto.Sha256
import space.einundzwanzig.meetup.core.crypto.toHex
import space.einundzwanzig.meetup.core.nostr.NostrEvents
import space.einundzwanzig.meetup.data.profile.blossom.BlossomUploadService
import space.einundzwanzig.meetup.data.profile.blossom.UploadResult
import space.einundzwanzig.meetup.data.profile.internal.DefaultBlossomUploadService
import space.einundzwanzig.meetup.data.profile.internal.EventSigner
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `BlossomUploadService` (spec 07 §5.5, §6.3) against a local server. Real time (`runBlocking`):
 * the HTTP helpers use `withTimeout`, which virtual test time would fire immediately.
 */
class BlossomUploadServiceTest {

    private val time = FakeTime()
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var respond: (String) -> MockResponse = { MockResponse.Builder().code(500).build() }
    private val client = OkHttpClient()
    private var signCount = 0

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return respond(request.url.encodedPath)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private val servers get() = listOf("a", "b", "c").map { "http://127.0.0.1:${server.port}/$it" }

    private val localSigner = EventSigner { kind, tags, content ->
        signCount++
        signed(SECRET1, kind, content, tags, createdAt = time.millis / 1000)
    }

    private fun service(signer: EventSigner = localSigner, servers: List<String> = this.servers) =
        DefaultBlossomUploadService(client, signer, time, Dispatchers.IO, servers)

    private fun image(name: String = "badge.JPG", bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File {
        val dir = kotlin.io.path.createTempDirectory("blossom").toFile().apply { deleteOnExit() }
        return File(dir, name).apply { writeBytes(bytes); deleteOnExit() }
    }

    @Test
    fun `fails over to the next server and signs once`() = runBlocking<Unit> {
        respond = { path ->
            when (path) {
                "/a/upload" -> MockResponse.Builder().code(401).addHeader("X-Reason", "auth missing").build()
                "/b/upload" -> MockResponse.Builder().code(201).body("{\"url\":\"https://cdn.example/abc.jpg\",\"sha256\":\"x\"}").build()
                else -> MockResponse.Builder().code(500).build()
            }
        }
        val file = image()
        val result = service().uploadImage(file)
        assertEquals(UploadResult.success("https://cdn.example/abc.jpg"), result)
        assertTrue(result.ok)
        assertEquals(1, signCount)
        assertEquals(listOf("/a/upload", "/b/upload"), requests.map { it.url.encodedPath })

        val first = requests[0]
        val second = requests[1]
        assertEquals("PUT", first.method)
        assertEquals("image/jpeg", first.headers["Content-Type"])
        assertEquals("4", first.headers["Content-Length"])
        assertTrue(first.body!!.toByteArray().contentEquals(byteArrayOf(1, 2, 3, 4)))
        // Same authorization for every server.
        assertEquals(first.headers["Authorization"], second.headers["Authorization"])

        val header = first.headers["Authorization"]!!
        assertTrue(header.startsWith("Nostr "))
        val json = DartUtf8.decodeStrict(DartBase64.decode(header.removePrefix("Nostr ")))
        val event = NostrEvents.fromJsonStringOrNull(json)!!
        assertTrue(event.isValid())
        assertEquals(event.toJson(), json) // key order id, pubkey, created_at, kind, tags, content, sig
        assertEquals(24242, event.kind)
        assertEquals("Bild fuer ein Einundzwanzig-Event-Badge", event.content)
        val digest = Sha256.digest(byteArrayOf(1, 2, 3, 4)).toHex()
        assertEquals(
            listOf(listOf("t", "upload"), listOf("x", digest), listOf("expiration", "${time.millis / 1000 + 600}")),
            event.tags,
        )
    }

    @Test
    fun `2xx without url tries the next server, last error wins`() = runBlocking<Unit> {
        respond = { path ->
            when (path) {
                "/a/upload" -> MockResponse.Builder().code(200).body("{\"url\":\"\"}").build()
                "/b/upload" -> MockResponse.Builder().code(200).body("{}").build()
                else -> MockResponse.Builder().code(413).build()
            }
        }
        assertEquals(UploadResult.failure("HTTP 413"), service().uploadImage(image()))
        respond = { MockResponse.Builder().code(201).body("{\"nourl\":1}").build() }
        assertEquals(UploadResult.failure("Antwort ohne URL"), service().uploadImage(image()))
    }

    @Test
    fun `non-string url is converted with toString`() = runBlocking<Unit> {
        respond = { MockResponse.Builder().code(200).body("{\"url\":12}").build() }
        assertEquals(UploadResult.success("12"), service().uploadImage(image()))
    }

    @Test
    fun `invalid JSON body and unreachable servers give an exception text`() = runBlocking<Unit> {
        respond = { MockResponse.Builder().code(200).body("<html>").build() }
        val bad = service(servers = servers.take(1)).uploadImage(image())
        assertFalse(bad.ok)
        assertTrue(bad.error!!.isNotEmpty())

        val unreachable = service(servers = listOf("http://127.0.0.1:1")).uploadImage(image())
        assertNull(unreachable.url)
        assertTrue(unreachable.error!!.isNotEmpty())

        assertEquals(UploadResult.failure("Kein Server erreichbar"), service(servers = emptyList()).uploadImage(image()))
    }

    @Test
    fun `early failures - missing file, empty file, signing failure`() = runBlocking<Unit> {
        assertEquals(UploadResult.failure("Datei nicht gefunden"), service().uploadImage(File("/does/not/exist.png")))
        // Dart File.exists() is false for a directory.
        val dir = kotlin.io.path.createTempDirectory("blossom-dir").toFile().apply { deleteOnExit() }
        assertEquals(UploadResult.failure("Datei nicht gefunden"), service().uploadImage(dir))
        assertEquals(UploadResult.failure("Datei ist leer"), service().uploadImage(image(bytes = ByteArray(0))))
        val failing = EventSigner { _, _, _ -> throw IllegalStateException("cancelled") }
        assertEquals(UploadResult.failure("Signieren fehlgeschlagen"), service(failing).uploadImage(image()))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `mime type by extension`() {
        assertEquals("image/jpeg", BlossomUploadService.mimeFor("/x/a.jpg"))
        assertEquals("image/jpeg", BlossomUploadService.mimeFor("/x/a.JPEG"))
        assertEquals("image/png", BlossomUploadService.mimeFor("a.png"))
        assertEquals("image/webp", BlossomUploadService.mimeFor("a.webp"))
        assertEquals("image/gif", BlossomUploadService.mimeFor("a.gif"))
        assertEquals("application/octet-stream", BlossomUploadService.mimeFor("a.heic"))
        assertEquals("application/octet-stream", BlossomUploadService.mimeFor("noext"))
        assertEquals("application/octet-stream", BlossomUploadService.mimeFor("/dir.png/file"))
    }

    @Test
    fun `upload result ok flag`() {
        assertFalse(UploadResult(url = "", error = null).ok)
        assertFalse(UploadResult.failure("x").ok)
        assertTrue(UploadResult.success("u").ok)
        assertEquals(3, BlossomUploadService.SERVERS.size)
    }
}
