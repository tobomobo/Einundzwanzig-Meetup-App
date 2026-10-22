package space.einundzwanzig.meetup.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.ThemeMode
import space.einundzwanzig.meetup.feature.onboarding.bunker.BunkerAction
import space.einundzwanzig.meetup.feature.onboarding.bunker.BunkerSheetContent
import space.einundzwanzig.meetup.feature.onboarding.bunker.BunkerSheetState
import space.einundzwanzig.meetup.feature.onboarding.bunker.BunkerStep
import space.einundzwanzig.meetup.feature.onboarding.bunker.BunkerTags
import space.einundzwanzig.meetup.feature.onboarding.common.UiText
import space.einundzwanzig.meetup.feature.onboarding.intro.IntroAction
import space.einundzwanzig.meetup.feature.onboarding.intro.IntroScreen
import space.einundzwanzig.meetup.feature.onboarding.intro.IntroTags
import space.einundzwanzig.meetup.feature.onboarding.intro.IntroUiState
import space.einundzwanzig.meetup.feature.onboarding.setup.IdentitySetupScreen
import space.einundzwanzig.meetup.feature.onboarding.setup.IdentitySetupUiState
import space.einundzwanzig.meetup.feature.onboarding.setup.SecureSheetState
import space.einundzwanzig.meetup.feature.onboarding.setup.SetupAction
import space.einundzwanzig.meetup.feature.onboarding.setup.SetupStep
import space.einundzwanzig.meetup.feature.onboarding.setup.SetupTags
import space.einundzwanzig.meetup.feature.onboarding.splash.SplashDestination
import space.einundzwanzig.meetup.feature.onboarding.splash.SplashScreen
import space.einundzwanzig.meetup.feature.onboarding.splash.SplashTags
import space.einundzwanzig.meetup.feature.onboarding.splash.SplashUiState

