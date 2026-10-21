package space.einundzwanzig.meetup.data.profile.internal

import kotlinx.coroutines.CancellationException
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.ui.nostr.NostrProfileSource
import space.einundzwanzig.meetup.data.profile.NostrProfileService

/**
 * The `:core:ui` [NostrProfileSource] for `NostrAvatar` / `NpubChip` (Dart: `NostrAvatar`
 * default loader `NostrProfileService.fetchProfilePicture`, active identity via
 * `NostrService.getNpub()` = `SigningService.npub()`; `NpubChip` → `fetchDisplayName`).
 */
internal class ProfileNostrProfileSource(
    private val activeNpub: suspend () -> String?,
    private val profiles: NostrProfileService,
) : NostrProfileSource {

    override suspend fun activePubkeyHex(): String? {
        val npub = activeNpub()?.takeIf { it.isNotEmpty() } ?: return null
        return decodeOrNull(npub)?.takeIf { it.isNotEmpty() }
    }

    override suspend fun pictureUrl(pubkeyHex: String): String? = profiles.fetchProfilePicture(pubkeyHex)

    /** Decode failure → no lookup; a wrong HRP decodes to `""` → `fetchDisplayName("")` → `null` (Dart parity). */
    override suspend fun displayNameForNpub(npub: String): String? {
        val hex = decodeOrNull(npub.trim()) ?: return null
        return profiles.fetchDisplayName(hex)
    }

    private fun decodeOrNull(npub: String): String? = try {
        Nip19.decodeNpub(npub)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
