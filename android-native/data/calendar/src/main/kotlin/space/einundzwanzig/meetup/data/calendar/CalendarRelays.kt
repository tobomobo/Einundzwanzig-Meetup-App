package space.einundzwanzig.meetup.data.calendar

/** Relay sets of the calendar domain (spec 10 §5.2). */
object CalendarRelays {

    /** The community relay: community-mode reads, always included on publish. */
    const val COMMUNITY: String = "wss://nostr.einundzwanzig.space"

    /**
     * Fixed part of the RSVP relay set (Dart `NewsReactionsService.readTargets`): these four,
     * then the active relays (set union, insertion order). Used for reading AND publishing RSVPs.
     */
    val RSVP_BASE: List<String> = listOf(
        COMMUNITY,
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band",
    )

    /** `{COMMUNITY} ∪ active` (fetch by address). */
    fun readByAddress(active: List<String>): List<String> = (listOf(COMMUNITY) + active).distinct()

    /** `active ∪ {COMMUNITY}` (calendar publish/cancel). */
    fun publish(active: List<String>): List<String> = (active + COMMUNITY).distinct()

    /** [RSVP_BASE] ∪ active. */
    fun rsvp(active: List<String>): List<String> = (RSVP_BASE + active).distinct()
}
