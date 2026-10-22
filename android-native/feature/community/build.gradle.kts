plugins {
    id("einundzwanzig.android.feature")
}

dependencies {
    // V4V, PlebRap player, glossary data, portal field helpers + PortalEntityCache.
    implementation(project(":data:community"))
    // SatoshiDuell status for the hub card.
    implementation(project(":data:proofs"))
    // PortalApiService (courses / lecturers).
    implementation(project(":data:meetups"))
    // SigningService.npub() for the SatoshiDuell auto-login link.
    implementation(project(":data:identity"))
}
