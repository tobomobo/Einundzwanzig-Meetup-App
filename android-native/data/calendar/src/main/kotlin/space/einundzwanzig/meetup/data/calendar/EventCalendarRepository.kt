package space.einundzwanzig.meetup.data.calendar

import space.einundzwanzig.meetup.data.calendar.model.CalendarItem

/**
 * Source of the event calendar screen (Dart `EventCalendarScreen._load`, spec 10 §2.3, §8.6):
 * Nostr events, portal meetup dates and course dates merged into one de-duplicated list.
 * Filter/group with `CalendarMerger`.
 */
interface EventCalendarRepository {

    /**
     * Loads concurrently `CalendarEventService.fetchEvents(worldwide)`,
     * `MeetupCalendarService.fetchPortalFirst()` and the portal course list; then course details
     * in sequential batches of [COURSE_BATCH] (parallel within a batch); merges (course > meetup >
     * Nostr per minute+title key) and sorts by start. Never throws.
     * [force] bypasses the 60 s meetup-date cache (`fetchPortalFirst(force = true)`, pull-to-refresh).
     */
    suspend fun load(worldwide: Boolean, force: Boolean = false): List<CalendarItem>

    companion object {
        /** Course details fetched in parallel per batch. */
        const val COURSE_BATCH: Int = 8
    }
}
