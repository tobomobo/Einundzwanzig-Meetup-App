package space.einundzwanzig.meetup.feature.badges

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.LogEntry
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.security.ExpiryInfo
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.issuance.scan.AdminCheckInfo
import space.einundzwanzig.meetup.data.issuance.scan.BadgeSaveUseCase
import space.einundzwanzig.meetup.data.issuance.scan.BadgeSavedEvent
import space.einundzwanzig.meetup.data.issuance.scan.BadgeScanProcessor
import space.einundzwanzig.meetup.data.issuance.scan.ClaimInfo
import space.einundzwanzig.meetup.data.issuance.scan.FoundInfo
import space.einundzwanzig.meetup.data.issuance.scan.PendingBadge
import space.einundzwanzig.meetup.data.issuance.scan.SaveOutcome
import space.einundzwanzig.meetup.data.issuance.scan.ScanOutcome
import space.einundzwanzig.meetup.data.issuance.session.MeetupSession
import space.einundzwanzig.meetup.data.issuance.session.NonceValidation
import space.einundzwanzig.meetup.data.issuance.session.RollingQrService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.feature.badges.nfc.NfcAvailability
import space.einundzwanzig.meetup.feature.badges.nfc.NfcReadResult
import space.einundzwanzig.meetup.feature.badges.nfc.NfcStatusSource
import space.einundzwanzig.meetup.feature.badges.nfc.NfcTagHandle
import space.einundzwanzig.meetup.feature.badges.nfc.NfcWriteResult
import space.einundzwanzig.meetup.feature.badges.scanner.ScannerLabels

internal fun badge(
    name: String = "Würzburg, DE",
    eventId: String = "wuerzburg-2026-10-21",
    sig: String = "a".repeat(128),
) = MeetupBadge(
    id = "wuerzburg",
    meetupName = name,
    date = DartDateTime(0, false),
    iconPath = "assets/badge_icon.png",
    meetupEventId = eventId,
    sig = sig,
    sigVersion = 2,
    adminPubkey = "b".repeat(64),
)

internal fun foundInfo(unknownSigner: Boolean = false) = FoundInfo(
    fullName = "Würzburg, DE",
    blockHeight = 915_000,
    verifiedBy = "npub1abc...xyz",
    sigVersion = 2,
    claim = ClaimInfo.Bound,
    adminCheck = if (unknownSigner) AdminCheckInfo.UnknownSigner else AdminCheckInfo.KnownOrganizer("Satoshi"),
    expiry = ExpiryInfo.Valid(3 * 3600 + 12 * 60),
    unknownSigner = unknownSigner,
)

internal fun found(unknownSigner: Boolean = false) = ScanOutcome.Found(PendingBadge(badge()), foundInfo(unknownSigner))

internal class FakeProcessor : BadgeScanProcessor {
    var qrResult: (String) -> ScanOutcome? = { null }
    var nfcResult: (ByteArray) -> ScanOutcome = { ScanOutcome.ReviewDemo }
    var gate: CompletableDeferred<Unit>? = null
    val qrCalls = mutableListOf<Pair<String, String>>()
    val nfcCalls = mutableListOf<ByteArray>()

    override suspend fun processQrText(raw: String, unknownMeetupLabel: String): ScanOutcome? {
        qrCalls += raw to unknownMeetupLabel
        gate?.await()
        return qrResult(raw)
    }

    override suspend fun processQr(scanned: JsonObject, unknownMeetupLabel: String): ScanOutcome = error("unused")

    override suspend fun processNfcPayload(recordPayload: ByteArray, unknownMeetupLabel: String): ScanOutcome {
        nfcCalls += recordPayload
        return nfcResult(recordPayload)
    }
}

internal class FakeSaveUseCase : BadgeSaveUseCase {
    val outcomes = ArrayDeque<SaveOutcome>()
    var confirmError: Exception? = null
    var coAttendanceCount = 2
    val confirmed = mutableListOf<PendingBadge>()
    val published = mutableListOf<MeetupBadge>()

    override val badgeSaved: SharedFlow<BadgeSavedEvent> = MutableSharedFlow()

    override suspend fun confirm(pending: PendingBadge, openedForMeetupLat: Double, openedForMeetupLng: Double): SaveOutcome {
        confirmed += pending
        confirmError?.let { throw it }
        return outcomes.removeFirst()
    }

