plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // Part of the public API (DartJson/JsonElement, StateFlow, CoroutineScope).
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.android)
}

// DartJsonTest reads the shared vectors from spec/fixtures directly; rerun it when those change.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("spec/fixtures"))
        .withPropertyName("specFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
