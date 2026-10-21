package space.einundzwanzig.meetup.data.identity.signer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.isDartInt
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.identity.SigningError
import space.einundzwanzig.meetup.data.identity.SigningException
import space.einundzwanzig.meetup.data.identity.WrongAccountException
import space.einundzwanzig.meetup.data.identity.internal.dartToString
import space.einundzwanzig.meetup.data.identity.internal.dartToStringOrEmpty

/** The counterpart named in signer error texts. */
enum class SignerActor(
    /** The German subject used in the messages. */
    val label: String,
) {
    /** NIP-55. */
    AMBER("Amber"),

    /** NIP-46. */
    REMOTE_SIGNER("Der Signer"),

    /** NIP-07 (web only). */
    EXTENSION("Die Erweiterung"),
}

/**
 * Verifies an external signer's answer (Dart `verifySignerResponse`, Security Audit N1, spec 01
 * §7.6) and builds the result. Shared by Amber, NIP-46 and NIP-07 so no signer is checked weaker
 * than another:
 * 1. `pubkey` (lower-cased) must be [expectedPubkeyHex] → else [WrongAccountException];
 * 2. `sig` non-empty; 3. an int `kind` must equal [kind]; 4. a present `content` must equal [content];
 * 5. `created_at` from the signer if int (else [fallbackCreatedAt]), tags from the signer if they
 *    are a list of lists (elements stringified), else the caller's [tags];
 * 6. the event must pass `Event.isValid()` (10-digit `created_at`, id equality, BIP-340).
 *
 * @throws SigningException (or [WrongAccountException]) naming [actor].
 */
fun verifySignerResponse(
    signed: JsonObject,
    expectedPubkeyHex: String,
    kind: Int,
    tags: List<List<String>>,
    content: String,
    fallbackCreatedAt: Long,
    actor: SignerActor,
): NostrEvent {
    val who = actor.label
    val signedPubkey = signed["pubkey"].dartToStringOrEmpty()
    if (signedPubkey.lowercase() != expectedPubkeyHex.lowercase()) throw WrongAccountException()

    val sig = signed["sig"].dartToStringOrEmpty()
    if (sig.isEmpty()) throw SigningException("$who hat kein signiertes Event geliefert.", error = SigningError.SignerNoEvent(actor))

    val signedKind = signed["kind"]
    if (signedKind != null && signedKind.isDartInt && signedKind.dartIntOrNull() != kind.toLong()) {
        val got = signedKind.dartIntOrNull()
        throw SigningException(
            "$who hat einen anderen Event-Typ signiert ($got statt $kind).",
            error = SigningError.SignerKindChanged(actor, got ?: 0L, kind),
        )
    }
    if (signed.containsKey("content") && signed["content"].dartToStringOrEmpty() != content) {
        throw SigningException("$who hat den Inhalt des Events verändert.", error = SigningError.SignerContentChanged(actor))
    }

    val id = signed["id"].dartToStringOrEmpty()
    val createdAt = signed["created_at"].dartIntOrNull() ?: fallbackCreatedAt
    val parsedTags = tagsFromSigned(signed["tags"], tags)

    val valid = runCatching { EventIds.isValid(id, signedPubkey, createdAt, kind, parsedTags, content, sig) }
        .getOrDefault(false)
    if (!valid) throw SigningException("$who hat keine gültige Signatur geliefert.", error = SigningError.SignerBadSig(actor))

    return NostrEvent(
        id = id,
        pubkey = signedPubkey,
        createdAt = createdAt,
        kind = kind,
        tags = parsedTags,
        content = content,
        sig = sig,
    )
}

private fun tagsFromSigned(raw: Any?, fallback: List<List<String>>): List<List<String>> {
    if (raw !is JsonArray) return fallback
    val out = ArrayList<List<String>>(raw.size)
    for (tag in raw) {
        if (tag !is JsonArray) return fallback
        out += tag.map { it.dartToString() }
    }
    return out
}
