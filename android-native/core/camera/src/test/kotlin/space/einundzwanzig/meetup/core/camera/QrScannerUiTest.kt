package space.einundzwanzig.meetup.core.camera

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.camera.core.SurfaceRequest
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import space.einundzwanzig.meetup.core.ui.motion.LocalReducedMotion
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

private class FakeCamera(override val hasTorch: Boolean, private val autoOpen: Boolean) : BoundQrCamera {
    override var analysisEnabled: Boolean = true
    var torchLit = false
    var released = false
    private var torchListener: ((Boolean) -> Unit)? = null
    private var stateListener: ((Boolean) -> Unit)? = null

    /** Emits a CameraX state: `true` = error after binding (in use, fatal), `false` = open. */
    fun emitState(unavailable: Boolean) {
        stateListener?.invoke(unavailable)
    }

    override fun observeState(onUnavailable: (Boolean) -> Unit) {
        stateListener = onUnavailable
        if (autoOpen) onUnavailable(false)
    }

    override fun setTorch(on: Boolean) {
        torchLit = on
        torchListener?.invoke(on)
    }

    override fun observeTorch(onChange: (Boolean) -> Unit) {
        torchListener = onChange
    }

    override fun release() {
        released = true
    }
}

private class FakeBackend(
    var hasTorch: Boolean = true,
    var fail: Boolean = false,
    var autoOpen: Boolean = true,
) : QrCameraBackend {
    var binds = 0
    var onCodes: ((List<String>) -> Unit)? = null
    var camera: FakeCamera? = null

    override suspend fun bind(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        onSurfaceRequest: (SurfaceRequest) -> Unit,
        onCodes: (List<String>) -> Unit,
    ): BoundQrCamera {
        binds++
        if (fail) throw NoCameraException()
        this.onCodes = onCodes
        return FakeCamera(hasTorch, autoOpen).also { camera = it }
    }
}

