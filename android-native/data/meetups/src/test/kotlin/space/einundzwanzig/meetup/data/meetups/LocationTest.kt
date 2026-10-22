package space.einundzwanzig.meetup.data.meetups

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.meetups.internal.LocationAcquisition
import space.einundzwanzig.meetup.data.meetups.internal.LocationPermissionBridgeImpl
import space.einundzwanzig.meetup.data.meetups.internal.LocationPlatform
import space.einundzwanzig.meetup.data.meetups.internal.MeetupLocationServiceImpl
import space.einundzwanzig.meetup.data.meetups.location.Geo
import space.einundzwanzig.meetup.data.meetups.location.GpsStatus
import space.einundzwanzig.meetup.data.meetups.location.LocationMeetupResult
import space.einundzwanzig.meetup.data.meetups.location.LocationPermission
import space.einundzwanzig.meetup.data.meetups.location.LocationResult
import space.einundzwanzig.meetup.data.meetups.location.LocationStatus
import space.einundzwanzig.meetup.data.meetups.location.MeetupCandidate
import space.einundzwanzig.meetup.data.meetups.location.MeetupLocationService
import space.einundzwanzig.meetup.data.meetups.location.Position
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import java.io.IOException

class LocationTest {

    private fun meetup(id: String, lat: Double = 0.0, lng: Double = 0.0, city: String = id) =
        Meetup(id = id, city = city, country = "DE", telegramLink = "", lat = lat, lng = lng)

    // ---- haversine vectors (spec 09 §10) -------------------------------------------------

    @Test
    fun `haversine with R 6378137 matches the spec vectors`() {
        val tol = 1e-6
        assertEquals(504.980394, Geo.distanceKm(48.1351, 11.5820, 52.5200, 13.4050), tol)
        assertEquals(613.114143, Geo.distanceKm(48.1351, 11.5820, 53.5511, 9.9937), tol)
        assertEquals(255.536106, Geo.distanceKm(52.5200, 13.4050, 53.5511, 9.9937), tol)
        assertEquals(5.009377, Geo.distanceKm(52.5200, 13.4050, 52.5650, 13.4050), tol)
        assertEquals(0.0, Geo.distanceKm(52.52, 13.405, 52.52, 13.405), 0.0)
        assertEquals(270.982665, Geo.distanceKm(49.9769, 9.1525, 48.1351, 11.5820), tol)
    }

    @Test
    fun `radius constants`() {
        assertEquals(5.0, MeetupLocationService.PARTICIPANT_RADIUS_KM, 0.0)
        assertEquals(10.0, MeetupLocationService.CREATION_RADIUS_KM, 0.0)
        assertEquals(50.0, MeetupLocationService.DERIVED_RADIUS_KM, 0.0)
        assertEquals(2.0, MeetupLocationService.AMBIGUITY_THRESHOLD_KM, 0.0)
    }

    // ---- LocationMeetupResult ------------------------------------------------------------

    private fun result(vararg d: Double) =
        LocationMeetupResult(GpsStatus.OK, candidates = d.mapIndexed { i, km -> MeetupCandidate(meetup("m$i"), km) })

    @Test
    fun `ambiguity and creation radius`() {
        assertTrue(result(1.0, 2.5).isAmbiguous)
        assertFalse(result(1.0, 3.0).isAmbiguous)
        assertFalse(result(1.0).isAmbiguous)
        assertEquals(listOf(10.0), result(10.0, 10.0001).withinCreationRadius.map { it.distanceKm })
        assertEquals("m0", result(1.0, 3.0).nearest?.meetup?.id)
        assertNull(result().nearest)
    }

    @Test
    fun `participant radius skips references without coordinates`() {
        val svc = object : MeetupLocationService {
            override fun isLocationServiceEnabled() = true
            override fun permissionState() = LocationPermission.GRANTED
            override suspend fun getCurrentLocation() = LocationResult(LocationStatus.ERROR)
            override suspend fun resolveLocation() = LocationMeetupResult(GpsStatus.ERROR)
            override fun openLocationSettings() = false
            override fun openAppSettings() = false
        }
        assertTrue(svc.isWithinParticipantRadius(10.0, 10.0, 0.0, 0.0))
        assertFalse(svc.isWithinParticipantRadius(52.5200, 13.4050, 52.5650, 13.4050)) // 5.009 km
        assertTrue(svc.isWithinParticipantRadius(52.5200, 13.4050, 52.5600, 13.4050))
    }

