package space.einundzwanzig.meetup.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The one shared [Json] configuration of the app.
 *
 * - `ignoreUnknownKeys`: servers, relays and newer app versions may add fields.
 * - `explicitNulls = false`: `null` properties are omitted when encoding, and absent nullable
 *   properties decode as `null` (Dart `json['x'] as String?`).
 * - `isLenient = false`: malformed JSON (unquoted keys/strings) is rejected like `jsonDecode`.
 * - `encodeDefaults` stays `false` (kotlinx default): a property equal to its default value is
 *   **not** written. Where a Dart `toJson` always writes a key (wire formats, backups, hashes),
 *   annotate the property with `@EncodeDefault` or give it no default.
 */
@OptIn(ExperimentalSerializationApi::class)
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = false
}
