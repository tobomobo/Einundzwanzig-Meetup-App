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
    // DartString.lower: the canonical table-based Dart toLowerCase (binding resolution #12) for
    // slugify / city and slug matching. Move to :core:common once it lives there.
    implementation(project(":data:badges"))

    // JVM unit tests sign and verify events (BIP-340).
    testImplementation(libs.secp256k1.jvm)
}
