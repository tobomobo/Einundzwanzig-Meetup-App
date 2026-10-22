package space.einundzwanzig.meetup.data.identity.nip55

/** NIP-55 constants (Amber). */
internal object Nip55 {
    const val AMBER_PACKAGE = "com.greenart7c3.nostrsigner"

    /** Permissions requested with `get_public_key` (exact Flutter string). */
    const val GET_PUBLIC_KEY_PERMISSIONS = """[{"type":"sign_event"},{"type":"nip44_encrypt"},{"type":"nip44_decrypt"}]"""
}

/**
 * A failed NIP-55 call with the Flutter bridge's error [code]: `cancelled`, `rejected`,
 * `signer_missing`, `busy`, `launch_failed`, `bad_args`.
 */
internal class Nip55Exception(val code: String, message: String? = null) : Exception(message ?: code)

/** Signed event JSON plus the bare signature (`result`/`signature` extra). */
internal data class Nip55SignResult(val event: String, val sig: String?)

/** Transport to Amber: ContentResolver first (background), then a foreground intent. */
internal interface Nip55Transport {
    /** `packageManager.getPackageInfo(amber)` succeeds. */
    fun isInstalled(): Boolean

    /** Foreground `get_public_key`; the raw value (npub, hex or JSON). @throws Nip55Exception */
    suspend fun getPublicKey(): String

    /** `sign_event` for the unsigned [eventJson] as [npub]. @throws Nip55Exception */
    suspend fun signEvent(eventJson: String, npub: String): Nip55SignResult

    /** `nip44_encrypt` / `nip44_decrypt`. @throws Nip55Exception */
    suspend fun nip44(type: String, peerHex: String, value: String, npub: String): String
}
