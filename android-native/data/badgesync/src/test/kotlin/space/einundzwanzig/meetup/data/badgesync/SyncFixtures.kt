package space.einundzwanzig.meetup.data.badgesync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import space.einundzwanzig.meetup.core.common.json.DartJson
import java.io.File

/** `spec/fixtures/08-backup-sync-vectors.json`, read directly (no copies). */
object SyncFixtures {
    val all: JsonObject by lazy {
        DartJson.decode(File("../../spec/fixtures/08-backup-sync-vectors.json").readText()).jsonObject
    }

    val sync: JsonObject get() = all.getValue("sync").jsonObject
}
