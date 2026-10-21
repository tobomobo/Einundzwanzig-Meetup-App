package space.einundzwanzig.meetup.data.community.portal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.data.community.internal.DART_REGEX_WHITESPACE
import space.einundzwanzig.meetup.data.community.internal.dartToString
import space.einundzwanzig.meetup.data.community.internal.isPresent
import java.time.ZoneId

/**
 * Tolerant readers for portal course/lecturer JSON (`pv`, `pImg` and the inline expressions of
 * `lib/screens/community_hub_screen.dart`, spec 11 §2.7–§2.8). Input is the raw portal object as
 * returned by the portal API (`:data:meetups`).
 */
object PortalFields {

    private val IMAGE_KEYS = listOf("image", "logo", "avatar", "picture", "cover")
    private val URL_REGEX = Regex("https?://[^$DART_REGEX_WHITESPACE)\\]>,]+")

    /**
     * Dart `pv(e, key)`: the scalar value of [key] as Dart `toString()`; for a dotted key `a.b`
     * that has no flat scalar value, the nested `e[a][b]`; `""` otherwise. Maps/lists never
     * match directly.
     */
    fun pv(e: JsonObject, key: String): String {
        val v = e[key]
        if (v.isPresent && v !is JsonObject && v !is JsonArray) return v.dartToString()
        if (key.contains('.')) {
            val parts = key.split('.')
            val n = e[parts[0]]
            if (n is JsonObject) {
                val inner = n[parts[1]]
                if (inner.isPresent) return inner.dartToString()
            }
        }
        return ""
    }

    /** Dart `pImg(e)`: first of image/logo/avatar/picture/cover whose value starts with `http`, else `""`. */
    fun pImg(e: JsonObject): String {
        for (k in IMAGE_KEYS) {
            val v = pv(e, k)
            if (v.startsWith("http")) return v
        }
        return ""
    }

    /**
     * URLs in [text] matching `https?://[^\s\)\]>,]+` (Dart `\s`), deduplicated in first-seen
     * order (`toSet().toList()`).
     */
    fun extractLinks(text: String): List<String> =
        URL_REGEX.findAll(text).map { it.value }.toCollection(LinkedHashSet()).toList()

    /**
     * `e['id'] is int` → the id, else `null` (then the list shows a bottom sheet instead of a
     * detail screen). Typed `Int` to match `PortalApiService.getCourse/getLecturer(id: Int)` and the
     * `CourseDetails`/`LecturerDetails` routes; a JSON integer outside the 32-bit range is treated
     * like a non-int id (`null`) instead of silently wrapping to a different id.
     */
    fun intId(e: JsonObject): Int? =
        e["id"].dartIntOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    /** List row title: `(e.name ?? e.title ?? '').toString()`. */
    fun listName(e: JsonObject): String = firstPresent(e, "name", "title")?.dartToString() ?: ""

    /** List row description: `(e.description ?? e.intro ?? e.bio ?? '').toString()`. */
    fun listDescription(e: JsonObject): String = firstPresent(e, "description", "intro", "bio")?.dartToString() ?: ""

    /** Course detail screen model (`CourseDetailScreen.build`). */
    fun course(e: JsonObject, zone: ZoneId = ZoneId.systemDefault()): PortalCourse {
        val desc = pv(e, "description")
        val lecturer = e["lecturer"] as? JsonObject
        val events = (e["events"] as? JsonArray ?: e["course_events"] as? JsonArray).objects()
        return PortalCourse(
            name = pv(e, "name").ifEmpty { pv(e, "title") },
            description = desc,
            image = pImg(e),
            lecturerName = pv(e, "lecturer.name"),
            lecturerIntro = pv(e, "lecturer.intro"),
            lecturerImage = lecturer?.let(::pImg) ?: "",
            lecturerId = lecturer?.let(::intId),
            lecturer = lecturer,
            events = events.map { courseEvent(it, zone) },
            portalLink = pv(e, "portalLink"),
            links = extractLinks(desc),
        )
    }

    /** One upcoming date of a course (`_eventRow`). */
    fun courseEvent(ev: JsonObject, zone: ZoneId = ZoneId.systemDefault()): PortalCourseEvent {
        val from = pv(ev, "from").ifEmpty { pv(ev, "start") }
        val start = DartDateTime.tryParse(from, zone)
        return PortalCourseEvent(
            from = from,
            location = pv(ev, "location").ifEmpty { pv(ev, "venue.name") },
            link = pv(ev, "link"),
            title = pv(ev, "name").ifEmpty { pv(ev, "title") },
            description = pv(ev, "description"),
            start = start,
            whenLabel = start?.let { formatWhen(it, zone) } ?: from,
        )
    }

