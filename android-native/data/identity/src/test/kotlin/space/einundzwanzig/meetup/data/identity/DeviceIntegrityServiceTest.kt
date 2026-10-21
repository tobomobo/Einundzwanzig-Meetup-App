package space.einundzwanzig.meetup.data.identity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.identity.integrity.DeviceIntegrityStatus
import space.einundzwanzig.meetup.data.identity.internal.DefaultDeviceIntegrityService
import space.einundzwanzig.meetup.data.identity.internal.PathProbe

/** Spec 01 §8.8: findings, status and caching. */
class DeviceIntegrityServiceTest {

    private fun service(existing: Set<String>, tags: String? = "release-keys", probe: PathProbe? = null) =
        DefaultDeviceIntegrityService(probe ?: PathProbe { it in existing }, { tags }, FakeTime(), Dispatchers.Unconfined)

    @Test
    fun cleanDeviceIsSecure() = runBlocking {
        val r = service(emptySet(), tags = "test-keys").check()
        assertEquals(DeviceIntegrityStatus.SECURE, r.status)
        assertFalse(r.isCompromised)
    }

    @Test
    fun rootMagiskAndBusyboxAreReportedInOrder() = runBlocking {
        val r = service(setOf("/system/xbin/busybox", "/data/adb/modules", "/sbin/su")).check()
        assertEquals(DeviceIntegrityStatus.WARNING, r.status)
        assertTrue(r.isCompromised)
        assertEquals(
            listOf("Root-Binary gefunden: /sbin/su", "Magisk-Indikator: /data/adb/modules", "BusyBox installiert: /system/xbin/busybox"),
            r.findings,
        )
    }

    @Test
    fun failureIsUnknownAndTheResultIsCached() = runBlocking {
        var calls = 0
        val s = service(emptySet(), probe = PathProbe {
            calls++
            throw SecurityException("nope")
        })
        val r = s.check()
        assertEquals(DeviceIntegrityStatus.UNKNOWN, r.status)
        assertEquals(listOf("Prüfung fehlgeschlagen"), r.findings)
        s.check()
        assertEquals(1, calls)
        s.check(forceRefresh = true)
        assertEquals(2, calls)
    }
}
