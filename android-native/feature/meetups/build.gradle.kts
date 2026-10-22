plugins {
    id("einundzwanzig.android.feature")
}

dependencies {
    // MeetupService, MeetupLocationService, NearbyMeetupService, GeocodingService, PortalApiService.
    implementation(project(":data:meetups"))
    // MeetupCalendarService.fetchPortalFirst (meetup details dates).
    implementation(project(":data:calendar"))
    // UserProfileRepository (favorites `favorite_meetups` / `home_meetup`).
    implementation(project(":data:identity"))
    // OSM map composables (nearby map, location picker).
    implementation(project(":core:map"))
}
