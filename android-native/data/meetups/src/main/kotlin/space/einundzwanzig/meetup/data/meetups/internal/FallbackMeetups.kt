package space.einundzwanzig.meetup.data.meetups.internal

import space.einundzwanzig.meetup.data.meetups.model.Meetup

/**
 * The bundled offline directory (Dart `allMeetups` in `lib/models/meetup.dart`).
 *
 * Declared as a Kotlin constant so [space.einundzwanzig.meetup.data.meetups.MeetupService.fallbackMeetups]
 * is safe to read on the main thread (no Java-resource/APK lookup, nothing to strip at packaging).
 * The test fixture `fallback_meetups.json` (test resources) mirrors this list and a unit test
 * compares both.
 *
 * Deliberate deviation: Dart spells Hamburg `"Hambmurg"`. Organizers who picked it offline signed
 * the badge slug `hambmurg`, which never groups with the online `hamburg` badges. The native list
 * uses `"Hamburg"` (spec 09 §14 item 17 recommendation). Already issued Flutter badges keep
 * verifying; only offline-issued native badges now match the online slug.
 */
internal object FallbackMeetups {
    val list: List<Meetup> = listOf(
        Meetup(
            id = "m_muc",
            city = "München",
            country = "DE",
            telegramLink = "t.me/einundzwanzig_muc",
            lat = 48.1351,
            lng = 11.5820,
        ),
        Meetup(
            id = "m_hh",
            city = "Hamburg",
            country = "DE",
            telegramLink = "t.me/einundzwanzig_hh",
            lat = 53.5511,
            lng = 9.9937,
        ),
        Meetup(
            id = "m_b",
            city = "Berlin",
            country = "DE",
            telegramLink = "t.me/einundzwanzig_berlin",
            lat = 52.5200,
            lng = 13.4050,
        ),
    )
}
