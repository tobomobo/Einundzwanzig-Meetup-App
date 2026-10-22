package space.einundzwanzig.meetup.testing

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.navigation.*

/** Runs Navigation 3 reflective serializer lookup on device, including the R8 migrationTest build. */
@RunWith(AndroidJUnit4::class)
class NavigationDeviceTest {
    private val samples: List<Destination> = listOf(
        // Onboarding
        Splash,
        Intro,
        IdentitySetup,
        // Shell
        HomeTab,
        WalletTab,
        EventsTab,
        // Badges
        BadgeScanner,
        BadgeQrCamera,
        RollingQr,
        NfcTagWriter,
        MeetupSessionWizard,
        // Wallet
        BadgeWallet,
        BadgeCollection(groupKey = "Würzburg", query = "wü"),
        BadgeCollection(groupKey = BadgeCollection.ORGANIZER_GROUP_KEY),
        BadgeDetails(unionKey = "evt:wuerzburg-de-2026-09-12", collectionIndex = 3),
        BadgeDetails(unionKey = "org:sig:${"ab".repeat(32)}", collectionIndex = 0),
        BadgeWorldMap,
        // Reputation
        ReputationQr,
        ReputationCard,
        ReputationScanner,
        ReputationScanResult(code = "21v3:eyJ2Ijo2fQ==.sig.id.1700000000.pubkey"),
        ReputationVerify(),
        ReputationVerify(initialInput = "21rep::npub1abc::telegram::user::sig=00"),
        VerifyPerson(),
        VerifyPerson(initialTargetNpub = "npub1qv02xpsc3lhxxx5x7xswf88w3u7kykft9ea7t78tz7ywxf7mxs9qrxujnc"),
        VerifyPersonQrCamera,
        TrustNetworkHub,
        // Network & admin
        MyTrustNetwork,
        AdminPanel,
        // Proofs
        PlatformProofs,
        HumanityProof,
        // Profile
        ProfileEdit,
        RelaySettings,
        // Meetups
        NearbyMeetups,
        MeetupDetails(meetupId = "107"),
        MeetupDetails(meetupId = "m_muc"),
        MeetupDetails(meetupId = ""),
        MeetupSelection,
        LocationPicker(),
        LocationPicker(initialLatitude = 49.79245, initialLongitude = 9.93296),
        PortalMeetups,
        PortalMeetupEvents(meetupId = 107, meetupName = "Einundzwanzig Würzburg"),
        PortalEventEditor(meetupId = 107, meetupName = "Einundzwanzig Würzburg"),
        PortalEventEditor(meetupId = 107, meetupName = "Einundzwanzig Würzburg", eventId = 4711, eventIndex = 0),
        PortalEventEditor(meetupId = 107, meetupName = "Einundzwanzig Würzburg", eventIndex = 3),
        CommunityPortal,
        // Events
        MeetupCalendar(),
        MeetupCalendar(initialSearch = "Würzburg", initialMeetupId = "107"),
        MeetupCalendar(initialSearch = "Würzburg"),
        MeetupCalendar(preselectNextFavorite = true),
        EventCalendar(),
        EventCalendar(initialDay = "2026-10-20"),
        EventEditor,
        MyEvents,
        // Chat
        MeetupChat(roomId = "a1b2c3"),
        MeetupChat(roomId = "a1b2c3", roomName = "Einundzwanzig Würzburg"),
        MeetupChat(roomId = " "),
        EventChat(eventAddress = "31923:${"cd".repeat(32)}:meetup-1", title = "Bitcoin Stammtisch"),
        // News
        News(),
        News(markAsRead = true),
        NewsArticleDetails(articleId = "30023:${"ef".repeat(32)}:einundzwanzig-news-42", listIndex = 0),
        NewsArticleDetails(articleId = "https://einundzwanzig.space/news/42"),
        NewsArticleDetails(articleId = "", listIndex = 7),
        // Community
        CommunityHub,
        PortalArea,
        Courses,
        CourseDetails(courseId = 12),
        LecturerDetails(lecturerId = 34),
        ValueForValue,
        PlebrapLibrary,
        Glossary,
        // Bitcoin
        BitcoinDashboard,
        Converter,
        MempoolSettings,
        // Settings
        DiagnosticLog,
    )

    @Test fun everyDestinationRestoresUsingNavigation3ReflectionOnDevice() {
        val serializer = NavKeySerializer<NavKey>()
        val declared = Destination.serializer().descriptor.getElementDescriptor(1).elementNames.toSet()
        val covered = samples.map {
            Json.parseToJsonElement(Json.encodeToString(Destination.serializer(), it))
                .jsonObject.getValue("type").jsonPrimitive.content
        }.toSet()
        assertEquals(57, declared.size)
        assertEquals(declared, covered)
        for (key in samples) {
            val saved = Json.encodeToString(serializer, key)
            assertEquals(key, Json.decodeFromString(serializer, saved))
        }
    }
}
