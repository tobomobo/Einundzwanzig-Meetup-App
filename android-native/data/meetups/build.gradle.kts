plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // AppLogger, TimeSource, dispatchers, DartJson, DartUri, DartDateTime.
    api(project(":core:common"))
    // KeyValueStore (leader cache, RSVP cache) and SecureStore (portal token) stay internal.
    implementation(project(":core:data"))
    // OkHttp helpers + the shared client; JsonObject/JsonElement appear in the public API.
    implementation(project(":core:network"))
    api(libs.kotlinx.serialization.json)
    // DartString (canonical Dart case table, spec 00 item 12) so bySlug equals MeetupKey.slug
    // byte for byte. Switch to :core:common once DartString moves there; no other badge API used.
    implementation(project(":data:badges"))
    // SigningService (kind 22242 portal login, mode-aware npub); brings :core:crypto (Nip19).
    implementation(project(":data:identity"))
    // LocationManagerCompat (no Google Play services dependency, spec 09 §8.5 / §14 item 20).
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.secp256k1.jvm)
}

// Opt-in live portal test (Dart `--dart-define=LIVE_PORTAL_TEST=true`): run with
// `LIVE_PORTAL_TEST=1 tools/gradlew-locked :data:meetups:testDebugUnitTest` or `-PlivePortalTest`.
tasks.withType<Test>().configureEach {
    val live = providers.environmentVariable("LIVE_PORTAL_TEST")
        .orElse(providers.gradleProperty("livePortalTest"))
        .orNull
    if (live != null) {
        systemProperty("LIVE_PORTAL_TEST", live)
        outputs.upToDateWhen { false }
    }
}
