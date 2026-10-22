package space.einundzwanzig.meetup.data.community.plebrap

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.data.community.plebrap.internal.Media3AudioCache
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * [Media3AudioCache] against a server like plebrap.de: chunked body (no Content-Length) and Range
 * headers ignored (always `200` with the whole file). The whole point of the cache is that such a
 * track ends up fully cached with its length stored, so the player's source can seek.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class Media3AudioCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val body = Random(21).nextBytes(BODY_SIZE)
    private val rangeHeaders: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    /** Throttles the next response so a download can be cancelled midway. */
    @Volatile
    private var throttleNext = false

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private lateinit var simpleCache: SimpleCache
    private lateinit var audioCache: Media3AudioCache
    private lateinit var url: String

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                rangeHeaders += request.headers["Range"]
                val builder = MockResponse.Builder().code(200).chunkedBody(Buffer().write(body), 8 * 1024)
                if (throttleNext) {
                    throttleNext = false
                    builder.throttleBody(4 * 1024, 20, TimeUnit.MILLISECONDS)
                }
                return builder.build()
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        url = server.url("/0/01-Money%20Fest.mp3?cdp=a&_=192e4484b03").toString()
        simpleCache = SimpleCache(tmp.newFolder("plebrap_audio"), NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
        audioCache = Media3AudioCache({ simpleCache }, client, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        simpleCache.release()
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun downloadWithoutLengthOrRangesEndsFullyCachedAndSeekable() = runBlocking {
        assertFalse(audioCache.isFullyCached(url))

        val progress = withTimeout(TIMEOUT_MS) { audioCache.download(url).toList() }

        assertEquals(1f, progress.last())
        assertTrue(audioCache.isFullyCached(url))
        assertEquals(BODY_SIZE.toLong(), ContentMetadata.getContentLength(simpleCache.getContentMetadata(url)))
        assertEquals(1, server.requestCount)

        // The player's read-only source now knows the length (→ seekable) and needs no network.
        val source = audioCache.playerDataSourceFactory.createDataSource()
        assertEquals(BODY_SIZE.toLong(), source.open(DataSpec(Uri.parse(url))))
        assertArrayEquals(body, source.readAllAndClose())

        val seeked = audioCache.playerDataSourceFactory.createDataSource()
        assertEquals(BODY_SIZE - 1000L, seeked.open(DataSpec.Builder().setUri(url).setPosition(1000).build()))
        assertArrayEquals(body.copyOfRange(1000, BODY_SIZE), seeked.readAllAndClose())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cancelledDownloadResumesBySkippingTheFull200Response() = runBlocking {
        throttleNext = true
        val first = launch(Dispatchers.Default) { audioCache.download(url).collect {} }
        withTimeout(TIMEOUT_MS) { while (server.requestCount == 0) delay(10) }
        delay(400)
        first.cancelAndJoin()

        val partial = simpleCache.getCachedBytes(url, 0, C.LENGTH_UNSET.toLong())
        assertTrue("partial = $partial", partial in 1 until BODY_SIZE)
        assertFalse(audioCache.isFullyCached(url))

        val progress = withTimeout(TIMEOUT_MS) { audioCache.download(url).toList() }

        assertEquals(1f, progress.last())
        assertEquals(listOf(null, "bytes=$partial-"), rangeHeaders.toList())
        assertTrue(audioCache.isFullyCached(url))
        assertEquals(BODY_SIZE.toLong(), ContentMetadata.getContentLength(simpleCache.getContentMetadata(url)))
        val source = audioCache.playerDataSourceFactory.createDataSource()
        assertEquals(BODY_SIZE.toLong(), source.open(DataSpec(Uri.parse(url))))
        assertArrayEquals(body, source.readAllAndClose())
    }

    @Test
    fun spanFilesDeletedBehindTheIndexAreNotFullyCachedAndDownloadAgain() = runBlocking {
        withTimeout(TIMEOUT_MS) { audioCache.download(url).toList() }
        assertTrue(audioCache.isFullyCached(url))

        // Android trimmed cacheDir under storage pressure; SimpleCache's index still lists the spans.
        simpleCache.getCachedSpans(url).forEach { checkNotNull(it.file).delete() }
        assertTrue(simpleCache.isCached(url, 0, BODY_SIZE.toLong()))

        assertFalse(audioCache.isFullyCached(url))
        assertTrue(simpleCache.getCachedSpans(url).isEmpty())

        val progress = withTimeout(TIMEOUT_MS) { audioCache.download(url).toList() }
        assertEquals(1f, progress.last())
        assertEquals(2, server.requestCount)
        assertTrue(audioCache.isFullyCached(url))
        val source = audioCache.playerDataSourceFactory.createDataSource()
        assertEquals(BODY_SIZE.toLong(), source.open(DataSpec(Uri.parse(url))))
        assertArrayEquals(body, source.readAllAndClose())
    }

    @Test
    fun writerWaitsForALockedSpanInsteadOfBypassingTheCache() = runBlocking {
        // Someone else (the player opening a source, or a cancelled writer winding down) holds the hole.
        val hole = checkNotNull(simpleCache.startReadWriteNonBlocking(url, 0, C.LENGTH_UNSET.toLong()))
        assertTrue(hole.isHoleSpan)

        val download = async(Dispatchers.Default) { audioCache.download(url).toList() }
        delay(300)
        assertTrue(download.isActive)
        assertEquals(0, server.requestCount)

        simpleCache.releaseHoleSpan(hole)
        val progress = withTimeout(TIMEOUT_MS) { download.await() }

        assertEquals(1f, progress.last())
        assertTrue(audioCache.isFullyCached(url))
    }

    private fun DataSource.readAllAndClose(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
        } finally {
            close()
        }
        return out.toByteArray()
    }

    private companion object {
        const val BODY_SIZE = 300_000
        const val TIMEOUT_MS = 20_000L
    }
}
