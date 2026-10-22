package space.einundzwanzig.meetup.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeParseException

/*
 * Every destination of the app, one NavKey per full-screen route of the Flutter app.
 *
 * Sources: section 2 ("Screens & UX flows") of spec/01..13. ROUTES.md lists every key with its
 * spec section and owning feature module.
 *
 * Rules applied here:
 * - Argument-less screens are `data object`s; screens with arguments are `data class`es with
 *   primitive/String arguments only. Where Flutter passes a whole model, the key carries the stable
 *   identifier the spec names and the feature resolves the model from its repository.
 * - Bottom sheets and dialogs opened in place (settings sheet, scan sheet, bunker sheet, details
 *   sheets, pickers, ...) are not destinations.
 * - Dead Flutter screens the specs say not to port (RadarScreen, SettingsScreen, MeetupListScreen,
 *   CreateMeetupScreen, EventsScreen, ExternalEventsScreen, PortalEventsScreen, PosScreen) have no key.
 *
 * Keep the class names stable: the Navigation 3 back stack is saved by class name.
 */

/**
 * Root of every destination of the app.
 *
 * Sealed so that kotlinx.serialization generates one polymorphic serializer covering every key
 * (see [DestinationSerializersModule]) and so that `when` over a destination is exhaustive.
 */
@Serializable
sealed interface Destination : NavKey

// region Onboarding (spec 01 §2.1–2.4, spec 12 §2.3)

/**
 * Splash / session check (spec 01 §2.1, spec 12 §2.3).
 *
 * Root of the back stack on cold start. After the Flutter storage migration and the profile load it
 * calls [Navigator.resetTo] with [Intro] when the user is not onboarded, else with
 * [TopLevelDestination.start].
 */
@Serializable
data object Splash : Destination

/**
 * Intro screen with language chip, "COMMUNITY BETRETEN" and "BACKUP LADEN" (spec 01 §2.2).
 *
 * Opened by [Splash] when the user is not onboarded, and by the home reset flow
 * (spec 12 §2.5.12) through [Navigator.resetTo]. Joining when onboarded or a successful backup
 * restore resets the stack to [TopLevelDestination.start].
 */
@Serializable
data object Intro : Destination

/**
 * Identity setup wizard: choose / new / resume / passkey / existing / existingMore / nameOnly /
 * meetup (spec 01 §2.3). The steps are internal state of this one destination.
 *
 * Pushed by [Intro] ("COMMUNITY BETRETEN" while not onboarded) and by the home screen when the
 * nickname is empty or "Anon" (spec 12 §2.5.10). Completion resets the stack to
 * [TopLevelDestination.start]; the close button pops.
 */
@Serializable
data object IdentitySetup : Destination

// endregion

// region App shell tabs (spec 12 §2.4)

/**
 * The bottom-navigation tabs of the app shell (spec 12 §2.4), in bar order.
 *
 * The bar shows Home, Badges, the central scan button, Events and the "In der Nähe" action. The scan
 * button opens an in-place sheet (not a destination) whose options push [BadgeScanner] and
 * [ReputationQr]; "In der Nähe" pushes [NearbyMeetups] (it is an action, not a tab). Every tab keeps
 * its state while another tab is shown. Icons and labels belong to `:app`.
 *
 * Resetting the stack to [start] is the native equivalent of Flutter's
 * `pushAndRemoveUntil(AppShell)`.
 */
@Serializable
sealed interface TopLevelDestination : Destination {
    companion object {
        /** All tabs in bottom-bar order. The scan button sits between [WalletTab] and [EventsTab]. */
        val entries: List<TopLevelDestination> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            listOf(HomeTab, WalletTab, EventsTab)
        }

        /** The tab the shell opens with (Flutter `_currentIndex = 0`). */
        val start: TopLevelDestination get() = HomeTab
    }
}

/**
 * Home dashboard tab (spec 12 §2.5): profile header, home-meetup carousel, tile grid.
 *
 * Every tap on this tab, both switching to it and re-selecting it, triggers the dashboard refresh
 * (`refreshAfterScan`, spec 12 §2.4 `_onTabTap`). Home also refreshes when the scanner opened from
 * the scan sheet returns.
 */
