package space.einundzwanzig.meetup.data.proofs.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartUri
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.core.network.HttpResult
import space.einundzwanzig.meetup.core.network.getCapped
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.proofs.satoshiduell.DuellStatus
import space.einundzwanzig.meetup.data.proofs.satoshiduell.SatoshiDuellClient
import space.einundzwanzig.meetup.data.proofs.satoshiduell.SatoshiDuellClient.Companion.BASE_URL
import space.einundzwanzig.meetup.data.proofs.satoshiduell.SatoshiDuellClient.Companion.CACHE_TTL
import space.einundzwanzig.meetup.data.proofs.satoshiduell.SatoshiDuellClient.Companion.PUBLISHABLE_KEY
import space.einundzwanzig.meetup.data.proofs.satoshiduell.SatoshiDuellClient.Companion.REQUEST_TIMEOUT
import java.io.IOException
import javax.inject.Inject

/** Port of `SatoshiDuellService`. */
internal class DefaultSatoshiDuellClient(
    private val http: OkHttpClient,
    private val identity: ProofsIdentity,
    private val time: TimeSource,
    private val log: DiagnosticLog,
    private val dispatcher: CoroutineDispatcher,
    private val baseUrl: String,
) : SatoshiDuellClient {

    @Inject
    constructor(
        http: OkHttpClient,
        identity: ProofsIdentity,
        time: TimeSource,
        log: DiagnosticLog,
        @DefaultDispatcher dispatcher: CoroutineDispatcher,
    ) : this(http, identity, time, log, dispatcher, BASE_URL)

    private val mutex = Mutex()

    @Volatile
    private var cachedStatus: DuellStatus? = null

    @Volatile
    private var cachedAtMillis: Long = 0

    private val headers = mapOf(
        "apikey" to PUBLISHABLE_KEY,
        "Authorization" to "Bearer $PUBLISHABLE_KEY",
        "Accept" to "application/json",
    )

    override suspend fun openDuelCount(forceRefresh: Boolean): Int = fetchStatus(forceRefresh).actionCount

    override suspend fun fetchStatus(forceRefresh: Boolean): DuellStatus = withContext(dispatcher) { status(forceRefresh) }

    private suspend fun status(forceRefresh: Boolean): DuellStatus = mutex.withLock {
        val cached = cachedStatus
        if (!forceRefresh && cached != null && time.nowMillis() - cachedAtMillis < CACHE_TTL.inWholeMilliseconds) {
            return@withLock cached
        }
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.diag(TAG, "fetchStatus fehlgeschlagen: $e")
            cachedStatus ?: DuellStatus.EMPTY
        }
    }

    private suspend fun load(): DuellStatus {
        val npub = identity.npub()
        if (npub.isNullOrEmpty()) return DuellStatus.EMPTY
        val username = usernameFor(npub) ?: return DuellStatus.EMPTY

        val me = DartString.lower(username)
        val meEnc = DartUri.encodeQueryComponent("\"" + me.replace("\"", "") + "\"")

        var myTurn = 0
        var waiting = 0
        val mine = get(
            "$baseUrl/duels?select=status,creator,challenger,target_player,creator_score,challenger_score" +
                "&or=(creator.eq.$meEnc,challenger.eq.$meEnc,target_player.eq.$meEnc)" +
                "&order=created_at.desc&limit=50",
        )
        if (mine.status == 200) {
            for (g in objectsOf(mine)) {
                val st = g["status"].dartToStringOrEmpty()
                if (st != "open" && st != "active") continue
                val isCreator = DartString.lower(g["creator"].dartToStringOrEmpty()) == me
                val myScore = if (isCreator) g["creator_score"] else g["challenger_score"]
                if (myScore == null || myScore is JsonNull) {
                    if (st == "open" && isCreator) waiting++ else myTurn++
                } else {
                    waiting++
                }
            }
        } else {
            log.diag(TAG, "meine-Duelle-Abfrage: HTTP ${mine.status}")
        }

        var lobby = 0
        val open = get("$baseUrl/duels?select=creator,target_player&status=eq.open&limit=100")
        if (open.status == 200) {
            for (g in objectsOf(open)) {
                val creator = DartString.lower(g["creator"].dartToStringOrEmpty())
                val target = g["target_player"].dartToStringOrNull()?.let(DartString::lower)
                if (creator == me) continue
                if (target == null || target.isEmpty() || target == me) lobby++
            }
        } else {
            log.diag(TAG, "Lobby-Abfrage: HTTP ${open.status}")
        }

        val status = DuellStatus(myTurn = myTurn, waiting = waiting, lobby = lobby)
        log.diag(TAG, "Status für \"$me\": dran=$myTurn · wartet=$waiting · Lobby=$lobby")
        cachedStatus = status
        cachedAtMillis = time.nowMillis()
        return status
    }

    private suspend fun usernameFor(npub: String): String? {
        val r = get("$baseUrl/profiles?npub=eq.${DartUri.encodeQueryComponent(DartString.lower(npub))}&select=username&limit=1")
        if (r.status != 200) {
            log.diag(TAG, "profiles-Abfrage: HTTP ${r.status}")
            return null
        }
        val list = decode(r) as? JsonArray ?: return null
        val first = list.firstOrNull() as? JsonObject ?: return null
        return first["username"].dartToStringOrEmpty().ifEmpty { null }
    }

    private suspend fun get(url: String): HttpResult.Response =
        when (val r = http.getCapped(url, headers, maxBytes = MAX_BODY_BYTES, timeout = REQUEST_TIMEOUT)) {
            is HttpResult.Response -> r
            is HttpResult.Failure -> throw r.cause
            is HttpResult.TooLarge -> throw IOException("Antwort zu groß")
        }

    private fun decode(r: HttpResult.Response): JsonElement = DartJson.decode(DartUtf8.decodeStrict(r.body))

    /** Dart `if (list is List) list.whereType<Map>()`. */
    private fun objectsOf(r: HttpResult.Response): List<JsonObject> =
        (decode(r) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    private companion object {
        const val TAG = "SatoshiDuell"
        const val MAX_BODY_BYTES: Long = 1024L * 1024
    }
}
