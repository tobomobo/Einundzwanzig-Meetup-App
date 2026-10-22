package space.einundzwanzig.meetup.widget

import android.content.Context
import android.content.Intent
import androidx.annotation.ColorRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.R
import space.einundzwanzig.meetup.WidgetRouterActivity
import space.einundzwanzig.meetup.core.navigation.DeepLinks
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetSnapshot
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore
import space.einundzwanzig.meetup.data.calendar.widget.WidgetFormat
import space.einundzwanzig.meetup.data.migration.MigrationGate
import java.time.LocalDate
import java.time.ZoneId

/**
 * The home-screen widget (spec 14 §3.2): Glance replacement of the Flutter `RemoteViews` layout
 * (§3.1.2), fed by the unchanged `HomeWidgetPreferences` file, so the last values show right after
 * the update. Always dark like the Flutter widget; system font (Glance cannot use custom fonts).
 */
class MeetupWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun homeWidgetStore(): HomeWidgetStore
        fun migrationGate(): MigrationGate
    }

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        // A widget update can start the process before any activity: wait for the Flutter import.
        entry.migrationGate().await()
        val store = entry.homeWidgetStore()
        val initial = runCatching { store.snapshot() }.getOrDefault(HomeWidgetSnapshot())
        provideContent { ObservedWidgetContent(store, initial) }
    }

    companion object {
        val SMALL = DpSize(250.dp, 110.dp)
        val MEDIUM = DpSize(250.dp, 220.dp)
        val LARGE = DpSize(300.dp, 300.dp)
    }
}

/**
 * Glance calls `provideGlance` once per session; `updateAll()` during a running session only
 * recomposes, so the values are observed here instead of captured once.
 */
@Composable
internal fun ObservedWidgetContent(store: HomeWidgetStore, initial: HomeWidgetSnapshot) {
    val snapshot by rememberWidgetSnapshot(store, initial)
    WidgetContent(snapshot)
}

/** The live widget values of [store], starting with [initial] (plain Compose, testable without Glance). */
@Composable
internal fun rememberWidgetSnapshot(store: HomeWidgetStore, initial: HomeWidgetSnapshot): State<HomeWidgetSnapshot> =
    remember(store) { widgetSnapshots(store) }.collectAsState(initial)

/** The store's snapshots; a failing read keeps the last rendered values (logged, never fatal). */
internal fun widgetSnapshots(store: HomeWidgetStore): Flow<HomeWidgetSnapshot> =
    store.snapshots().catch { AppLogger.warn("Widget", "Widget-Daten nicht lesbar", it) }

/** Router intent of one tap area (spec 14 §3.1.5): explicit, distinct action + data per target. */
internal fun widgetIntent(context: Context, target: String): Intent =
    Intent(context, WidgetRouterActivity::class.java)
        .setAction(DeepLinks.widgetAction(target))
        .setData(DeepLinks.widgetUri(target).toUri())
        .putExtra(DeepLinks.EXTRA_OPEN, target)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun open(context: Context, target: String): Action = actionStartActivity(widgetIntent(context, target))

/**
 * Countdown text: computed at render time from `meetupStartEpochMs` (never stale after midnight),
 * else the stored Flutter-era string (first render after the upgrade).
 */
internal fun countdownText(
    context: Context,
    snapshot: HomeWidgetSnapshot,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (!snapshot.hasMeetup) return context.getString(R.string.native_widget_choose_in_app)
    val days = snapshot.meetupDaysFrom(today, zone) ?: return snapshot.meetupCountdown
    return when (val c = WidgetFormat.countdown(days)) {
        WidgetFormat.Countdown.Today -> context.getString(R.string.native_widget_today)
        WidgetFormat.Countdown.Tomorrow -> context.getString(R.string.native_widget_tomorrow)
        is WidgetFormat.Countdown.InDays -> context.resources.getQuantityString(R.plurals.native_widget_in_days, c.days.toInt(), c.days.toInt())
    }
}