@Serializable
data object HomeTab : TopLevelDestination

/** Badge wallet tab, label "Badges" (spec 12 §2.4, spec 03 §2.1). Same screen as [BadgeWallet]. */
@Serializable
data object WalletTab : TopLevelDestination

/** Events hub tab with the "Meetups" and "Veranstaltungskalender" cards (spec 12 §2.4, spec 10 §2.1). */
@Serializable
data object EventsTab : TopLevelDestination

// endregion

// region Badges: scan, claim and issue (spec 02)

/**
 * Participant badge scanner (`MeetupVerificationScreen`, spec 02 §2.1): scan, verify, bind, check
 * presence and save a badge.
 *
 * Pushed (slide up) from the scan sheet option "Badge scannen" (spec 12 §2.4). On success it
 * replaces itself with [BadgeWallet] ([Navigator.replaceTop]). Flutter passes a placeholder meetup
 * `"global"` at 0/0 whose coordinates are never usable, so the key has no argument; the presence
 * reference falls back to the meetup looked up by badge name (spec 02 §2.1, step 4 of
 * `_confirmSaveBadge`).
 */
@Serializable
data object BadgeScanner : Destination

/**
 * Full-screen camera for the organizer's badge QR (`_QRScannerScreen`, spec 02 §2.2).
 *
 * Pushed by [BadgeScanner] ("QR-CODE SCANNEN"). Sends the raw text of the first accepted code
 * (the app-review payload or a JSON object with `t`/`type`) through [NavResults.BadgeQrScanned]
 * and pops; [BadgeScanner] decodes it again. Popping without a result means "cancelled".
 */
@Serializable
data object BadgeQrCamera : Destination

/**
 * Organizer rolling-QR display of the running session (spec 02 §2.3). Never creates a session.
 *
 * Pushed by [AdminPanel] (after starting a session and "AKTIVES MEETUP ÖFFNEN"), the home live
 * session card (spec 12 §2.5.4) and the event calendar after an event badge session started
 * (spec 10 §2.3). [NfcTagWriter] and [MeetupSessionWizard] (both behind the NFC flag) open it with
 * [Navigator.navigateFromRoot] (Flutter `pushAndRemoveUntil(RollingQRScreen, route.isFirst)`), so
 * back from the rolling QR returns to the root of the stack.
 */
@Serializable
data object RollingQr : Destination

/**
 * NFC tag writer for the session base payload (spec 02 §2.4). Behind `BuildConfig.NFC_ENABLED`
 * (off). Pushed by [MeetupSessionWizard]; on success it opens [RollingQr] after 3 s.
 */
@Serializable
data object NfcTagWriter : Destination

/**
 * Two-step NFC/QR session wizard (spec 02 §2.5). Behind `BuildConfig.NFC_ENABLED` (off). Pushed by
 * [AdminPanel] after a session start when NFC is enabled.
 */
@Serializable
data object MeetupSessionWizard : Destination

// endregion

// region Badge wallet (spec 03)

/**
 * Badge wallet pushed over the shell (spec 03 §2.1). Same screen as [WalletTab], fresh UI state.
 *
 * [BadgeScanner] replaces itself with this key after a saved badge, after a rejected duplicate
 * and from "ZUR WALLET" on an already collected badge.
 */
@Serializable
data object BadgeWallet : Destination

/**
 * Two-column grid of one wallet group, newest first (`_CollectionScreen`, spec 03 §2.1.1).
 *
 * Pushed by a collection card of the wallet in "Nach Meetup" mode.
 *
 * @property groupKey the group key of spec 03 §8.1: the trimmed meetup name (`"?"` if blank) or
 *   [ORGANIZER_GROUP_KEY] for the organizer markers.
 * @property query the wallet search query at navigation time; the group is built from the filtered
 *   collection (spec 03 §8.1), which matters for the organizer group.
 */
