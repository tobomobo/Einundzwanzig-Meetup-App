package space.einundzwanzig.meetup.data.community.plebrap.internal

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.common.IoDispatcher
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio cache of the PlebRap player: one Media3 [SimpleCache] in `cacheDir/plebrap_audio`
 * (LRU, [MAX_BYTES]) — the replacement for just_audio's `LockCachingAudioSource` temp files.
 *
 * - [download] writes a whole track with a [CacheWriter] (the only writer). The writer blocks
 *   on span locks ([CacheDataSource.FLAG_BLOCK_ON_CACHE]) so a short player lock or a cancelled
 *   writer that is still winding down cannot make it bypass the cache; afterwards it verifies
 *   that the track really is complete.
 * - [playerDataSourceFactory] is **read-only**: it plays cached bytes from disk and streams
 *   missing bytes from the network without writing. It only holds a span lock for the instant of
 *   opening a source and never waits for one, so it never blocks on a running download. A fully
 *   cached track has a known length and is therefore seekable; a streamed one usually is not
 *   (no range support / Content-Length).
 *
 * Note: on first play a track is fetched twice (the player streams it while [download] fetches
 * it for the cache), unlike just_audio's single proxied download.
 *
 * Cache key = the URL string as-is (pre-encoded escapes kept).
 */
@OptIn(UnstableApi::class)
@Singleton
internal class Media3AudioCache(
    openCache: () -> Cache,
    private val okHttpClient: OkHttpClient,
    private val io: CoroutineDispatcher,
) : AudioCache {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(
        openCache = {
            SimpleCache(
                File(context.cacheDir, DIR_NAME),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context),
            )
        },
        okHttpClient = okHttpClient,
        io = io,
    )

    /** Opened on first use (disk I/O), never on the main thread. */
    private val cache: Cache by lazy(openCache)

    private val upstreamFactory: DataSource.Factory by lazy { OkHttpDataSource.Factory(okHttpClient) }

    private val readOnlyFactory: DataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(null)
    }

    /**
     * Data sources for the player: read from cache where complete, else network; never writes.
     * The cache itself is opened lazily on the first `createDataSource()` call, which ExoPlayer
     * makes on its playback thread (keeps disk I/O off the main thread).
     */
    val playerDataSourceFactory: DataSource.Factory = DataSource.Factory { readOnlyFactory.createDataSource() }

    private val writerDataSourceFactory: CacheDataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
    }

    override suspend fun isFullyCached(url: String): Boolean = withContext(io) {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(url))
        length != C.LENGTH_UNSET.toLong() && cache.isCached(url, 0, length)
    }

    override fun download(url: String): Flow<Float> = channelFlow {
        val writerRef = AtomicReference<CacheWriter?>(null)
        // CacheWriter.cache() blocks and touches the disk: build and run it on IO, stop it via
        // cancel() when the collector goes away.
        val work = async(io) {
            val writer = CacheWriter(
                writerDataSourceFactory.createDataSource(),
                DataSpec(Uri.parse(url)),
                null,
            ) { requestLength, bytesCached, _ ->
                val p = if (requestLength > 0) (bytesCached.toFloat() / requestLength).coerceIn(0f, 1f) else 0f
                trySend(p)
            }
            writerRef.set(writer)
            ensureActive()
            try {
                writer.cache()
            } catch (e: IOException) {
                // A cancelled writer stops with InterruptedIOException; report it as cancellation,
                // not as a download failure.
                ensureActive()
                throw e
            }
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            writerRef.get()?.cancel()
            throw e
        }
        // cache() also returns normally when bytes went around the cache (e.g. a span it could not
        // lock); only a complete entry with a stored length makes the track seekable.
        if (!isFullyCached(url)) throw IOException("Titel nicht vollständig im Zwischenspeicher")
        send(1f)
    }

    private companion object {
        const val DIR_NAME = "plebrap_audio"

        /** 41 tracks are ≈ 250 MB; keep them all, evict least recently used beyond that. */
        const val MAX_BYTES = 400L * 1024 * 1024
    }
}