    var publishGate: CompletableDeferred<Unit>? = null

    override suspend fun publishCoAttendance(badge: MeetupBadge): Int {
        publishGate?.await()
        published += badge
        return coAttendanceCount
    }
}

internal class FakeNfc(var availability: NfcAvailability = NfcAvailability.ENABLED) : NfcStatusSource {
    var settingsOpened = 0
    override fun availability(): NfcAvailability = availability
    override fun openNfcSettings() {
        settingsOpened++
    }
}

internal class FakeTag(
    private val read: NfcReadResult = NfcReadResult.Payload(byteArrayOf(2, 'e'.code.toByte(), 'n'.code.toByte())),
    private val write: NfcWriteResult = NfcWriteResult.Written,
    private val formats: Boolean = false,
) : NfcTagHandle {
    var reads = 0
    val writes = mutableListOf<Pair<ByteArray, Int>>()
    override suspend fun readFirstRecordPayload(): NfcReadResult {
        reads++
        return read
    }

    override suspend fun writeTextRecord(textPayload: ByteArray, actualSize: Int, onFormatting: () -> Unit): NfcWriteResult {
        writes += textPayload to actualSize
        if (formats) onFormatting()
        return write
    }
}

internal object FixedLabels : ScannerLabels {
    override fun unknownMeetup(): String = "Unbekanntes Meetup"
}

internal fun session(name: String = "Würzburg", country: String = "DE", expiresAt: Long, startedAt: Long = expiresAt - 4 * 3600) =
    MeetupSession(
        seed = "00".repeat(32),
        startedAt = startedAt,
        expiresAt = expiresAt,
        meetupId = "wuerzburg-de",
        meetupName = name,
        meetupCountry = country,
        blockHeight = 915_000,
        pubkey = "npub1session",
    )

internal class FakeRollingQrService(var stored: MeetupSession? = null) : RollingQrService {
    var basePayload: JsonObject? = null
    var qrError: Exception? = null
    var generated = 0
    var ended = 0
    override val activeSession: StateFlow<MeetupSession?> = MutableStateFlow(null)

    override suspend fun getOrCreateSession(
        meetupId: String,
        meetupName: String,
        meetupCountry: String,
        blockHeight: Long,
        lat: Double,
        lng: Double,
        validUntilEpoch: Long?,
    ): MeetupSession = error("unused")

    override suspend fun loadSession(): MeetupSession? = stored

    override suspend fun endSession() {
        ended++
        stored = null
    }

    var basePayloadCalls = 0

    override suspend fun getBasePayload(): JsonObject? {
        basePayloadCalls++
        return basePayload
    }

    /** Per-call generation delays (virtual time), consumed in order; empty = instant. */
    val generationDelays = ArrayDeque<Long>()

    override suspend fun generateQrString(session: MeetupSession): String {
        generationDelays.removeFirstOrNull()?.let { kotlinx.coroutines.delay(it) }
        qrError?.let { throw it }
        generated++
        return "qr-$generated"
    }

    override fun validateNonce(payload: Map<String, JsonElement>): NonceValidation = error("unused")

    override fun secondsUntilNextChange(): Int = 10
}

internal class FakeProfiles(var current: UserProfile = UserProfile()) : UserProfileRepository {
    override suspend fun load(): UserProfile = current
    override suspend fun save(profile: UserProfile) {
        current = profile
    }

    override suspend fun update(transform: (UserProfile) -> UserProfile): UserProfile = transform(current).also { current = it }
    override val profile: Flow<UserProfile> get() = MutableStateFlow(current)
}

internal fun meetup(city: String, country: String = "DE", id: String = city.lowercase()) =
    Meetup(id = id, name = city, city = city, country = country, telegramLink = "", lat = 49.8, lng = 9.9)

/** Time that follows a test scheduler: `base + currentTime`. */
internal class SchedulerTime(private val base: Long, private val current: () -> Long) : TimeSource {
    override fun nowMillis(): Long = base + current()
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
 * A dynamic-proxy stub of a data-layer interface: every call goes to [answer] with the JVM
 * method name and arguments; suspend functions return their value directly.
 */
internal inline fun <reified T : Any> stub(noinline answer: (name: String, args: Array<Any?>) -> Any?): T =
    java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when (method.name) {
            "toString" -> "stub(${T::class.java.simpleName})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> answer(method.name, args ?: emptyArray())
        }
    } as T