@Serializable
data class BadgeCollection(
    val groupKey: String,
    val query: String = "",
) : Destination {
    companion object {
        /** Group key of the organizer markers (`"\u0000organizer"`, spec 03 §8.1). */
        const val ORGANIZER_GROUP_KEY: String = "\u0000organizer"
    }
}

/**
 * Details of one badge: hero, timestamps, cryptographic proof, share as image (spec 03 §2.2).
 *
 * Pushed by wallet cards (normal and compact) and collection cards. Flutter passes the badge
 * object. Badge ids are not unique and duplicates share a union key, so the key carries both the
 * union key and the position in the full collection (the `#n` label minus one). The screen takes the
 * badge at [collectionIndex] if its union key matches, else the first badge with that union key.
 *
 * @property unionKey `BadgeIdentity.unionKey(badge)` (spec 02 §8.1).
 * @property collectionIndex index of the badge in the full, unfiltered collection.
 */
@Serializable
data class BadgeDetails(
    val unionKey: String,
    val collectionIndex: Int,
) : Destination {
    init {
        require(unionKey.isNotEmpty()) { "unionKey must not be empty" }
        require(collectionIndex >= 0) { "collectionIndex must be >= 0, was $collectionIndex" }
    }
}

/**
 * OpenStreetMap world map of all located badges, shareable as an image (spec 03 §2.4).
 *
 * Pushed by the wallet app-bar map button; shows the whole collection including organizer markers.
 */
@Serializable
data object BadgeWorldMap : Destination

// endregion

// region Trust & reputation (spec 04, spec 02 §2.6)

/**
 * "REPUTATION" screen with the signed 21v3 reputation QR, stats, proof status and relay publish
 * (spec 04 §2.1).
 *
 * Pushed (slide up) from the scan sheet option "Reputation prüfen" (spec 12 §2.4), the wallet share
 * sheet "QR-Code anzeigen" (spec 03 §2.1.2) and the home reputation tile (spec 12 §2.5.8).
 */
@Serializable
data object ReputationQr : Destination

/**
 * "Reputations-Profil" share card (spec 04 §2.2).
 *
 * Pushed by the home score pill (spec 12 §2.5.3) and the [ReputationQr] app-bar share action.
 */
@Serializable
data object ReputationCard : Destination

/**
 * "REPUTATION PRÜFEN" camera scanner for `21v3:`/`21v2:`/`21:` codes (`SecureQRScanner`,
 * spec 04 §2.3, spec 02 §2.6).
 *
 * Pushed by the [ReputationQr] app-bar scan action and its empty-state button. Every outcome
 * replaces this screen with [ReputationScanResult] ([Navigator.replaceTop]).
 */
@Serializable
data object ReputationScanner : Destination

/**
 * "ERGEBNIS" screen for a scanned reputation QR (`_VerificationResultScreen`, spec 04 §2.3,
 * spec 02 §2.6).
 *
 * Replaces [ReputationScanner]. Flutter passes the parsed verification result; the key carries the
 * raw scanned text instead and the screen runs the pure parse/verify of spec 04 §7.7 again, which
 * yields the same valid/invalid/unknown-format outcome.
 *
 * @property code the raw QR text, starting with `21v3:`, `21v2:` or `21:`.
 */
@Serializable
data class ReputationScanResult(
    val code: String,
) : Destination {
    init {
        require(code.isNotEmpty()) { "code must not be empty" }
    }
}

/**
 * Verify a `21rep::` verify string or an npub (`ReputationVerifyScreen`, spec 04 §2.4).
 *
 * Orphan in Flutter (no caller). Ported as the spec asks so it can be wired later.
 *
 * @property initialInput prefilled input; when non-empty the screen verifies right away.
 */
@Serializable
data class ReputationVerify(
    val initialInput: String? = null,
) : Destination

/**
 * "Person prüfen": co-attendance path between the user and another npub (spec 04 §2.7,
 * spec 05 §2.3).
 *
 * Pushed by the [MyTrustNetwork] app-bar action and the [TrustNetworkHub] tool card.
 *
 * @property initialTargetNpub prefilled npub; when set the check runs after the first frame.
 */
@Serializable
data class VerifyPerson(
    val initialTargetNpub: String? = null,
) : Destination

