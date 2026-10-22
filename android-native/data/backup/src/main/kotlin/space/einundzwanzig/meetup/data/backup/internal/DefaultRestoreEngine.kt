package space.einundzwanzig.meetup.data.backup.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.admin.model.AdminEntry
import space.einundzwanzig.meetup.data.backup.BackupDocument
import space.einundzwanzig.meetup.data.backup.BackupFormatException
import space.einundzwanzig.meetup.data.backup.RestoreConfirmation
import space.einundzwanzig.meetup.data.backup.RestoreDecision
import space.einundzwanzig.meetup.data.backup.RestoreEngine
import space.einundzwanzig.meetup.data.backup.RestoreError
import space.einundzwanzig.meetup.data.backup.RestoreOutcome
import space.einundzwanzig.meetup.data.backup.RestorePolicy
import space.einundzwanzig.meetup.data.badges.model.BadgeFormatException
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.badgesync.BadgeSync
import space.einundzwanzig.meetup.data.badgesync.BadgeSyncFormat
import space.einundzwanzig.meetup.data.badgesync.RestoreHoldException
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** A restore step failed with a typed reason (Dart `throw Exception('…')`). */
internal class RestoreFailure(val error: RestoreError) : Exception(error.german)

/**
 * Dart `restoreBackup` from step 7 on (spec 08 §2.5). Order matters for crash safety; every step
 * mirrors the Dart code, with these deliberate changes:
 * - the user object, every badge and every optional section are type-checked **before** the
 *   confirmation and before anything is written (spec 08 §14 risk 12);
 * - after the confirmation the write phase is not cancellable;
 * - parsing, validation and the write phase run on [cpu] (only [RestoreEngine.restore]'s `confirm`
 *   callback runs in the caller's context), so a large backup never blocks the main thread;
 * - a failing `endRestore` is logged, not thrown: the hold stays persisted and is settled by the
 *   next sync run or `pendingFrom()`, and the computed outcome is still returned;
 * - a fresh restore that switches the keystore clears the (owner-less) proofs before writing the
 *   restored ones, so settling the hold no longer wipes them ([clearProofsAfterFreshSwitch]).
 */
