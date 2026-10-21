plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // MeetupBadge, BadgeIdentity, DartSort, DartString are part of this module's public API
    // (calculator inputs); brings :core:common (DartDateTime, TimeSource) and :core:crypto.
    api(project(":data:badges"))
    implementation(project(":core:common"))
    implementation(project(":core:crypto"))

    // The ported regression test signs real kind 21000/21002 events.
    testImplementation(libs.secp256k1.jvm)
}

// Tests read the shared vectors from spec/fixtures directly; rerun them when those change.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("spec/fixtures"))
        .withPropertyName("specFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