/**
 * Full-screen camera that reads an npub or reputation QR for [VerifyPerson] (spec 04 §2.7.1).
 *
 * Sends the lower-cased npub through [NavResults.NpubScanned] and pops. Popping without a result
 * means "cancelled".
 */
@Serializable
data object VerifyPersonQrCamera : Destination

/**
 * "Vertrauensnetzwerk" hub with the "Netzwerk-Analyse" tool card (spec 04 §2.8).
 *
 * Orphan in Flutter (no caller). Ported as the spec asks; its card pushes [VerifyPerson].
 */
@Serializable
data object TrustNetworkHub : Destination

// endregion

// region Co-attendance network & organizer (spec 05)

/**
 * "Meine Vernetzung": co-attendance trust network graph and lists (`MyNetworkScreen`, spec 05 §2.1).
 *
 * Pushed by the home tile `trust_network` (spec 12 §2.5.8).
 */
@Serializable
data object MyTrustNetwork : Destination

/**
 * Organizer panel: start/end a meetup session (spec 05 §2.2).
 *
 * Pushed by the home tile `organisator`, visible only for admins and the review demo
 * (spec 12 §2.5.8). Home re-checks the running session when it becomes visible again.
 */
@Serializable
data object AdminPanel : Destination

// endregion

// region Proofs (spec 06)

/**
 * Platform links ("Plattform-Verknüpfung"): create and revoke kind-21003 platform proofs
 * (`PlatformProofScreen`, spec 06 §2.1).
 *
 * Pushed by the profile identity action "Plattformen verknüpfen" (spec 07 §2.2.5). After a proof
 * was created the screen closes itself (spec 06 §2.1).
 */
@Serializable
data object PlatformProofs : Destination

/**
 * Proof of Humanity check against zap receipts on relays (`HumanityProofScreen`, spec 06 §2.2).
 *
 * Pushed by the profile identity action "Proof of Humanity" (spec 07 §2.2.5).
 */
@Serializable
data object HumanityProof : Destination

// endregion

// region Profile & Nostr infrastructure (spec 07, spec 01 §2.7)

/**
 * Profile screen, read-only (verified organizer) or edit mode, including the Nostr key and signer
 * management section (spec 07 §2.2, spec 01 §2.7).
 *
 * Pushed by the settings sheet row "Profil bearbeiten" (spec 12 §2.6). Saving resets the stack to
 * [TopLevelDestination.start] (spec 07 §2.2.6).
 */
@Serializable
data object ProfileEdit : Destination

/**
 * Nostr relay settings (`RelaySettingsScreen`, spec 07 §2.1).
 *
 * Pushed by the settings sheet row "Nostr-Relays" (spec 12 §2.6).
 */
@Serializable
data object RelaySettings : Destination

// endregion

// region Meetups, location & portal (spec 09)

/**
 * "MEETUPS IN DER NÄHE": map, radius and date filters, place search (spec 09 §2.2).
 *
 * Pushed by the bottom-bar action "In der Nähe" (spec 12 §2.4, light haptic) and the portal area
 * row "Karte" (spec 11 §2.6).
 */
@Serializable
data object NearbyMeetups : Destination

/**
 * Meetup details: dates, links, location (spec 09 §2.3).
 *
 * Pushed by [NearbyMeetups] (pin tap, "Details") and the home favorite card action "Info"
 * (spec 12 §2.5.5). Flutter passes the `Meetup`; the screen resolves it by id from the portal
 * meetup list, falling back to the bundled offline list (`allMeetups`, spec 09 §3.1). The id comes
 * from remote data and is not validated here: an id that resolves to no meetup (including `""`,
 * which Dart produces for a portal entry without id and with an empty name) shows a not-found state.
 *
 * @property meetupId `Meetup.id` (portal id as string, e.g. `"107"`, or `m_muc`-style fallback ids).
 */
@Serializable
data class MeetupDetails(
    val meetupId: String,
) : Destination

