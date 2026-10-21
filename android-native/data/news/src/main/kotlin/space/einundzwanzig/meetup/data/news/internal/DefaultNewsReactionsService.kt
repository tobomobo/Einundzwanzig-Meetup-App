package space.einundzwanzig.meetup.data.news.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayFanOut
import space.einundzwanzig.meetup.core.nostr.relay.RelayPublishOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayPublishStatus
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.news.ArticleLikes
import space.einundzwanzig.meetup.data.news.NewsReactionsService
import space.einundzwanzig.meetup.data.news.NewsReactionsService.Companion.FETCH_CONNECT_TIMEOUT
import space.einundzwanzig.meetup.data.news.NewsReactionsService.Companion.KEY_LIKED_REACTIONS
import space.einundzwanzig.meetup.data.news.NewsReactionsService.Companion.KIND_REACTION
import space.einundzwanzig.meetup.data.news.NewsReactionsService.Companion.LIKED_CAP
import space.einundzwanzig.meetup.data.news.NewsReactionsService.Companion.LIKES_LIMIT
import space.einundzwanzig.meetup.data.news.NewsReactionsService.Companion.LIKES_SUBSCRIPTION_ID
import space.einundzwanzig.meetup.data.news.NewsReactionsService.Companion.LIKE_CONTENT
import space.einundzwanzig.meetup.data.news.NewsReactionsService.Companion.PUBLISH_CONNECT_TIMEOUT
import space.einundzwanzig.meetup.data.news.NewsReactionsService.Companion.READ_ONLY_RELAYS
import space.einundzwanzig.meetup.data.news.NewsReactionsService.Companion.TIMEOUT
import space.einundzwanzig.meetup.data.news.NewsReactionsService.Companion.isPositive
import space.einundzwanzig.meetup.data.news.NewsReactionsService.Companion.likeTags
import space.einundzwanzig.meetup.data.news.NewsReactionsService.Companion.likedEntry
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Port of `lib/services/news_reactions_service.dart`. */
@Singleton
internal class DefaultNewsReactionsService @Inject constructor(
    private val lookup: LightningAddressLookup,
    private val relayClient: RelayClient,
    private val signing: SigningService,
    private val store: KeyValueStore,
    private val log: DiagnosticLog,
    @ApplicationScope private val appScope: CoroutineScope,
) : NewsReactionsService {

    override suspend fun readTargets(): List<String> = lookup.readTargets()

    override suspend fun writeTargets(): List<String> = readTargets().filter { it !in READ_ONLY_RELAYS }

    override suspend fun hasLikedLocally(articleAddress: String): Boolean {
        val me = myPubkey() ?: return false
        return likedEntries().contains(likedEntry(me, articleAddress))
    }

    override suspend fun fetchLikes(articleAddress: String): ArticleLikes {
        val me = myPubkey()
        // A set, not a counter: the same reaction comes back from several relays.
        val reactors = LinkedHashSet<String>()
        val perRelay = LinkedHashMap<String, Int>()
        var mine = false

        val targets = readTargets()
        // Dart map literal order: kinds, #a, limit.
        val filter = jsonObjectOf(
            "kinds" to listOf(KIND_REACTION),
            "#a" to listOf(articleAddress),
            "limit" to LIKES_LIMIT,
        )
        withTimeoutOrNull(TIMEOUT) {
            relayClient.queryAll(
                targets,
                listOf(filter),
                RelayQueryOptions(
                    connectTimeout = FETCH_CONNECT_TIMEOUT,
                    responseTimeout = TIMEOUT,
                    subscriptionId = LIKES_SUBSCRIPTION_ID,
                    logTag = TAG,
                ),
                RelayFanOut.PARALLEL,
            ) { relay, event ->
                val content = stringOf(event["content"])
                val pubkey = stringOf(event["pubkey"])
                if (pubkey.isEmpty() || !isPositive(content)) return@queryAll
                reactors += pubkey
                perRelay[relay] = (perRelay[relay] ?: 0) + 1
                if (me != null && pubkey == me) mine = true
            }
        }

        // The local list beats the relay answer for the own reaction.
        var localOnly = false
        if (me != null && !mine && likedEntries().contains(likedEntry(me, articleAddress))) {
            mine = true
            localOnly = true
        }
        log.debug(
            TAG,
            "Herzen fuer $articleAddress: ${reactors.size} gesamt, eigenes: $mine, " +
                "pro Relay: ${if (perRelay.isEmpty()) "keine Treffer" else perRelay.entries.joinToString(", ", "{", "}") { "${it.key}: ${it.value}" }}",
        )
        log.debug(
            TAG,
            "eigener Pubkey: ${if (me == null) "keiner" else "${me.take(8)}…"} | " +
                "eigenes Herz aus Merkzettel: $localOnly | " +
                "gefundene Reagierende: ${reactors.joinToString(", ") { "${it.take(8)}…" }}",
        )
        return ArticleLikes(reactors.size + (if (localOnly) 1 else 0), mine)
    }

    override suspend fun like(articleAddress: String, authorPubkey: String): Boolean {
        // Sending twice would count twice on the website (it counts events, not people).
        val me = myPubkey()
        if (me != null && likedEntries().contains(likedEntry(me, articleAddress))) {
            log.debug(TAG, "Herz bereits vergeben — nichts gesendet.")
            return true
        }

        val signed: NostrEvent = try {
            signing.signEvent(KIND_REACTION, likeTags(articleAddress, authorPubkey), LIKE_CONTENT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Signieren fehlgeschlagen", e)
            return false
        }

        // Publishing and remembering run in the application scope, like the un-cancellable Dart
        // Future: if the caller (e.g. the article screen) goes away after a relay already said
        // OK, the like must still land in the local list, or a second visit could send it again
        // and the website would count it twice.
        return appScope.async { publishAndRemember(signed, articleAddress) }.await()
    }

    private suspend fun publishAndRemember(signed: NostrEvent, articleAddress: String): Boolean {
        val targets = writeTargets()
        val results = ConcurrentHashMap<String, String>()
        val accepted = ConcurrentHashMap.newKeySet<String>()
        // Wait for ALL relays (or the overall budget), not only the first OK: a reaction that
        // lands on a single relay may never reach the website.
        withTimeoutOrNull(TIMEOUT) {
            coroutineScope {
                for (url in targets) {
                    launch {
                        val r = relayClient.publish(
                            url,
                            signed,
                            RelayPublishOptions(connectTimeout = PUBLISH_CONNECT_TIMEOUT, ackTimeout = TIMEOUT),
                        )
                        when (r.status) {
                            RelayPublishStatus.ACCEPTED -> {
                                results[url] = "angenommen"
                                accepted += url
                            }
                            RelayPublishStatus.REJECTED -> results[url] = "abgelehnt: ${r.message}"
                            RelayPublishStatus.CONNECT_FAILED -> results[url] = "nicht erreichbar"
                            else -> Unit // no answer
                        }
                    }
                }
            }
        }
        for (url in targets) log.debug(TAG, "$url -> ${results[url] ?: "keine Antwort"}")
        log.debug(
            TAG,
            "Herz ${signed.id} auf $articleAddress: ${accepted.size} von ${targets.size} Relays angenommen",
        )
        if (accepted.isNotEmpty()) rememberLike(signed.pubkey, articleAddress)
        return accepted.isNotEmpty()
    }

    private suspend fun myPubkey(): String? = try {
        signing.pubkeyHex()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.debug(TAG, "Kein eigener Pubkey: ${e.message}")
        null
    }

    private suspend fun likedEntries(): Set<String> = try {
        store.getStringList(KEY_LIKED_REACTIONS).orEmpty().toSet()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptySet()
    }

    private suspend fun rememberLike(pubkey: String, articleAddress: String) {
        val entry = likedEntry(pubkey, articleAddress)
        try {
            val ok = store.edit {
                val current = get(KEY_LIKED_REACTIONS)
                // Dart `getStringList` throws on another type → the write is skipped.
                if (current != null && getStringList(KEY_LIKED_REACTIONS) == null) {
                    throw IllegalStateException("$KEY_LIKED_REACTIONS ist keine Liste")
                }
                val list = getStringList(KEY_LIKED_REACTIONS).orEmpty().toMutableList()
                if (entry in list) return@edit
                list += entry
                // Cap against unbounded growth: the oldest entries drop out.
                if (list.size > LIKED_CAP) list.subList(0, list.size - LIKED_CAP).clear()
                setStringList(KEY_LIKED_REACTIONS, list)
            }
            if (!ok) log.warn(TAG, "Merkzettel konnte nicht geschrieben werden")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Merkzettel konnte nicht geschrieben werden", e)
        }
    }

    /** Dart `(event[key] ?? '').toString()`. */
    private fun stringOf(value: JsonElement?): String = when (value) {
        null, is JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

    companion object {
        const val TAG: String = "NewsReactions"
    }
}
