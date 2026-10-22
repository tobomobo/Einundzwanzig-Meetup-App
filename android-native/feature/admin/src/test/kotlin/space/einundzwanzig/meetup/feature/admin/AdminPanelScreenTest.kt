package space.einundzwanzig.meetup.feature.admin

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.meetups.location.GpsStatus
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class AdminPanelScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = mutableListOf<AdminPanelAction>()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(state: AdminPanelUiState) {
        rule.setContent { EinundzwanzigTheme { AdminPanelScreen(state, onAction = { actions += it }) } }
    }

    private val idle = AdminPanelUiState(adminNpub = NPUB, sessionChecked = true)

    @Test
    fun `idle panel shows status, start tile and how-it-works box`() {
        show(idle)
        rule.onAllNodesWithText(s(L10nR.string.apOrganizer)).assertCountEquals(2)
        rule.onNodeWithText(s(L10nR.string.apMeetupSession)).assertExists()
        rule.onNodeWithText(s(L10nR.string.apGeneratesProof)).assertExists()
        rule.onNodeWithText(s(L10nR.string.apHowItWorks)).performScrollTo().assertExists()
        rule.onNodeWithText(s(L10nR.string.apCrossConfirmTitle)).performScrollTo().assertExists()
    }

    @Test
    fun `tapping the start tile asks to start`() {
        show(idle)
        rule.onNodeWithTag(AdminPanelTags.START_TILE).performClick()
        assertEquals(listOf<AdminPanelAction>(AdminPanelAction.StartClicked), actions)
    }

    @Test
    fun `trust score promotion shows its label`() {
        show(idle.copy(viaTrustScore = true))
        rule.onNodeWithText(s(L10nR.string.apViaTrustScore)).assertExists()
    }

    @Test
    fun `before the session check neither tile nor session card shows`() {
        show(AdminPanelUiState())
        rule.onNodeWithTag(AdminPanelTags.START_TILE).assertDoesNotExist()
        rule.onNodeWithTag(AdminPanelTags.ACTIVE_SESSION).assertDoesNotExist()
    }

    @Test
    fun `running session shows countdown and its actions`() {
        show(idle.copy(session = ActiveSessionUi("Würzburg", 0, "3h 07m 42s")))
        rule.onNodeWithText(s(L10nR.string.apSessionRunning)).assertExists()
        rule.onNodeWithText("3h 07m 42s").assertExists()
        rule.onNodeWithText("Würzburg").assertExists()
        rule.onNodeWithTag(AdminPanelTags.START_TILE).assertDoesNotExist()
        rule.onNodeWithText(s(L10nR.string.apOpenActiveMeetup)).performClick()
        rule.onNodeWithText(s(L10nR.string.apEndMeetupEarly)).performClick()
        assertEquals(listOf(AdminPanelAction.OpenActiveMeetup, AdminPanelAction.EndClicked), actions)
    }

    @Test
    fun `start confirmation dialog confirms and cancels`() {
        show(idle.copy(dialog = AdminDialog.ConfirmStart))
        rule.onNodeWithText(s(L10nR.string.apNewMeetupQ)).assertExists()
        rule.onNodeWithText(s(L10nR.string.apStart)).performClick()
        rule.onNodeWithText(s(L10nR.string.apCancel)).performClick()
        assertEquals(listOf(AdminPanelAction.ConfirmStart, AdminPanelAction.DismissDialog), actions)
    }

    @Test
    fun `end confirmation dialog ends`() {
        show(idle.copy(dialog = AdminDialog.ConfirmEnd))
        rule.onNodeWithText(s(L10nR.string.apSessionEndQ)).assertExists()
        rule.onNodeWithText(s(L10nR.string.apEnd)).performClick()
        assertEquals(listOf<AdminPanelAction>(AdminPanelAction.ConfirmEnd), actions)
    }

    @Test
    fun `denied gps dialog offers app settings, retry and continue`() {
        show(idle.copy(dialog = AdminDialog.ContinueWithoutGps(GpsStatus.DENIED)))
        rule.onNodeWithText(s(L10nR.string.orgGpsSoftTitle)).assertExists()
        rule.onNodeWithText(s(L10nR.string.gpsOpenLocationSettings)).assertDoesNotExist()
        rule.onNodeWithText(s(L10nR.string.gpsOpenAppSettings)).performClick()
        rule.onNodeWithText(s(L10nR.string.gpsRetry)).performClick()
        rule.onNodeWithText(s(L10nR.string.orgGpsSoftContinue)).performClick()
        assertEquals(
            listOf(AdminPanelAction.OpenAppSettings, AdminPanelAction.GpsRetry, AdminPanelAction.GpsContinue),
            actions,
        )
    }

    @Test
    fun `disabled gps dialog offers location settings`() {
        show(idle.copy(dialog = AdminDialog.ContinueWithoutGps(GpsStatus.SERVICE_DISABLED)))
        rule.onNodeWithText(s(L10nR.string.gpsOpenAppSettings)).assertDoesNotExist()
        rule.onNodeWithText(s(L10nR.string.gpsOpenLocationSettings)).performClick()
        assertEquals(listOf<AdminPanelAction>(AdminPanelAction.OpenLocationSettings), actions)
    }

    @Test
    fun `portal picker lists rows, searches, picks and enters manually`() {
        val wue = meetup("Würzburg", name = "Einundzwanzig Würzburg")
        val koeln = meetup("Köln", lat = 0.0, lng = 0.0)
        var state by mutableStateOf(
            idle.copy(dialog = AdminDialog.PortalPicker(PortalMeetupPicker.order(listOf(koeln, wue), setOf("würzburg")))),
        )
        rule.setContent {
            EinundzwanzigTheme {
                AdminPanelScreen(state, onAction = {
                    actions += it
                    if (it is AdminPanelAction.PickerQueryChanged) {
                        state = state.copy(dialog = (state.dialog as AdminDialog.PortalPicker).copy(query = it.query))
                    }
                })
            }
        }
        rule.onNodeWithText(s(L10nR.string.apPickPortalTitle)).assertExists()
        rule.onNodeWithText("Einundzwanzig Würzburg").assertExists()
        rule.onNodeWithContentDescription(context.getString(R.string.native_admin_own_meetup)).assertExists()
        rule.onNodeWithContentDescription(context.getString(R.string.native_admin_no_location)).assertExists()

        rule.onNodeWithTag(AdminPanelTags.PICKER_SEARCH).performTextInput("köl")
        rule.waitForIdle()
        rule.onNodeWithText("Würzburg").assertDoesNotExist()
        rule.onNodeWithText("Köln").performClick()
        rule.onNodeWithText(s(L10nR.string.apEnterManually)).performClick()
        assertTrue(AdminPanelAction.PickerQueryChanged("köl") in actions)
        assertTrue(AdminPanelAction.PickerRowClicked(koeln) in actions)
        assertEquals(AdminPanelAction.PickerEnterManually, actions.last())
    }

    @Test
    fun `pick confirmation shows the city in capitals`() {
        val wue = meetup("Würzburg")
        show(idle.copy(dialog = AdminDialog.PortalPicker(PortalMeetupPicker.order(listOf(wue), emptySet()), confirming = wue)))
        rule.onNodeWithText(s(L10nR.string.apConfirmPickTitle)).assertExists()
        rule.onNodeWithText("WÜRZBURG").assertExists()
        rule.onNodeWithText(s(L10nR.string.apConfirmPickYes)).performClick()
        rule.onNodeWithText(s(L10nR.string.dialogCancel)).performClick()
        assertEquals(listOf(AdminPanelAction.PickerConfirmYes, AdminPanelAction.PickerConfirmCancel), actions)
    }

    @Test
    fun `no reference dialog names the city`() {
        show(idle.copy(dialog = AdminDialog.NoReference("Irgendwo")))
        rule.onNodeWithText(s(L10nR.string.apNoRefBody, "Irgendwo")).assertExists()
        rule.onNodeWithText(s(L10nR.string.apNoRefContinue)).performClick()
        assertEquals(listOf<AdminPanelAction>(AdminPanelAction.NoReferenceContinue), actions)
    }

    @Test
    fun `custom name without gps offers settings and retry`() {
        show(idle.copy(dialog = AdminDialog.CustomNeedsLocation))
        rule.onNodeWithText(s(L10nR.string.apCustomNeedsGpsTitle)).assertExists()
        rule.onNodeWithText(s(L10nR.string.gpsRetry)).performClick()
        assertEquals(listOf<AdminPanelAction>(AdminPanelAction.GpsRetry), actions)
    }

    @Test
    fun `manual name dialog shows the validation error and submits`() {
        show(idle.copy(dialog = AdminDialog.ManualName("", showError = true)))
        rule.onNodeWithText(s(L10nR.string.gpsNoMeetupTitle)).assertExists()
        rule.onNodeWithText(s(L10nR.string.gpsNameRequired)).assertExists()
        rule.onNodeWithTag(AdminPanelTags.MANUAL_NAME).performTextInput("Garten")
        rule.onNodeWithText(s(L10nR.string.gpsStartAnyway)).performClick()
        assertEquals(
            listOf(AdminPanelAction.ManualNameChanged("Garten"), AdminPanelAction.ManualNameSubmit),
            actions,
        )
    }

    @Test
    fun `nearby sheet lists candidates with distance`() {
        val a = candidate("Würzburg", 1.25)
        val b = candidate("Kitzingen", 8.0)
        show(idle.copy(dialog = AdminDialog.PickNearby(listOf(a, b))))
        rule.onNodeWithText(s(L10nR.string.gpsPickMeetup)).assertExists()
        rule.onNodeWithText(s(L10nR.string.gpsDistanceKm, "1.3")).assertExists()
        rule.onNodeWithText("Kitzingen").performClick()
        // The sheet animates out first, then reports the pick.
        rule.waitForIdle()
        assertEquals(listOf<AdminPanelAction>(AdminPanelAction.NearbyPicked(b)), actions)
    }

    @Test
    fun `portal picker tolerates duplicate meetups from the portal`() {
        val wue = meetup("Würzburg")
        show(idle.copy(dialog = AdminDialog.PortalPicker(PortalMeetupPicker.order(listOf(wue, wue), emptySet()))))
        rule.onAllNodesWithText("Würzburg").assertCountEquals(2)
    }

    @Test
    fun `busy shows the blocking indicator`() {
        show(idle.copy(busy = true))
        rule.onNodeWithTag(AdminPanelTags.BUSY).assertExists()
    }

    private companion object {
        const val NPUB = "npub1dzu6ceugmm7xqs2drhwl5jdyhwrhc3ezgr0w6tmmvqzjmn5ac0ysl4qd9z"
    }
}
