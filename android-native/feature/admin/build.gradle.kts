plugins {
    id("einundzwanzig.android.feature")
}

dependencies {
    // AdminConstants (super admin npub); exposes :data:identity (SigningService, UserProfileRepository).
    implementation(project(":data:admin"))
    implementation(project(":data:identity"))
    // DartString (Dart-compatible case mapping for the picker sort/filter).
    implementation(project(":data:badges"))
    // RollingQrService (session state), OrganizerSessionService (start), IssuanceFeatures (NFC flag).
    implementation(project(":data:issuance"))
    // MeetupLocationService (GPS + candidates), MeetupService (portal directory), PortalApiService (own meetups).
    implementation(project(":data:meetups"))
}
