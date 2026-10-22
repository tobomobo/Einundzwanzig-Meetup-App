package space.einundzwanzig.meetup.testing

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoints
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.crypto.Keys
import space.einundzwanzig.meetup.core.crypto.Schnorr
import space.einundzwanzig.meetup.core.crypto.Sha256
import space.einundzwanzig.meetup.core.crypto.hexToBytes
import space.einundzwanzig.meetup.data.migration.MigrationState

/** Read-only assertions after adb install -r over a real Flutter APK. No relay events are published. */
@RunWith(AndroidJUnit4::class)
class UpgradeDeviceTest {
    @Test fun flutterIdentityAndCollectionSurviveUpgradeAndCanStillSign() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val expectedNpub = args.getString("expectedNpub")
        assumeTrue("Run with the upgrade harness and its expected public identity", expectedNpub != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPoints.get(context.applicationContext, DeviceParityEntryPoint::class.java)
        withTimeout(60_000) { entry.migration().gate.await() }
        val report = entry.migration().currentReport()
        assertTrue("Migration state: ${report.state}, failed key names: ${report.failedKeys}",
            report.state == MigrationState.IMPORTED || report.state == MigrationState.IMPORTED_WITH_ERRORS)
        assertEquals(emptyList<String>(), report.failedKeys)
        assertEquals(expectedNpub, entry.keys().getNpub())
        assertEquals(expectedNpub, entry.signing().npub())
        val profile = entry.profiles().load()
        args.getString("expectedNickname")?.let { assertEquals(it, profile.nickname) }
        args.getString("expectedHomeMeetup")?.let { assertEquals(it, profile.homeMeetupId) }
        args.getString("expectedBadgeCount")?.let {
            assertEquals(it.toInt(), entry.badges().loadBadges().size)
        }
        assertFalse(entry.badges().badgesUnreadable.value)
        assertEquals(listOf("107"), profile.favoriteMeetupIds)
        assertEquals("Offline upgrade test headline", context.getSharedPreferences("HomeWidgetPreferences", Context.MODE_PRIVATE)
            .getString("newsTitle", null))
        assertEquals("upgrade-test-article", entry.preferences().getString("widget_latest_news_id"))
        assertTrue(entry.signing().canSign())
        val priv = checkNotNull(entry.keys().getPrivHex())
        val message = Sha256.digest("upgrade-local-signing-check".toByteArray())
        val signature = Schnorr.sign(message, priv.hexToBytes())
        assertTrue(Schnorr.verify(signature, message, Keys.xOnlyPublicKey(priv.hexToBytes())))
        assertEquals(emptySet<String>(), entry.migration().pendingLegacySecrets())
    }
}
