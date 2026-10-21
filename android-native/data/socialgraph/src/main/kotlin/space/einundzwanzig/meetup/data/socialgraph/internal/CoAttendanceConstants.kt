package space.einundzwanzig.meetup.data.socialgraph.internal

/** Constants of `coattendance_service.dart` (spec 05 §8.2.5). */
internal object CoAttendanceConstants {
    /** Parameterized-replaceable co-attendance kind. */
    const val KIND = 30079

    /** `["client", …]` tag value. */
    const val CLIENT = "einundzwanzig-meetup-app"

    /** Pref key: JSON object `{sigId: relaysAccepted}`. */
    const val KEY_PUBLISH_STATUS = "coatt_publish_status"

    const val MAX_MEETUPS_PER_STAGE = 60
    const val MAX_PEOPLE_PER_STAGE = 300
    const val AUTHOR_BATCH = 100
    const val KEY_BATCH = 60
    const val PARALLEL_BATCHES = 3

    /** Full re-sync after 24 h. */
    const val FULL_SYNC_EVERY_MS = 24L * 60 * 60 * 1000

    /** `since` overlap of incremental fetches: 1 h. */
    const val SINCE_OVERLAP_S = 3600L

    /** Publish: wait this long for `["OK", id, true]`. */
    const val PUBLISH_OK_WAIT_S = 6L
}
