plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // AppLogger, TimeSource, DartJson, DartDateTime, dartTrim, RelayParseTally, dispatchers.
    implementation(project(":core:common"))
    // Shared KeyValueStore (news_last_read_ts, news_liked_reactions).
    implementation(project(":core:data"))
    // OkHttpClient + getCapped for the RSS feed and LNURL-pay.
    implementation(project(":core:network"))
    // Hex (like `p` tag / zap pubkey validation).
    implementation(project(":core:crypto"))
    // RelayClient, EventGuard, NostrEvent (internal only; no public news API exposes them).
    implementation(project(":core:nostr"))
    // SigningService for reactions (kind 7) and zap requests (kind 9734).
    implementation(project(":data:identity"))
    // LightningAddressLookup (lud16 lookup + shared read targets).
    implementation(project(":data:profile"))

    // JVM unit tests sign events (BIP-340).
    testImplementation(libs.secp256k1.jvm)
    testImplementation(libs.okhttp.mockwebserver)
}
