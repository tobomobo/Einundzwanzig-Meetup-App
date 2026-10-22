package space.einundzwanzig.meetup.feature.community.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService

/** The npub of the active identity (SatoshiDuell auto-login link). Narrow seam for tests. */
fun interface NpubSource {
    suspend fun npub(): String?
}

/** The portal course/lecturer endpoints the screens need (subset of [PortalApiService]). */
interface PortalCoursesSource {
    suspend fun getCourses(): List<JsonObject>
    suspend fun getCourse(id: Int): JsonObject?
    suspend fun getLecturers(): List<JsonObject>
    suspend fun getLecturer(id: Int): JsonObject?
}

@Module
@InstallIn(SingletonComponent::class)
internal object CommunityFeatureModule {

    @Provides
    fun npubSource(signing: SigningService): NpubSource = NpubSource { signing.npub() }

    @Provides
    fun portalCoursesSource(api: PortalApiService): PortalCoursesSource = object : PortalCoursesSource {
        override suspend fun getCourses() = api.getCourses()
        override suspend fun getCourse(id: Int) = api.getCourse(id)
        override suspend fun getLecturers() = api.getLecturers()
        override suspend fun getLecturer(id: Int) = api.getLecturer(id)
    }
}
