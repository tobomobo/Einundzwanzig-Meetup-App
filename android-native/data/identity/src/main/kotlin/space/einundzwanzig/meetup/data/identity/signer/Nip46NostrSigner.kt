package space.einundzwanzig.meetup.data.identity.signer

import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.identity.SigningError
import space.einundzwanzig.meetup.data.identity.SigningException
import space.einundzwanzig.meetup.data.identity.nip46.Nip46Client
import space.einundzwanzig.meetup.data.identity.nip46.Nip46Exception
import space.einundzwanzig.meetup.data.identity.nip46.Nip46RemoteException
import space.einundzwanzig.meetup.data.identity.nip46.Nip46TimeoutException

/**
 * Remote signer (Dart `Nip46NostrSigner`). [clientProvider] returns the held session (rehydrating
 * it if needed). [signFn] / [nip44Fn] replace the client in tests (Dart `debugSignFn`/`debugNip44Fn`).
 */
internal class Nip46NostrSigner(
    private val expectedPubkeyHex: String,
    private val clientProvider: suspend () -> Nip46Client,
    private val timeSource: TimeSource,
    private val signFn: (suspend (JsonObject) -> JsonObject)? = null,
    private val nip44Fn: (suspend (method: String, peerHex: String, value: String) -> String)? = null,
) : NostrSigner {

    override suspend fun pubkeyHex(): String = expectedPubkeyHex

    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent {
        val createdAt = timeSource.nowSeconds()
        // Unlike NIP-07 the pubkey is sent: many signers check it against the approved account.
        val unsigned = jsonObjectOf(
            "pubkey" to expectedPubkeyHex,
            "created_at" to createdAt,
            "kind" to kind,
            "tags" to tags,
            "content" to content,
        )
        val signed = try {
            signFn?.invoke(unsigned) ?: clientProvider().signEvent(unsigned)
        } catch (e: Nip46TimeoutException) {
            throw SigningException(NO_ANSWER, e, SigningError.BunkerNoAnswer)
        } catch (e: Nip46RemoteException) {
            throw SigningException("Der Signer hat abgelehnt: ${e.message}", e, SigningError.BunkerRejected(e.message))
        } catch (e: Nip46Exception) {
            throw SigningException(e.message, e, SigningError.BunkerFailure(e.message))
        }
        return verifySignerResponse(signed, expectedPubkeyHex, kind, tags, content, createdAt, SignerActor.REMOTE_SIGNER)
    }

    override suspend fun nip44Encrypt(peerHex: String, plaintext: String): String = nip44("nip44_encrypt", peerHex, plaintext)

    override suspend fun nip44Decrypt(peerHex: String, payload: String): String = nip44("nip44_decrypt", peerHex, payload)

    private suspend fun nip44(method: String, peerHex: String, value: String): String = try {
        nip44Fn?.invoke(method, peerHex, value) ?: clientProvider().let { client ->
            if (method == "nip44_encrypt") client.nip44Encrypt(peerHex, value) else client.nip44Decrypt(peerHex, value)
        }
    } catch (e: Nip46TimeoutException) {
        AppLogger.warn(
            "Nip44Probe",
            "$method: keine Signer-Antwort nach ${e.limit.inWholeSeconds} s; ${e.undecryptable} unlesbare Antworten, " +
                "${e.foreignSender} Antworten fremder Absender.",
        )
        throw SigningException(NO_ANSWER, e, SigningError.BunkerNoAnswer)
    } catch (e: Nip46RemoteException) {
        throw SigningException("Der Signer hat abgelehnt: ${e.message}", e, SigningError.BunkerRejected(e.message))
    } catch (e: Nip46Exception) {
        throw SigningException(e.message, e, SigningError.BunkerFailure(e.message))
    }

    companion object {
        const val NO_ANSWER = "Der Signer hat nicht geantwortet. Ist die Signer-App geöffnet und die Freigabe bestätigt?"
    }
}
