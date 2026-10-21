package space.einundzwanzig.meetup.data.identity.passkey

import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.DartBase64
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.core.crypto.hexToBytes
import space.einundzwanzig.meetup.core.crypto.toHex

/**
 * WebAuthn JSON for Credential Manager, byte-compatible with what `passkeys_android` 2.13 built
 * for the Flutter app (same RP id, salt and user id, so a Flutter passkey wrap opens natively).
 */
object PasskeyJson {
    /** Relying party id (needs asset links on this domain). */
    const val RP_ID = "einundzwanzig.space"

    /** Relying party name. */
    const val RP_NAME = "21meetup"

    /** PRF salt (UTF-8). */
    const val PRF_SALT = "21meetup-easyauth-prf-v1"

    /** [PRF_SALT] as base64url without padding: `MjFtZWV0dXAtZWFzeWF1dGgtcHJmLXYx`. */
    val prfSaltB64: String = DartBase64.encodeUrlNoPadding(DartUtf8.encode(PRF_SALT))

    /** Registration request (`CreatePublicKeyCredentialRequest`). */
    fun registrationRequest(userIdHex: String, userName: String, challengeB64: String): String = DartJson.encode(
        jsonObjectOf(
            "rp" to jsonObjectOf("id" to RP_ID, "name" to RP_NAME),
            "user" to jsonObjectOf(
                "name" to userName,
                "displayName" to userName,
                // base64url WITH '=' padding (passkeys_android); changing it only affects new credentials.
                "id" to DartBase64.encodeUrl(userIdHex.hexToBytes()),
            ),
            "challenge" to challengeB64,
            "attestation" to "none",
            "timeout" to 60000,
            // Without ES256 + RS256 Android answers TYPE_NOT_SUPPORTED_ERROR.
            "pubKeyCredParams" to listOf(
                mapOf("type" to "public-key", "alg" to -7),
                mapOf("type" to "public-key", "alg" to -257),
            ),
            "authenticatorSelection" to jsonObjectOf(
                "authenticatorAttachment" to "platform",
                "requireResidentKey" to false,
                "residentKey" to "preferred",
                "userVerification" to "preferred",
            ),
            "excludeCredentials" to emptyList<Any>(),
            "extensions" to prfExtension(),
        ),
    )

    /** Authentication request (`GetPublicKeyCredentialOption`). */
    fun authenticationRequest(credentialIds: List<String>, challengeB64: String): String {
        val fields = linkedMapOf<String, Any?>(
            "challenge" to challengeB64,
            "timeout" to 60000,
            "rpId" to RP_ID,
        )
        if (credentialIds.isNotEmpty()) {
            fields["allowCredentials"] = credentialIds.map { mapOf("type" to "public-key", "id" to it, "transports" to emptyList<String>()) }
        }
        fields["userVerification"] = "preferred"
        fields["extensions"] = prfExtension()
        return DartJson.encode(fields)
    }

    /** A fresh 32-byte challenge, base64url without padding (no server verifies it). */
    fun randomChallenge(): String = DartBase64.encodeUrlNoPadding(space.einundzwanzig.meetup.core.crypto.CryptoRandom.bytes(32))

    /** The credential `id` of a WebAuthn response JSON. */
    fun credentialId(responseJson: String): String? =
        (DartJson.decode(responseJson) as? JsonObject)?.get("id")?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content
        }

    /**
     * `clientExtensionResults.prf.results.first` (base64url, padding optional) as lowercase hex,
     * or `null` if absent, empty or undecodable.
     */
    fun prfSecretHex(responseJson: String): String? = try {
        val root = DartJson.decode(responseJson) as? JsonObject
        val ext = root?.get("clientExtensionResults") as? JsonObject
        val prf = ext?.get("prf") as? JsonObject
        val results = prf?.get("results") as? JsonObject
        val first = (results?.get("first") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        if (first.isNullOrEmpty()) null else DartBase64.decodeUrlLenient(first).takeIf { it.isNotEmpty() }?.toHex()
    } catch (e: Exception) {
        null
    }

    private fun prfExtension(): JsonObject = jsonObjectOf("prf" to jsonObjectOf("eval" to jsonObjectOf("first" to prfSaltB64)))
}
