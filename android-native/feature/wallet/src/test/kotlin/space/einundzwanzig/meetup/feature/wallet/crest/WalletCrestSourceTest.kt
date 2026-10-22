package space.einundzwanzig.meetup.feature.wallet.crest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalLogoRegistry

class WalletCrestSourceTest {

    private class FakeRegistry : PortalLogoRegistry {
        val map = MutableStateFlow<Map<String, String>>(emptyMap())
        var ensureCalls = 0
        override val logos: StateFlow<Map<String, String>> = map
        override fun put(name: String, logo: String) {
            map.value = map.value + (name to logo)
        }
        override fun seedFrom(meetups: List<Meetup>) = Unit
        override suspend fun ensureLoaded() {
            ensureCalls++
        }
        override fun logoFor(titleOrCity: String): String {
            val q = titleOrCity.trim().lowercase()
            return map.value.entries.firstOrNull { (k, _) -> k.lowercase().let { it == q || it.contains(q) || q.contains(it) } }
                ?.let { absoluteImageUrl(it.value) }.orEmpty()
        }
    }

    @Test
    fun `own image url is trimmed and absolutized`() = runTest {
        val source = WalletCrestSource(FakeRegistry(), backgroundScope)
        assertEquals("https://portal.einundzwanzig.space/storage/x.png", source.crestUrl("Ulm", " /storage/x.png ").first())
        assertEquals("https://blossom/x.png", source.crestUrl("Ulm", "https://blossom/x.png").first())
    }

    @Test
    fun `city lookup uses the text before the first comma and follows the registry`() = runTest {
        val registry = FakeRegistry()
        val source = WalletCrestSource(registry, backgroundScope)
        val flow = source.crestUrl("München, DE", null)
        assertNull(flow.first())
        registry.put("Einundzwanzig München", "/logos/muc.png")
        assertEquals("https://portal.einundzwanzig.space/logos/muc.png", flow.first())
    }

    @Test
    fun `empty city gives no crest`() = runTest {
        val source = WalletCrestSource(FakeRegistry(), backgroundScope)
        assertNull(source.crestUrl(", DE", "").first())
        assertEquals("", WalletCrestSource.cityOf(" , x"))
    }

    @Test
    fun `ensureLoaded triggers the registry fetch`() = runTest {
        val registry = FakeRegistry()
        WalletCrestSource(registry, this).ensureLoaded()
        testScheduler.advanceUntilIdle()
        assertEquals(1, registry.ensureCalls)
    }
}
