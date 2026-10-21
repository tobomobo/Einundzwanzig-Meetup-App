package space.einundzwanzig.meetup.data.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.crypto.Keys
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.backup.internal.dartToStringOrEmpty
import space.einundzwanzig.meetup.data.backup.internal.dartToStringOrNull
import space.einundzwanzig.meetup.data.backup.internal.nonNull
import space.einundzwanzig.meetup.data.backup.internal.strictBool
import space.einundzwanzig.meetup.data.backup.internal.strictListOrNull
import space.einundzwanzig.meetup.data.backup.internal.strictString
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofBackup

/**
 * The pure rules of a backup import (Dart `BackupService` static helpers, spec 08 §7.6, §8.2–§8.5,
 * §8.9). All string normalisation uses Dart's `trim`/`toLowerCase` ([DartString], spec 00 #12).
 */
object RestorePolicy {

    private val HEX = Regex("^[0-9a-fA-F]+$")

    /**
     * The identity a backup belongs to: `nostr.active_npub` (trimmed) if non-empty, else
     * `nostr.npub` (trimmed); `""` if `nostr` is missing or not an object.
     */
    fun backupIdentityNpub(data: JsonObject): String {
        val nostr = data["nostr"] as? JsonObject ?: return ""
        val active = DartString.trim(nostr["active_npub"].dartToStringOrEmpty())
        if (active.isNotEmpty()) return active
        return DartString.trim(nostr["npub"].dartToStringOrEmpty())
    }

    /** Every identity the backup may switch to: ordered set of lower-cased `active_npub`, `npub`. */
    fun backupIdentities(data: JsonObject): List<String> {
        val nostr = data["nostr"] as? JsonObject ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (raw in listOf(nostr["active_npub"], nostr["npub"])) {
            val trimmed = DartString.trim(raw.dartToStringOrEmpty())
            if (trimmed.isNotEmpty()) out += DartString.lower(trimmed)
        }
        return out.toList()
    }

    /** Case-insensitive comparison of the device identity with the backup identity. */
    fun restoreDecision(existingNpub: String, backupNpub: String): RestoreDecision {
        val existing = DartString.lower(DartString.trim(existingNpub))
        val backup = DartString.lower(DartString.trim(backupNpub))
        return when {
            existing.isEmpty() -> RestoreDecision.FRESH
            backup.isNotEmpty() && backup == existing -> RestoreDecision.SAME_IDENTITY
            backup.isEmpty() -> RestoreDecision.ADD_TO_IDENTITY
            else -> RestoreDecision.REPLACES_IDENTITY
        }
    }

    /**
     * Fresh/replace mode: the profile as stored in the backup `user` object (spec 08 §8.3).
     * `isAdmin` maps to the vouch source only; these flags are caches the admin/humanity domains
     * re-verify (Audit C2).
     *
     * @throws BackupFormatException for fields Flutter's typed constructor would reject.
     */
    fun freshProfile(user: JsonObject): UserProfile {
        val home = user.strictString("homeMeetupId", "", "user.homeMeetupId")
        val favorites = user.strictListOrNull("favoriteMeetupIds", "user.favoriteMeetupIds")
            ?.map { it.dartToStringOrNull() ?: "null" }
            ?: if (home.isNotEmpty()) listOf(home) else emptyList()
        return UserProfile(
            nickname = user.strictString("nickname", UserProfile.DEFAULT_NICKNAME, "user.nickname"),
            fullName = user.strictString("fullName", "", "user.fullName"),
            homeMeetupId = home,
            favoriteMeetupIds = favorites,
            adminViaVouch = user.strictBool("isAdmin", false, "user.isAdmin"),
            isAdminVerified = user.strictBool("isAdminVerified", false, "user.isAdminVerified"),
            isNostrVerified = user.strictBool("isNostrVerified", false, "user.isNostrVerified"),
            nostrNpub = user.strictString("nostrNpub", "", "user.nostrNpub"),
            telegramHandle = user.strictString("telegramHandle", "", "user.telegramHandle"),
            twitterHandle = user.strictString("twitterHandle", "", "user.twitterHandle"),
        )
    }

