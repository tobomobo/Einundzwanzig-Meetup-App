package space.einundzwanzig.meetup.data.identity

import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import javax.inject.Inject

/** Flutter NostrService's kind-21000 compatibility API. Signing never publishes an event. */
class LegacyNostrService @Inject constructor(
    private val signing: SigningService,
    private val clock: TimeSource,
) {
    suspend fun sign(data: String): String {
        requireSigner()
        return signing.signEvent(21000, emptyList(), data).sig
    }

    fun verify(
        data: String,
        signature: String,
        pubkeyHex: String,
        eventId: String,
        createdAt: Long,
        tags: List<List<String>> = emptyList(),
        kind: Int = 21000,
    ): Boolean = runCatching { NostrEvent(eventId, pubkeyHex, createdAt, kind, tags, data, signature).isValid() }.getOrDefault(false)

    suspend fun signBadge(meetupId: String, meetupName: String, date: String, blockHeight: Long): Map<String, Any> {
        val npub = signing.npub() ?: throw SigningException("No private key", error = SigningError.NoPrivateKey)
        requireSigner()
        val content = DartJson.encode(linkedMapOf(
            "meetup_id" to meetupId,
            "meetup_name" to meetupName,
            "date" to date,
            "block_height" to blockHeight,
            "npub" to npub,
            "timestamp" to clock.nowMillis(),
        ))
        val signed = signing.signEvent(21000, listOf(listOf("meetup", meetupId), listOf("block", blockHeight.toString())), content)
        return linkedMapOf(
            "event_id" to signed.id, "pubkey" to signed.pubkey, "npub" to npub,
            "signature" to signed.sig, "content" to content, "created_at" to signed.createdAt,
        )
    }

    private suspend fun requireSigner() {
        if (!signing.canSign()) throw SigningException("No private key", error = SigningError.NoPrivateKey)
    }
}
