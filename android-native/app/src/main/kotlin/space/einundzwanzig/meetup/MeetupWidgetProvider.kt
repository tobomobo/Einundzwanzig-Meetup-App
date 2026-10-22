package space.einundzwanzig.meetup

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import space.einundzwanzig.meetup.widget.MeetupWidget

/**
 * Home-screen widget receiver. The fully qualified name `space.einundzwanzig.meetup.MeetupWidgetProvider`
 * is part of the upgrade contract (spec 14 F2): widgets placed with the Flutter build stay bound to
 * it, so it must never be renamed or moved.
 */
class MeetupWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MeetupWidget()
}