/**
 * "MEETUP AUSWÄHLEN": full-screen favorites picker (spec 09 §2.4).
 *
 * Pushed by the home CTA "Wähle deinen Stammtisch" when no favorite exists (spec 12 §2.5.5). Saving
 * writes the favorites to the user profile and pops; Home reloads the profile when it is visible
 * again.
 */
@Serializable
data object MeetupSelection : Destination

/**
 * "Ort des Events": tap-on-map venue picker (`LocationPickerScreen`, spec 09 §2.6).
 *
 * Pushed by the event editor's badge location row (spec 10 §2.4). "Ort übernehmen" sends the pick
 * through [NavResults.LocationPicked] and pops; popping without a result keeps the old value.
 *
 * @property initialLatitude preset pin latitude; set together with [initialLongitude]. The pin is
 *   preset only when both are non-null and not both 0.
 * @property initialLongitude preset pin longitude.
 */
@Serializable
data class LocationPicker(
    val initialLatitude: Double? = null,
    val initialLongitude: Double? = null,
) : Destination {
    init {
        require((initialLatitude == null) == (initialLongitude == null)) {
            "initialLatitude and initialLongitude must be set together"
        }
    }
}

/**
 * "Meine Meetups": portal login, the organizer's portal meetups and co-leader management
 * (`PortalMeetupsScreen`, spec 09 §2.1).
 *
 * Pushed by the home tile "Meine Meetups" (spec 12 §2.5.8) and the portal area row "Meine Meetups"
 * (spec 11 §2.6).
 */
@Serializable
data object PortalMeetups : Destination

/**
 * Portal dates of one meetup with "Termin anlegen" (`ManageEventsScreen`, spec 09 §2.1).
 *
 * Pushed by the "Termine verwalten" button of a meetup card in [PortalMeetups]. Flutter passes the
 * `PortalMeetup`; only its id and name are used.
 *
 * @property meetupId `PortalMeetup.id`.
 * @property meetupName `PortalMeetup.name`, shown as the app-bar title.
 */
@Serializable
data class PortalMeetupEvents(
    val meetupId: Int,
    val meetupName: String,
) : Destination

/**
 * Create or edit one portal meetup date (`EventEditor` in `portal_meetups_screen.dart`,
 * spec 09 §2.1).
 *
 * Pushed by [PortalMeetupEvents]: the FAB creates (no [eventId], no [eventIndex]); a row edits and
 * passes both the date's id (`null` for dates without a portal id) and its position.
 *
 * Resolution without a network round trip: `:data:meetups` keeps the last `getMyMeetupEvents()`
 * result in memory (the list [PortalMeetupEvents] just showed). The editor takes this meetup's dates
 * from it, sorted by start ascending (the order of [PortalMeetupEvents]), and uses the date at
 * [eventIndex] if its id equals [eventId], else the date whose id is [eventId] (when non-null), else
 * shows a not-found state.
 *
 * Dates without a portal id open like in Flutter (`_EventEditor(existing: ev)`), prefilled with
 * start, location, description and link; only saving fails with "Fehler: Termin ohne ID". On success
 * the editor sends [NavResults.PortalEventSaved] and pops.
 *
 * @property meetupId `PortalMeetup.id` the date belongs to.
 * @property meetupName `PortalMeetup.name`, shown in the context card.
 * @property eventId `PortalMeetupEvent.id` of the edited date; `null` when creating or when the date
 *   has no portal id.
 * @property eventIndex position of the edited date in this meetup's dates sorted by start, or `null`
 *   when creating.
 */
@Serializable
data class PortalEventEditor(
    val meetupId: Int,
    val meetupName: String,
    val eventId: Int? = null,
    val eventIndex: Int? = null,
) : Destination {
    init {
        require(eventIndex == null || eventIndex >= 0) { "eventIndex must be >= 0, was $eventIndex" }
    }

    /** `true` when an existing date is edited (Flutter `existing != null`). */
    val isEdit: Boolean get() = eventId != null || eventIndex != null
}

/**
 * "Portal-Webseite": static link hub to einundzwanzig.space pages (`CommunityPortalScreen`,
 * spec 09 §2.7).
 *
 * Pushed by the portal area row "Portal-Webseite" (spec 11 §2.6).
 */