@Singleton
internal class DefaultRestoreEngine @Inject constructor(
    private val profiles: UserProfileRepository,
    private val badges: BadgeRepository,
    private val badgeSync: BadgeSync,
    private val identity: BackupIdentity,
    private val admins: BackupAdmins,
    private val proofs: BackupProofs,
    private val log: DiagnosticLog,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
) : RestoreEngine {

    /** Serializes imports on this engine (defense in depth; features use [BackupSession] first). */
    private val restoreMutex = Mutex()

    override suspend fun restore(
        document: BackupDocument,
        confirm: suspend (RestoreConfirmation) -> Boolean,
    ): RestoreOutcome = restoreMutex.withLock {
        // Cancellable phase: reading, validating and asking. Nothing is written yet.
        val plan = try {
            prepare(document, confirm) ?: return@withLock RestoreOutcome.Cancelled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG_APP, "Import Fehler: ${e.message ?: e::class.simpleName}")
            return@withLock RestoreOutcome.Failed(errorOf(e))
        }
        // Write phase: once confirmed it always runs to the end (Flutter's restoreBackup could not
        // be cancelled after the dialogs either), so no half-switched identity is left behind.
        withContext(NonCancellable + cpu) { apply(plan) }
    }

    /** Everything the write phase needs, parsed and type-checked up front. */
    private class Plan(
        val version: Long,
        val data: JsonObject,
        val userJson: JsonObject,
        val incoming: List<MeetupBadge>,
        val existingNpub: String,
        val decision: RestoreDecision,
        val freshUser: UserProfile?,
        val shape: Shape,
    )

    /** The optional sections, validated before the hold (spec 08 §14 risk 12). */
    private class Shape(
        /** `nostr` if it will be applied (`version >= 2`, not additive), else `null`. */
        val nostr: JsonObject?,
        val hasKey: Boolean,
        val adminRegistry: JsonArray?,
        val platformProofs: JsonArray?,
        val humanity: JsonObject?,
        val humanityVerified: Boolean,
        /** Additive import onto a device whose humanity proof is already verified: kept as is. */
        val humanityAlready: Boolean,
    )

    /** Parses, validates and confirms; `null` = the user declined. Writes nothing. */
    private suspend fun prepare(
        document: BackupDocument,
        confirm: suspend (RestoreConfirmation) -> Boolean,
    ): Plan? {
        val data = document.data
        val version = document.version
        val (userJson, incoming, backupNpub) = withContext(cpu) {
            val user = data.strictObjectOrNull("user") ?: throw BackupFormatException("user ist kein Objekt")
            Triple(user, parseBadges(data), RestorePolicy.backupIdentityNpub(data))
        }

        val existingNpub = badgeSync.pendingFrom() ?: DartString.trim(identity.npub() ?: "")
        val decision = RestorePolicy.restoreDecision(existingNpub, backupNpub)
        val additive = decision.isAdditive
        val humanityAlready = additive && proofs.humanityVerifiedPref()
        // Profile fields and every optional section are validated before the confirmation and
        // the hold, so a type error cannot leave a half-written restore behind.
        val (freshUser, shape) = withContext(cpu) {
            val fresh = if (additive) {
                // Dart `_mergeProfile`: `favoriteMeetupIds as List?` (the other fields use toString()).
                userJson.strictListOrNull("favoriteMeetupIds", "user.favoriteMeetupIds")
                null
            } else {
                RestorePolicy.freshProfile(userJson)
            }
            fresh to validateShape(data, version, additive, humanityAlready)
        }
        log.info(TAG_RESTORE, "Backup entschlüsselt; Modus: ${decision.logName}.")
        when (decision) {
            RestoreDecision.REPLACES_IDENTITY ->
                if (!confirm(RestoreConfirmation.Replace(existingNpub, backupNpub))) return null
            RestoreDecision.ADD_TO_IDENTITY ->
                if (!confirm(RestoreConfirmation.AddUnknown(existingNpub))) return null
            else -> Unit
        }
        return Plan(version, data, userJson, incoming, existingNpub, decision, freshUser, shape)
    }

    /**
     * Every Dart cast of the later steps, done before anything is written: `nostr as Map` and its
     * typed fields (only where Flutter reads them), `admin_registry as List` (v2+),
     * `platform_proofs as List`, `humanity_proof as Map`, `verified as bool` and, where Flutter
     * applies the proof (verified, not [humanityAlready]), `receipt_event_id as String?`,
     * `first_zap_at as int?` and `checked_at as int?`.
     */
    private fun validateShape(data: JsonObject, version: Long, additive: Boolean, humanityAlready: Boolean): Shape {
        var nostr: JsonObject? = null
        var hasKey = false
        if (!additive && version >= 2) {
            nostr = data.strictObjectOrNull("nostr", "nostr")
            if (nostr != null) {
                hasKey = nostr.strictBool("has_key", false, "nostr.has_key")
                if (hasKey) {
                    nostr.strictString("nsec", "", "nostr.nsec")
                    nostr.strictString("npub", "", "nostr.npub")
                    nostr.strictString("priv_hex", "", "nostr.priv_hex")
                }
            }
        }
        val admins = if (version >= 2) data.strictListOrNull("admin_registry") else null
        val platform = data.strictListOrNull("platform_proofs")
        val humanity = data.strictObjectOrNull("humanity_proof")
        val humanityVerified = humanity?.strictBool("verified", false, "humanity_proof.verified") ?: false
        if (humanity != null && humanityVerified && !humanityAlready) {
            humanity.strictString("receipt_event_id", "", "humanity_proof.receipt_event_id")
            for (key in listOf("first_zap_at", "checked_at")) {
                val v = humanity.nonNull(key) ?: continue
                v.dartIntOrNull() ?: throw BackupFormatException("humanity_proof.$key ist keine Ganzzahl")
            }
        }
        return Shape(nostr, hasKey, admins, platform, humanity, humanityVerified, humanityAlready)
    }

    /** The write phase (Dart step 12 onwards). Runs under [NonCancellable]. */
    private suspend fun apply(plan: Plan): RestoreOutcome {
        val data = plan.data
        val shape = plan.shape
        val existingNpub = plan.existingNpub
        val decision = plan.decision
        val replacing = decision == RestoreDecision.REPLACES_IDENTITY
        val additive = decision.isAdditive
        var held = false
        try {
            badgeSync.beginRestoreHold(
                fromNpub = existingNpub,
                expectNpubs = if (replacing) RestorePolicy.backupIdentities(data) else emptyList(),
                replace = replacing,
            )
            held = true

            var user = plan.freshUser ?: RestorePolicy.mergeProfile(profiles.load(), plan.userJson)
            if (!replacing) profiles.save(user)

            val incoming = plan.incoming
            log.info(
                TAG_RESTORE,
                "Datei enthält ${incoming.size} Badge-Einträge, " +
                    "${BadgeIdentity.unionByIdentity(incoming, emptyList()).size} " +
                    "verschiedene Veranstaltungen/Organisator-Marker.",
            )
            var restored = incoming
            var addedBadges = incoming.size
            var badgesSaved: Boolean
            when {
                additive -> {
                    val before = badges.loadBadges()
                    restored = BadgeSyncFormat.merge(before, incoming)
                    addedBadges = RestorePolicy.addedBadgeCount(before, restored)
                    log.info(
                        TAG_RESTORE,
                        "Lokaler Bestand vorher: ${before.size}; nach Vereinigung: ${restored.size}; " +
                            "neu hinzugekommen: $addedBadges.",
                    )
                    badgesSaved = restoreBadges(restored)
                }
                replacing -> {
                    if (restored.isEmpty()) {
                        badgeSync.markRestoreClear()
                    } else if (!badges.stageIncoming(restored)) {
                        throw RestoreFailure(RestoreError.StageFailed(restored.size))
                    }
                    badgesSaved = true
                }
                else -> badgesSaved = restoreBadges(restored)
            }

            shape.nostr?.let { user = restoreNostr(it, user, persistProfile = !replacing) }

            if (replacing) {
                badgesSaved = finishReplace(data, existingNpub, restored, user)
            } else if (decision == RestoreDecision.FRESH) {
                clearProofsAfterFreshSwitch(existingNpub)
            }

            shape.adminRegistry?.let { restoreAdmins(it) }
            // `my_vouches` of older backups is ignored on purpose.

            val platformProofs = shape.platformProofs?.let(RestorePolicy::validatedPlatformProofs).orEmpty()
            var proofsSaved = restorePlatformProofs(platformProofs, additive)

            val humanityAlready = shape.humanityAlready
            val humanityJson = shape.humanity
            if (!humanityAlready && humanityJson != null && shape.humanityVerified) {
                if (!proofs.restoreHumanity(humanityJson, additive)) {
                    proofsSaved = false
                    log.warn(TAG_SERVICE, "Humanity-Proof aus dem Backup nicht gespeichert.")
                }
            }

            if (!badgesSaved) throw RestoreFailure(RestoreError.BadgesNotSaved(restored.size))
            if (!proofsSaved) throw RestoreFailure(RestoreError.ProofsNotSaved)

            log.info(TAG_RESTORE, "Restore abgeschlossen; Schreiben bestätigt. ${restored.size} Einträge verarbeitet.")
            return if (additive) {
                RestoreOutcome.Merged(addedBadges)
            } else {
                RestoreOutcome.Restored(
                    badges = restored.size,
                    nostrKey = shape.hasKey,
                    platformProofs = platformProofs.size.takeIf { it > 0 },
                    humanity = shape.humanityVerified && !humanityAlready,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG_APP, "Import Fehler: ${e.message ?: e::class.simpleName}")
            return RestoreOutcome.Failed(errorOf(e))
        } finally {
            // Already NonCancellable. A failed settle must not replace the outcome (the contract is
            // "never throws"); the persisted hold is settled by the next sync run or pendingFrom().
            if (held) {
                try {
                    badgeSync.endRestore()
                } catch (e: Exception) {
                    log.warn(TAG_RESTORE, "Restore-Riegel nicht aufgelöst: ${e.message ?: e::class.simpleName}")
                }
            }
        }
    }

    /**
     * Native fix (Flutter bug in `badge_sync_service.dart` settle + `backup_service.dart`): a fresh
     * restore holds with `from = ""`; once the backup key is in the keystore, settling sees a
     * switch and — without `badge_restore_proofs` — wiped the platform/humanity proofs this restore
     * writes next. Clearing here (before the proofs are written) records `badge_restore_proofs`,
     * so settle keeps them, also after a crash before `endRestore`. On a device without identity
     * the cleared proofs belong to nobody.
     */
    private suspend fun clearProofsAfterFreshSwitch(existingNpub: String) {
        val now = try {
            DartString.trim(identity.npub() ?: "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Settle reads the same npub: unreadable there too → no switch, nothing is wiped.
            log.warn(TAG_SERVICE, "Identität nach dem Restore nicht lesbar", e)
            ""
        }
        if (badgeSync.identitySwitched(existingNpub, now)) badgeSync.clearIdentityProofs()
    }

    /** `data['badges']` → badges; a missing/empty `iconPath` becomes the default asset. */
    private fun parseBadges(data: JsonObject): List<MeetupBadge> {
        val list = data.strictListOrNull("badges") ?: throw BackupFormatException("badges ist keine Liste")
        return list.mapIndexed { index, raw ->
            val map = raw as? JsonObject ?: throw BackupFormatException("badges[$index] ist kein Objekt")
            val icon = map.strictString("iconPath", "", "badges[$index].iconPath")
            val patched = if (icon.isEmpty()) {
                JsonObject(LinkedHashMap(map).apply { put("iconPath", JsonPrimitive(DEFAULT_ICON)) })
            } else {
                map
            }
            try {
                MeetupBadge.fromJson(patched)
            } catch (e: BadgeFormatException) {
                throw BackupFormatException("badges[$index]: ${e.message}", e)
            }
        }
    }

    /** Dart `restoreBadges` (spec 08 §8.5). */
    internal suspend fun restoreBadges(list: List<MeetupBadge>): Boolean {
        val wrote = badges.saveBadges(list, replaceUnreadable = true)
        if (!wrote && list.isNotEmpty()) {
            log.warn(TAG_SERVICE, "Badges aus dem Backup nicht gespeichert.")
            return false
        }
        log.info(
            TAG_RESTORE,
            "Badge-Datei schreiben: ${list.size} Eingänge, " +
                "${BadgeIdentity.unionByIdentity(list, emptyList()).size} eindeutige Einträge; " +
                "geschrieben: $wrote.",
        )
        return true
    }

    /** Spec 08 §8.6. Returns the updated profile. */
    private suspend fun restoreNostr(nostr: JsonObject, start: UserProfile, persistProfile: Boolean): UserProfile {
        var user = start
        val hasKey = nostr.strictBool("has_key", false, "nostr.has_key")
        val mode = nostr["signing_mode"].dartToStringOrNull() ?: "local"
        var restoredLocalKey = false

        if (hasKey) {
            val nsec = nostr.strictString("nsec", "", "nostr.nsec")
            val npub = nostr.strictString("npub", "", "nostr.npub")
            val privHex = nostr.strictString("priv_hex", "", "nostr.priv_hex")
            if (nsec.isNotEmpty() && npub.isNotEmpty() && privHex.isNotEmpty()) {
                if (!RestorePolicy.backupKeyConsistent(nsec, npub, privHex)) {
                    log.security(
                        TAG_SERVICE,
                        "Backup-Schlüssel inkonsistent (nsec/npub/priv_hex gehören nicht zusammen) — nicht übernommen.",
                    )
                    user = alignWithKeystore(user)
                    if (persistProfile) profiles.save(user)
                } else {
                    identity.saveKeys(nsec, npub, privHex)
                    restoredLocalKey = true
                }
            }
        }

        var externalRestored = false
        if (mode == "amber" || mode == "nip07" || mode == "nip46") {
            val activeRaw = nostr["active_npub"].dartToStringOrEmpty()
            val externalNpub = activeRaw.ifEmpty { nostr["npub"].dartToStringOrEmpty() }
            if (externalNpub.isNotEmpty()) {
                try {
                    when (mode) {
                        "nip07" -> identity.restoreNip07(externalNpub)
                        "nip46" -> identity.restoreNip46(externalNpub, nostr["nip46_bunker_uri"].dartToStringOrEmpty())
                        else -> identity.restoreAmber(externalNpub)
                    }
                    user = user.copy(nostrNpub = externalNpub, isNostrVerified = true, hasNostrKey = restoredLocalKey)
                    profiles.save(user)
                    externalRestored = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(TAG_SERVICE, "Externer Signer ($mode) aus dem Backup nicht wiederherstellbar", e)
                }
            }
        }

        if (!externalRestored && restoredLocalKey) {
            identity.useLocalMode()
            user = user.copy(
                nostrNpub = nostr["npub"].dartToStringOrEmpty(),
                isNostrVerified = true,
                hasNostrKey = true,
            )
            profiles.save(user)
        }
        return user
    }

    /** Dart `_alignNostrIdentityWithKeystore` without the save (the caller decides). */
    private suspend fun alignWithKeystore(user: UserProfile): UserProfile {
        val existing = identity.keystoreNpub()
        return if (!existing.isNullOrEmpty()) {
            user.copy(nostrNpub = existing, isNostrVerified = true, hasNostrKey = true)
        } else {
            user.copy(nostrNpub = "", isNostrVerified = false, hasNostrKey = false)
        }
    }

    /** Spec 08 §8.7. Returns `badgesSaved`. */
    private suspend fun finishReplace(
        data: JsonObject,
        existingNpub: String,
        restored: List<MeetupBadge>,
        user: UserProfile,
    ): Boolean {
        val nowNpub = DartString.trim(identity.npub() ?: "")
        val wanted = RestorePolicy.backupIdentities(data)
        val now = DartString.lower(nowNpub)
        val switched = badgeSync.identitySwitched(existingNpub, nowNpub) && (wanted.isEmpty() || now in wanted)
        if (!switched) {
            val unchanged = nowNpub.isNotEmpty() && now == DartString.lower(DartString.trim(existingNpub))
            if (unchanged) badges.discardIncoming()
            throw RestoreFailure(if (nowNpub.isEmpty()) RestoreError.KeyUnreadable else RestoreError.KeyNotTaken)
        }
        badgeSync.clearIdentityProofs()
        var saved = true
        if (restored.isEmpty()) {
            saved = badges.saveBadges(emptyList(), discardLocked = true)
            if (saved) badgeSync.clearRestoreMark()
        } else if (!badges.promoteIncoming(formerNpub = existingNpub)) {
            saved = false
        }
        if (saved) profiles.save(user)
        return saved
    }

    /** Spec 08 §8.8; applies in every decision mode. */
    private suspend fun restoreAdmins(list: JsonArray) {
        var restoredAdmins = 0
        var rejectedAdmins = 0
        for (raw in list) {
            try {
                val entry = AdminEntry.fromJson(raw)
                if (!Nip19.isValidNpub(entry.npub)) {
                    rejectedAdmins++
                    continue
                }
                admins.addAdmin(entry)
                restoredAdmins++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                rejectedAdmins++
            }
        }
        try {
            admins.forceRefresh()
            log.debug(TAG_BACKUP, "Admin-Registry revalidiert. Restored: $restoredAdmins, Rejected: $rejectedAdmins")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            log.warn(
                TAG_BACKUP,
                "Admin-Registry Revalidierung fehlgeschlagen (offline?). Einträge werden beim nächsten Online-Start geprüft.",
            )
        }
    }

    /** Spec 08 §8.9: format-checked entries only, stored verbatim. */
    private suspend fun restorePlatformProofs(validated: List<JsonElement>, additive: Boolean): Boolean {
        if (validated.isEmpty()) return true
        val ok = if (additive) proofs.unionPlatformProofs(validated) else proofs.replacePlatformProofs(validated)
        if (!ok) log.warn(TAG_SERVICE, "Plattform-Proofs aus dem Backup nicht gespeichert.")
        return ok
    }

    internal companion object {
        /** The typed cases a [RestoreHoldException] maps to (by its Flutter message). */
        internal val HOLD_ERRORS: List<RestoreError> = listOf(
            RestoreError.SwitchPending,
            RestoreError.HoldNotPersisted,
            RestoreError.ClearNotMarked,
            RestoreError.ProofsNotCleared,
        )

        /** Typed error of a failed restore step. */
        internal fun errorOf(e: Exception): RestoreError = when (e) {
            is RestoreFailure -> e.error
            // :data:badgesync carries only the Flutter text; RestoreEndToEndTest pins every message it
            // throws to one of these typed cases.
            is RestoreHoldException -> HOLD_ERRORS.firstOrNull { it.german == e.message }
                ?: RestoreError.Other(e.message ?: "RestoreHoldException")
            is BackupFormatException -> RestoreError.InvalidContent(e.message ?: "Formatfehler")
            else -> RestoreError.Other(e.message ?: e::class.simpleName ?: "Fehler")
        }

        private const val TAG_RESTORE = "BackupRestore"
        private const val TAG_SERVICE = "BackupService"
        private const val TAG_BACKUP = "Backup"
        private const val TAG_APP = "App"
        private const val DEFAULT_ICON = "assets/badge_icon.png"
    }
}
