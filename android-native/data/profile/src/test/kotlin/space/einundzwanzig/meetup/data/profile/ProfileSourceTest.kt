package space.einundzwanzig.meetup.data.profile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.profile.internal.ProfileNostrProfileSource

/** The `:core:ui` avatar / npub-chip adapter (spec 07 §2.3, §2.4, §10.8 public identity case). */
class ProfileSourceTest {

    private val asked = mutableListOf<String>()

    private val profiles = object : NostrProfileService {
        override suspend fun fetchProfilePicture(pubkeyHex: String): String? {
            asked += "pic:$pubkeyHex"
            return "https://images.example/$pubkeyHex.png"
        }

        override suspend fun fetchDisplayName(pubkeyHex: String): String? {
            asked += "name:$pubkeyHex"
            return if (pubkeyHex.isEmpty()) null else "Name"
        }

        override suspend fun cachedDisplayNames(): Map<String, String> = emptyMap()
        override suspend fun fetchDisplayNames(pubkeysHex: Collection<String>): Map<String, String> = emptyMap()
        override suspend fun setLocalPicture(path: String) = Unit
        override suspend fun getLocalPicture(): String? = null
        override suspend fun getExistingLocalPicture(): String? = null
        override fun observeLocalPicture(): Flow<String?> = emptyFlow()
        override suspend fun clearCache() = Unit
    }

    @Test
    fun `active identity is the signer npub decoded, also for public-only identities`() = runTest {
        val hex = "fa5e3477d2d6b92d667dcab66c8bbb1527014599c5eecddd545e3a39d7870268"
        val source = ProfileNostrProfileSource({ Nip19.encodeNpub(hex) }, profiles)
        assertEquals(hex, source.activePubkeyHex())
        assertEquals("https://images.example/$hex.png", source.pictureUrl(hex))
    }

    @Test
    fun `no identity, invalid npub or wrong HRP give no active pubkey`() = runTest {
        assertNull(ProfileNostrProfileSource({ null }, profiles).activePubkeyHex())
        assertNull(ProfileNostrProfileSource({ "" }, profiles).activePubkeyHex())
        assertNull(ProfileNostrProfileSource({ "npub1invalid" }, profiles).activePubkeyHex())
        assertNull(ProfileNostrProfileSource({ Nip19.encodeNsec(SECRET1) }, profiles).activePubkeyHex())
    }

    @Test
    fun `display name decodes the npub, failures skip the lookup`() = runTest {
        val source = ProfileNostrProfileSource({ null }, profiles)
        val hex = pubOf(SECRET1)
        assertEquals("Name", source.displayNameForNpub(" ${Nip19.encodeNpub(hex)} "))
        assertNull(source.displayNameForNpub("garbage"))
        // Wrong HRP decodes to "" (Dart quirk) → fetchDisplayName("") → null.
        assertNull(source.displayNameForNpub(Nip19.encodeNsec(SECRET1)))
        assertEquals(listOf("name:$hex", "name:"), asked)
    }
}
