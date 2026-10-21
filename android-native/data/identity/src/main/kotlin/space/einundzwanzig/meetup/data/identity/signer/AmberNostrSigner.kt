package space.einundzwanzig.meetup.data.identity.signer

import space.einundzwanzig.meetup.core.common.dart.dartTrim
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.identity.AmberConnectResult
import space.einundzwanzig.meetup.data.identity.SignerMissingException
import space.einundzwanzig.meetup.data.identity.SigningCancelledException
import space.einundzwanzig.meetup.data.identity.SigningError
import space.einundzwanzig.meetup.data.identity.SigningException
import space.einundzwanzig.meetup.data.identity.internal.dartToStringOrEmpty
import space.einundzwanzig.meetup.data.identity.nip55.Nip55Exception
import space.einundzwanzig.meetup.data.identity.nip55.Nip55Transport

/** Amber via NIP-55 (Dart `AmberNostrSigner`). */
internal class AmberNostrSigner(
    private val expectedPubkeyHex: String,
    private val transport: Nip55Transport,
    private val timeSource: TimeSource,
) : NostrSigner {

    override suspend fun pubkeyHex(): String = expectedPubkeyHex

    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent {
        val createdAt = timeSource.nowSeconds()
        // 'pubkey' is hex (NIP-01); NIP-55 expects the npub as current_user.
        val unsigned = jsonObjectOf(
            "pubkey" to expectedPubkeyHex,
            "created_at" to createdAt,
            "kind" to kind,
            "tags" to tags,
            "content" to content,
        )
        val result = try {
            transport.signEvent(DartJson.encode(unsigned), currentUserNpub())
        } catch (e: Nip55Exception) {
            throw mapError(e)
        }
        if (result.event.isEmpty()) throw SigningException("Amber hat kein signiertes Event geliefert.", error = SigningError.SignerNoEvent(SignerActor.AMBER))
        val signed = try {
            DartJson.decode(result.event) as? JsonObject ?: throw IllegalArgumentException("kein JSON-Objekt")
        } catch (e: Exception) {
            val detail = e.message ?: e::class.simpleName.orEmpty()
            throw SigningException("Amber-Antwort nicht lesbar: $detail", e, SigningError.SignerUnreadable(SignerActor.AMBER, detail))
        }
        // Security Audit N1: Amber is foreign code; check it like NIP-07/NIP-46.
        return verifySignerResponse(signed, expectedPubkeyHex, kind, tags, content, createdAt, SignerActor.AMBER)
    }

    override suspend fun nip44Encrypt(peerHex: String, plaintext: String): String =
        nip44("nip44_encrypt", peerHex, plaintext)

    override suspend fun nip44Decrypt(peerHex: String, payload: String): String =
        nip44("nip44_decrypt", peerHex, payload)

    private suspend fun nip44(type: String, peerHex: String, value: String): String = try {
        transport.nip44(type, peerHex, value, currentUserNpub())
    } catch (e: Nip55Exception) {
        throw mapError(e)
    }

    private fun currentUserNpub(): String = try {
        Nip19.encodeNpub(expectedPubkeyHex)
    } catch (e: Exception) {
        expectedPubkeyHex
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

        /** Dart `AmberNostrSigner.connect()`: availability, `get_public_key`, value parsing. */
        suspend fun connect(transport: Nip55Transport): AmberConnectResult {
            return try {
                if (!transport.isInstalled()) return AmberConnectResult.Missing
                val raw = transport.getPublicKey()
                if (raw.isBlank()) return AmberConnectResult.Cancelled
                parsePublicKey(raw)
            } catch (e: Nip55Exception) {
                when (e.code) {
                    "cancelled" -> AmberConnectResult.Cancelled
                    "signer_missing" -> AmberConnectResult.Missing
                    else -> AmberConnectResult.Error(e.message ?: e.code)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AmberConnectResult.Error(e.toString())
            }
        }

        /** Parses Amber's `get_public_key` answer: npub, 64 hex, or JSON `{"result"|"npub"|"pubkey": …}`. */
        fun parsePublicKey(raw: String): AmberConnectResult {
            var value = raw.dartTrim()
            if (value.startsWith("{")) {
                runCatching { DartJson.decode(value) as JsonObject }.getOrNull()?.let { map ->
                    val picked = map["result"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }
                        ?: map["npub"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }
                        ?: map["pubkey"]
                    value = picked.dartToStringOrEmpty().dartTrim()
                }
            }
            return try {
                when {
                    value.startsWith("npub1") -> AmberConnectResult.Success(Nip19.decodeNpub(value), value)
                    HEX64.matches(value) -> value.lowercase().let { AmberConnectResult.Success(it, Nip19.encodeNpub(it)) }
                    else -> AmberConnectResult.Error("Unerwartetes Schlüsselformat von Amber.")
                }
            } catch (e: Exception) {
                AmberConnectResult.Error("Ungültiger pubkey von Amber: ${e.message ?: e::class.simpleName}")
            }
        }

        /** Flutter bridge error codes → exceptions (spec 01 §6.6). */
        fun mapError(e: Nip55Exception): SigningException = when (e.code) {
            "cancelled", "rejected" -> SigningCancelledException()
            "signer_missing" -> SignerMissingException()
            else -> (e.message ?: e.code).let { SigningException("Amber-Fehler: $it", e, SigningError.AmberError(it)) }
        }
    }
}
