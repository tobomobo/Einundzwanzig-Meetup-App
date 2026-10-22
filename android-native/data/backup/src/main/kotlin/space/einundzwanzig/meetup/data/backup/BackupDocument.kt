package space.einundzwanzig.meetup.data.backup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull

/**
 * A decrypted backup that passed the Flutter shape checks: a JSON object with `user` and `badges`
 * (spec 08 §2.5 steps 5–6). Field-level validation happens in [RestoreEngine.restore].
 *
 * @property data the whole backup object (unknown keys such as `my_vouches` are kept and ignored).
 */
class BackupDocument private constructor(val data: JsonObject) {

    /**
     * `data['version'] ?? 1` (Dart `int`).
     *
     * @throws BackupFormatException if `version` is present but not an int (Dart's cast throws).
     */
    val version: Long
        get() {
            val raw = data["version"]
            if (raw == null || raw is JsonNull) return 1
            return raw.dartIntOrNull() ?: throw BackupFormatException("version ist keine Ganzzahl")
        }

    override fun toString(): String = "BackupDocument(version=${runCatching { version }.getOrNull()}, keys=${data.keys})"

    companion object {
        /** Format version written by [BackupExporter] (v5: + platform proofs + humanity proof). */
        const val CURRENT_VERSION: Int = 5

        /** Constant `app` field (not validated on import). */
        const val APP_NAME: String = "Einundzwanzig Meetup App"

        /**
         * Dart `jsonDecode` + `containsKey('user') && containsKey('badges')`, on [dispatcher]
         * (decoding a backup of several MB is CPU-heavy; never run it on the main thread).
         */
        suspend fun parse(json: String, dispatcher: CoroutineDispatcher = Dispatchers.Default): ParseResult =
            withContext(dispatcher) { parseBlocking(json) }

        /**
         * Same as [parse] on the calling thread. CPU-bound: only for callers that are already off
         * the main thread (workers, tests, tooling).
         */
        fun parseBlocking(json: String): ParseResult {
            val element: JsonElement = try {
                DartJson.decode(json)
            } catch (_: Exception) {
                return ParseResult.NotValid
            }
            val obj = element as? JsonObject ?: return ParseResult.NotValid
            if (!obj.containsKey("user") || !obj.containsKey("badges")) return ParseResult.NotEinundzwanzig
            return ParseResult.Ok(BackupDocument(obj))
        }

        /** Wraps an already parsed object without the shape checks (tests, tooling). */
        fun of(data: JsonObject): BackupDocument = BackupDocument(data)
    }
}

/** Result of [BackupDocument.parse]. */
sealed interface ParseResult {
    /** A backup to hand to [RestoreEngine.restore]. */
    data class Ok(val document: BackupDocument) : ParseResult

    /** Not a JSON object (`backupNotValid`). */
    data object NotValid : ParseResult

    /** JSON object without `user`/`badges` (`backupNotEinundzwanzig`). */
    data object NotEinundzwanzig : ParseResult
}

/** A backup field has a type Flutter's importer would reject (its casts throw). */
class BackupFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * How an import treats the device's identity (spec 08 §3.5, §8.2).
 *
 * @property logName the Dart enum name used in the diagnostic log.
 */
enum class RestoreDecision(val logName: String) {
    /** No identity on the device: plain restore. */
    FRESH("fresh"),

    /** Same npub (case-insensitive): profile and badges are merged additively. */
    SAME_IDENTITY("sameIdentity"),

    /** The backup has no identity: added to the device's identity after confirmation. */
    ADD_TO_IDENTITY("addToIdentity"),

    /** A different identity: replaces the device's identity after a destructive confirmation. */
    REPLACES_IDENTITY("replacesIdentity"),
    ;

    /** `sameIdentity` or `addToIdentity`. */
    val isAdditive: Boolean get() = this == SAME_IDENTITY || this == ADD_TO_IDENTITY
}
