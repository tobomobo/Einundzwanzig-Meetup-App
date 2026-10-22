package space.einundzwanzig.meetup.data.calendar.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.calendar.CalendarEventService
import space.einundzwanzig.meetup.data.calendar.EventCalendarRepository
import space.einundzwanzig.meetup.data.calendar.MeetupCalendarService
import space.einundzwanzig.meetup.data.calendar.merge.CalendarMerger
import space.einundzwanzig.meetup.data.calendar.model.CalendarItem
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class EventCalendarRepositoryImpl @Inject constructor(
    private val nostr: CalendarEventService,
    private val meetups: MeetupCalendarService,
    private val portal: PortalApiService,
    private val time: TimeSource,
    private val log: DiagnosticLog,
) : EventCalendarRepository {

    override suspend fun load(worldwide: Boolean, force: Boolean): List<CalendarItem> = coroutineScope {
        val zone = ZoneId.systemDefault()
        val nostrJob = async { guarded("Nostr") { nostr.fetchEvents(worldwide = worldwide) } }
        val meetupJob = async { guarded("Meetups") { meetups.fetchPortalFirst(force) } }
        val coursesJob = async { guarded("Kurse") { portal.getCourses() } }
        val events = nostrJob.await()
        val dates = meetupJob.await()
        val courses = coursesJob.await()

        val items = ArrayList<CalendarItem>()
        events.mapTo(items) { CalendarItem.fromNostr(it) }
        dates.mapTo(items) { CalendarItem.fromMeetup(it) }

        val courseIds = courses.mapNotNull { it["id"].dartIntValue() }.distinct()
        val details = ArrayList<JsonObject>()
        for (batch in courseIds.chunked(EventCalendarRepository.COURSE_BATCH)) {
            details += batch.map { id -> async { guardedOne { portal.getCourse(id) } } }.awaitAll().filterNotNull()
        }
        items += CalendarMerger.courseItems(details, Instant.ofEpochMilli(time.nowMillis()), zone)
        CalendarMerger.merge(items, zone)
    }

    private suspend fun <T> guarded(label: String, block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(TAG, "$label konnten nicht geladen werden", e)
        emptyList()
    }

    private suspend fun guardedOne(block: suspend () -> JsonObject?): JsonObject? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.debug(TAG, "Kursdetails fehlgeschlagen: $e")
        null
    }

    private companion object {
        const val TAG = "Calendar"
    }
}
