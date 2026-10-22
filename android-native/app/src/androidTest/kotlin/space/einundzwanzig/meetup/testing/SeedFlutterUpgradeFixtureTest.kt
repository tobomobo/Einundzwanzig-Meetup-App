package space.einundzwanzig.meetup.testing

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Explicit opt-in fixture step executed BEFORE upgrading the real Flutter 1.7.2 debug APK.
 * Keeps its UI-created identity; adds one unsigned legacy badge using its existing FSS AES key.
 * No native application classes are referenced, so this test also runs under the Flutter process.
 * The seed is synthetic spec-14 data and must only be used on the dedicated offline test AVD.
 */
@RunWith(AndroidJUnit4::class)
class SeedFlutterUpgradeFixtureTest {
    @Test fun addSyntheticLegacyBadgeToFlutterWallet() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("seedFlutterFixture") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "space.einundzwanzig.meetup")
        val fixture = JSONObject(instrumentation.context.assets.open("flutter-upgrade-wallet.json")
            .bufferedReader().use { it.readText() })
        val keys = context.getSharedPreferences("FlutterSecureKeyStorage", Context.MODE_PRIVATE)
        val wrappedName = "AESVGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK"
        val wrapped = Base64.decode(checkNotNull(keys.getString(wrappedName, null)), Base64.DEFAULT)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val rsa = ks.getKey(context.packageName + ".FlutterSecureStoragePluginKeyOAEP", null) as PrivateKey
        val unwrap = Cipher.getInstance("RSA/ECB/OAEPPadding", "AndroidKeyStoreBCWorkaround").apply {
            init(Cipher.UNWRAP_MODE, rsa, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        }
        val aes = unwrap.unwrap(wrapped, "AES", Cipher.SECRET_KEY) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aes) }
        val encrypted = cipher.iv + cipher.doFinal(fixture.getString("keyHex").toByteArray())
        val prefix = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIHNlY3VyZSBzdG9yYWdlCg_"
        assertTrue(context.getSharedPreferences("FlutterSecureStorage", Context.MODE_PRIVATE).edit()
            .putString(prefix + "badge_encryption_key", Base64.encodeToString(encrypted, Base64.DEFAULT)).commit())
        assertTrue(context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit()
            .putString("flutter.badges_encrypted", fixture.getString("blob"))
            .putString("flutter.home_meetup", "107")
            .putString("flutter.favorite_meetups", "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu![\"107\"]")
            .putString("flutter.widget_latest_news_id", "upgrade-test-article")
            .putString("flutter.widget_latest_news_title", "Offline upgrade test headline")
            .commit())
        assertTrue(context.getSharedPreferences("HomeWidgetPreferences", Context.MODE_PRIVATE).edit()
            .putString("newsTitle", "Offline upgrade test headline")
            .putBoolean("newsIsNew", true)
            .commit())
    }
}
