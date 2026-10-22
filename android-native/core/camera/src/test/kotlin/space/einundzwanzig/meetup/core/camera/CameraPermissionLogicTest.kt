package space.einundzwanzig.meetup.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.camera.CameraPermissionStatus.Denied
import space.einundzwanzig.meetup.core.camera.CameraPermissionStatus.Granted
import space.einundzwanzig.meetup.core.camera.CameraPermissionStatus.NotRequested
import space.einundzwanzig.meetup.core.camera.CameraPermissionStatus.PermanentlyDenied

class CameraPermissionLogicTest {

    @Test
    fun `initial status`() {
        assertEquals(Granted, CameraPermissionLogic.initial(granted = true, shouldShowRationale = false))
        assertEquals(Granted, CameraPermissionLogic.initial(granted = true, shouldShowRationale = true))
        assertEquals(Denied, CameraPermissionLogic.initial(granted = false, shouldShowRationale = true))
        // Before the first request Android reports no rationale: not "denied forever" yet.
        assertEquals(NotRequested, CameraPermissionLogic.initial(granted = false, shouldShowRationale = false))
    }

    @Test
    fun `after request`() {
        assertEquals(Granted, CameraPermissionLogic.afterRequest(granted = true, shouldShowRationale = false))
        assertEquals(Denied, CameraPermissionLogic.afterRequest(granted = false, shouldShowRationale = true))
        assertEquals(PermanentlyDenied, CameraPermissionLogic.afterRequest(granted = false, shouldShowRationale = false))
    }

    @Test
    fun `resume after granting in settings`() {
        assertEquals(Granted, CameraPermissionLogic.onResume(PermanentlyDenied, granted = true, shouldShowRationale = false))
        assertEquals(Granted, CameraPermissionLogic.onResume(Denied, granted = true, shouldShowRationale = false))
    }

    @Test
    fun `resume keeps a permanent denial`() {
        assertEquals(PermanentlyDenied, CameraPermissionLogic.onResume(PermanentlyDenied, granted = false, shouldShowRationale = false))
        assertEquals(NotRequested, CameraPermissionLogic.onResume(NotRequested, granted = false, shouldShowRationale = false))
    }

    @Test
    fun `resume after reset in settings shows rationale state`() {
        assertEquals(Denied, CameraPermissionLogic.onResume(PermanentlyDenied, granted = false, shouldShowRationale = true))
    }

    @Test
    fun `resume after revocation falls back to not requested`() {
        assertEquals(NotRequested, CameraPermissionLogic.onResume(Granted, granted = false, shouldShowRationale = false))
        assertEquals(Denied, CameraPermissionLogic.onResume(Granted, granted = false, shouldShowRationale = true))
    }

    @Test
    fun `auto request only once and only when never asked`() {
        assertTrue(CameraPermissionLogic.shouldAutoRequest(NotRequested, alreadyAutoRequested = false))
        assertFalse(CameraPermissionLogic.shouldAutoRequest(NotRequested, alreadyAutoRequested = true))
        assertFalse(CameraPermissionLogic.shouldAutoRequest(Denied, alreadyAutoRequested = false))
        assertFalse(CameraPermissionLogic.shouldAutoRequest(PermanentlyDenied, alreadyAutoRequested = false))
        assertFalse(CameraPermissionLogic.shouldAutoRequest(Granted, alreadyAutoRequested = false))
    }

    @Test
    fun `state object follows request results and resumes`() {
        var granted = false
        var rationale = false
        var launches = 0
        val state = CameraPermissionState({ granted }, { rationale })
        state.launcher = { launches++ }
        assertEquals(NotRequested, state.status)
        assertFalse(state.isGranted)

        state.launchPermissionRequest()
        assertEquals(1, launches)
        rationale = true
        state.onRequestResult(false)
        assertEquals(Denied, state.status)

        rationale = false
        state.onRequestResult(false)
        assertEquals(PermanentlyDenied, state.status)

        state.refresh()
        assertEquals(PermanentlyDenied, state.status)

        granted = true
        state.refresh()
        assertEquals(Granted, state.status)
        assertTrue(state.isGranted)
    }

    @Test
    fun `open settings delegates to the opener`() {
        var opened = 0
        val state = CameraPermissionState({ false }, { false })
        state.settingsOpener = { opened++ }
        state.openAppSettings()
        assertEquals(1, opened)
    }
}
