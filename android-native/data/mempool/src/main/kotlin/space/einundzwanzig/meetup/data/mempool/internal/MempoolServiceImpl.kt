package space.einundzwanzig.meetup.data.mempool.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.dartToInt
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.json.dartNumOrNull
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.network.HttpResult
import space.einundzwanzig.meetup.core.network.getCapped
import space.einundzwanzig.meetup.data.mempool.BitcoinDashboardData
import space.einundzwanzig.meetup.data.mempool.HostTestFailure
import space.einundzwanzig.meetup.data.mempool.MempoolHosts
import space.einundzwanzig.meetup.data.mempool.MempoolHttpException
import space.einundzwanzig.meetup.data.mempool.MempoolMode
import space.einundzwanzig.meetup.data.mempool.MempoolService
import space.einundzwanzig.meetup.data.mempool.MempoolService.Companion.PATH_DIFFICULTY
import space.einundzwanzig.meetup.data.mempool.MempoolService.Companion.PATH_FEES
import space.einundzwanzig.meetup.data.mempool.MempoolService.Companion.PATH_HASHRATE
import space.einundzwanzig.meetup.data.mempool.MempoolService.Companion.PATH_LIGHTNING
import space.einundzwanzig.meetup.data.mempool.MempoolService.Companion.PATH_PRICES
import space.einundzwanzig.meetup.data.mempool.MempoolService.Companion.PATH_TIP_HEIGHT
import space.einundzwanzig.meetup.data.mempool.MempoolService.Companion.SUPPORTED_CURRENCIES
import space.einundzwanzig.meetup.data.mempool.MempoolService.Companion.USER_AGENT
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * OkHttp + coroutines port of `lib/services/mempool.dart` (spec 13 §5, §8).
 *
 * Uses a variant of the shared client with its **own** connection pool, so [resetClient] can
 * drop this domain's keep-alive sockets without touching other domains. Every request runs
 * with a whole-call timeout via `getCapped`.
 */
