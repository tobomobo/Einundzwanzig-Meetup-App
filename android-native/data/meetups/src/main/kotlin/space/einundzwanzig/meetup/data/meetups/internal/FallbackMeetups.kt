package space.einundzwanzig.meetup.data.meetups.internal

import kotlinx.serialization.builtins.ListSerializer
import space.einundzwanzig.meetup.core.network.AppJson
import space.einundzwanzig.meetup.data.meetups.model.Meetup

/**
 * The bundled offline directory (Dart `allMeetups` in `lib/models/meetup.dart`), shipped as the
 * Java resource `space/einundzwanzig/meetup/data/meetups/fallback_meetups.json`.
 *
 * Deliberate deviation: Dart spells Hamburg `"Hambmurg"`. Organizers who picked it offline signed
 * the badge slug `hambmurg`, which never groups with the online `hamburg` badges. The native list
 * uses `"Hamburg"` (spec 09 §14 item 17 recommendation). Already issued Flutter badges keep
 * verifying; only offline-issued native badges now match the online slug.
 */
internal object FallbackMeetups {
    private const val RESOURCE = "/space/einundzwanzig/meetup/data/meetups/fallback_meetups.json"

    val list: List<Meetup> by lazy {
        val text = FallbackMeetups::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Bundled resource $RESOURCE missing")
        AppJson.decodeFromString(ListSerializer(Meetup.serializer()), text)
    }
}