@Serializable
data object CommunityPortal : Destination

// endregion

// region Calendar & events (spec 10)

/**
 * "MEETUP TERMINE": portal meetup dates with search, favorites and portal RSVP (`CalendarScreen`,
 * spec 10 §2.2).
 *
 * Pushed by the events hub card "Meetups" (spec 10 §2.1), the wallet empty state (spec 03 §2.1), the
 * portal area rows "Meetups" and "Events" (spec 11 §2.6) without arguments; by the home favorite
 * card action "EVENTS" with [initialSearch] = city and [initialMeetupId] = resolved meetup id
 * (spec 12 §2.5.5); and by the home-screen widget's meetup area with [preselectNextFavorite]
 * (spec 12 §2.5.10, spec 10 §2.10).
 *
 * @property initialSearch prefilled search text.
 * @property initialMeetupId exact-meetup filter, applied only while the search text still equals
 *   [initialSearch] (spec 10 §2.2).
 * @property preselectNextFavorite resolve the front favorite card (the favorite with the soonest
 *   date, the one the widget shows) and filter by it, like Flutter's widget route `'meetup'`
 *   (spec 12 §2.5.10). The resolution must use the same use case Home uses for its favorites
 *   carousel and for `WidgetService.updateMeetup` (spec 12 §8.3, to live in `:data:calendar`), so the
 *   calendar opens the meetup the widget shows; do not re-implement it in `:feature:events`. With
 *   `front` = the first card of that use case (falling back to `user.homeMeetupId` as key and the
 *   legacy home meetup's city when there is no card): `initialSearch = cityFor(front.key)`,
 *   `initialMeetupId = resolveFavorite(front.key)?.id`; when that city is empty the calendar opens
 *   unfiltered. Exclusive with the two explicit arguments.
 */
@Serializable
data class MeetupCalendar(
    val initialSearch: String? = null,
    val initialMeetupId: String? = null,
    val preselectNextFavorite: Boolean = false,
) : Destination {
    init {
        require(!preselectNextFavorite || (initialSearch == null && initialMeetupId == null)) {
            "preselectNextFavorite excludes initialSearch and initialMeetupId"
        }
    }
}

/**
 * "Veranstaltungskalender": month/year/list views of meetups, NIP-52 events and courses
 * (`EventCalendarScreen`, spec 10 §2.3).
 *
 * Pushed by the events hub card (no day) and the home "Events" tile with today
 * (spec 12 §2.5.8). Home refreshes the session and its event lists when visible again.
 *
 * @property initialDay ISO-8601 local date (`yyyy-MM-dd`) to focus and select; `null` = today.
 */