@RunWith(AndroidJUnit4::class)
class QrScannerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setScanner(
        backend: FakeBackend,
        permission: CameraPermissionState = CameraPermissionState({ true }, { false }),
        state: QrScannerState = QrScannerState(ScanDebouncer(0, 0)),
        enabled: () -> Boolean = { true },
        onResult: (String) -> Boolean,
    ) {
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalQrCameraBackend provides backend, LocalReducedMotion provides true) {
                    QrScanner(onResult = onResult, state = state, permissionState = permission, enabled = enabled())
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `granted permission binds the camera and delivers codes until accepted`() {
        val backend = FakeBackend()
        val state = QrScannerState(ScanDebouncer(0, 0))
        val seen = mutableListOf<String>()
        setScanner(backend, state = state) { seen += it; it == "npub1hit" }

        assertEquals(1, backend.binds)
        assertEquals(CameraStatus.Running, state.cameraStatus)
        rule.onNodeWithTag(QrScannerTestTags.Preview).assertExists()
        rule.onNodeWithTag(QrScannerTestTags.Permission).assertDoesNotExist()

        rule.runOnIdle { backend.onCodes!!(listOf("junk")) }
        rule.runOnIdle { backend.onCodes!!(listOf("npub1hit")) }
        rule.runOnIdle { backend.onCodes!!(listOf("npub1later")) }
        rule.runOnIdle {
            assertEquals(listOf("junk", "npub1hit"), seen)
            assertTrue(state.isStopped)
            assertFalse(backend.camera!!.analysisEnabled)
        }

        rule.runOnIdle { state.resume() }
        rule.runOnIdle {
            assertTrue(backend.camera!!.analysisEnabled)
            backend.onCodes!!(listOf("npub1again"))
            assertEquals("npub1again", seen.last())
        }
    }

    @Test
    fun `disabled scanner delivers nothing`() {
        val backend = FakeBackend()
        var enabled by mutableStateOf(false)
        val seen = mutableListOf<String>()
        setScanner(backend, enabled = { enabled }) { seen += it; false }
        rule.runOnIdle {
            assertFalse(backend.camera!!.analysisEnabled)
            backend.onCodes!!(listOf("a"))
            assertTrue(seen.isEmpty())
            enabled = true
        }
        rule.runOnIdle {
            assertTrue(backend.camera!!.analysisEnabled)
            backend.onCodes!!(listOf("a"))
            assertEquals(listOf("a"), seen)
        }
    }

    @Test
    fun `torch button toggles the torch with one stable label`() {
        val backend = FakeBackend(hasTorch = true)
        setScanner(backend) { false }
        rule.onNodeWithContentDescription("Taschenlampe").assertIsDisplayed().assertIsOff().performClick()
        rule.runOnIdle { assertTrue(backend.camera!!.torchLit) }
        rule.onNodeWithContentDescription("Taschenlampe").assertIsOn().performClick()
        rule.runOnIdle { assertFalse(backend.camera!!.torchLit) }
    }

    @Test
    fun `a hit and resume leave the torch on`() {
        val backend = FakeBackend(hasTorch = true)
        val state = QrScannerState(ScanDebouncer(0, 0))
        setScanner(backend, state = state) { true }
        rule.runOnIdle { state.setTorch(true) }
        rule.runOnIdle { backend.onCodes!!(listOf("21review:einundzwanzig-meetup-demo")) }
        rule.runOnIdle {
            assertTrue(state.isStopped)
            assertTrue(backend.camera!!.torchLit)
            state.resume()
        }
        rule.runOnIdle { assertTrue(state.torchOn) }
    }

    @Test
    fun `stop from the gallery path blocks delivery until resume`() {
        val backend = FakeBackend()
        val state = QrScannerState(ScanDebouncer(0, 0))
        val seen = mutableListOf<String>()
        setScanner(backend, state = state) { seen += it; false }
        rule.runOnIdle { state.stop() }
        rule.runOnIdle {
            assertFalse(backend.camera!!.analysisEnabled)
            backend.onCodes!!(listOf("a"))
            assertTrue(seen.isEmpty())
            state.resume()
        }
        rule.runOnIdle {
            backend.onCodes!!(listOf("a"))
            assertEquals(listOf("a"), seen)
        }
    }

    @Test
    fun `stopped scanner state survives recreation`() {
        val restoration = StateRestorationTester(rule)
        lateinit var state: QrScannerState
        restoration.setContent { state = rememberQrScannerState() }
        rule.runOnIdle { state.stop() }
        val before = state
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle {
            assertTrue(before !== state)
            assertTrue(state.isStopped)
            assertFalse(state.deliver(listOf("x"), 10_000, { true }))
        }
    }

    @Test
    fun `loading stays until the camera is open`() {
        val backend = FakeBackend(autoOpen = false)
        val state = QrScannerState(ScanDebouncer(0, 0))
        setScanner(backend, state = state) { false }
        assertEquals(CameraStatus.Starting, state.cameraStatus)
        rule.onNodeWithTag(QrScannerTestTags.Loading).assertExists()
        rule.runOnIdle { backend.camera!!.emitState(false) }
        rule.runOnIdle { assertEquals(CameraStatus.Running, state.cameraStatus) }
    }

    @Test
    fun `camera error after binding shows unavailable and retry rebinds`() {
        val backend = FakeBackend()
        val state = QrScannerState(ScanDebouncer(0, 0))
        setScanner(backend, state = state) { false }
        assertEquals(CameraStatus.Running, state.cameraStatus)
        val first = backend.camera!!
        rule.runOnIdle { first.emitState(true) }
        rule.onNodeWithTag(QrScannerTestTags.Unavailable).assertIsDisplayed()
        assertEquals(CameraStatus.Unavailable, state.cameraStatus)

        rule.onNodeWithText("Erneut versuchen").performClick()
        rule.waitForIdle()
        assertEquals(2, backend.binds)
        assertTrue(first.released)
        assertEquals(CameraStatus.Running, state.cameraStatus)
        rule.onNodeWithTag(QrScannerTestTags.Unavailable).assertDoesNotExist()
    }

    @Test
    fun `camera reopened by CameraX after an error runs again`() {
        val backend = FakeBackend()
        val state = QrScannerState(ScanDebouncer(0, 0))
        setScanner(backend, state = state) { false }
        rule.runOnIdle { backend.camera!!.emitState(true) }
        rule.runOnIdle { backend.camera!!.emitState(false) }
        rule.runOnIdle { assertEquals(CameraStatus.Running, state.cameraStatus) }
        rule.onNodeWithTag(QrScannerTestTags.Unavailable).assertDoesNotExist()
        assertEquals(1, backend.binds)
    }

    @Test
    fun `no torch button without a flash unit`() {
        setScanner(FakeBackend(hasTorch = false)) { false }
        rule.onNodeWithTag(QrScannerTestTags.Torch).assertDoesNotExist()
    }

    @Test
    fun `camera failure shows the unavailable state and retry binds again`() {
        val backend = FakeBackend(fail = true)
        val state = QrScannerState(ScanDebouncer(0, 0))
        setScanner(backend, state = state) { false }
        rule.onNodeWithTag(QrScannerTestTags.Unavailable).assertIsDisplayed()
        rule.onNodeWithText("Kamera nicht verfügbar").assertIsDisplayed()
        assertEquals(CameraStatus.Unavailable, state.cameraStatus)

        backend.fail = false
        rule.onNodeWithText("Erneut versuchen").performClick()
        rule.waitForIdle()
        assertEquals(2, backend.binds)
        assertEquals(CameraStatus.Running, state.cameraStatus)
        rule.onNodeWithTag(QrScannerTestTags.Unavailable).assertDoesNotExist()
    }

    @Test
    fun `missing permission shows the request panel and never binds`() {
        val backend = FakeBackend()
        var launches = 0
        val permission = CameraPermissionState({ false }, { false }).apply { launcher = { launches++ } }
        setScanner(backend, permission = permission) { false }
        rule.onNodeWithTag(QrScannerTestTags.Permission).assertIsDisplayed()
        rule.onNodeWithText("Kamerazugriff benötigt").assertIsDisplayed()
        rule.onNodeWithText("Kamera erlauben").performClick()
        assertEquals(1, launches)
        assertEquals(0, backend.binds)
    }

    @Test
    fun `granting the permission starts the camera`() {
        val backend = FakeBackend()
        var granted = false
        val permission = CameraPermissionState({ granted }, { false })
        setScanner(backend, permission = permission) { false }
        assertEquals(0, backend.binds)
        rule.runOnIdle {
            granted = true
            permission.onRequestResult(true)
        }
        rule.waitForIdle()
        assertEquals(1, backend.binds)
        rule.onNodeWithTag(QrScannerTestTags.Preview).assertExists()
    }

    @Test
    fun `leaving composition releases the camera`() {
        val backend = FakeBackend()
        var show by mutableStateOf(true)
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalQrCameraBackend provides backend, LocalReducedMotion provides true) {
                    if (show) {
                        QrScanner(
                            onResult = { false },
                            permissionState = CameraPermissionState({ true }, { false }),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val camera = backend.camera!!
        assertFalse(camera.released)
        rule.runOnIdle { show = false }
        rule.waitForIdle()
        assertTrue(camera.released)
    }

    @Test
    fun `remembered permission state reads the granted permission`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        lateinit var state: CameraPermissionState
        rule.setContent { EinundzwanzigTheme { state = rememberCameraPermissionState(autoRequest = false) } }
        rule.runOnIdle { assertEquals(CameraPermissionStatus.Granted, state.status) }
    }

    @Test
    fun `remembered permission state without the permission is not requested yet`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        lateinit var state: CameraPermissionState
        rule.setContent { EinundzwanzigTheme { state = rememberCameraPermissionState(autoRequest = false) } }
        rule.runOnIdle { assertEquals(CameraPermissionStatus.NotRequested, state.status) }
    }

    @Test
    fun `permanently denied permission survives recreation`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        val restoration = StateRestorationTester(rule)
        lateinit var state: CameraPermissionState
        restoration.setContent { EinundzwanzigTheme { state = rememberCameraPermissionState(autoRequest = false) } }
        rule.runOnIdle { state.onRequestResult(false) }
        rule.runOnIdle { assertEquals(CameraPermissionStatus.PermanentlyDenied, state.status) }
        val before = state
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle {
            assertTrue(before !== state)
            assertEquals(CameraPermissionStatus.PermanentlyDenied, state.status)
        }
    }

    @Test
    fun `saved status yields to a permission granted meanwhile`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        val restoration = StateRestorationTester(rule)
        lateinit var state: CameraPermissionState
        restoration.setContent { EinundzwanzigTheme { state = rememberCameraPermissionState(autoRequest = false) } }
        rule.runOnIdle { state.onRequestResult(false) }
        rule.runOnIdle { shadowOf(app).grantPermissions(Manifest.permission.CAMERA) }
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { assertEquals(CameraPermissionStatus.Granted, state.status) }
    }
}
