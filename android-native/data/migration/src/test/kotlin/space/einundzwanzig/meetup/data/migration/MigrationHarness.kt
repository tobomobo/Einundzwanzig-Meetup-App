package space.einundzwanzig.meetup.data.migration

import android.content.Context
import kotlinx.coroutines.Dispatchers
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLogger
import space.einundzwanzig.meetup.core.crypto.Hex
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.secure.SecureStore
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.data.migration.internal.AppVersionCode
import space.einundzwanzig.meetup.data.migration.internal.DefaultFlutterMigration
import space.einundzwanzig.meetup.data.migration.internal.LegacyLogImporter
import space.einundzwanzig.meetup.data.migration.legacy.Fss
import space.einundzwanzig.meetup.data.migration.legacy.RsaMode
import java.io.File

/** One simulated device (Flutter files + native stores); [migration] = a new process start. */
internal class MigrationHarness(val context: Context) {
    val device = FlutterDevice(context).also { it.reset() }
    val keystore = FakeLegacyKeystore()
    val kv = InMemoryKeyValueStore()
    val secure = InMemorySecureStore()
    val importedLogs = mutableListOf<Pair<String?, Boolean?>>()
    val registeredSecrets = mutableListOf<String>()
    var appVersion = 100L
    var now: () -> Long = { 1_786_000_000_000L }

    val v10 = MigrationFixtures.vectors.obj("fss_v10_gcm")
    val aes: ByteArray = Hex.decode(v10.str("aes128KeyHex"))

    fun migration(secureStore: SecureStore = secure, keyValueStore: KeyValueStore = kv) = DefaultFlutterMigration(
        context = context,
        keyValueStore = keyValueStore,
        secureStore = secureStore,
        keystore = keystore,
        logImporter = object : LegacyLogImporter {
            override suspend fun import(rawDartJson: String?, verbose: Boolean?) {
                importedLogs += rawDartJson to verbose
            }

            override fun registerSecrets(values: Collection<String>) {
                registeredSecrets += values
            }
        },
        hooks = emptySet(),
        timeSource = object : TimeSource {
            override fun nowMillis() = now()
        },
        log = DiagnosticLogger(),
        io = Dispatchers.IO,
        versionCode = AppVersionCode { appVersion },
    )

    fun writeKeys() = device.write(Fss.KEYS, mapOf(Fss.WRAPPED_V10_GCM to FakeLegacyKeystore.wrapB64(aes, RsaMode.OAEP)))

    /** GCM entries for [values] (logical names; a `_BACKUP` suffix is kept) plus raw [extra] entries. */
    fun writeE2(values: Map<String, String>, extra: Map<String, String> = emptyMap()) {
        writeKeys()
        device.write(Fss.DATA, e2Entries(values) + extra)
    }

    fun e2Entries(values: Map<String, String>): Map<String, String> =
        values.entries.associate { (k, v) -> Fss.ENTRY + k to FssWriter.gcmEntry(aes, v) }

    val migrationDir: File get() = File(context.noBackupFilesDir, "flutter_migration")
    val stateFile: File get() = File(migrationDir, "state.json")
    val snapshotDir: File get() = File(migrationDir, "snapshot")
}
