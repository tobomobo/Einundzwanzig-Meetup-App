package space.einundzwanzig.meetup.feature.events.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.data.chat.ChatRoom
import space.einundzwanzig.meetup.data.chat.ChatService
import space.einundzwanzig.meetup.data.chat.EventChatService
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.issuance.session.RollingQrService
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.portal.Attendee
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import space.einundzwanzig.meetup.data.meetups.portal.PortalLogoRegistry
import space.einundzwanzig.meetup.data.meetups.portal.PortalResult
import space.einundzwanzig.meetup.data.profile.NostrProfileService
import java.io.File
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Narrow seams over the large data-layer facades, so the ViewModels can be tested with small
 * fakes. The implementations only delegate; no data logic lives here.
 */

/** The own identity and profile names. */
interface EventsIdentity {
    /** `SigningService.pubkeyHex()`, `null` without identity or when the store is unreadable. */
    suspend fun pubkeyHex(): String?

    /** Batch display-name prefetch for npub chips (`NostrProfileService.fetchDisplayNames`). */
    suspend fun prefetchDisplayNames(hexes: Collection<String>)
}

/** The portal RSVP calls of the meetup dates list (spec 10 §2.2, §5.1). */
interface EventsPortal {
    suspend fun tokenMatchesCurrentKey(): Boolean
    suspend fun loginWithNostr(): PortalResult
    suspend fun rsvp(eventId: Int, status: String): PortalResult
    suspend fun getRsvpCached(eventId: Int, forceRefresh: Boolean = false): JsonObject?
    suspend fun getRsvpAttendees(eventId: Int): List<Attendee>
    fun isGoing(r: JsonObject?): Boolean
}

/** Chat lookups of "Meine Termine" (spec 10 §2.5, spec 12 §8.6). */
interface EventsChat {
    /**
     * Home `_findRoomFor(favKey)`: room by resolved portal id, else by city. Throws
     * `ChatRelayUnavailable` (no fallback to the city on relay failure).
     */
    suspend fun findMeetupRoom(favKey: String): ChatRoom?

    /** Unread comment counts per event address. */
    suspend fun eventUnreadCounts(addresses: List<String>): Map<String, Int>
}

/** The running badge session (`RollingQRService.loadSession`). */
fun interface EventsSessions {
    /** `meetupId` of the running, non-expired session, else `null`. */
    suspend fun runningSessionMeetupId(): String?
}

/** Re-encodes a gallery pick for the Blossom upload (≤ 1200 px, JPEG q85; spec 10 §2.4). */
fun interface BadgeImagePreparer {
    /** [uri] = content URI string of the picked image; returns a JPEG in the cache dir. */
    suspend fun prepare(uri: String): File
}

/** Crest of a meetup row of the event calendar (`MeetupCalendarService.logoFor(title)`). */
fun interface EventCrests {
    /** Absolute logo URL or `""`. */
    fun logoFor(title: String): String
}

/** Device time zone (overridable in tests). */
fun interface ZoneProvider {
    fun zone(): ZoneId
}

internal class DefaultEventsIdentity @Inject constructor(
    private val signing: SigningService,
    private val profiles: NostrProfileService,
) : EventsIdentity {
    override suspend fun pubkeyHex(): String? = try {
        signing.pubkeyHex()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override suspend fun prefetchDisplayNames(hexes: Collection<String>) {
        if (hexes.isEmpty()) return
        runCatching { profiles.fetchDisplayNames(hexes) }
    }
}

internal class DefaultEventsPortal @Inject constructor(private val api: PortalApiService) : EventsPortal {
    override suspend fun tokenMatchesCurrentKey(): Boolean = api.tokenMatchesCurrentKey()
    override suspend fun loginWithNostr(): PortalResult = api.loginWithNostr()
    override suspend fun rsvp(eventId: Int, status: String): PortalResult = api.rsvp(eventId, status)
    override suspend fun getRsvpCached(eventId: Int, forceRefresh: Boolean): JsonObject? =
        api.getRsvpCached(eventId, forceRefresh)
    override suspend fun getRsvpAttendees(eventId: Int): List<Attendee> = api.getRsvpAttendees(eventId)
    override fun isGoing(r: JsonObject?): Boolean = api.isGoing(r)
}

internal class DefaultEventsChat @Inject constructor(
    private val chat: ChatService,
    private val eventChat: EventChatService,
    private val meetups: MeetupService,
) : EventsChat {
    override suspend fun findMeetupRoom(favKey: String): ChatRoom? {
        val meetup = meetups.resolveFavorite(favKey)
        if (meetup != null) chat.findRoomForMeetupId(meetup.id)?.let { return it }
        return chat.findRoomForCity(meetups.cityFor(favKey))
    }

    override suspend fun eventUnreadCounts(addresses: List<String>): Map<String, Int> =
        if (addresses.isEmpty()) emptyMap() else eventChat.unreadCounts(addresses)
}

internal class DefaultEventsSessions @Inject constructor(private val rollingQr: RollingQrService) : EventsSessions {
    override suspend fun runningSessionMeetupId(): String? = runCatching { rollingQr.loadSession()?.meetupId }
        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        .getOrNull()
}

internal class DefaultEventCrests @Inject constructor(private val registry: PortalLogoRegistry) : EventCrests {
    override fun logoFor(title: String): String = registry.logoFor(title)
}

internal class DefaultBadgeImagePreparer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : BadgeImagePreparer {
    override suspend fun prepare(uri: String): File = withContext(io) {
        prepareUri(Uri.parse(uri))
    }

    private fun prepareUri(uri: Uri): File {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= MAX_PX) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Bild konnte nicht gelesen werden")
        val scale = MAX_PX.toFloat() / max(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1),
                true,
            ).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        val dir = File(context.cacheDir, "badge_upload").apply { mkdirs() }
        val out = File(dir, "badge_${System.nanoTime()}.jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        bitmap.recycle()
        return out
    }

    private companion object {
        const val MAX_PX = 1200
        const val QUALITY = 85
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class EventsGatewayModule {
    @Binds abstract fun identity(impl: DefaultEventsIdentity): EventsIdentity

    @Binds abstract fun portal(impl: DefaultEventsPortal): EventsPortal

    @Binds abstract fun chat(impl: DefaultEventsChat): EventsChat

    @Binds abstract fun sessions(impl: DefaultEventsSessions): EventsSessions

    @Binds abstract fun crests(impl: DefaultEventCrests): EventCrests

    @Binds abstract fun imagePreparer(impl: DefaultBadgeImagePreparer): BadgeImagePreparer

    companion object {
        @dagger.Provides
        @Singleton
        fun zoneProvider(): ZoneProvider = ZoneProvider { ZoneId.systemDefault() }
    }
}
