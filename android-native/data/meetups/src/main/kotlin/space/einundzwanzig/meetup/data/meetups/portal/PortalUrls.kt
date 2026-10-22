package space.einundzwanzig.meetup.data.meetups.portal

import space.einundzwanzig.meetup.core.common.dart.DartUri
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Hosts, paths and timeouts of the Einundzwanzig portal (spec 09 §5). */
object PortalUrls {
    /** Portal web base, also used to absolutize relative image paths. */
    const val BASE_URL = "https://portal.einundzwanzig.space"

    /** REST API base. */
    const val API_BASE = "$BASE_URL/api"

    /** Meetup directory (`GET`, no auth). */
    const val MEETUPS_URL = "$API_BASE/mobile/meetups"

    /** Public per-meetup leader list (`GET`, no auth). */
    const val LEADERS_URL = "$API_BASE/meetup-leaders"

    /** Prefix of portal meetup pages; the slug is appended `Uri.encodeComponent`-encoded. */
    const val MEETUP_PAGE_PREFIX = "$BASE_URL/de/meetup/"

    /** `device_name` sent with the portal login. */
    const val DEVICE_NAME = "Einundzwanzig Meetup App"

    /** Whole-request timeout of the portal client and of the meetup list. */
    val TIMEOUT: Duration = 15.seconds

    /** Timeout of `GET /api/meetup-leaders`. */
    val LEADERS_TIMEOUT: Duration = 8.seconds

    /**
     * Portal page for [slug] (already trimmed): `https://portal.einundzwanzig.space/de/meetup/<slug>`
     * with Dart `Uri.encodeComponent` (`a b~*` → `a%20b~*`). Must not use `URLEncoder`: the
     * result feeds `MeetupEventMatcher.portalIdentity`.
     */
    fun meetupPageFor(slug: String): String = MEETUP_PAGE_PREFIX + DartUri.encodeComponent(slug)

    /**
     * Dart `MeetupCalendarService.absoluteImageUrl`: trims; empty or `http://`/`https://` URLs are
     * returned as they are, anything else is treated as a portal-relative path
     * (`/storage/x.png` → `https://portal.einundzwanzig.space/storage/x.png`, `x.png` → `…/x.png`).
     */
    fun absoluteImageUrl(url: String): String {
        val u = url.dartTrim()
        if (u.isEmpty() || u.startsWith("http://") || u.startsWith("https://")) return u
        return BASE_URL + (if (u.startsWith("/")) "" else "/") + u
    }
}
