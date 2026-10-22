plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // AppLogger, TimeSource, DartJson, DartDateTime, @ApplicationScope.
    api(project(":core:common"))
    // Shared KeyValueStore (admin_registry_cache, sunset keys, organic_admin_claims, my_personal_vouches).
    api(project(":core:data"))
    // RelayClient, NostrEvent, NostrFilter, SubscriptionIds (+ :core:crypto Nip19/EventIds).
    api(project(":core:nostr"))
    // SigningService (canSign, npub, signEvent) and UserProfileRepository (reVerifyAdmin).
    api(project(":data:identity"))
    // MeetupBadge (public API input), BadgeSecurity.verify/normalize, BadgeClaims.verifyClaim.
    api(project(":data:badges"))
    // TrustScoreCalculator (self verification), ZoneIdProvider.
    implementation(project(":data:trust"))

    // JVM unit tests sign and verify kind 21000/21002 events (BIP-340).
    testImplementation(libs.secp256k1.jvm)
}
