package space.einundzwanzig.meetup.feature.admin

import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.LogEntry
import space.einundzwanzig.meetup.data.issuance.session.MeetupSession
import space.einundzwanzig.meetup.data.meetups.location.GpsStatus
import space.einundzwanzig.meetup.data.meetups.location.LocationMeetupResult
import space.einundzwanzig.meetup.data.meetups.location.MeetupCandidate
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal fun meetup(
    city: String,
    name: String = city,
    country: String = "DE",
    lat: Double = 50.0,
    lng: Double = 10.0,
    id: String = city.lowercase(),
) = Meetup(id = id, name = name, city = city, country = country, telegramLink = "", lat = lat, lng = lng)

internal fun candidate(city: String, km: Double, country: String = "DE") = MeetupCandidate(meetup(city, country = country), km)

internal fun session(name: String, expiresAt: Long, startedAt: Long = expiresAt - 4 * 3600) = MeetupSession(
    seed = "00".repeat(32),
    startedAt = startedAt,
    expiresAt = expiresAt,
    meetupId = name.lowercase(),
    meetupName = name,
    meetupCountry = "DE",
    blockHeight = 0,
    pubkey = "npub1test",
)

internal class MutableTime(var millis: Long) : TimeSource {
    override fun nowMillis(): Long = millis
}

/** Time that follows a test scheduler: `base + currentTime`. */
internal class SchedulerTime(private val base: Long, private val current: () -> Long) : TimeSource {
    override fun nowMillis(): Long = base + current()
}

internal class FakeAdminPanelRepository(
    private val time: TimeSource,
    var info: AdminInfo = AdminInfo("npub1me", "", false),
    var stored: MeetupSession? = null,
    var locations: MutableList<LocationMeetupResult> = mutableListOf(LocationMeetupResult(GpsStatus.ERROR)),
    var meetups: List<Meetup> = emptyList(),
    var mine: Set<String> = emptySet(),
    var startError: Exception? = null,
    var resolveError: Exception? = null,
    var endError: Exception? = null,
    var infoError: Exception? = null,
) : AdminPanelRepository {
    /** `false` returns an expired session as-is (it expired between load and the VM's own check). */
    var autoExpire = true
    var endCalls = 0
    var resolveCalls = 0
    var locationSettingsOpened = 0
    var appSettingsOpened = 0
    val starts = mutableListOf<StartCall>()

    data class StartCall(val name: String, val country: String, val lat: Double, val lng: Double)

    override suspend fun loadAdminInfo(): AdminInfo {
        infoError?.let { throw it }
        return info
    }

    override suspend fun loadSession(): MeetupSession? {
        val s = stored ?: return null
        if (autoExpire && s.isExpired(time.nowSeconds())) {
            // Mirrors RollingQrService.loadSession, which ends an expired session itself.
            stored = null
            endCalls++
            return null
        }
        return s
    }

    override suspend fun endSession() {
        endCalls++
        endError?.let { throw it }
        stored = null
    }

    override suspend fun resolveLocation(): LocationMeetupResult {
        resolveCalls++
        resolveError?.let { throw it }
        return if (locations.size > 1) locations.removeAt(0) else locations.first()
    }

    override suspend fun portalMeetups(): List<Meetup> = meetups

    override suspend fun myMeetupNames(): Set<String> = mine

    override suspend fun startSession(meetupName: String, meetupCountry: String, lat: Double, lng: Double): MeetupSession {
        starts += StartCall(meetupName, meetupCountry, lat, lng)
        startError?.let { throw it }
        return session(meetupName, time.nowSeconds() + 4 * 3600).also { stored = it }
    }

    override fun openLocationSettings() {
        locationSettingsOpened++
    }

    override fun openAppSettings() {
        appSettingsOpened++
    }
}

/** Swallows log output. */
internal object NoLog : DiagnosticLog {
    override val entries: StateFlow<List<LogEntry>> = MutableStateFlow(emptyList())
    override val verbose: StateFlow<Boolean> = MutableStateFlow(false)
    override val isVerbose: Boolean = false
    override fun debug(tag: String, message: String) = Unit
    override fun info(tag: String, message: String) = Unit
    override fun diag(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, error: Any?) = Unit
    override fun error(tag: String, message: String, error: Any?, stack: Array<StackTraceElement>?) = Unit
    override fun security(tag: String, message: String) = Unit
    override fun section(title: String) = Unit
    override fun setVerbose(on: Boolean) = Unit
    override suspend fun clear() = Unit
}

/**
 * A dynamic-proxy stub of a (large) data-layer interface: every call goes to [answer] with the
 * JVM method name (property getters are `getX`); suspend functions return their value directly.
 */
internal inline fun <reified T : Any> stub(noinline answer: (name: String) -> Any?): T =
    java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when (method.name) {
            "toString" -> "stub(${T::class.java.simpleName})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> answer(method.name)
        }
    } as T
