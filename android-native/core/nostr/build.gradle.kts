plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // DartJson, AppLogger, TimeSource, RelayParseTally.
    api(project(":core:common"))
    // SignedNostrEvent / EventIds are part of this module's public API (NostrEvent typealias).
    api(project(":core:crypto"))
    // KeyValueStore for the relay configuration (custom_relays, disabled_default_relays).
    implementation(project(":core:data"))
    // @WebSocketClient OkHttpClient; OkHttp types appear in the public constructors of the clients.
    api(project(":core:network"))

    // The Android AAR only ships device ABIs; JVM unit tests load the host library from here.
    testImplementation(libs.secp256k1.jvm)
    testImplementation(libs.okhttp.mockwebserver)
}

// Tests read the shared vectors from spec/fixtures directly; rerun them when those change.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("spec/fixtures"))
        .withPropertyName("specFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