/** Robolectric Compose tests of the stateless onboarding screens (German resources). */
@RunWith(AndroidJUnit4::class)
class OnboardingScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent { EinundzwanzigTheme { content() } }

    // region splash

    @Test
    fun splashShowsTheLoadingIndicatorWhileChecking() {
        show { SplashScreen(SplashUiState.Checking, onRetry = {}) }
        rule.onNodeWithTag(SplashTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun splashStorageErrorOffersRetry() {
        var retries = 0
        show { SplashScreen(SplashUiState.StorageUnavailable, onRetry = { retries++ }) }
        rule.onNodeWithTag(SplashTags.STORAGE_ERROR).assertIsDisplayed()
        rule.onNodeWithText("Sicherer Speicher nicht lesbar").assertIsDisplayed()
        rule.onNodeWithTag(SplashTags.RETRY).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun splashReadyStillShowsTheBolt() {
        show { SplashScreen(SplashUiState.Ready(SplashDestination.HOME), onRetry = {}) }
        rule.onNodeWithTag(SplashTags.LOADING).assertExists()
    }

    // endregion

    // region intro

    @Test
    fun introJoinAndRestore() {
        val actions = mutableListOf<IntroAction>()
        show { IntroScreen(IntroUiState(), onAction = { actions += it }) }
        rule.onNodeWithText("COMMUNITY BETRETEN").assertExists()
        rule.onNodeWithTag(IntroTags.JOIN).performScrollTo().performClick()
        rule.onNodeWithTag(IntroTags.RESTORE).performScrollTo().performClick()
        assertEquals(listOf(IntroAction.Join, IntroAction.RestoreBackup), actions)
    }

    @Test
    fun introJoiningDisablesBothButtons() {
        show { IntroScreen(IntroUiState(joining = true), onAction = {}) }
        rule.onNodeWithTag(IntroTags.JOIN).assertIsNotEnabled()
        rule.onNodeWithTag(IntroTags.RESTORE).assertIsNotEnabled()
    }

    @Test
    fun introJoiningAnnouncesTheBusyButton() {
        show { IntroScreen(IntroUiState(joining = true), onAction = {}) }
        rule.onNodeWithTag(IntroTags.JOIN).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Wird geladen"),
        )
    }

    @Test
    fun introRendersInLightTheme() {
        val actions = mutableListOf<IntroAction>()
        rule.setContent { EinundzwanzigTheme(themeMode = ThemeMode.LIGHT) { IntroScreen(IntroUiState(), onAction = { actions += it }) } }
        rule.onNodeWithTag(IntroTags.JOIN).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf<IntroAction>(IntroAction.Join), actions)
    }

    @Test
    fun introLanguageDialogSelectsALanguage() {
        val actions = mutableListOf<IntroAction>()
        show { IntroScreen(IntroUiState(locale = "de", showLanguageDialog = true), onAction = { actions += it }) }
        rule.onNodeWithTag(IntroTags.LANGUAGE_DIALOG).assertIsDisplayed()
        rule.onNodeWithText("Sprache wählen").assertIsDisplayed()
        rule.onNodeWithTag(IntroTags.language("es")).performClick()
        rule.onNodeWithTag(IntroTags.language(null)).performClick()
        assertEquals(listOf<IntroAction>(IntroAction.SelectLanguage("es"), IntroAction.SelectLanguage(null)), actions)
    }

    @Test
    fun introLanguageChipOpensTheDialog() {
        val actions = mutableListOf<IntroAction>()
        show { IntroScreen(IntroUiState(), onAction = { actions += it }) }
        rule.onNodeWithTag(IntroTags.LANGUAGE_CHIP).performClick()
        assertEquals(listOf<IntroAction>(IntroAction.OpenLanguage), actions)
    }

    // endregion

    // region identity setup

    private fun setup(state: IdentitySetupUiState, actions: MutableList<SetupAction> = mutableListOf()) =
        show { IdentitySetupScreen(state, onAction = { actions += it }) }

    @Test
    fun setupBootstrapShowsOnlyProgressAndSubtitle() {
        setup(IdentitySetupUiState(bootstrapping = true))
        rule.onNodeWithTag(SetupTags.BOOTSTRAP).assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.CARD_NEW).assertDoesNotExist()
    }

    @Test
    fun setupChooseWithoutIdentity() {
        val actions = mutableListOf<SetupAction>()
        setup(IdentitySetupUiState(bootstrapping = false), actions)
        rule.onNodeWithTag(SetupTags.CARD_RESUME).assertDoesNotExist()
        rule.onNodeWithTag(SetupTags.CARD_NEW).performClick()
        rule.onNodeWithTag(SetupTags.CARD_EXISTING).performClick()
        rule.onNodeWithTag(SetupTags.CLOSE).performClick()
        assertEquals(listOf(SetupAction.PickNew, SetupAction.PickExisting, SetupAction.Close), actions)
        rule.onNodeWithTag(SetupTags.REVIEW_BOX).assertDoesNotExist()
    }

    @Test
    fun setupChooseOffersResumeAndReviewSignIn() {
        val actions = mutableListOf<SetupAction>()
        setup(IdentitySetupUiState(bootstrapping = false, hasLocalKey = true, offerReviewSignIn = true), actions)
        rule.onNodeWithTag(SetupTags.CARD_RESUME).performClick()
        rule.onNodeWithTag(SetupTags.REVIEW_SUBMIT).performScrollTo().performClick()
        assertEquals(listOf(SetupAction.PickResume, SetupAction.ReviewSignIn), actions)
    }

    @Test
    fun setupOverwriteConfirmation() {
        val actions = mutableListOf<SetupAction>()
        setup(IdentitySetupUiState(bootstrapping = false, hasLocalKey = true, confirmOverwrite = true), actions)
        rule.onNodeWithText("Neue Identität anlegen?").assertIsDisplayed()
        rule.onNodeWithText("Neu anlegen").performClick()
        assertEquals(listOf<SetupAction>(SetupAction.ConfirmOverwrite), actions)
    }

    @Test
    fun setupNewStepEnablesCreateOnlyWithValidPasswords() {
        setup(IdentitySetupUiState(bootstrapping = false, step = SetupStep.NEW, name = "Hal", pass = "1234567", pass2 = "1234567"))
        rule.onNodeWithTag(SetupTags.CREATE).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun setupNewStepCreate() {
        val actions = mutableListOf<SetupAction>()
        setup(
            IdentitySetupUiState(bootstrapping = false, step = SetupStep.NEW, name = "Hal", pass = "12345678", pass2 = "12345678"),
            actions,
        )
        rule.onNodeWithTag(SetupTags.CREATE).performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithTag(SetupTags.BACK).performClick()
        assertEquals(listOf(SetupAction.Register, SetupAction.Back), actions)
    }

    @Test
    fun setupNewStepTypingForwardsActions() {
        val actions = mutableListOf<SetupAction>()
        setup(IdentitySetupUiState(bootstrapping = false, step = SetupStep.NEW), actions)
        rule.onNodeWithTag(SetupTags.NAME).performTextInput("Hal")
        assertTrue(actions.contains(SetupAction.NameChanged("Hal")))
    }

    @Test
    fun setupErrorBoxIsShown() {
        setup(IdentitySetupUiState(bootstrapping = false, step = SetupStep.EXISTING_MORE, error = UiText.Raw("Falsches Passwort")))
        rule.onNodeWithText("Falsches Passwort").assertIsDisplayed()
    }

    @Test
    fun setupBusyBlocksInput() {
        val actions = mutableListOf<SetupAction>()
        setup(IdentitySetupUiState(bootstrapping = false, busy = true), actions)
        rule.onNodeWithTag(SetupTags.CARD_NEW).performClick()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun setupExistingShowsBunkerPrimaryAndOtherWay() {
        val actions = mutableListOf<SetupAction>()
        setup(IdentitySetupUiState(bootstrapping = false, step = SetupStep.EXISTING), actions)
        rule.onNodeWithText("Signer verbinden").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.CARD_PRIMARY).performClick()
        rule.onNodeWithTag(SetupTags.CARD_OTHER_WAY).performClick()
        assertEquals(listOf(SetupAction.ConnectPrimary, SetupAction.OtherWay), actions)
    }

    @Test
    fun setupExistingMoreImportAndAlternatives() {
        val actions = mutableListOf<SetupAction>()
        setup(IdentitySetupUiState(bootstrapping = false, step = SetupStep.EXISTING_MORE, importKey = "nsec1abc"), actions)
        rule.onNodeWithTag(SetupTags.IMPORT_ACTION).performScrollTo().performClick()
        rule.onNodeWithTag(SetupTags.CARD_AMBER).performScrollTo().performClick()
        rule.onNodeWithTag(SetupTags.RESTORE).performScrollTo().performClick()
        assertEquals(listOf(SetupAction.ImportKey, SetupAction.ConnectAmber, SetupAction.RestoreBackup), actions)
        // Bunker is the primary path: no extra bunker card.
        rule.onNodeWithTag(SetupTags.CARD_BUNKER).assertDoesNotExist()
    }

    @Test
    fun setupResumeWithPasskeyOffersPasswordFallback() {
        val actions = mutableListOf<SetupAction>()
        setup(
            IdentitySetupUiState(bootstrapping = false, step = SetupStep.RESUME, hasPasskeyWrap = true, hasPasswordWrap = true),
            actions,
        )
        rule.onNodeWithTag(SetupTags.RESUME_PASSKEY).performClick()
        rule.onNodeWithTag(SetupTags.RESUME_USE_PASSWORD).performClick()
        assertEquals(listOf(SetupAction.ResumeWithPasskey, SetupAction.UsePasswordInstead), actions)
        rule.onNodeWithTag(SetupTags.RESUME_PASS).assertDoesNotExist()
    }

    @Test
    fun setupPasskeyStep() {
        val actions = mutableListOf<SetupAction>()
        setup(IdentitySetupUiState(bootstrapping = false, step = SetupStep.PASSKEY), actions)
        rule.onNodeWithTag(SetupTags.PASSKEY_ADD).performClick()
        rule.onNodeWithTag(SetupTags.PASSKEY_LATER).performClick()
        assertEquals(listOf(SetupAction.AddPasskey, SetupAction.PasskeyLater), actions)
    }

    @Test
    fun setupMeetupStepStates() {
        val actions = mutableListOf<SetupAction>()
        val meetups = listOf(meetup("1", "Berlin"), meetup("2", "Wien"))
        setup(IdentitySetupUiState(bootstrapping = false, step = SetupStep.MEETUP, meetups = meetups, selectedMeetups = listOf("2")), actions)
        rule.onNodeWithText("Wien").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.MEETUP_PICK).performClick()
        rule.onNodeWithTag(SetupTags.MEETUP_SAVE).performClick()
        rule.onNodeWithTag(SetupTags.MEETUP_LATER).performClick()
        assertEquals(listOf(SetupAction.OpenMeetupPicker, SetupAction.SaveMeetup, SetupAction.MeetupLater), actions)
    }

    @Test
    fun setupMeetupLoading() {
        setup(IdentitySetupUiState(bootstrapping = false, step = SetupStep.MEETUP, meetupsLoading = true))
        rule.onNodeWithTag(SetupTags.MEETUP_LOADING).assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.MEETUP_PICK).assertDoesNotExist()
    }

    @Test
    fun setupSecureSheetStep1AndStep2() {
        val actions = mutableListOf<SetupAction>()
        setup(IdentitySetupUiState(bootstrapping = false, step = SetupStep.NEW, secureSheet = SecureSheetState(step = 1)), actions)
        rule.onNodeWithTag(SetupTags.SECURE_SHEET).assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.SECURE_COPY).assertDoesNotExist()
        rule.onNodeWithTag(SetupTags.SECURE_PRIMARY).performClick()
        rule.onNodeWithTag(SetupTags.SECURE_SKIP).performClick()
        assertEquals(listOf(SetupAction.SecureCreateBackup, SetupAction.SecureSkip), actions)
    }

    @Test
    fun setupSecureSheetStep2() {
        val actions = mutableListOf<SetupAction>()
        setup(IdentitySetupUiState(bootstrapping = false, step = SetupStep.NEW, secureSheet = SecureSheetState(step = 2)), actions)
        rule.onNodeWithTag(SetupTags.SECURE_PRIMARY).performClick()
        rule.onNodeWithTag(SetupTags.SECURE_COPY).performClick()
        rule.onNodeWithTag(SetupTags.SECURE_SKIP).performClick()
        assertEquals(listOf(SetupAction.SecureSaveFile, SetupAction.SecureCopy, SetupAction.SecureLater), actions)
    }

    // endregion

    // region bunker sheet

    @Test
    fun bunkerChooseOffersBothModes() {
        val actions = mutableListOf<BunkerAction>()
        show { BunkerSheetContent(BunkerSheetState(), onAction = { actions += it }) }
        rule.onNodeWithText("Mit Remote-Signer verbinden").assertIsDisplayed()
        rule.onNodeWithTag(BunkerTags.MODE_SIGNER).performClick()
        rule.onNodeWithTag(BunkerTags.MODE_PASTE).performClick()
        assertEquals(listOf(BunkerAction.StartSignerApp, BunkerAction.ShowPaste), actions)
    }

    @Test
    fun bunkerPasteConnectAndBusyState() {
        val actions = mutableListOf<BunkerAction>()
        show { BunkerSheetContent(BunkerSheetState(step = BunkerStep.PASTE, pasteInput = "bunker://x"), onAction = { actions += it }) }
        rule.onNodeWithTag(BunkerTags.CONNECT).performScrollTo().performClick()
        assertEquals(listOf<BunkerAction>(BunkerAction.ConnectPasted), actions)
    }

    @Test
    fun bunkerBusyDisablesConnectAndShowsWaiting() {
        show { BunkerSheetContent(BunkerSheetState(step = BunkerStep.PASTE, pasteInput = "bunker://x", busy = true), onAction = {}) }
        rule.onNodeWithTag(BunkerTags.CONNECT).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(BunkerTags.WAITING).assertExists()
    }

    @Test
    fun bunkerErrorIsShown() {
        show { BunkerSheetContent(BunkerSheetState(step = BunkerStep.PASTE, error = UiText.Raw("Zeit abgelaufen")), onAction = {}) }
        rule.onNodeWithTag(BunkerTags.ERROR).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Zeit abgelaufen").assertExists()
    }

    // endregion
}
