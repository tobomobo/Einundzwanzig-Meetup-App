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
    // NostrProfileSource (npub chips of co-leaders) is provided by the app shell from :data:profile.
    implementation(project(":data:profile"))
    // OSM map composables (nearby map, location picker).
    implementation(project(":core:map"))

    testImplementation(libs.androidx.compose.ui.test.junit4)
}
