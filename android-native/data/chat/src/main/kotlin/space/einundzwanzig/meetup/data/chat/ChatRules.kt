package space.einundzwanzig.meetup.data.chat

import space.einundzwanzig.meetup.data.badges.text.DartString
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Pure chat rules shared by the data layer and `:feature:chat` (spec 11 §2.1, §8.8). No
 * Android or UI dependencies.
 */
object ChatRules {

    /** Window in which an own message with the same content counts as the relay echo of the optimistic one. */
    const val DUPLICATE_WINDOW_MILLIS: Long = 2 * 60 * 1000L

    /**
     * Dart `ChatService._slugify`: Dart-lowercase (`DartString.lower`, binding resolution #12), `ä→ae ö→oe ü→ue ß→ss`, every run of
     * characters outside `[a-z0-9]` → `-`, leading/trailing `-` removed. No Unicode
     * normalisation (decomposed umlauts become `-`).
     */
    fun slugify(input: String): String = DartString.lower(input)
        .replace("ä", "ae")
        .replace("ö", "oe")
        .replace("ü", "ue")
        .replace("ß", "ss")
        .replace(NON_SLUG, "-")
        .replace(EDGE_DASHES, "")

    /**
     * Dart `findRoomForCity` matching: first room whose lower-cased slug equals the city slug or
     * ends with `-<citySlug>`, else the first room whose slugified name equals it, else `null`
     * (also for a city that slugifies to `""`).
     */
    fun matchCity(rooms: List<ChatRoom>, city: String): ChatRoom? {
        val needle = slugify(city)
        if (needle.isEmpty()) return null
        rooms.firstOrNull { r ->
            val slug = DartString.lower(r.meetupSlug)
            slug == needle || slug.endsWith("-$needle")
        }?.let { return it }
        return rooms.firstOrNull { slugify(it.name) == needle }
    }

    /**
     * Dart `ChatScreen._isDuplicate`: only own messages ([myPubkey] non-empty and equal to the
     * sender); `true` if [existing] holds a message of the same sender with the same content
     * less than 2 minutes apart.
     */
    fun isDuplicate(existing: List<ChatMessage>, message: ChatMessage, myPubkey: String?): Boolean {
        if (message.pubkey.isEmpty() || message.pubkey != myPubkey) return false
        return existing.any { x ->
            x.pubkey == message.pubkey &&
                x.content == message.content &&
                abs(ChronoUnit.MILLIS.between(x.createdAt, message.createdAt)) < DUPLICATE_WINDOW_MILLIS
        }
    }

    /**
     * Adds [message] to [messages] the way the chat screen does for live events: ignored when
     * its id is already present or it is a [isDuplicate]; otherwise appended and stably sorted
     * ascending by `createdAt`. Returns `null` when nothing changed.
     */
    fun merge(messages: List<ChatMessage>, message: ChatMessage, myPubkey: String?): List<ChatMessage>? {
        if (messages.any { it.id == message.id } || isDuplicate(messages, message, myPubkey)) return null
        return (messages + message).sortedBy { it.createdAt }
    }

    /** Dart `shortKey`: `abcdef…1234` for keys longer than 12 chars (U+2026). */
    fun shortKey(p: String): String = if (p.length > 12) p.substring(0, 6) + "…" + p.substring(p.length - 4) else p

    /** Dart `shortNpub`: first 12 + `…` + last 6 for npubs longer than 20 chars. */
    fun shortNpub(n: String): String = if (n.length > 20) n.substring(0, 12) + "…" + n.substring(n.length - 6) else n

    /**
     * Dart `timeLabel`: `HH:mm` on the same local calendar day as [now], else `d.M. HH:mm` (day
     * and month not padded, e.g. `5.3. 09:07`).
     */
    fun timeLabel(time: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = time.atZone(zone)
        val n = now.atZone(zone)
        val hm = d.hour.toString().padStart(2, '0') + ":" + d.minute.toString().padStart(2, '0')
        return if (d.toLocalDate() == n.toLocalDate()) hm else "${d.dayOfMonth}.${d.monthValue}. $hm"
    }

    private val NON_SLUG = Regex("[^a-z0-9]+")
    private val EDGE_DASHES = Regex("^-+|-+$")
}
