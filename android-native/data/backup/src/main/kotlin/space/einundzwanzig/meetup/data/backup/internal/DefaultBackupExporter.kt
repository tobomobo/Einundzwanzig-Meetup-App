package space.einundzwanzig.meetup.data.backup.internal

import kotlinx.coroutines.CoroutineDispatcher
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.backup.BackupCrypto
import space.einundzwanzig.meetup.data.backup.BackupDocument
import space.einundzwanzig.meetup.data.backup.BackupExporter
import space.einundzwanzig.meetup.data.backup.BackupFileName
import space.einundzwanzig.meetup.data.backup.BackupPasswordRules
import space.einundzwanzig.meetup.data.backup.BuildResult
import space.einundzwanzig.meetup.data.backup.ExportResult
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Dart `createBackup` steps 4–9 (spec 08 §2.4, §3.2, §8.1). */
@Singleton
internal class DefaultBackupExporter @Inject constructor(
    private val profiles: UserProfileRepository,
    private val badges: BadgeRepository,
    private val identity: BackupIdentity,
    private val admins: BackupAdmins,
    private val proofs: BackupProofs,
    private val time: TimeSource,
    @param:DefaultDispatcher private val cpu: CoroutineDispatcher,
    private val log: DiagnosticLog,
) : BackupExporter {

    /** Device zone for the local `timestamp` and the file name (tests pin it). */
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    override suspend fun buildDocument(): BuildResult {
        val user = profiles.load()
        val badgeList = badges.loadBadges()
        if (badges.badgesUnreadable.value) {
            log.warn(TAG, "Backup abgelehnt: Badge-Sammlung nicht lesbar.")
            return BuildResult.BadgesUnreadable
        }

        val nsec = identity.keystoreNsec()
        val npub = identity.keystoreNpub()
        val privHex = identity.keystorePrivHex()
        val mode = identity.mode()
        val activeNpub = identity.npub()
        val isExternal = identity.isExternalSigner()
        val bunkerUri = identity.nip46BunkerUri()
        // Keys follow the actual existence of a local key, not the mode (mixed state keeps the nsec).
        val hasLocalKey = !nsec.isNullOrEmpty()
        val backupNpub = if (hasLocalKey) npub ?: "" else activeNpub ?: npub ?: ""
        val backupActiveNpub = if (isExternal && hasLocalKey) activeNpub ?: "" else ""
        val adminList = admins.getAdminList()
        val platformProofs = proofs.platformProofEntries()
        val humanity = proofs.humanityBackupJson()

        val document = jsonObjectOf(
            "version" to BackupDocument.CURRENT_VERSION,
            "timestamp" to DartDateTime.now(time).toIso8601String(zone()),
            "app" to BackupDocument.APP_NAME,
            "user" to jsonObjectOf(
                "nickname" to user.nickname,
                "fullName" to user.fullName,
                "homeMeetupId" to user.homeMeetupId,
                "favoriteMeetupIds" to user.favoriteMeetupIds,
                "isAdmin" to user.isAdmin,
                "isAdminVerified" to user.isAdminVerified,
                "isNostrVerified" to user.isNostrVerified,
                "nostrNpub" to user.nostrNpub,
                "telegramHandle" to user.telegramHandle,
                "twitterHandle" to user.twitterHandle,
            ),
            "badges" to badgeList.map { it.toJson() },
            "nostr" to jsonObjectOf(
                "nsec" to (nsec ?: ""),
                "npub" to backupNpub,
                "priv_hex" to if (hasLocalKey) privHex ?: "" else "",
                "has_key" to hasLocalKey,
                "signing_mode" to mode.stored,
                "active_npub" to backupActiveNpub,
                // Bunker address only, never the client session key (spec 08 §9 #3).
                "nip46_bunker_uri" to (bunkerUri ?: ""),
            ),
            "admin_registry" to adminList.map { it.toJsonMap() },
            "platform_proofs" to platformProofs,
            "humanity_proof" to humanity,
        )
        log.info(TAG, "Backup erstellt: ${badgeList.size} Badges, Schlüssel: $hasLocalKey, Modus: ${mode.stored}.")
        return BuildResult.Ok(DartJson.encode(document), user.nickname)
    }

    override suspend fun export(password: String): ExportResult {
        if (!BackupPasswordRules.longEnough(password)) return ExportResult.PasswordTooShort
        val built = buildDocument()
        if (built !is BuildResult.Ok) return ExportResult.BadgesUnreadable
        val content = BackupCrypto.encryptV2(built.json, password, dispatcher = cpu)
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(time.nowMillis()), zone())
        return ExportResult.Ready(BackupFileName.of(built.nickname, now), content)
    }

    private companion object {
        const val TAG = "BackupService"
    }
}
