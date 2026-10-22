package space.einundzwanzig.meetup.feature.profile.edit

import androidx.compose.runtime.Immutable
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.feature.profile.bunker.BunkerAction
import space.einundzwanzig.meetup.data.meetups.model.Meetup

/** Nickname validator result (spec 07 §2.2.2 item 2). */
enum class NicknameError { REQUIRED, ANON, TOO_SHORT }

/** Signer the user wants to switch to (confirmation before connecting, spec 01 §2.7.4). */
enum class SignerTarget { AMBER, BUNKER }

/** Why the screen needs device authentication (BiometricPrompt runs in the route). */
enum class AuthPurpose {
    /** Show the nsec of a freshly generated key. */
    SHOW_NEW_KEY,

    /** "NSEC" button: show the stored nsec. */
    SHOW_NSEC,

    /** ncryptsec export (fast path through the vault allowed). */
    EXPORT,

    /** ncryptsec export with a new password ("anderes Passwort"). */
    EXPORT_NEW_PASSWORD,
}

/** Dialogs and sheets of the profile screen; at most one is open. */
@Immutable
sealed interface ProfileDialog {
    /** "Achtung!" before leaving the verified read-only mode. */
    data object UnlockWarning : ProfileDialog

    /** Long key education dialog before creating a key. */
    data object KeyEducation : ProfileDialog

    /** Non-dismissible nsec backup dialog (FLAG_SECURE). */
    class NsecBackup(val nsec: String) : ProfileDialog {
        override fun toString() = "NsecBackup(***)"
        override fun equals(other: Any?) = other is NsecBackup && other.nsec == nsec
        override fun hashCode() = nsec.hashCode()
    }

    /** "nsec anzeigen?" confirmation. */
    data object ShowNsecConfirm : ProfileDialog

    /** nsec import dialog. */
    data object ImportNsec : ProfileDialog

    /** Password + confirmation for the ncryptsec export. */
    data object ExportPassword : ProfileDialog

    /** ncryptsec result sheet (FLAG_SECURE). */
    class NcryptsecSheet(val ncryptsec: String, val fromVault: Boolean) : ProfileDialog {
        override fun toString() = "NcryptsecSheet(fromVault=$fromVault)"
        override fun equals(other: Any?) = other is NcryptsecSheet && other.ncryptsec == ncryptsec && other.fromVault == fromVault
        override fun hashCode() = ncryptsec.hashCode() * 31 + fromVault.hashCode()
    }

    /** "Signer wechseln?" before connecting Amber / a bunker over an existing identity. */
    data class SwitchSignerConfirm(val target: SignerTarget) : ProfileDialog

    /** Amber is not installed. */
    data object AmberNotFound : ProfileDialog

    /** "Signer trennen?" confirmation. */
    data object DisconnectConfirm : ProfileDialog

    /** NIP-46 BunkerConnectSheet (spec 01 §2.5). */
    data object BunkerConnect : ProfileDialog

    /** Favourites / home meetup picker (MeetupSearchSheet, spec 09 §2.5). */
    data object MeetupPicker : ProfileDialog
}

