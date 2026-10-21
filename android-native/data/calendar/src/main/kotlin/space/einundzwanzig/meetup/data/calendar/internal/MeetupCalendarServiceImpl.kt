package space.einundzwanzig.meetup.data.calendar.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.core.network.HttpResult
import space.einundzwanzig.meetup.core.network.getCapped
import space.einundzwanzig.meetup.data.calendar.MeetupCalendarService
import space.einundzwanzig.meetup.data.calendar.ics.IcsMeetupDates
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import space.einundzwanzig.meetup.data.meetups.portal.PortalLogoRegistry
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
internal class MeetupCalendarServiceImpl @Inject constructor(
    private val portal: PortalApiService,
    private val logos: PortalLogoRegistry,
    private val http: OkHttpClient,
    private val time: TimeSource,
    private val log: DiagnosticLog,
    @DefaultDispatcher private val cpu: CoroutineDispatcher = Dispatchers.Default,
) : MeetupCalendarService {

    override suspend fun fetchPortalFirst(): List<MeetupDate> {
        try {
            val raw = portal.getAllMeetupEvents()
            val zone = ZoneId.systemDefault()
            val events = PortalDates.map(raw, Instant.ofEpochMilli(time.nowMillis()), zone) { name, logo -> logos.put(name, logo) }
            if (events.isNotEmpty()) {
                val f = events.first()
                log.diag(TAG, "QUELLE=PORTAL (${events.size} Termine). Erster: \"${f.title}\" -> ${hhmm(f.start, zone)}")
                return events
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug(TAG, "Portal-Termine fehlgeschlagen, iCal-Fallback: $e")
        }
        return fetchIcs()
    }

    override suspend fun fetchIcs(): List<MeetupDate> {
        val result = http.getCapped(MeetupCalendarService.CALENDAR_URL, maxBytes = MAX_ICS_BYTES, timeout = TIMEOUT)
        val response = result as? HttpResult.Response
        if (response == null || response.status != 200) {
            log.debug("App", "Fehler im CalendarService: ${if (response != null) "Server Fehler: ${response.status}" else result}")
            return emptyList()
        }
        return try {
            withContext(cpu) {
                val text = DartUtf8.decodeStrict(response.body)
                val raw = DTSTART_LINE.find(text)?.value ?: "kein DTSTART gefunden"
                log.diag(TAG, "QUELLE=ICS. Roh: \"$raw\"")
                IcsMeetupDates.fromIcs(text, Instant.ofEpochMilli(time.nowMillis()), ZoneId.systemDefault())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("App", "Fehler im CalendarService: $e")
            emptyList()
        }
    }

    private fun hhmm(start: Instant, zone: ZoneId): String {
        val t = start.atZone(zone)
        return "${two(t.hour)}:${two(t.minute)}"
    }

    private companion object {
        const val TAG = "Calendar"
        const val MAX_ICS_BYTES = 8L * 1024 * 1024
        val TIMEOUT = 15.seconds
        val DTSTART_LINE = Regex("DTSTART[^:\\n]*:[^\\r\\n]+")
    }
}
