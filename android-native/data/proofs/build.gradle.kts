plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // AppLogger, TimeSource, DartJson, DartUri, AppStartupTask, dispatchers.
    api(project(":core:common"))
    // Shared KeyValueStore (platform_proofs, humanity_*, zap caches). KeyValueEditor is part of
    // the public clearIdentityProofs helper.
    api(project(":core:data"))
    // OkHttpClient + getCapped (NIP-05, LNURL, SatoshiDuell).
    implementation(project(":core:network"))
    // RelayClient, RelayConfig, EventGuard, NostrEvent (+ :core:crypto Bech32/Nip19/Sha256/Bolt11).
    api(project(":core:nostr"))
    // SigningService (npub, canSign, kind-21003 signing).
    implementation(project(":data:identity"))
    // LightningAddressLookup for the zap-receipt provider check.
    implementation(project(":data:profile"))
    // DartString (byte-exact Dart toLowerCase/trim for signed fields and comparisons).
    implementation(project(":data:badges"))

    // JVM unit tests sign and verify events (BIP-340).
    testImplementation(libs.secp256k1.jvm)
    testImplementation(libs.okhttp.mockwebserver)
}
