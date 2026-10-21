plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // Public API: DartDateTime, DiagnosticLog, JsonObject (kotlinx via :core:common). Also AppLogger,
    // TimeSource, DartJson, @ApplicationScope, @DefaultDispatcher.
    api(project(":core:common"))
    // Shared KeyValueStore (social_my_follows, coatt_publish_status, coatt_graph_cache_v1).
    implementation(project(":core:data"))
    // RelayClient, RelayConfig, EventGuard, SubscriptionIds (+ :core:crypto Nip19).
    implementation(project(":core:nostr"))
    // AdminSigner (npub + signEvent via SigningService).
    implementation(project(":data:identity"))
    // Public API: MeetupBadge (input/output). Internally, MeetupKey, BadgeRepository, DartString, DartSort.
    api(project(":data:badges"))
    // AdminRegistry.fetchFromRelays (organizer follows), AdminSigner.
    implementation(project(":data:admin"))
    // MempoolService.getBlockHeight (organizer marker badge without block height).
    implementation(project(":data:mempool"))

    // JVM unit tests sign kind-3/30079 events (BIP-340).
    testImplementation(libs.secp256k1.jvm)
}
