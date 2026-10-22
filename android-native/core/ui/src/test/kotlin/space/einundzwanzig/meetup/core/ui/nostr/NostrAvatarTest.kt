package space.einundzwanzig.meetup.core.ui.nostr

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

/**
 * Port of `test/nostr_avatar_test.dart` (spec 07 §10.8): image downloads never complete
 * unless the test says so; metadata lookups are fake loaders.
 */
@RunWith(AndroidJUnit4::class)
class NostrAvatarTest {

    @get:Rule
    val rule = createComposeRule()

    /** Holds every image request until the test completes it (true = success). */
    private class HoldingInterceptor : Interceptor {
        val downloads = mutableListOf<CompletableDeferred<Boolean>>()

        @OptIn(ExperimentalCoilApi::class)
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val download = CompletableDeferred<Boolean>()
            downloads += download
            return if (download.await()) {
                SuccessResult(image = ColorImage(0xFFFF0000.toInt(), 8, 8), request = chain.request, dataSource = DataSource.NETWORK)
            } else {
                ErrorResult(image = null, request = chain.request, throwable = IllegalStateException("image unavailable"))
            }
        }
    }

    private val images = HoldingInterceptor()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @OptIn(DelicateCoilApi::class)
    @Before
    fun setUp() {
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components { add(images) }.build(),
        )
    }

    @OptIn(DelicateCoilApi::class)
    @After
    fun tearDown() {
        SingletonImageLoader.reset()
    }

    private fun url(): String? =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(AvatarPictureKey)).fetchSemanticsNodes().singleOrNull()?.config?.get(AvatarPictureKey)

    @Test
    fun `late result from previous identity cannot replace current picture`() {
        val a = CompletableDeferred<String?>()
        val b = CompletableDeferred<String?>()
        val loader: suspend (String) -> String? = { author -> if (author == "alice") a.await() else b.await() }
        var author by mutableStateOf("alice")
        rule.setContent { EinundzwanzigTheme { NostrAvatar(fallbackText = "Alice", pubkeyHex = author, pictureLoader = loader) } }
        rule.waitForIdle()
        author = "bob"
        rule.waitForIdle()
        b.complete("https://images.example/bob.png")
        rule.waitForIdle()
        assertEquals("https://images.example/bob.png", url())
        a.complete("https://images.example/alice.png")
        rule.waitForIdle()
        assertEquals("https://images.example/bob.png", url())
    }

    @Test
    fun `identity switch immediately clears old picture, absent metadata uses letter`() {
        val b = CompletableDeferred<String?>()
        val loader: suspend (String) -> String? = { author ->
            if (author == "alice") "https://images.example/alice.png" else b.await()
        }
        var author by mutableStateOf("alice")
        rule.setContent { EinundzwanzigTheme { NostrAvatar(fallbackText = "Alice", pubkeyHex = author, pictureLoader = loader) } }
        rule.waitForIdle()
        assertNotNull(url())
        author = "bob"
        rule.waitForIdle()
        assertNull(url())
        rule.onNodeWithText("A").assertExists()
        b.complete(null)
        rule.waitForIdle()
        assertNull(url())
    }

    @Test
    fun `failed image download restores letter fallback`() {
        rule.setContent {
            EinundzwanzigTheme {
                NostrAvatar(fallbackText = "Alice", pubkeyHex = "alice", pictureLoader = { "https://images.example/fails.png" })
            }
        }
        rule.waitUntil(5_000) { images.downloads.isNotEmpty() }
        assertNotNull(url())
        rule.onNodeWithText("A").assertDoesNotExist()
        images.downloads.single().complete(false)
        rule.waitForIdle()
        assertNull(url())
        rule.onNodeWithText("A").assertExists()
    }

    @Test
    fun `signer without local private key resolves public identity and loads avatar`() {
        val author = "fa5e3477d2d6b92d667dcab66c8bbb1527014599c5eecddd545e3a39d7870268"
        val publicOnly = object : NostrProfileSource {
            override suspend fun activePubkeyHex(): String = author
        }
        var queried: String? = null
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalNostrProfileSource provides publicOnly) {
                    NostrAvatar(
                        fallbackText = "Alice",
                        pictureLoader = { hex ->
                            queried = hex
                            "https://images.example/external.png"
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
        assertEquals(author, queried)
        assertEquals("https://images.example/external.png", url())
    }

    @Test
    fun `completion after avatar disposal is harmless`() {
        val pending = CompletableDeferred<String?>()
        var show by mutableStateOf(true)
        rule.setContent {
            EinundzwanzigTheme {
                if (show) NostrAvatar(fallbackText = "Alice", pubkeyHex = "alice", pictureLoader = { pending.await() })
            }
        }
        rule.waitForIdle()
        show = false
        rule.waitForIdle()
        pending.complete("https://images.example/a.png")
        rule.waitForIdle()
    }

    @Test
    fun `empty fallback text shows a question mark`() {
        rule.setContent { EinundzwanzigTheme { NostrAvatar(fallbackText = "") } }
        rule.onNodeWithText("?").assertExists()
    }

    // ---- NpubChip ----

    private val npub = "npub1dzu6ceugmm7xqs2drhwl5jdyhwrhc3ezgr0w6tmmvqzjmn5ac0ysl4qd9z"

    @Test
    fun `npub chip shows start and end without a name`() {
        rule.setContent { EinundzwanzigTheme { NpubChip(npub) } }
        rule.onNodeWithText("npub1dzu6c…ysl4qd9z").assertExists()
    }

    @Test
    fun `npub chip with name copies the full npub and confirms`() {
        val host = SnackbarHostState()
        var opened: String? = null
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalAppSnackbarHostState provides host) {
                    NpubChip(" $npub ", displayNameLoader = { "  Satoshi " }, onOpenProfile = { opened = it })
                    AppSnackbarHost(host)
                }
            }
        }
        rule.onNodeWithText("Satoshi").assertExists().performClick()
        assertEquals(npub, opened)
        rule.onNodeWithContentDescription("npub kopieren").performClick()
        rule.waitForIdle()
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        assertEquals(npub, clip?.getItemAt(0)?.text?.toString())
        rule.onNodeWithText("npub kopiert").assertExists()
        rule.mainClock.advanceTimeBy(2_500)
        rule.onNodeWithText("npub kopiert").assertDoesNotExist()
    }

    @Test
    fun `blank npub renders nothing`() {
        rule.setContent { EinundzwanzigTheme { NpubChip("   ") } }
        rule.onNodeWithText("npub", substring = true).assertDoesNotExist()
    }
}