@Composable
internal fun WidgetContent(s: HomeWidgetSnapshot) {
    val context = LocalContext.current
    val size = LocalSize.current
    val showSecondRow = size.height >= MeetupWidget.MEDIUM.height
    val showNews = size.height >= MeetupWidget.LARGE.height || size.width >= MeetupWidget.LARGE.width && showSecondRow
    val orange = colorRes(context, R.color.widget_orange)
    val grey = colorRes(context, R.color.widget_grey)
    val white = colorRes(context, R.color.widget_text)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_bg))
            .padding(16.dp),
    ) {
        // 1. Header: logo · "Stand HH:mm" · refresh
        Row(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(R.drawable.widget_logo),
                contentDescription = context.getString(R.string.native_widget_logo),
                contentScale = ContentScale.Fit,
                modifier = GlanceModifier.defaultWeight().height(18.dp).clickable(open(context, DeepLinks.TARGET_HOME)),
            )
            if (s.updated.isNotEmpty()) {
                Text(
                    context.getString(R.string.native_widget_updated, s.updated),
                    style = TextStyle(color = grey, fontSize = 10.sp),
                    modifier = GlanceModifier.padding(end = 6.dp),
                )
            }
            Box(
                modifier = GlanceModifier
                    .padding(start = 14.dp, top = 4.dp, end = 6.dp, bottom = 4.dp)
                    .semantics { contentDescription = context.getString(R.string.native_widget_refresh) }
                    .clickable(actionRunCallback<RefreshWidgetAction>()),
            ) {
                Text("⟳", style = TextStyle(color = orange, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            }
        }

        // 2. Bitcoin area
        Column(modifier = GlanceModifier.fillMaxWidth().clickable(open(context, DeepLinks.TARGET_BITCOIN))) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.getString(R.string.native_widget_bitcoin),
                    style = TextStyle(color = orange, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(
                    context.getString(R.string.native_widget_block),
                    style = TextStyle(color = grey, fontSize = 9.sp),
                    modifier = GlanceModifier.padding(end = 8.dp),
                )
                Text(s.block, style = TextStyle(color = orange, fontSize = 30.sp, fontWeight = FontWeight.Bold))
            }
            // Divider margins 10/12 (spec 14 §3.1.2).
            Spacer(GlanceModifier.height(10.dp))
            Box(GlanceModifier.fillMaxWidth().height(1.dp).background(colorRes(context, R.color.widget_divider))) {}
            Spacer(GlanceModifier.height(12.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Metric(context.getString(R.string.native_widget_price), s.priceText, 15.sp, grey, white)
                Metric(context.getString(R.string.native_widget_moscow), s.moscow, 15.sp, grey, white)
                Metric(context.getString(R.string.native_widget_fees), s.fees, 15.sp, grey, white)
            }
            if (showSecondRow) {
                Spacer(GlanceModifier.height(14.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Metric(context.getString(R.string.native_widget_hashrate), s.hashrate, 13.sp, grey, white)
                    Metric(context.getString(R.string.native_widget_supply), s.supply, 13.sp, grey, white)
                    Metric(context.getString(R.string.native_widget_difficulty), s.difficulty, 13.sp, grey, white)
                }
            }
        }
        // Each metrics row has margin-bottom 14 (spec 14 §3.1.2).
        Spacer(GlanceModifier.height(14.dp))

        // 3. Meetup area
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ImageProvider(R.drawable.widget_meetup_bg))
                .padding(12.dp)
                .clickable(open(context, DeepLinks.TARGET_MEETUP)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    context.getString(R.string.native_widget_next_meetup),
                    style = TextStyle(color = orange, fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.padding(bottom = 2.dp),
                )
                Text(
                    if (s.hasMeetup) s.meetupCity else context.getString(R.string.native_widget_no_meetup),
                    style = TextStyle(color = white, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
            }
            Text(
                countdownText(context, s),
                style = TextStyle(color = orange, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End),
            )
        }

        // 4. News row
        if (showNews) {
            // margin-top 10 + padding-top 10 / padding-bottom 4 (spec 14 §3.1.2).
            Spacer(GlanceModifier.height(10.dp))
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 4.dp)
                    .clickable(open(context, DeepLinks.TARGET_NEWS)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    context.getString(R.string.native_widget_news),
                    style = TextStyle(color = orange, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.padding(end = 8.dp),
                )
                if (s.newsIsNew) {
                    Box(
                        modifier = GlanceModifier
                            .background(ImageProvider(R.drawable.widget_new_badge))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            context.getString(R.string.native_widget_news_new),
                            style = TextStyle(color = colorRes(context, R.color.widget_badge_text), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        )
                    }
                    Spacer(GlanceModifier.width(8.dp))
                }
                Text(
                    s.newsTitle,
                    style = TextStyle(color = colorRes(context, R.color.widget_news_text), fontSize = 12.sp),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text("›", style = TextStyle(color = grey, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

/** Widget colours come from `res/values/colors.xml` (fixed dark palette of the Flutter widget). */
private fun colorRes(context: Context, @ColorRes id: Int): ColorProvider =
    ColorProvider(Color(ContextCompat.getColor(context, id)))

@Composable
private fun androidx.glance.layout.RowScope.Metric(
    label: String,
    value: String,
    valueSize: TextUnit,
    labelColor: ColorProvider,
    valueColor: ColorProvider,
) {
    Column(modifier = GlanceModifier.defaultWeight()) {
        Text(label, style = TextStyle(color = labelColor, fontSize = 9.sp), modifier = GlanceModifier.padding(bottom = 3.dp))
        Text(value, style = TextStyle(color = valueColor, fontSize = valueSize, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}
