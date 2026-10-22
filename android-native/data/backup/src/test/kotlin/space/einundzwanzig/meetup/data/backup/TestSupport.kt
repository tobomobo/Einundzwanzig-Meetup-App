package space.einundzwanzig.meetup.data.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.data.admin.model.AdminEntry
import space.einundzwanzig.meetup.data.backup.internal.BackupAdmins
import space.einundzwanzig.meetup.data.backup.internal.BackupIdentity
import space.einundzwanzig.meetup.data.backup.internal.BackupProofs
import space.einundzwanzig.meetup.data.backup.internal.DefaultBackupExporter
import space.einundzwanzig.meetup.data.backup.internal.DefaultRestoreEngine
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.badgesync.BadgeSync
import space.einundzwanzig.meetup.data.badgesync.RestoreHoldException
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofBackup
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset

/** `spec/fixtures/08-backup-sync-vectors.json`. */
object Fixtures {
    val root: JsonObject by lazy {
        DartJson.decode(File("../../spec/fixtures/08-backup-sync-vectors.json").readText()).jsonObject
    }

    operator fun get(key: String): JsonElement = root.getValue(key)
}

class FakeTime(var millis: Long = 1_760_961_600_000L) : TimeSource {
    override fun nowMillis(): Long = millis
}

class FakeProfiles(var stored: UserProfile = UserProfile()) : UserProfileRepository {
    val saves = mutableListOf<UserProfile>()
    private val flow = MutableStateFlow(stored)

    override suspend fun load(): UserProfile = stored

    override suspend fun save(profile: UserProfile) {
        stored = profile
        saves += profile
        flow.value = profile
    }

    override suspend fun update(transform: (UserProfile) -> UserProfile): UserProfile = transform(stored).also { save(it) }

    override val profile: Flow<UserProfile> = flow
}

/** Signer + keystore like `SigningService`: external modes report their own npub. */
internal class FakeIdentity : BackupIdentity {
    var mode = SigningMode.LOCAL
    var externalNpub: String? = null
    var nsec: String? = null
    var keyNpub: String? = null
    var privHex: String? = null
    var bunkerUri: String? = null
    var npubThrows: Exception? = null
    var onSaveKeys: (() -> Unit)? = null
    val calls = mutableListOf<String>()

    override suspend fun mode(): SigningMode = mode
    override suspend fun npub(): String? {
        npubThrows?.let { throw it }
        return if (mode.isExternal) externalNpub else keyNpub
    }
    override suspend fun isExternalSigner(): Boolean = mode.isExternal
    override suspend fun nip46BunkerUri(): String? = bunkerUri
    override suspend fun restoreAmber(npub: String) {
        calls += "amber:$npub"
        mode = SigningMode.AMBER
        externalNpub = npub
    }
    override suspend fun restoreNip07(npub: String) {
        calls += "nip07:$npub"
        mode = SigningMode.NIP07
        externalNpub = npub
    }
    override suspend fun restoreNip46(npub: String, bunkerUri: String) {
        calls += "nip46:$npub"
        require(bunkerUri.startsWith("bunker://")) { "Ungültige Bunker-Adresse" }
        mode = SigningMode.NIP46
        externalNpub = npub
        this.bunkerUri = bunkerUri
    }
    override suspend fun useLocalMode() {
        calls += "local"
        mode = SigningMode.LOCAL
    }
    override suspend fun keystoreNsec(): String? = nsec
    override suspend fun keystoreNpub(): String? = keyNpub
    override suspend fun keystorePrivHex(): String? = privHex
    override suspend fun saveKeys(nsec: String, npub: String, privHex: String) {
        calls += "saveKeys:$npub"
        onSaveKeys?.invoke()
        this.nsec = nsec
        keyNpub = npub
        this.privHex = privHex
    }
}

internal class FakeAdmins(val list: MutableList<AdminEntry> = mutableListOf()) : BackupAdmins {
    var refreshes = 0
    var refreshThrows = false

    override suspend fun getAdminList(): List<AdminEntry> = list.toList()
    override suspend fun addAdmin(entry: AdminEntry) {
        check(!entry.isLegacyOrganicClaim) { "organic" }
        if (list.none { it.npub == entry.npub }) list += entry
    }
    override suspend fun forceRefresh(): Int {
        refreshes++
        if (refreshThrows) throw java.io.IOException("offline")
        return list.size
    }
}

