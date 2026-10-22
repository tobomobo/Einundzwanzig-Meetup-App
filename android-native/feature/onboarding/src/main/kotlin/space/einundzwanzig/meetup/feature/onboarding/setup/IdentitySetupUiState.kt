package space.einundzwanzig.meetup.feature.onboarding.setup

import androidx.compose.runtime.Immutable
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.data.identity.RecommendedExistingPath
import space.einundzwanzig.meetup.data.identity.easyauth.LocalEasyAuth
import space.einundzwanzig.meetup.data.identity.easyauth.PasskeyRegistration
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfResult
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.feature.onboarding.bunker.BunkerAction
import space.einundzwanzig.meetup.feature.onboarding.common.UiText

/** Wizard steps of the identity setup (spec 01 §2.3.1). */
enum class SetupStep(val titleRes: Int, val subtle: Boolean) {
    CHOOSE(L10nR.string.idSetupTitle, subtle = false),
    NEW(L10nR.string.idSetupNewTitle, subtle = true),
    RESUME(L10nR.string.idSetupResumeTitle, subtle = true),
    PASSKEY(L10nR.string.idSetupPasskeyTitle, subtle = false),
    EXISTING(L10nR.string.idSetupExistingTitle, subtle = false),
    EXISTING_MORE(L10nR.string.idSetupExistingTitle, subtle = true),
    NAME_ONLY(L10nR.string.idSetupNameTitle, subtle = true),
    MEETUP(L10nR.string.idSetupMeetupTitle, subtle = false),
}

/** Password fields with their own show/hide toggle. */
enum class PasswordField { PASS, PASS2, IMPORT_PASS, RESUME_PASS, REVIEW_PASS }

/** The "secure your identity" sheet (spec 01 §2.3.7). [step] is 1 (backup file) or 2 (ncryptsec). */
@Immutable
data class SecureSheetState(val step: Int, val busy: Boolean = false)

/** A passkey ceremony the route must run with the Activity (Credential Manager needs it). */
@Immutable
sealed interface PasskeyRequest {
    data class Register(val registration: PasskeyRegistration) : PasskeyRequest
    data class Unlock(val credentialIds: List<String>) : PasskeyRequest
}

@Immutable
data class IdentitySetupUiState(
    val step: SetupStep = SetupStep.CHOOSE,
    val busy: Boolean = false,
    val bootstrapping: Boolean = true,
    val error: UiText? = null,
    val passkeySupported: Boolean = false,
    val path: RecommendedExistingPath = RecommendedExistingPath.BUNKER,
    val hasLocalKey: Boolean = false,
    val hasPasswordWrap: Boolean = false,
    val hasPasskeyWrap: Boolean = false,
    val resumeUsePassword: Boolean = false,
    val offerReviewSignIn: Boolean = false,
    val meetups: List<Meetup> = emptyList(),
    val selectedMeetups: List<String> = emptyList(),
    val meetupsLoading: Boolean = false,
    val name: String = "",
    val pass: String = "",
    val pass2: String = "",
    val importKey: String = "",
    val importPass: String = "",
    val resumePass: String = "",
    val revealed: Set<PasswordField> = emptySet(),
    val secureSheet: SecureSheetState? = null,
    val showBunkerSheet: Boolean = false,
    val showMeetupPicker: Boolean = false,
    /** Native: confirm "Neu hier" when an identity survived on the device (spec 01 §2.3.3 note). */
    val confirmOverwrite: Boolean = false,
    val pendingPasskey: PasskeyRequest? = null,
    /**
     * The route already started the Credential Manager UI for [pendingPasskey]. A recreated
     * composition (rotation, theme or locale change) must not start a second system prompt.
     */
    val passkeyLaunched: Boolean = false,
    val reviewUser: String = "",
    val reviewPass: String = "",
    val reviewBusy: Boolean = false,
    val reviewError: String? = null,
) {
    val canResume: Boolean get() = hasLocalKey || hasPasswordWrap || hasPasskeyWrap

    /** The whole list absorbs input (Flutter `AbsorbPointer`). */
    val inputBlocked: Boolean get() = busy || bootstrapping

    val passLengthOk: Boolean get() = pass.length >= LocalEasyAuth.MIN_PASSWORD_LENGTH
    val passMatch: Boolean get() = pass.isNotEmpty() && pass == pass2

    /** "Loslegen" enabled. */
    val pwOk: Boolean get() = passLengthOk && passMatch
}

/** User input on the identity setup. */
sealed interface SetupAction {
    data object Close : SetupAction
    data object Back : SetupAction

    data object PickResume : SetupAction
    data object PickNew : SetupAction
    data object ConfirmOverwrite : SetupAction
    data object DismissOverwrite : SetupAction
    data object PickExisting : SetupAction

    data class NameChanged(val value: String) : SetupAction
    data class PassChanged(val value: String) : SetupAction
    data class Pass2Changed(val value: String) : SetupAction
    data class ImportChanged(val value: String) : SetupAction
    data class ImportPassChanged(val value: String) : SetupAction
    data class ResumePassChanged(val value: String) : SetupAction
    data class ToggleReveal(val field: PasswordField) : SetupAction

    data object Register : SetupAction
    data object Resume : SetupAction
    data object ResumeWithPasskey : SetupAction
    data object UsePasswordInstead : SetupAction
    data object AddPasskey : SetupAction
    data object PasskeyLater : SetupAction

    /** The route is about to start the Credential Manager UI for [request]. */
    data class PasskeyLaunched(val request: PasskeyRequest) : SetupAction

    /** Result of the Credential Manager ceremony the route ran for [request]. */
    data class PasskeyDone(val request: PasskeyRequest, val result: Result<PasskeyPrfResult>) : SetupAction

    data object ConnectPrimary : SetupAction
    data object OtherWay : SetupAction
    data object ImportKey : SetupAction
    data object ConnectBunker : SetupAction
    data object ConnectAmber : SetupAction
    data object RestoreBackup : SetupAction

    data object SaveName : SetupAction

    data object OpenMeetupPicker : SetupAction
    data object DismissMeetupPicker : SetupAction
    data class MeetupsPicked(val ids: List<String>) : SetupAction
    data object SaveMeetup : SetupAction
    data object MeetupLater : SetupAction

    data object SecureCreateBackup : SetupAction
    data object SecureSkip : SetupAction
    data object SecureSaveFile : SetupAction
    data object SecureCopy : SetupAction
    data object SecureLater : SetupAction

    /** The SAF document for the key file: [uri] `null` = cancelled; [header] = `idSetupSecureFileHeader`. */
    data class KeyFilePicked(val uri: String?, val header: String) : SetupAction

    data class ReviewUserChanged(val value: String) : SetupAction
    data class ReviewPassChanged(val value: String) : SetupAction
    data object ReviewSignIn : SetupAction

    data class Bunker(val action: BunkerAction) : SetupAction
}

/** One-shot effects the route executes. */
sealed interface SetupEffect {
    data object Back : SetupEffect
    data object GoHome : SetupEffect
    data class CopyPlain(val text: String) : SetupEffect

    /** Open SAF `CreateDocument("text/plain")` with [fileName]. */
    data class CreateKeyFile(val fileName: String) : SetupEffect
}
