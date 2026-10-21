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
    // SigningService (kind 22242 portal login, mode-aware npub); brings :core:crypto (Nip19).
    implementation(project(":data:identity"))
    // LocationManagerCompat (no Google Play services dependency, spec 09 §8.5 / §14 item 20).
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.secp256k1.jvm)
}
