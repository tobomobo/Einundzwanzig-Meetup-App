package space.einundzwanzig.meetup.feature.reputation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.feature.reputation.common.openNostr

@RunWith(AndroidJUnit4::class)
class NostrFallbackTest {
    @Test
    fun `missing or blocked nostr handlers both open the web profile`() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        for (failure in listOf(ActivityNotFoundException("Missing"), SecurityException("Blocked"))) {
            val attempts = mutableListOf<String>()
            val context = object : ContextWrapper(app) {
                override fun startActivity(intent: Intent) {
                    attempts += intent.dataString.orEmpty()
                    if (intent.data?.scheme == "nostr") throw failure
                }
            }
            openNostr(context, "npub1profile")
            assertEquals(listOf("nostr:npub1profile", "https://njump.me/npub1profile"), attempts)
        }
    }
}
