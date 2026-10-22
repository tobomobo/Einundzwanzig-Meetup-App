package space.einundzwanzig.meetup.feature.community

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.CommunityHub
import space.einundzwanzig.meetup.core.navigation.CourseDetails
import space.einundzwanzig.meetup.core.navigation.Courses
import space.einundzwanzig.meetup.core.navigation.Glossary
import space.einundzwanzig.meetup.core.navigation.LecturerDetails
import space.einundzwanzig.meetup.core.navigation.PlebrapLibrary
import space.einundzwanzig.meetup.core.navigation.PortalArea
import space.einundzwanzig.meetup.core.navigation.ValueForValue
import space.einundzwanzig.meetup.feature.community.courses.CourseDetailsRoute
import space.einundzwanzig.meetup.feature.community.courses.CoursesRoute
import space.einundzwanzig.meetup.feature.community.courses.LecturerDetailsRoute
import space.einundzwanzig.meetup.feature.community.glossary.GlossaryRoute
import space.einundzwanzig.meetup.feature.community.hub.CommunityHubRoute
import space.einundzwanzig.meetup.feature.community.plebrap.PlebrapRoute
import space.einundzwanzig.meetup.feature.community.portal.PortalAreaRoute
import space.einundzwanzig.meetup.feature.community.v4v.ValueForValueRoute

/** Registers the community destinations (spec 11 §2.5–§2.12) in the app's `entryProvider`. */
fun EntryProviderScope<NavKey>.communityEntries() {
    entry<CommunityHub> { CommunityHubRoute() }
    entry<PortalArea> { PortalAreaRoute() }
    entry<Courses> { CoursesRoute() }
    entry<CourseDetails> { key -> CourseDetailsRoute(key.courseId) }
    entry<LecturerDetails> { key -> LecturerDetailsRoute(key.lecturerId) }
    entry<ValueForValue> { ValueForValueRoute() }
    entry<PlebrapLibrary> { PlebrapRoute() }
    entry<Glossary> { GlossaryRoute() }
}
