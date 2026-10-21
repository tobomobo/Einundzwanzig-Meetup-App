package space.einundzwanzig.meetup.data.identity.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.identity.integrity.DeviceIntegrityService
import space.einundzwanzig.meetup.data.identity.integrity.DeviceIntegrityStatus
import space.einundzwanzig.meetup.data.identity.integrity.IntegrityReport
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** File-system probe; replaceable in tests. */
internal fun interface PathProbe {
    /** `true` if [path] exists as file or directory. */
    fun exists(path: String): Boolean
}

@Singleton
internal class DefaultDeviceIntegrityService(
    private val probe: PathProbe,
    private val buildTags: () -> String?,
    private val timeSource: TimeSource,
    private val io: CoroutineDispatcher,
) : DeviceIntegrityService {

    @Inject
    constructor(timeSource: TimeSource, @IoDispatcher io: CoroutineDispatcher) : this(
        probe = { File(it).exists() },
        buildTags = ::readBuildTags,
        timeSource = timeSource,
        io = io,
    )

    private val mutex = Mutex()
    private var cached: IntegrityReport? = null

    override suspend fun check(forceRefresh: Boolean): IntegrityReport = mutex.withLock {
        cached?.takeUnless { forceRefresh }?.let { return@withLock it }
        val report = try {
            val findings = withContext(io) { findings() }
            if (findings.isNotEmpty()) AppLogger.security("DeviceIntegrity", "${findings.size} Auffälligkeit(en) gefunden")
            IntegrityReport(
                if (findings.isEmpty()) DeviceIntegrityStatus.SECURE else DeviceIntegrityStatus.WARNING,
                findings,
                timeSource.nowMillis(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warn("DeviceIntegrity", "Integritätsprüfung fehlgeschlagen: $e")
            IntegrityReport(DeviceIntegrityStatus.UNKNOWN, listOf("Prüfung fehlgeschlagen"), timeSource.nowMillis())
        }
        cached = report
        report
    }

    private fun findings(): List<String> = buildList {
        ROOT_BINARIES.filter(probe::exists).forEach { add("Root-Binary gefunden: $it") }
        MAGISK_PATHS.filter(probe::exists).forEach { add("Magisk-Indikator: $it") }
        try {
            if (buildTags()?.contains("test-keys") == true) {
                AppLogger.debug("DeviceIntegrity", "Build verwendet test-keys (kann OEM-ROM sein)")
            }
        } catch (_: Exception) {
        }
        BUSYBOX_PATHS.filter(probe::exists).forEach { add("BusyBox installiert: $it") }
    }

    internal companion object {
        val ROOT_BINARIES = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/app/Superuser.apk",
            "/system/app/SuperSU.apk",
            "/data/local/bin/su",
            "/data/local/xbin/su",
        )
        val MAGISK_PATHS = listOf("/sbin/.magisk", "/data/adb/magisk", "/data/adb/modules")
        val BUSYBOX_PATHS = listOf("/system/xbin/busybox", "/system/bin/busybox")

        /** `getprop ro.build.tags` (android.os.Build.TAGS holds the same value without a process). */
        fun readBuildTags(): String? = android.os.Build.TAGS
    }
}