internal class FakeProofs : BackupProofs {
    var platformWriteSucceeds = true
    var humanityWriteSucceeds = true
    var platform: List<JsonObject> = emptyList()
    var humanity: JsonObject = DartJson.decode(
        """{"verified":false,"first_zap_at":0,"receipt_event_id":"","checked_at":0}""",
    ).jsonObject
    var humanityVerified = false
    val restoredHumanity = mutableListOf<Pair<JsonObject, Boolean>>()

    override suspend fun platformProofEntries(): List<JsonObject> = platform
    override suspend fun replacePlatformProofs(validated: List<JsonElement>): Boolean {
        if (!platformWriteSucceeds) return false
        val keep = PlatformProofBackup.restorable(validated)
        if (keep.isNotEmpty()) platform = keep.map { it.jsonObject }
        return true
    }
    override suspend fun unionPlatformProofs(validated: List<JsonElement>): Boolean {
        if (!platformWriteSucceeds) return false
        val union = PlatformProofBackup.union(platform, PlatformProofBackup.restorable(validated))
        if (union.isNotEmpty()) platform = union
        return true
    }
    override suspend fun humanityBackupJson(): JsonObject = humanity
    override suspend fun humanityVerifiedPref(): Boolean = humanityVerified
    override suspend fun restoreHumanity(proof: JsonObject, additive: Boolean): Boolean {
        restoredHumanity += proof to additive
        return humanityWriteSucceeds
    }
}

/** Records the restore-hold calls; [identitySwitched] follows spec 08 §8.11. */
class FakeBadgeSync : BadgeSync {
    var pending: String? = null
    var holdThrows: RestoreHoldException? = null
    var endThrows: Exception? = null
    val calls = mutableListOf<String>()

    override fun init() = Unit
    override fun trigger() = Unit
    override suspend fun retryIfDirty() = Unit
    override suspend fun syncNow(): Boolean = true
    override suspend fun beginRestoreHold(fromNpub: String, expectNpubs: List<String>, replace: Boolean) {
        calls += "begin:$fromNpub:$expectNpubs:$replace"
        holdThrows?.let { throw it }
    }
    override suspend fun pendingFrom(): String? = pending
    override suspend fun endRestore() {
        calls += "end"
        endThrows?.let { throw it }
    }
    override suspend fun settleRestore() = Unit
    override suspend fun markRestoreClear() {
        calls += "markClear"
    }
    override suspend fun clearRestoreMark() {
        calls += "clearMark"
    }
    override suspend fun clearIdentityProofs() {
        calls += "clearProofs"
    }
    override suspend fun forgetLocalState() = Unit
    override fun identitySwitched(from: String, now: String): Boolean {
        val c = DartString.lower(DartString.trim(now))
        return c.isNotEmpty() && c != DartString.lower(DartString.trim(from))
    }
}

/** One device with in-memory stores, a real badge repository and fakes for the other domains. */
internal class BackupHarness(
    val prefs: InMemoryKeyValueStore = InMemoryKeyValueStore(),
    val secure: InMemorySecureStore = InMemorySecureStore(),
    val time: FakeTime = FakeTime(),
    val zone: ZoneId = ZoneOffset.UTC,
) {
    val repo: BadgeRepository = BadgeRepository.create(prefs, secure, time, Dispatchers.Unconfined)
    val profiles = FakeProfiles()
    val identity = FakeIdentity()
    val admins = FakeAdmins()
    val proofs = FakeProofs()
    val sync = FakeBadgeSync()

    val engine = DefaultRestoreEngine(profiles, repo, sync, identity, admins, proofs, AppLogger, Dispatchers.Unconfined)
    val exporter = DefaultBackupExporter(profiles, repo, identity, admins, proofs, time, Dispatchers.Unconfined, AppLogger)
        .also { e -> e.zone = { zone } }
}

fun doc(json: String): BackupDocument = (BackupDocument.parseBlocking(json) as ParseResult.Ok).document

fun logText(): String = AppLogger.entries.value.joinToString("\n") { it.message }
