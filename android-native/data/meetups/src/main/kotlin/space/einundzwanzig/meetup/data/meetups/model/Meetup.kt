package space.einundzwanzig.meetup.data.meetups.model

import kotlinx.serialization.Serializable
import space.einundzwanzig.meetup.data.meetups.internal.dartLower

/**
 * One meetup group of the Einundzwanzig portal directory (Dart `Meetup`, `lib/models/meetup.dart`).
 *
 * Built from `GET /api/mobile/meetups` (spec 09 §8.1) or from the bundled offline fallback list
 * ([space.einundzwanzig.meetup.data.meetups.MeetupService.fallbackMeetups]). [id] is the identity
 * key: the portal meetup ID as a string (`"107"`), or `m_muc`/`m_hh`/`m_b` for fallback entries.
 *
 * The class is `@Serializable` with the Dart field names so features can pass it through
 * navigation keys or saved state (Dart had no `toJson`; this JSON never leaves the device).
 * The field `adminSecret` was removed on purpose (Security Audit Fix 5) — do not reintroduce it.
 */
@Serializable
data class Meetup(
    /** Portal meetup ID as string, or the fallback ID. */
    val id: String,
    /** Group name, e.g. "Einundzwanzig Berlin Mitte" (several groups can share a city). */
    val name: String = "",
    val city: String,
    /** ISO-3166 alpha-2 country code (portal default `DE`). */
    val country: String,
    /** Community/Telegram link (portal field `url`); may lack a scheme (`t.me/x`). */
    val telegramLink: String,
    val lat: Double,
    val lng: Double,
    /** Portal `logo`, possibly relative (`/storage/...`): use `PortalUrls.absoluteImageUrl`. */
    val logoUrl: String = "",
    /** Portal `intro`. */
    val description: String = "",
    val website: String = "",
    /** Portal page of the group; derived from the slug when the portal sends none. */
    val portalLink: String = "",
    val twitterUsername: String = "",
    /** npub of the group or its organizer (portal `nostr`). */
    val nostrNpub: String = "",
    /** First non-empty of portal `cover`, `image`, `logo`. */
    val coverImagePath: String = "",
) {
    /**
     * The group name when it differs from the city (case-insensitive), else `null`. Lets lists
     * tell apart several groups in one city.
     */
    val groupName: String?
        get() = name.takeIf { it.isNotEmpty() && it.dartLower() != city.dartLower() }

    /** `true` unless both coordinates are exactly 0 ("no coordinates" everywhere in the app). */
    val hasCoords: Boolean get() = lat != 0.0 || lng != 0.0
}