    /** Lecturer detail screen model (`LecturerDetailScreen.build`). */
    fun lecturer(e: JsonObject): PortalLecturer {
        val pairs = listOf(
            "Website" to pv(e, "website"),
            LINK_X to pv(e, "twitter").ifEmpty { pv(e, "twitter_username") },
            LINK_NOSTR to pv(e, "nostr"),
            "Portal" to pv(e, "portalLink"),
        ).filter { it.second.isNotEmpty() }
        return PortalLecturer(
            name = pv(e, "name"),
            intro = pv(e, "intro"),
            bio = pv(e, "description").ifEmpty { pv(e, "bio") },
            image = pImg(e),
            courses = (e["courses"] as? JsonArray).objects(),
            links = pairs.map { (k, v) -> PortalLink(label = k, value = v, url = lecturerLinkUrl(k, v)) },
        )
    }

    /**
     * Target of a lecturer link row: the value if it starts with `http`; `https://x.com/<v>` for
     * X; else `https://<v>`. Native guard (spec 11 §14 item 11): a bare `npub1…` Nostr value opens
     * `https://njump.me/<npub>` instead of the broken `https://npub1…`.
     */
    fun lecturerLinkUrl(key: String, value: String): String = when {
        value.startsWith("http") -> value
        key == LINK_X -> "https://x.com/$value"
        key == LINK_NOSTR && (value.startsWith("npub1") || value.startsWith("nprofile1")) -> "https://njump.me/$value"
        else -> "https://$value"
    }

    /** Calendar insert title: `title || loc || "Termin"`. End = start + 2 h. */
    fun calendarTitle(ev: PortalCourseEvent, fallback: String = "Termin"): String =
        ev.title.ifEmpty { ev.location.ifEmpty { fallback } }

    /** `dd.MM.yyyy · HH:mm` from the Dart fields (UTC fields for a `Z`/offset string, local otherwise). */
    private fun formatWhen(d: DartDateTime, zone: ZoneId): String {
        val f = d.fields(zone)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${two(f.dayOfMonth)}.${two(f.monthValue)}.${f.year} · ${two(f.hour)}:${two(f.minute)}"
    }

    private fun firstPresent(e: JsonObject, vararg keys: String): JsonElement? =
        keys.firstNotNullOfOrNull { k -> e[k]?.takeIf { it.isPresent } }

    private fun JsonArray?.objects(): List<JsonObject> = this?.filterIsInstance<JsonObject>() ?: emptyList()

    private const val LINK_X = "X (Twitter)"
    private const val LINK_NOSTR = "Nostr"
}

/** Course detail data (spec 11 §2.8). Empty strings mean "absent". */
data class PortalCourse(
    val name: String,
    val description: String,
    val image: String,
    val lecturerName: String,
    val lecturerIntro: String,
    val lecturerImage: String,
    /** Lecturer id if `lecturer.id is int` and fits 32 bits (enables the lecturer detail navigation); see [PortalFields.intId]. */
    val lecturerId: Int?,
    /** Raw lecturer object, the fallback for the lecturer detail screen. */
    val lecturer: JsonObject?,
    val events: List<PortalCourseEvent>,
    /** Show "Im Portal öffnen" only if this starts with `http`. */
    val portalLink: String,
    val links: List<String>,
)

/** One course date. [start] is `DateTime.tryParse(from)` (plain ISO parse); `null` hides the calendar action. */
data class PortalCourseEvent(
    val from: String,
    val location: String,
    val link: String,
    val title: String,
    val description: String,
    val start: DartDateTime?,
    /** `dd.MM.yyyy · HH:mm`, or the raw [from] when unparsable. */
    val whenLabel: String,
)

/** Lecturer detail data (spec 11 §2.8). */
data class PortalLecturer(
    val name: String,
    val intro: String,
    val bio: String,
    val image: String,
    /** Raw course objects (row: `pImg(c)`, `pv(c,'name')`, tap if `id is int`). */
    val courses: List<JsonObject>,
    /** Ordered, non-empty link rows labelled `"$label: $value"`. */
    val links: List<PortalLink>,
)

/** A lecturer link row: [label] (`Website`, `X (Twitter)`, `Nostr`, `Portal`), raw [value], resolved [url]. */
data class PortalLink(val label: String, val value: String, val url: String) {
    /** Row text `"<label>: <value>"`. */
    val text: String get() = "$label: $value"
}
