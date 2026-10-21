package space.einundzwanzig.meetup.data.proofs.platform

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.dartNumOrNull
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf

/**
 * A stored platform link (Dart `PlatformProof`, spec 06 §3.2).
 *
 * @property platform platform id (catalogue id or custom id); at most one proof per id.
 * @property username trimmed username as signed.
 * @property proofSig 128 lowercase hex chars: the BIP-340 signature of the kind-21003 event.
 * @property createdAt Unix seconds (device clock at creation; also inside the signed content).
 */
data class PlatformProof(
    val platform: String,
    val username: String,
    val proofSig: String,
    val createdAt: Long,
) {
    /** Local list / backup entry: `{"platform","username","proof_sig","created_at"}` in this order. */
    fun toStorageJson(): JsonObject = jsonObjectOf(
        "platform" to platform,
        "username" to username,
        "proof_sig" to proofSig,
        "created_at" to createdAt,
    )

    /** Kind-30078 value (Dart `toJson`): `{"username","proof_sig","created_at"}`. */
    fun toReputationJson(): JsonObject = jsonObjectOf(
        "username" to username,
        "proof_sig" to proofSig,
        "created_at" to createdAt,
    )

    /** Reputation-QR `pp` value: `{"u": username, "s": proofSig}`. */
    fun toQrJson(): JsonObject = jsonObjectOf("u" to username, "s" to proofSig)

    companion object {
        /** Pref key of the local list (JSON string). */
        const val STORAGE_KEY: String = "platform_proofs"

        /**
         * Dart `PlatformProof.fromJson(platform, json)` (reputation event): lenient with
         * defaults `""`, `""`, `0`. Values of the wrong type also fall back to the default
         * (Dart's `as String?` would throw and reject the whole reputation event).
         */
        fun fromReputationJson(platform: String, json: JsonObject): PlatformProof = PlatformProof(
            platform = platform,
            username = json["username"].dartStringOrNull() ?: "",
            proofSig = json["proof_sig"].dartStringOrNull() ?: "",
            createdAt = json["created_at"].lenientLong() ?: 0L,
        )

        /**
         * One local list entry, or `null` if it lacks a string `platform`, `username` or
         * `proof_sig`. A missing or non-integral `created_at` reads as 0 (Dart threw and lost the
         * whole list, spec 06 §14 R9).
         */
        fun fromStorageJson(json: JsonElement): PlatformProof? {
            val obj = json as? JsonObject ?: return null
            return PlatformProof(
                platform = obj["platform"].dartStringOrNull() ?: return null,
                username = obj["username"].dartStringOrNull() ?: return null,
                proofSig = obj["proof_sig"].dartStringOrNull() ?: return null,
                createdAt = obj["created_at"].lenientLong() ?: 0L,
            )
        }

        private fun JsonElement?.lenientLong(): Long? =
            dartIntOrNull() ?: dartNumOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() && it == Math.floor(it) }?.toLong()
    }
}
