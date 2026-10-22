plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // AppLogger, TimeSource, DartJson, SafeUrl, @ApplicationScope.
    implementation(project(":core:common"))
    // Shared KeyValueStore (profile caches, display names, local picture path).
    implementation(project(":core:data"))
    // OkHttpClient + executeCapped for the Blossom upload.
    implementation(project(":core:network"))
    // RelayClient, RelayConfig, ProfileRelayQuery, EventGuard, NostrEvent (+ :core:crypto as api).
    api(project(":core:nostr"))
    // SigningService for the Blossom auth event and the active identity of avatars.
    implementation(project(":data:identity"))
    // NostrProfileSource seam implemented by this module for NostrAvatar / NpubChip.
    implementation(project(":core:ui"))

    // JVM unit tests sign events (BIP-340).
    testImplementation(libs.secp256k1.jvm)
    testImplementation(libs.okhttp.mockwebserver)
}