    @Test
    fun `status mapping to GpsStatus`() {
        assertEquals(GpsStatus.DENIED, LocationStatus.DENIED_FOREVER.toGpsStatus())
        assertEquals(GpsStatus.ERROR, LocationStatus.NO_FIX.toGpsStatus())
        assertEquals(GpsStatus.SERVICE_DISABLED, LocationStatus.SERVICE_DISABLED.toGpsStatus())
    }

    @Test
    fun `resolveWith sorts candidates with coordinates`() {
        val pos = Position(52.52, 13.405, timeMillis = 0)
        val r = MeetupLocationServiceImpl.resolveWith(
            LocationResult(LocationStatus.OK, pos),
            listOf(meetup("hh", 53.5511, 9.9937), meetup("none"), meetup("b", 52.52, 13.405)),
        )
        assertEquals(GpsStatus.OK, r.status)
        assertEquals(listOf("b", "hh"), r.candidates.map { it.meetup.id })
        assertEquals(GpsStatus.DENIED, MeetupLocationServiceImpl.resolveWith(LocationResult(LocationStatus.DENIED_FOREVER), emptyList()).status)
    }

    // ---- acquisition state machine (spec 09 §8.5) ----------------------------------------

    private class FakePlatform(
        var service: Boolean = true,
        var permission: LocationPermission = LocationPermission.GRANTED,
        var afterRequest: LocationPermission = LocationPermission.DENIED,
        var first: Position? = null,
        var second: Position? = null,
        var last: Position? = null,
    ) : LocationPlatform {
        var requests = 0
        override fun isServiceEnabled() = service
        override fun checkPermission() = permission
        override suspend fun requestPermission(): LocationPermission {
            requests++
            return afterRequest
        }
        override suspend fun currentPositionBalanced(timeoutSeconds: Long): Position {
            assertEquals(25L, timeoutSeconds)
            return first ?: throw IOException("timeout")
        }
        override suspend fun currentPositionLocationManager(timeoutSeconds: Long): Position {
            assertEquals(20L, timeoutSeconds)
            return second ?: throw IOException("timeout")
        }
        override suspend fun lastKnownPosition() = last
    }

    private val time = FakeTime()
    private fun acquire(p: FakePlatform) = LocationAcquisition(p, time, testLog())

    @Test
    fun `service off`() = runTest {
        assertEquals(LocationStatus.SERVICE_DISABLED, acquire(FakePlatform(service = false)).getCurrentLocation().status)
    }

    @Test
    fun `denied requests once and stays denied`() = runTest {
        val p = FakePlatform(permission = LocationPermission.DENIED)
        assertEquals(LocationStatus.DENIED, acquire(p).getCurrentLocation().status)
        assertEquals(1, p.requests)
    }

    @Test
    fun `denied then granted gets a fix`() = runTest {
        val pos = Position(1.0, 2.0, 30.0, time.now)
        val p = FakePlatform(permission = LocationPermission.DENIED, afterRequest = LocationPermission.GRANTED, first = pos)
        assertEquals(LocationResult(LocationStatus.OK, pos), acquire(p).getCurrentLocation())
    }

    @Test
    fun `denied forever re-validates with one request`() = runTest {
        val p = FakePlatform(permission = LocationPermission.DENIED_FOREVER, afterRequest = LocationPermission.DENIED_FOREVER)
        assertEquals(LocationStatus.DENIED_FOREVER, acquire(p).getCurrentLocation().status)
        assertEquals(1, p.requests)
    }

    @Test
    fun `denied forever reset in the settings prompts again and gets a fix`() = runTest {
        val pos = Position(1.0, 2.0, 30.0, time.now)
        val p = FakePlatform(
            permission = LocationPermission.DENIED_FOREVER,
            afterRequest = LocationPermission.GRANTED,
            first = pos,
        )
        assertEquals(LocationResult(LocationStatus.OK, pos), acquire(p).getCurrentLocation())
        assertEquals(1, p.requests)
    }

    @Test
    fun `second stage after first failure`() = runTest {
        val pos = Position(1.0, 2.0, 5.0, time.now)
        assertEquals(pos, acquire(FakePlatform(second = pos)).getCurrentLocation().position)
    }

    @Test
    fun `last known accepted below 11 minutes`() = runTest {
        val last = Position(1.0, 2.0, timeMillis = time.now - (10 * 60 + 59) * 1000L)
        val r = acquire(FakePlatform(last = last)).getCurrentLocation()
        assertEquals(LocationStatus.OK, r.status)
        assertEquals(last, r.position)
    }

