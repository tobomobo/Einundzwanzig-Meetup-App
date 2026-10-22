package space.einundzwanzig.meetup

import android.app.Application
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetSnapshot
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore
import space.einundzwanzig.meetup.widget.MeetupWidget
import space.einundzwanzig.meetup.widget.ObservedWidgetContent
import space.einundzwanzig.meetup.widget.rememberWidgetSnapshot
import space.einundzwanzig.meetup.widget.WidgetContent
import java.time.Instant

/** Glance rendering of the home-screen widget (spec 14 §3.2): responsive sizes and live updates. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35], qualifiers = "de")
class WidgetGlanceTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private val snapshot = HomeWidgetSnapshot(
        block = "921.345",
        price = "95.000",
        hashrate = "812 EH/s",
        meetupCity = "Wien",
        newsTitle = "Neuer Artikel",
    )

    private fun render(size: DpSize, assertions: androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest.() -> Unit) =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(size)
            provideComposable { WidgetContent(snapshot) }
            assertions()
        }

    @Test
    fun `small size shows header, block, price row and meetup only`() = render(MeetupWidget.SMALL) {
        onNode(hasText("921.345")).assertExists()
        onNode(hasText("PREIS")).assertExists()
        onNode(hasText("Wien")).assertExists()
        onNode(hasText("HASHRATE")).assertDoesNotExist()
        onNode(hasText("Neuer Artikel")).assertDoesNotExist()
    }

    @Test
    fun `medium size adds the second metrics row but no news`() = render(MeetupWidget.MEDIUM) {
        onNode(hasText("HASHRATE")).assertExists()
        onNode(hasText("812 EH/s")).assertExists()
        onNode(hasText("Neuer Artikel")).assertDoesNotExist()
    }

    @Test
    fun `large size is the full layout with the news row`() = render(MeetupWidget.LARGE) {
        onNode(hasText("HASHRATE")).assertExists()
        onNode(hasText("Neuer Artikel")).assertExists()
    }

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a running session shows values written after the first render`() {
        // The Glance unit-test harness only snapshots the tree on an Idle transition it can miss, so
        // the observation (used by ObservedWidgetContent inside provideContent) is checked in Compose.
        val store = FakeWidgetStore(snapshot)
        compose.setContent {
            val s by rememberWidgetSnapshot(store, snapshot)
            Text("${s.meetupCity}|${s.newsTitle}")
        }
        compose.onNodeWithText("Wien|Neuer Artikel").assertExists()
        compose.runOnIdle { store.state.value = snapshot.copy(meetupCity = "Graz", newsTitle = "Noch ein Artikel") }
        compose.onNodeWithText("Graz|Noch ein Artikel").assertExists()
    }

    @Test
    fun `observed widget content renders the store values in Glance`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(MeetupWidget.LARGE)
        provideComposable { ObservedWidgetContent(FakeWidgetStore(snapshot.copy(meetupCity = "Linz")), snapshot) }
        onNode(hasText("Linz")).assertExists()
    }

    private class FakeWidgetStore(initial: HomeWidgetSnapshot) : HomeWidgetStore {
        val state = MutableStateFlow(initial)
        override suspend fun snapshot(): HomeWidgetSnapshot = state.value
        override fun snapshots(): Flow<HomeWidgetSnapshot> = state
        override suspend fun writeBitcoin(values: Map<String, String>) = Unit
        override suspend fun updateMeetup(city: String, start: Instant?) = Unit
        override suspend fun publishLatestNews(id: String, title: String) = Unit
        override suspend fun markNewsSeen() = Unit
    }
}
