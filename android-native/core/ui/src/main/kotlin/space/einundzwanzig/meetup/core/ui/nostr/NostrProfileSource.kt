package space.einundzwanzig.meetup.core.ui.nostr

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Data seam for [NostrAvatar] and [NpubChip]. The implementation lives in `:data:profile`
 * (profile lookup on relays, display-name cache, active identity) and is provided once by the
 * app shell through [LocalNostrProfileSource]. The UI here never talks to relays itself.
 *
 * All functions may suspend for network I/O and return null when nothing is known. They must
 * not throw for "not found"; unexpected exceptions are caught by the callers.
 */
interface NostrProfileSource {
    /** Hex pubkey of the active identity (local key, Amber, NIP-46 or public-only), or null. */
    suspend fun activePubkeyHex(): String? = null

    /** Profile picture URL from kind 0 metadata of [pubkeyHex]. */
    suspend fun pictureUrl(pubkeyHex: String): String? = null

    /** Display name for a bech32 [npub]; the implementation decodes NIP-19 itself. */
    suspend fun displayNameForNpub(npub: String): String? = null

    companion object {
        /** No data: avatars show the letter fallback, chips the shortened npub. */
        val None: NostrProfileSource = object : NostrProfileSource {}
    }
}

/** The app-wide [NostrProfileSource]; [NostrProfileSource.None] until the app provides one. */
val LocalNostrProfileSource = staticCompositionLocalOf { NostrProfileSource.None }