    /**
     * Additive modes (spec 08 §8.4): only empty device fields are filled from the backup, favorites
     * are united (device first), the device keeps its custom nickname, home meetup, identity and
     * every verification/admin flag.
     *
     * @throws BackupFormatException if `favoriteMeetupIds` is present but not a list.
     */
    fun mergeProfile(device: UserProfile, user: JsonObject): UserProfile {
        fun fill(current: String, incoming: JsonElement?): String {
            if (DartString.trim(current).isNotEmpty()) return current
            val next = incoming.dartToStringOrEmpty()
            return if (DartString.trim(next).isEmpty()) current else next
        }

        val nickIncoming = user["nickname"].dartToStringOrEmpty()
        val nickname = when {
            device.hasCustomNickname -> device.nickname
            DartString.trim(nickIncoming).isEmpty() -> device.nickname
            else -> nickIncoming
        }
        val backupHome = user["homeMeetupId"].dartToStringOrEmpty()
        val backupFavs = user.strictListOrNull("favoriteMeetupIds", "user.favoriteMeetupIds")
            ?.map { it.dartToStringOrNull() ?: "null" }
            ?.filter { it.isNotEmpty() }
            ?: if (backupHome.isNotEmpty()) listOf(backupHome) else emptyList()
        val favs = LinkedHashSet<String>().apply {
            device.favoriteMeetupIds.filterTo(this) { it.isNotEmpty() }
            addAll(backupFavs)
        }.toMutableList()
        val home = if (DartString.trim(device.homeMeetupId).isNotEmpty()) device.homeMeetupId else backupHome
        if (home.isNotEmpty() && home !in favs) favs.add(0, home)
        return device.copy(
            nickname = nickname,
            fullName = fill(device.fullName, user.nonNull("fullName")),
            homeMeetupId = home,
            favoriteMeetupIds = favs,
            telegramHandle = fill(device.telegramHandle, user.nonNull("telegramHandle")),
            twitterHandle = fill(device.twitterHandle, user.nonNull("twitterHandle")),
        )
    }

    /** Number of [merged] badges whose union key (event identity) is not in [before]. */
    fun addedBadgeCount(before: List<MeetupBadge>, merged: List<MeetupBadge>): Int {
        val keys = before.mapTo(HashSet()) { BadgeIdentity.unionKey(it) }
        return merged.count { BadgeIdentity.unionKey(it) !in keys }
    }

    /**
     * Dart `unionPlatformProofs`: keyed by lower-cased `platform`, device entries first (later
     * device duplicates overwrite earlier ones), incoming only if absent; non-objects skipped.
     */
    fun unionPlatformProofs(device: List<JsonElement>, incoming: List<JsonElement>): List<JsonObject> =
        PlatformProofBackup.union(device, incoming)

    /**
     * Audit 2 #4 format check of a restored platform proof (no signature verification):
     * entries that are objects with a 128-hex `proof_sig` string. Other entries are dropped
     * (a non-string `proof_sig` too, like Dart's `as String?` inside its try/catch).
     */
    fun validatedPlatformProofs(entries: JsonArray): List<JsonElement> =
        PlatformProofBackup.restorable(entries)

    /** `^[0-9a-fA-F]{128}$`. */
    fun isValidProofSig(sig: String): Boolean = sig.length == 128 && HEX.matches(sig)

    /** Security Audit N3: `nsec` ⇔ `priv_hex` ⇔ `npub` (case-sensitive npub compare). */
    fun backupKeyConsistent(nsec: String, npub: String, privHex: String): Boolean =
        Keys.backupKeyConsistent(nsec, npub, privHex)

    /** `NostrService.shortenNpub` for the confirmation dialogs. */
    fun shortenNpub(npub: String): String = Nip19.shortenNpub(npub)
}