@Singleton
internal class MempoolServiceImpl @Inject constructor(
    private val baseClient: OkHttpClient,
    private val config: MempoolConfigImpl,
    private val heights: BlockHeightStore,
    private val time: TimeSource,
    private val log: DiagnosticLog,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MempoolService {

    private val pool = ConnectionPool()
    private val client: OkHttpClient = baseClient.newBuilder().connectionPool(pool).build()

    private val clientLock = Any()

    /** Host the pooled connections belong to; guarded by [clientLock]. */
    private var clientHost: String = ""

    /** Bumped by [clearState]; a run started in an older generation must not write anything back. */
    private val generation = AtomicInteger()

    private val _lastDashboard = MutableStateFlow<BitcoinDashboardData?>(null)
    override val lastDashboard: StateFlow<BitcoinDashboardData?> = _lastDashboard.asStateFlow()

    // Error picture of the current run (Dart statics `_lastError` / `_lastBlocked`).
    @Volatile
    private var lastError = ""

    @Volatile
    private var lastBlocked = false

    // Noise suppression for repeated network errors (Dart `_lastNetSig` / `_lastNetSigAt`).
    private val netSigLock = Any()
    private var lastNetSig: String? = null
    private var lastNetSigAt: Long = 0

    private val flightMutex = Mutex()
    @Volatile
    private var inFlight: Deferred<BitcoinDashboardData>? = null

    // ---------------------------------------------------------------- client

    override fun resetClient() {
        val host = config.host
        synchronized(clientLock) {
            pool.evictAll()
            clientHost = host
        }
        log.diag(TAG, "HTTP-Client neu aufgebaut für $host")
    }

    /** Resets the client once per host change, even with six parallel sources (Dart `_get`). */
    private fun ensureClientFor(host: String) {
        val reset = synchronized(clientLock) {
            if (clientHost == host) {
                false
            } else {
                pool.evictAll()
                clientHost = host
                true
            }
        }
        if (reset) log.diag(TAG, "HTTP-Client neu aufgebaut für $host")
    }

    override fun clearLastDashboard() {
        _lastDashboard.value = null
    }

    override fun clearState() {
        // Fence a run that is still in flight: it must not write the old height/dataset back.
        generation.incrementAndGet()
        inFlight = null
        _lastDashboard.value = null
        heights.clear()
        lastError = ""
        lastBlocked = false
        synchronized(netSigLock) {
            lastNetSig = null
            lastNetSigAt = 0
        }
        synchronized(clientLock) {
            pool.evictAll()
            clientHost = ""
        }
    }

    /** Central GET (Dart `_get`): effective host, client reset on host change, status 200 only. */
    private suspend fun get(path: String, maxTimeout: Duration? = null): String {
        config.ensureLoaded()
        val host = config.host
        ensureClientFor(host)
        var timeout = MempoolHosts.timeoutFor(host)
        if (maxTimeout != null && maxTimeout < timeout) timeout = maxTimeout
        val result = client.getCapped("$host/api$path", DEFAULT_HEADERS, MAX_BODY_BYTES, timeout)
        return result.bodyOf(path)
    }

    // ---------------------------------------------------------------- sources

    override suspend fun getBlockHeight(): Int = try {
        val body = get(PATH_TIP_HEIGHT, maxTimeout = BLOCK_HEIGHT_MAX_TIMEOUT)
        val h = body.dartTrim().toIntOrNull()
        if (h == null || h <= 0) {
            val shown = if (body.length > 60) "${body.substring(0, 60)}…" else body
            log.diag(TAG, "Blockhöhe: unlesbare Antwort (kein Integer): \"$shown\"")
            0
        } else {
            h
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logFailure("Blockhöhe", e)
        0
    }

    override suspend fun getPrices(): Map<String, Double> = try {
        val data = decodeObject(get(PATH_PRICES))
        buildMap {
            for (cur in SUPPORTED_CURRENCIES) {
                // Dart: `if (v is num) result[cur] = v.toDouble()`; other types are skipped.
                data[cur].dartNumOrNull()?.let { put(cur, it) }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logFailure("Preise", e)
        emptyMap()
    }

    override suspend fun testHost(rawHost: String): Int {
        val host = MempoolHosts.normalize(rawHost)
        // A throw-away pool: the test must tell the truth about the network right now.
        val testPool = ConnectionPool()
        val testClient = baseClient.newBuilder().connectionPool(testPool).build()
        try {
            val result = testClient.getCapped(
                "$host/api$PATH_TIP_HEIGHT",
                TEST_HEADERS,
                MAX_BODY_BYTES,
                MempoolHosts.timeoutFor(host),
            )
            val body = result.bodyOf(PATH_TIP_HEIGHT)
            val h = body.dartTrim().toIntOrNull()
            if (h == null || h <= 0) throw MempoolHttpException(PATH_TIP_HEIGHT, 200, "Keine Zahl: $body")
            log.diag(TAG, "Verbindungstest OK: $host -> Block $h")
            return h
        } finally {
            testPool.evictAll()
        }
    }

    // ---------------------------------------------------------------- dashboard

    override suspend fun getDashboardData(): BitcoinDashboardData {
        val running = flightMutex.withLock {
            inFlight?.takeIf { it.isActive } ?: scope.async(io) { fetchDashboardGuarded() }.also { inFlight = it }
        }
        return running.await()
    }

    private suspend fun fetchDashboardGuarded(): BitcoinDashboardData = try {
        fetchDashboard(generation.get())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Never throw to callers; report a dead run carrying the previous values.
        log.error(TAG, "Dashboard-Abruf abgebrochen", e)
        lastError = HostTestFailure.errorTypeName(e)
        assemble(
            prev = _lastDashboard.value,
            height = heights.load().toInt(),
            fees0 = FEES_FAILED,
            prices = emptyMap(),
            hashrate0 = -1.0,
            diff0 = DIFF_FAILED,
            ln0 = LN_FAILED,
            ok = 0,
        )
    }

    private suspend fun fetchDashboard(gen: Int): BitcoinDashboardData {
        config.ensureLoaded()
        // Captured before the mode check: the fallback may only run while this choice stands.
        val persistedAtStart = config.persistedHost
        var data = fetchFromCurrentHost(gen)
        if (data.sourcesOk > 0) return data
        // Tor or own instance chosen: never silently switch to a clearnet mirror (privacy).
        if (config.mode != MempoolMode.CLEARNET) return data

        val original = config.host
        for (fb in MempoolHosts.FALLBACKS) {
            if (fb == original) continue
            if (generation.get() != gen) return data // app reset meanwhile
            // Atomic with setHost: a switch to Tor/own node during the loop ends the fallback.
            if (!config.trySetFallbackOverride(fb, persistedAtStart)) {
                log.diag(TAG, "Host-Auswahl während des Fallbacks geändert — kein Spiegelserver")
                return data
            }
            log.warn(TAG, "Keine Daten von $original — Fallback $fb")
            resetClient()
            data = fetchFromCurrentHost(gen)
            if (data.sourcesOk > 0) {
                if (config.persistedHost != persistedAtStart) {
                    // The user chose another host while the mirror answered; it wins.
                    config.setSessionOverride(null)
                } else {
                    log.diag(TAG, "Fallback OK: $fb (${data.sourcesOk}/6 Quellen)")
                }
                return data // otherwise the override stays for the session
            }
        }
        if (config.persistedHost == persistedAtStart) {
            config.setSessionOverride(null)
            resetClient()
        }
        return data
    }

    private suspend fun fetchFromCurrentHost(gen: Int): BitcoinDashboardData {
        config.ensureLoaded()
        val prev = _lastDashboard.value
        lastError = ""
        lastBlocked = false
        val ok = AtomicInteger(0)

        val sources = coroutineScope {
            val height = async { getBlockHeight() }
            val fees = async {
                safe("Fees", ok, FEES_FAILED) {
                    val d = decodeObject(get(PATH_FEES))
                    listOf(
                        d.num("economyFee")?.dartToInt()?.toInt() ?: d.num("minimumFee")?.dartToInt()?.toInt() ?: 0,
                        d.num("halfHourFee")?.dartToInt()?.toInt() ?: 0,
                        d.num("fastestFee")?.dartToInt()?.toInt() ?: 0,
                    )
                }
            }
            val prices = async { getPrices() }
            val hashrate = async {
                safe("Hashrate", ok, -1.0) {
                    val d = decodeObject(get(PATH_HASHRATE))
                    (d.num("currentHashrate") ?: 0.0) / 1e18 // H/s -> EH/s
                }
            }
            val difficulty = async {
                safe("Difficulty", ok, DIFF_FAILED) {
                    val d = decodeObject(get(PATH_DIFFICULTY))
                    listOf(
                        d.num("difficultyChange") ?: 0.0,
                        (d.num("remainingBlocks")?.dartToInt() ?: 0L).toDouble(),
                    )
                }
            }
            val lightning = async {
                safe("Lightning", ok, LN_FAILED) {
                    val d = decodeObject(get(PATH_LIGHTNING))
                    val latest = when (val l = d["latest"]) {
                        null, is JsonNull -> d
                        is JsonObject -> l
                        else -> throw TypeError("'latest' ist kein Objekt")
                    }
                    listOf(
                        (latest.num("total_capacity") ?: 0.0) / 1e8, // sats -> BTC
                        (latest.num("node_count")?.dartToInt() ?: 0L).toDouble(),
                        (latest.num("channel_count")?.dartToInt() ?: 0L).toDouble(),
                    )
                }
            }
            Sources(
                height = height.await(),
                fees = fees.await(),
                prices = prices.await(),
                hashrate = hashrate.await(),
                difficulty = difficulty.await(),
                lightning = lightning.await(),
            )
        }

        val height0 = sources.height
        if (height0 > 0) ok.incrementAndGet() // the height counts as its own source

        // Monotonic guard: the height never goes down, across runs and processes.
        val lastKnown = heights.load()
        val height = if (height0 > lastKnown) height0.toLong() else lastKnown
        val current = generation.get() == gen
        if (height0 > lastKnown) {
            if (current) heights.save(height0.toLong())
        } else if (height0 == 0 && lastKnown > 0) {
            log.diag(
                TAG,
                "Blockhöhe NICHT geladen — zeige letzten bekannten Stand ($lastKnown). " +
                    "Das Widget behält damit den frischesten Wert, egal welcher Aufrufer zuletzt schreibt.",
            )
        } else if (height0 > 0 && height0 < lastKnown) {
            log.diag(TAG, "Veraltete Blockhöhe verworfen: Server meldet $height0, bekannt ist bereits $lastKnown.")
        }

        if (sources.prices.isNotEmpty()) ok.incrementAndGet() // own source, catches internally

        val okCount = ok.get()
        if (okCount == 0) {
            val hint = if (lastBlocked) {
                " — Server weist Anfragen ab (Cloudflare/Rate-Limit). Bei Tor: Onion-Adresse in den Einstellungen wählen."
            } else {
                ""
            }
            log.warn(TAG, "ALLE Quellen fehlgeschlagen @ ${config.host}$hint")
            resetClient()
        } else if (okCount < BitcoinDashboardData.SOURCES_TOTAL) {
            log.diag(TAG, "Nur $okCount von 6 Quellen geliefert.")
        }

        val data = assemble(
            prev = prev,
            height = height.toInt(),
            fees0 = sources.fees,
            prices = sources.prices,
            hashrate0 = sources.hashrate,
            diff0 = sources.difficulty,
            ln0 = sources.lightning,
            ok = okCount,
        )
        // A dead run never replaces a good dataset.
        if (okCount > 0 && current && generation.get() == gen) _lastDashboard.value = data
        return data
    }

    /** Last-good-value guards (error sentinels −1 / NaN) and assembly (spec 13 §8.3 steps 4, 6). */
    private fun assemble(
        prev: BitcoinDashboardData?,
        height: Int,
        fees0: List<Int>,
        prices: Map<String, Double>,
        hashrate0: Double,
        diff0: List<Double>,
        ln0: List<Double>,
        ok: Int,
    ): BitcoinDashboardData {
        val fees = if (fees0[0] >= 0) fees0 else listOf(prev?.feeLow ?: 0, prev?.feeMedium ?: 0, prev?.feeHigh ?: 0)
        val hashrate = if (hashrate0 >= 0) hashrate0 else prev?.hashrateEhs ?: 0.0
        val diff = if (!diff0[0].isNaN()) {
            diff0
        } else {
            listOf(prev?.difficultyChangePct ?: 0.0, (prev?.difficultyRemainingBlocks ?: 0).toDouble())
        }
        val ln = if (ln0[0] >= 0) {
            ln0
        } else {
            listOf(prev?.lnCapacityBtc ?: 0.0, (prev?.lnNodeCount ?: 0).toDouble(), (prev?.lnChannelCount ?: 0).toDouble())
        }
        return BitcoinDashboardData(
            blockHeight = height,
            feeLow = fees[0],
            feeMedium = fees[1],
            feeHigh = fees[2],
            priceEur = prices["EUR"] ?: prev?.priceEur ?: 0.0,
            priceUsd = prices["USD"] ?: prev?.priceUsd ?: 0.0,
            prices = prices.ifEmpty { prev?.prices ?: emptyMap() },
            supply = if (height > 0) BitcoinDashboardData.circulatingSupply(height) else prev?.supply ?: 0L,
            hashrateEhs = hashrate,
            difficultyChangePct = diff[0],
            difficultyRemainingBlocks = diff[1].dartToInt().toInt(),
            lnCapacityBtc = ln[0],
            lnNodeCount = ln[1].dartToInt().toInt(),
            lnChannelCount = ln[2].dartToInt().toInt(),
            updatedAtMillis = time.nowMillis(),
            sourcesOk = ok,
            sourcesTotal = BitcoinDashboardData.SOURCES_TOTAL,
            lastError = lastError,
            blocked = lastBlocked,
        )
    }

    /** Dart `safe()`: counts successes, logs failures and returns [fallback] instead of throwing. */
    private suspend fun <T> safe(source: String, ok: AtomicInteger, fallback: T, block: suspend () -> T): T = try {
        block().also { ok.incrementAndGet() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logFailure(source, e)
        fallback
    }

    /** Dart `_logFailure`: records the error code and logs, suppressing repeated network errors. */
    private fun logFailure(source: String, e: Throwable) {
        val host = config.host
        if (e is MempoolHttpException) {
            lastError = "HTTP ${e.statusCode}"
            if (e.looksBlocked) lastBlocked = true
            log.diag(TAG, "$source FEHLGESCHLAGEN — HTTP ${e.statusCode} @ $host${e.path} · Antwort: ${e.snippet}")
            return
        }
        val type = HostTestFailure.errorTypeName(e)
        lastError = type
        val sig = "$type@$host"
        val now = time.nowMillis()
        synchronized(netSigLock) {
            if (lastNetSig == sig && now - lastNetSigAt < NET_NOISE_WINDOW_MILLIS) return
            lastNetSig = sig
            lastNetSigAt = now
        }
        log.diag(
            TAG,
            "$source FEHLGESCHLAGEN — $type @ $host · ${dartErrorText(e, type)} " +
                "(weitere gleichartige Fehler dieses Abrufs werden unterdrueckt)",
        )
    }

    /** Dart's `$e` (`Type: message`), with the simple class name instead of the qualified one. */
    private fun dartErrorText(e: Throwable, type: String): String =
        e.message?.takeIf { it.isNotEmpty() }?.let { "$type: $it" } ?: type

    private class Sources(
        val height: Int,
        val fees: List<Int>,
        val prices: Map<String, Double>,
        val hashrate: Double,
        val difficulty: List<Double>,
        val lightning: List<Double>,
    )

    companion object {
        private const val TAG = "Mempool"

        /** Body cap; the largest response (`hashrate/3d`) is a few tens of KiB. */
        private const val MAX_BODY_BYTES = 2L * 1024 * 1024

        /** `getBlockHeight` cap so a badge scan with one retry stays below ~45 s even over Tor. */
        private val BLOCK_HEIGHT_MAX_TIMEOUT = 20.seconds

        private const val NET_NOISE_WINDOW_MILLIS = 30_000L

        private val FEES_FAILED = listOf(-1, -1, -1)
        private val DIFF_FAILED = listOf(Double.NaN, Double.NaN)
        private val LN_FAILED = listOf(-1.0, -1.0, -1.0)

        /** Headers of every dashboard/price/height request (native branch, spec 13 §5.3). */
        internal val DEFAULT_HEADERS: Map<String, String> = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Cache-Control" to "no-cache",
        )

        /** Headers of the connection test: no `Cache-Control`. */
        internal val TEST_HEADERS: Map<String, String> = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/plain, */*",
        )

        /** Turns a capped HTTP result into the body text or the exception Dart would have thrown. */
        private fun HttpResult.bodyOf(path: String): String = when (this) {
            is HttpResult.Response -> {
                val text = text()
                if (status != 200) throw MempoolHttpException(path, status, text)
                text
            }
            is HttpResult.TooLarge ->
                if (status != 200) throw MempoolHttpException(path, status, "") else throw IOException("Antwort zu gross")
            is HttpResult.Failure -> throw cause.asTimeoutIfWholeCall()
        }

        /** The whole-call timeout becomes [TimeoutException] (Dart's name); socket timeouts stay. */
        private fun IOException.asTimeoutIfWholeCall(): IOException =
            if (this::class == InterruptedIOException::class) TimeoutException(message ?: "timeout").also { it.initCause(this) } else this
    }
}
