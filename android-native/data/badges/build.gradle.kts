plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // DartDateTime, DartJson, TimeSource, AppLogger are part of this module's public API.
    api(project(":core:common"))
    // KeyValueStore / SecureStore: BadgeRepository.create(...) takes them (tests of other modules).
    api(project(":core:data"))
    // SigningService (BadgeSigner adapter), AppReviewDemo, ReviewDemoBadgeWriter; brings
    // :core:nostr (NostrEvent) and :core:crypto as api.
    api(project(":data:identity"))

    // JVM unit tests reach secp256k1 (signing / verifying badge and claim events).
    testImplementation(libs.secp256k1.jvm)
}

// Tests read the shared vectors from spec/fixtures directly; rerun them when those change.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("spec/fixtures"))
        .withPropertyName("specFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
