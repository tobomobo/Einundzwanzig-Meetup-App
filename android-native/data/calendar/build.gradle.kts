plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // TimeSource, DiagnosticLog, DartJson, DartDateTime, toStringAsFixed.
    api(project(":core:common"))
    // KeyValueStore (widget news ids) stays internal.
    implementation(project(":core:data"))
    // Shared OkHttpClient + capped HTTP helpers (ICS feed).
    implementation(project(":core:network"))
    // SigningService (NIP-52 events, RSVPs, cancellation); brings :core:nostr and :core:crypto as api.
    api(project(":data:identity"))
    // Meetup model, MeetupService, PortalApiService, PortalLogoRegistry, MeetupEventsSource hooks.
    api(project(":data:meetups"))

    // JVM unit tests sign and verify calendar events (secp256k1).
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.secp256k1.jvm)
}
