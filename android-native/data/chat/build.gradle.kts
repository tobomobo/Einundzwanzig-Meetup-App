plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // AppLogger, TimeSource, DartJson, dispatchers.
    implementation(project(":core:common"))
    // Shared KeyValueStore (read marks `chat_last_read`).
    implementation(project(":core:data"))
    // RelayClient, RelayAuthenticator, EventGuard, NostrEvent (+ :core:crypto as api).
    api(project(":core:nostr"))
    // SigningService (AUTH, kinds 9/9021/9022/1111), SigningMode for the AUTH deadline.
    implementation(project(":data:identity"))
    // LightningAddressLookup.readTargets() = the shared news/event read targets.
    implementation(project(":data:profile"))

    // JVM unit tests sign and verify events (BIP-340).
    testImplementation(libs.secp256k1.jvm)
}