@Serializable
data class EventCalendar(
    val initialDay: String? = null,
) : Destination {
    init {
        require(initialDay == null || parseDayOrNull(initialDay) != null) {
            "initialDay must be an ISO-8601 local date (yyyy-MM-dd), was '$initialDay'"
        }
    }

    /** [initialDay] as a [LocalDate], or `null` when no day was given. */
    val initialLocalDate: LocalDate? get() = initialDay?.let(::parseDayOrNull)

    companion object {
        /** Opens the calendar focused on [day]. */
        fun forDay(day: LocalDate): EventCalendar = EventCalendar(day.toString())

        private fun parseDayOrNull(value: String): LocalDate? = try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/**
 * "Veranstaltung eintragen": create a NIP-52 calendar event, optionally with an event badge
 * (`EventEditorScreen`, spec 10 §2.4). There is no edit mode.
 *
 * Pushed by the [EventCalendar] FAB "Event eintragen". After a successful publish it sends
 * [NavResults.EventPublished] and pops; the calendar reloads.
 */
@Serializable
data object EventEditor : Destination

/**
 * "Meine Termine": RSVP'd portal meetup dates and accepted NIP-52 events with chat entry points
 * (`MyEventsScreen`, spec 10 §2.5).
 *
 * Pushed by the home tile "Meine Termine" (`event_chats`, spec 12 §2.5.8). Flutter passes the lists
 * Home has loaded and two callbacks; natively the screen loads both lists itself, opens
 * [EventChat] directly and resolves the meetup chat room itself before opening [MeetupChat]
 * (spec 12 §8.6). Home reloads its counters when visible again.
 */
@Serializable
data object MyEvents : Destination

// endregion

// region Chat (spec 11 §2.1)

/**
 * NIP-29 meetup group chat room (`ChatScreen.room(room)`, spec 11 §2.1).
 *
 * Pushed by the home favorite card action "Chat" and by [MyEvents] after the room lookup
 * succeeded; lookup failures stay snackbars on the calling screen (spec 11 §2.1).
 *
 * The room id comes from relay data and is not validated here; an id that matches no room shows a
 * not-found state.
 *
 * @property roomId the NIP-29 group id `h` (non-empty for rooms from `ChatRoom.fromEvent`, may contain
 *   whitespace).
 * @property roomName room display name from the kind-39000 metadata; empty shows [roomId]
 *   until the screen has loaded the metadata.
 */
@Serializable
data class MeetupChat(
    val roomId: String,
    val roomName: String = "",
) : Destination

/**
 * NIP-22 comment thread on a NIP-52 calendar event (`ChatScreen.event(...)`, spec 11 §2.1).
 *
 * Pushed by the event calendar details sheet "Chat zum Termin" (Nostr events only, spec 10 §2.3)
 * and by a [MyEvents] row.
 *
 * @property eventAddress the event address `"<kind>:<pubkey>:<d>"`.
 * @property title the event title shown in the app bar.
 */
@Serializable
data class EventChat(
    val eventAddress: String,
    val title: String,
) : Destination {
    init {
        require(eventAddress.isNotBlank()) { "eventAddress must not be blank" }
    }

    /**
     * Hex pubkey of the event author: the second segment of [eventAddress] (Flutter passes
     * `event.pubkey`, which is the same value). Empty when the address has no second segment.
     */
    val eventAuthor: String get() = eventAddress.split(':', limit = 3).getOrNull(1).orEmpty()
}

// endregion

// region News (spec 11 §2.3–2.4)

/**
 * News list from media.einundzwanzig.space (`NewsScreen`, spec 11 §2.3).
 *
 * Pushed by the home News tile and the home-screen widget's news row with [markAsRead] (Flutter
 * `_openNews`, spec 12 §2.5.10), and by the community hub News card without it.
 *
 * @property markAsRead mark the articles as read and clear the widget's "NEU" marker on open
 *   (`NewsService.markRead()` + `WidgetService.markNewsSeen()`).
 */
@Serializable
data class News(
    val markAsRead: Boolean = false,
) : Destination

/**
 * Article detail with Markdown body, likes, zaps and share (`ArticleDetail`, spec 11 §2.4).
 *
 * Pushed by an article card in [News]. Flutter passes the `NewsArticle`. Article ids are neither
 * unique nor always non-empty (the RSS parser uses `guid`, else `link`, so a guid-less item without
 * link has id `""` and guid-less items with the same link share an id), so the key carries the id and
 * the card's position like [BadgeDetails] does. The screen takes the article at [listIndex] of the
 * news repository's current list if its id equals [articleId], else the first article with that id,
 * else shows a not-found state.
 *
 * @property articleId `NewsArticle.id` (the RSS guid, usually `30023:<pubkey>:<d>`, else the link,
 *   possibly empty).
 * @property listIndex index of the article in the list [News] shows; negative when unknown.
 */
@Serializable
data class NewsArticleDetails(
    val articleId: String,
    val listIndex: Int = -1,
) : Destination

// endregion

// region Community, portal area, courses, V4V, PlebRap, glossary (spec 11 §2.5–2.12)

/**
 * Community hub: portal card, News, Nostr, Shoutout, Podcast, SatoshiDuell, PlebRap
 * (`CommunityHubScreen`, spec 11 §2.5).
 *
 * Pushed by the home tile `community` (spec 12 §2.5.8).
 */
@Serializable
data object CommunityHub : Destination

/**
 * Portal area with rows to calendar, courses, map, my meetups and the portal website
 * (`PortalAreaScreen`, spec 11 §2.6).
 *
 * Pushed by the [CommunityHub] portal card and the home tile `portal_area` (spec 12 §2.5.8).
 */
@Serializable
data object PortalArea : Destination

/**
 * Courses and lecturers tabs (`CoursesScreen`, spec 11 §2.7).
 *
 * Pushed by the [PortalArea] row "Kurse". Items without an integer id open an in-place sheet.
 */
@Serializable
data object Courses : Destination

/**
 * Portal course detail (`CourseDetailScreen`, spec 11 §2.8).
 *
 * Pushed by [Courses] and by the course rows of [LecturerDetails]. Flutter also passes a fallback
 * map and renders `getCourse(id) ?? fallback`, so the fallback is shown when the detail request
 * fails (offline, 404). The fallback is either a list entry or a nested `lecturer['courses'][i]`
 * object that is never in the course list. Contract: `:data:community` keeps an in-memory map from
 * course id to the last seen course JSON, filled from the `getCourses` list and from the nested
 * `courses` arrays of lecturer detail responses; the screen renders `detail ?: cached[courseId]`.
 *
 * @property courseId portal course id.
 */
@Serializable
data class CourseDetails(
    val courseId: Int,
) : Destination

/**
 * Portal lecturer detail (`LecturerDetailScreen`, spec 11 §2.8).
 *
 * Pushed by the lecturers tab of [Courses] and the lecturer card of [CourseDetails]. Like
 * [CourseDetails], Flutter renders `getLecturer(id) ?? fallback`, where the fallback can be the
 * nested `course['lecturer']` object. Contract: `:data:community` keeps an in-memory map from
 * lecturer id to the last seen lecturer JSON, filled from the `getLecturers` list and from the nested
 * `lecturer` objects of course detail responses; the screen renders `detail ?: cached[lecturerId]`.
 *
 * @property lecturerId portal lecturer id.
 */
@Serializable
data class LecturerDetails(
    val lecturerId: Int,
) : Destination

/**
 * Value-for-Value donation via LNURL-pay (`V4VScreen`, spec 11 §2.10).
 *
 * Pushed by the settings sheet row "V4V" (spec 12 §2.6).
 */
@Serializable
data object ValueForValue : Destination

/**
 * PlebRap player and track library (`PlebrapPlayerScreen`, spec 11 §2.11). Playback continues after
 * leaving the screen.
 *
 * Pushed by the library icon of the home PlebRap tile and the [CommunityHub] PlebRap card.
 */
@Serializable
data object PlebrapLibrary : Destination

/**
 * Searchable glossary ("Nachschlagen", `GlossaryScreen`, spec 11 §2.12).
 *
 * Pushed by the "?" icon in the home logo bar (spec 12 §2.5.2).
 */
@Serializable
data object Glossary : Destination

// endregion

// region Bitcoin tools (spec 13)

/**
 * Full-screen Bitcoin dashboard (`BitcoinDashboardScreen`, spec 13 §2.1).
 *
 * Pushed by the home tile `btc_dashboard` and by the home-screen widget's Bitcoin area
 * ([DeepLinks]).
 */
@Serializable
data object BitcoinDashboard : Destination

/**
 * Fiat ↔ sats/BTC converter (`ConverterScreen`, spec 13 §2.2).
 *
 * Pushed by the home tile `converter`.
 */
@Serializable
data object Converter : Destination

/**
 * Mempool server settings with connection test (`MempoolSettingsScreen`, spec 13 §2.3).
 *
 * Pushed by the settings sheet row "Mempool-Server" (spec 12 §2.6) and the "change server" button
 * of [BitcoinDashboard], which reloads when it is visible again.
 */
@Serializable
data object MempoolSettings : Destination

// endregion

// region Settings & diagnostics (spec 12)

/**
 * Diagnostic log viewer (`LogScreen`, spec 12 §2.8).
 *
 * Pushed by the settings sheet row "Diagnose-Log" (spec 12 §2.6).
 */
@Serializable
data object DiagnosticLog : Destination

// endregion
