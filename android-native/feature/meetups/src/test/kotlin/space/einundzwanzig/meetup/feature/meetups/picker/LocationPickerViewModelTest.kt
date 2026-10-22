package space.einundzwanzig.meetup.feature.meetups.picker

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.core.map.LatLng
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.meetups.location.LocationResult
import space.einundzwanzig.meetup.data.meetups.location.LocationStatus
import space.einundzwanzig.meetup.data.meetups.location.Position
import space.einundzwanzig.meetup.feature.meetups.FakeLocationService
import space.einundzwanzig.meetup.feature.meetups.MainDispatcherRule
import space.einundzwanzig.meetup.feature.meetups.R
import space.einundzwanzig.meetup.feature.meetups.common.UiText

class LocationPickerViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val location = FakeLocationService(LocationResult(LocationStatus.OK, Position(48.1, 11.5, 5.0, 0)))

    @Test
    fun `initial pick is applied once and taps move it`() {
        val vm = LocationPickerViewModel(location, SavedStateHandle())
        vm.init(LatLng(1.0, 2.0))
        assertEquals(LatLng(1.0, 2.0), vm.state.value.pick)
        vm.onAction(LocationPickerAction.Pick(LatLng(3.0, 4.0)))
        vm.init(LatLng(1.0, 2.0))
        assertEquals(LatLng(3.0, 4.0), vm.state.value.pick)
    }

    @Test
    fun `no initial pick leaves it empty`() {
        val vm = LocationPickerViewModel(location, SavedStateHandle())
        vm.init(null)
        assertNull(vm.state.value.pick)
    }

    @Test
    fun `the pick survives process death`() {
        val handle = SavedStateHandle()
        LocationPickerViewModel(location, handle).onAction(LocationPickerAction.Pick(LatLng(5.0, 6.0)))
        val restored = LocationPickerViewModel(location, handle)
        restored.init(LatLng(1.0, 2.0))
        assertEquals(LatLng(5.0, 6.0), restored.state.value.pick)
    }

    @Test
    fun `jump to me moves the camera but not the pick`() = runTest {
        val vm = LocationPickerViewModel(location, SavedStateHandle())
        vm.onAction(LocationPickerAction.JumpToMe)
        assertEquals(LatLng(48.1, 11.5), vm.jumps.first())
        assertNull(vm.state.value.pick)
        assertFalse(vm.state.value.locating)
    }

    @Test
    fun `jump to me without a fix shows a hint`() = runTest {
        location.result = LocationResult(LocationStatus.NO_FIX)
        val vm = LocationPickerViewModel(location, SavedStateHandle())
        vm.onAction(LocationPickerAction.JumpToMe)
        val m = vm.messages.first()
        assertEquals(UiText.Res(R.string.native_loc_pick_no_fix), m.text)
        assertEquals(Tone.Warning, m.tone)
    }
}
