package space.einundzwanzig.meetup.core.camera

import androidx.camera.core.CameraState
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrCameraLogicTest {

    private fun state(type: CameraState.Type, code: Int? = null) =
        CameraState.create(type, code?.let { CameraState.StateError.create(it) })

    @Test
    fun `open without error is running`() {
        assertEquals(false, state(CameraState.Type.OPEN).toUnavailable())
    }

    @Test
    fun `errors after binding are unavailable`() {
        listOf(
            CameraState.Type.PENDING_OPEN to CameraState.ERROR_CAMERA_IN_USE,
            CameraState.Type.PENDING_OPEN to CameraState.ERROR_MAX_CAMERAS_IN_USE,
            CameraState.Type.CLOSING to CameraState.ERROR_CAMERA_DISABLED,
            CameraState.Type.CLOSING to CameraState.ERROR_CAMERA_FATAL_ERROR,
            CameraState.Type.CLOSING to CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED,
            CameraState.Type.CLOSED to CameraState.ERROR_STREAM_CONFIG,
        ).forEach { (type, code) -> assertEquals("$type/$code", true, state(type, code).toUnavailable()) }
    }

    @Test
    fun `transitional states keep the current status`() {
        assertNull(state(CameraState.Type.OPENING).toUnavailable())
        assertNull(state(CameraState.Type.PENDING_OPEN).toUnavailable())
        assertNull(state(CameraState.Type.CLOSING).toUnavailable())
        assertNull(state(CameraState.Type.CLOSED).toUnavailable())
        assertNull(state(CameraState.Type.OPENING, CameraState.ERROR_OTHER_RECOVERABLE_ERROR).toUnavailable())
    }

    @Test
    fun `torch keeps its offset in tall windows`() {
        assertEquals(182.dp, torchOffsetFromCenter(800.dp, 24.dp))
    }

    @Test
    fun `torch moves down in short windows so it stays below the top bar`() {
        // 400 dp split-screen pane: top edge must stay below the 24 dp inset + 64 dp bar.
        val offset = torchOffsetFromCenter(400.dp, 24.dp)
        assertEquals(84.dp, offset)
        assertEquals(0.dp, torchOffsetFromCenter(150.dp, 24.dp))
    }
}
