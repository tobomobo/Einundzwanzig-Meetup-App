package space.einundzwanzig.meetup.data.calendar.di

import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.data.calendar.CalendarEventService
import space.einundzwanzig.meetup.data.calendar.EventCalendarRepository
import space.einundzwanzig.meetup.data.calendar.EventRsvpService
import space.einundzwanzig.meetup.data.calendar.MeetupCalendarService
import space.einundzwanzig.meetup.data.calendar.MyEventsService
import space.einundzwanzig.meetup.data.calendar.internal.CalendarEventServiceImpl
import space.einundzwanzig.meetup.data.calendar.internal.CalendarHooks
import space.einundzwanzig.meetup.data.calendar.internal.EventCalendarRepositoryImpl
import space.einundzwanzig.meetup.data.calendar.internal.EventRsvpServiceImpl
import space.einundzwanzig.meetup.data.calendar.internal.HomeWidgetStoreImpl
import space.einundzwanzig.meetup.data.calendar.internal.MeetupCalendarServiceImpl
import space.einundzwanzig.meetup.data.calendar.internal.MyEventsServiceImpl
import space.einundzwanzig.meetup.data.calendar.internal.WidgetLaunchTargetStoreImpl
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetRenderer
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore
import space.einundzwanzig.meetup.data.calendar.widget.WidgetLaunchTargetStore
import space.einundzwanzig.meetup.data.meetups.nearby.MeetupEventsSource
import space.einundzwanzig.meetup.data.meetups.portal.PortalLogoLoader

/**
 * Binds the calendar domain as process-wide singletons, plus the two optional hooks of
 * `:data:meetups` (nearby dates, crest loading). [HomeWidgetRenderer] is optional; the app binds
 * it to re-render the Glance widget.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class CalendarModule {

    @Binds
    abstract fun calendarEventService(impl: CalendarEventServiceImpl): CalendarEventService

    @Binds
    abstract fun eventRsvpService(impl: EventRsvpServiceImpl): EventRsvpService

    @Binds
    abstract fun meetupCalendarService(impl: MeetupCalendarServiceImpl): MeetupCalendarService

    @Binds
    abstract fun eventCalendarRepository(impl: EventCalendarRepositoryImpl): EventCalendarRepository

    @Binds
    abstract fun myEventsService(impl: MyEventsServiceImpl): MyEventsService

    @Binds
    abstract fun homeWidgetStore(impl: HomeWidgetStoreImpl): HomeWidgetStore

    @Binds
    abstract fun widgetLaunchTargetStore(impl: WidgetLaunchTargetStoreImpl): WidgetLaunchTargetStore

    @Binds
    abstract fun meetupEventsSource(impl: CalendarHooks): MeetupEventsSource

    @Binds
    abstract fun portalLogoLoader(impl: CalendarHooks): PortalLogoLoader

    @BindsOptionalOf
    abstract fun homeWidgetRenderer(): HomeWidgetRenderer
}