/** Complete state of the profile screen (spec 07 §13 "ViewModel state"). */
@Immutable
data class ProfileEditUiState(
    val isLoading: Boolean = true,
    /** Load failed (e.g. unreadable secure storage); the screen offers a retry. */
    val loadError: String? = null,
    /** `!user.isAdminVerified` at load; "BEARBEITEN (Status verlieren)" switches to true. */
    val isEditing: Boolean = true,
    val nickname: String = "",
    /** The stored nickname (avatar letter, read-only tile). */
    val savedNickname: String = "",
    /** Validation errors are shown after the first save attempt (Flutter `Form.validate`). */
    val showValidation: Boolean = false,
    val selectedHome: String = "",
    val homeLabel: String = "",
    val favorites: List<String> = emptyList(),
    val homeMissing: Boolean = false,
    val meetups: List<Meetup> = emptyList(),
    /** A local nsec exists in the secure store. */
    val hasLocalKey: Boolean = false,
    val signingMode: SigningMode = SigningMode.LOCAL,
    val canSign: Boolean = false,
    /** `SigningService.npub() ?: user.nostrNpub`. */
    val npub: String = "",
    /** Locally picked picture (home screen), shown before the Nostr picture. */
    val localPicture: String? = null,
    val isImportingPicture: Boolean = false,
    val isGeneratingKey: Boolean = false,
    /** nsec import running (blocks a second tap on "Importieren"). */
    val isImportingNsec: Boolean = false,
    val isExporting: Boolean = false,
    val isCheckingBunker: Boolean = false,
    val isSaving: Boolean = false,
    val platformProofCount: Int = 0,
    val humanityVerified: Boolean = false,
    val dialog: ProfileDialog? = null,
    val pendingAuth: AuthPurpose? = null,
) {
    val isAmber: Boolean get() = signingMode == SigningMode.AMBER
    /** Hex pubkey of [npub] for the avatar lookup, `null` if it is not a valid npub. */
    val pubkeyHex: String? get() = npub.takeIf { it.isNotEmpty() }?.let { runCatching { Nip19.decodeNpub(it) }.getOrNull() }

    val isNip07: Boolean get() = signingMode == SigningMode.NIP07
    val isNip46: Boolean get() = signingMode == SigningMode.NIP46

    /** Dart `_hasNostrKey`. */
    val hasNostrKey: Boolean get() = hasLocalKey || isAmber || isNip07 || isNip46

    /** "Local mode" is derived from the mode, not from key existence (spec 01 §2.7.1 note). */
    val isLocalMode: Boolean get() = !isAmber && !isNip07 && !isNip46

    /** Re-offer the active signer type when the app currently cannot sign. */
    val offerAgain: Boolean get() = !canSign
    val offerBunker: Boolean get() = !isNip46 || offerAgain
    val offerAmber: Boolean get() = !isAmber || offerAgain
    val canDisconnect: Boolean get() = isAmber || isNip07 || isNip46

    /** Signer management renders nothing when there is neither an option nor a disconnect. */
    val showSignerSwitch: Boolean get() = offerBunker || offerAmber || canDisconnect

    val nicknameError: NicknameError? get() = if (showValidation) validateNickname(nickname) else null

    companion object {
        /** Validator on the trimmed value (Dart `TextFormField.validator`). */
        fun validateNickname(raw: String): NicknameError? {
            val v = raw.trim()
            return when {
                v.isEmpty() -> NicknameError.REQUIRED
                v.lowercase() == "anon" -> NicknameError.ANON
                v.length < 2 -> NicknameError.TOO_SHORT
                else -> null
            }
        }
    }
}

/** User actions of [ProfileEditScreen]. */
sealed interface ProfileEditAction {
    data object Back : ProfileEditAction
    data object Retry : ProfileEditAction
    data object DismissDialog : ProfileEditAction

    // Read-only mode
    data object RequestEdit : ProfileEditAction
    data object ConfirmEdit : ProfileEditAction

    // Form
    data class NicknameChanged(val value: String) : ProfileEditAction
    data object OpenMeetupPicker : ProfileEditAction
    data class MeetupPickerDone(val favorites: List<String>) : ProfileEditAction
    data object Save : ProfileEditAction

    // Key management (spec 01 §2.7)
    data object CopyNpub : ProfileEditAction
    data object CreateKey : ProfileEditAction
    data class KeyEducationResult(val confirmed: Boolean) : ProfileEditAction
    data object NsecCopied : ProfileEditAction
    data object ShowNsec : ProfileEditAction
    data object ShowNsecConfirmed : ProfileEditAction
    data object OpenImportNsec : ProfileEditAction
    data class ImportNsec(val nsec: String) : ProfileEditAction
    data object ExportEncrypted : ProfileEditAction
    data class ExportPassword(val password: String) : ProfileEditAction
    data object NcryptsecCopied : ProfileEditAction
    data object ExportOtherPassword : ProfileEditAction
    data class AuthResult(val purpose: AuthPurpose, val ok: Boolean) : ProfileEditAction

    /** The host is no `FragmentActivity`, so no prompt could be shown (counts as denied, with an error). */
    data class AuthUnavailable(val purpose: AuthPurpose) : ProfileEditAction

    // Signer management
    data object CheckBunker : ProfileEditAction
    data object ConnectAmber : ProfileEditAction
    data object ConnectBunker : ProfileEditAction
    data class SwitchSignerConfirmed(val target: SignerTarget) : ProfileEditAction
    data object Disconnect : ProfileEditAction
    data object DisconnectConfirmed : ProfileEditAction

    // Identity tiles
    data object OpenPlatformProofs : ProfileEditAction
    data object OpenHumanityProof : ProfileEditAction

    // Avatar (locally picked picture, shared with the Home header)
    data object PickAvatar : ProfileEditAction
    data class AvatarPicked(val uri: String) : ProfileEditAction

    // BunkerConnectSheet
    data class Bunker(val action: BunkerAction) : ProfileEditAction
}