    @Test
    fun `last known of 11 minutes is rejected`() = runTest {
        val last = Position(1.0, 2.0, timeMillis = time.now - 11 * 60 * 1000L)
        assertEquals(LocationStatus.NO_FIX, acquire(FakePlatform(last = last)).getCurrentLocation().status)
    }

    @Test
    fun `no last known gives NO_FIX`() = runTest {
        assertEquals(LocationStatus.NO_FIX, acquire(FakePlatform()).getCurrentLocation().status)
    }

    @Test
    fun `unexpected failure gives ERROR`() = runTest {
        val p = object : LocationPlatform by FakePlatform() {
            override fun isServiceEnabled(): Boolean = throw IllegalStateException("boom")
        }
        assertEquals(LocationStatus.ERROR, LocationAcquisition(p, time, testLog()).getCurrentLocation().status)
    }

    // ---- permission bridge ---------------------------------------------------------------

    @Test
    fun `bridge without a collector denies immediately`() = runTest {
        assertEquals(LocationPermission.DENIED, LocationPermissionBridgeImpl().request())
    }

    @Test
    fun `bridge round trip reports denied forever`() = runTest {
        val bridge = LocationPermissionBridgeImpl()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            bridge.pendingRequest.collect { r ->
                if (r != null && bridge.onLaunched(r.id)) bridge.onResult(r.id, granted = false, permanentlyDenied = true)
            }
        }
        assertEquals(LocationPermission.DENIED_FOREVER, bridge.request())
        assertTrue(bridge.deniedForever)
        assertNull(bridge.pendingRequest.value)
        job.cancel()
    }

    @Test
    fun `bridge without a collector keeps a remembered denied forever`() = runTest {
        val bridge = LocationPermissionBridgeImpl()
        bridge.onResult(granted = false, permanentlyDenied = true) // late answer, no request
        assertTrue(bridge.deniedForever)
        assertEquals(LocationPermission.DENIED_FOREVER, bridge.request())
        // A later prompt answered with a plain denial (permission reset to "Ask every time").
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            bridge.pendingRequest.collect { r ->
                if (r != null && bridge.onLaunched(r.id)) bridge.onResult(granted = false, permanentlyDenied = false)
            }
        }
        assertEquals(LocationPermission.DENIED, bridge.request())
        assertFalse(bridge.deniedForever)
        job.cancel()
    }

    @Test
    fun `bridge with a stale id times out instead of hanging`() = runTest {
        val bridge = LocationPermissionBridgeImpl()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            bridge.pendingRequest.collect { r ->
                if (r != null && bridge.onLaunched(r.id)) bridge.onResult(r.id + 99, granted = true, permanentlyDenied = false)
            }
        }
        assertEquals(LocationPermission.DENIED, bridge.request())
        assertNull(bridge.pendingRequest.value)
        // The mutex is released: a second request is answered normally.
        job.cancel()
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            bridge.pendingRequest.collect { r ->
                if (r != null && bridge.onLaunched(r.id)) bridge.onResult(granted = true, permanentlyDenied = false)
            }
        }
        assertEquals(LocationPermission.GRANTED, bridge.request())
        job2.cancel()
    }

    @Test
    fun `bridge id-less result completes the launched request after recreation`() = runTest {
        val bridge = LocationPermissionBridgeImpl()
        // First collector launches, then "dies" with the dialog open.
        val first = launch(UnconfinedTestDispatcher(testScheduler)) {
            bridge.pendingRequest.collect { r -> if (r != null) bridge.onLaunched(r.id) }
        }
        val result = async(UnconfinedTestDispatcher(testScheduler)) { bridge.request() }
        first.cancel()
        // Recreated Activity: sees a launched request (no relaunch), then receives the result.
        val second = launch(UnconfinedTestDispatcher(testScheduler)) {
            bridge.pendingRequest.collect { r -> if (r != null) assertFalse(bridge.onLaunched(r.id)) }
        }
        assertTrue(bridge.pendingRequest.value!!.launched)
        bridge.onResult(granted = false, permanentlyDenied = true)
        assertEquals(LocationPermission.DENIED_FOREVER, result.await())
        assertTrue(bridge.deniedForever)
        second.cancel()
    }

    @Test
    fun `bridge id-less result without a launched request is ignored`() = runTest {
        val bridge = LocationPermissionBridgeImpl()
        bridge.onResult(granted = true, permanentlyDenied = false)
        assertNull(bridge.pendingRequest.value)
    }
}
