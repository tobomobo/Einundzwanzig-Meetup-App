package space.einundzwanzig.meetup.core.navigation

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavKeySerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationsSerializationTest {

    private val json = Json
    private val polymorphicJson = Json { serializersModule = DestinationSerializersModule }

    /** At least one instance of every destination; data classes with non-default arguments. */
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

    @Test
    fun `samples cover every destination of the sealed hierarchy`() {
        val declared = Destination.serializer().descriptor.getElementDescriptor(1).elementNames.toSet()
        val covered = samples.map { serialNameOf(it) }.toSet()
        assertEquals("destinations without a serialization sample", emptySet<String>(), declared - covered)
        assertEquals(declared, covered)
        assertEquals(57, declared.size)
    }

    @Test
    fun `every destination round-trips through the sealed Destination serializer`() {
        for (key in samples) {
            val encoded = json.encodeToString(Destination.serializer(), key)
            val decoded = json.decodeFromString(Destination.serializer(), encoded)
            assertRoundTrip(key, decoded, encoded)
        }
    }

    @Test
    fun `every destination round-trips as a polymorphic NavKey with the module`() {
        val serializer = PolymorphicSerializer(NavKey::class)
        for (key in samples) {
            val encoded = polymorphicJson.encodeToString(serializer, key)
            val decoded = polymorphicJson.decodeFromString(serializer, encoded)
            assertRoundTrip(key, decoded, encoded)
        }
    }

    @Test
    fun `every destination round-trips through the Navigation 3 NavKeySerializer`() {
        // The serializer rememberNavBackStack uses on Android: class name + reflective lookup.
        val serializer = NavKeySerializer<NavKey>()
        for (key in samples) {
            val encoded = json.encodeToString(serializer, key)
            val decoded = json.decodeFromString(serializer, encoded)
            assertRoundTrip(key, decoded, encoded)
        }
    }

    @Test
    fun `every destination round-trips through its own class serializer`() {
        for (key in samples) {
            val serializer = serializer(key.javaClass)
            val encoded = json.encodeToString(serializer, key)
            val decoded = json.decodeFromString(serializer, encoded)
            assertRoundTrip(key, decoded, encoded)
        }
    }

    @Test
    fun `default arguments are omitted and restored`() {
        assertEquals("{}", json.encodeToString(MeetupCalendar.serializer(), MeetupCalendar()))
        assertEquals(MeetupCalendar(), json.decodeFromString(MeetupCalendar.serializer(), "{}"))
        assertEquals(News(), json.decodeFromString(News.serializer(), "{}"))
        assertEquals(
            NewsArticleDetails(articleId = ""),
            json.decodeFromString(NewsArticleDetails.serializer(), """{"articleId":""}"""),
        )
        assertEquals(
            PortalEventEditor(meetupId = 1, meetupName = "x"),
            json.decodeFromString(PortalEventEditor.serializer(), """{"meetupId":1,"meetupName":"x"}"""),
        )
    }

    @Test
    fun `organizer group key with NUL survives encoding`() {
        val key = BadgeCollection(groupKey = BadgeCollection.ORGANIZER_GROUP_KEY)
        val encoded = json.encodeToString(Destination.serializer(), key)
        val decoded = json.decodeFromString(Destination.serializer(), encoded) as BadgeCollection
        assertEquals("\u0000organizer", decoded.groupKey)
    }

    @Test
    fun `top level destinations are the three tabs in bar order`() {
        assertEquals(listOf(HomeTab, WalletTab, EventsTab), TopLevelDestination.entries)
        assertSame(HomeTab, TopLevelDestination.start)
        val tabs = samples.filterIsInstance<TopLevelDestination>().toSet()
        assertEquals(TopLevelDestination.entries.toSet(), tabs)
    }

    @Test
    fun `top level destinations round-trip through their own sealed serializer`() {
        for (tab in TopLevelDestination.entries) {
            val encoded = json.encodeToString(TopLevelDestination.serializer(), tab)
            assertSame(tab, json.decodeFromString(TopLevelDestination.serializer(), encoded))
        }
    }

    private fun assertRoundTrip(expected: Any, actual: Any, encoded: String) {
        assertEquals("round trip of $encoded", expected, actual)
        assertEquals(expected.javaClass, actual.javaClass)
        if (singletonOf(expected.javaClass) != null) {
            assertSame("data object must decode to its singleton: $encoded", expected, actual)
        }
        assertTrue(encoded.isNotEmpty())
    }

    /** The `INSTANCE` of a Kotlin object, without kotlin-reflect. */
    private fun singletonOf(type: Class<*>): Any? =
        runCatching { type.getField("INSTANCE").get(null) }.getOrNull()

    private fun serialNameOf(key: Destination): String =
        json.encodeToJsonElement(Destination.serializer(), key).jsonObject.getValue("type").jsonPrimitive.content
}
