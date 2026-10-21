plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // AppLogger/DiagnosticLog, TimeSource, DartJson, AppStartupTask, dispatchers, ApplicationScope.
    api(project(":core:common"))
    // KeyValueStore: badge_sync_* and badge_restore_* keys (Flutter names).
    api(project(":core:data"))
    // RelayClient (one-shot reads/publishes), EventGuard, NostrEvent; brings :core:crypto
    // (Sha256, DartBase64, DartUtf8, Nip19, Keys).
    api(project(":core:nostr"))
    // MeetupBadge (public API of BadgeSyncFormat), BadgeRepository, BadgeIdentity, DartSort.
    api(project(":data:badges"))
    // SigningService (NIP-44, signing, signerReady), SecureKeyStore (local-key agreement).
    implementation(project(":data:identity"))
    // HumanityProofRepository.clearForIdentitySwitch (identity switch wipes the old proofs).
    implementation(project(":data:proofs"))
    // ProcessLifecycleOwner: retryIfDirty on app resume (Dart home screen `resumed`).
    implementation(libs.androidx.lifecycle.process)

    // JVM unit tests sign events and run real NIP-44 (secp256k1).
    testImplementation(libs.secp256k1.jvm)
}

// The format tests read the shared vectors from spec/fixtures directly.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("spec/fixtures"))
        .withPropertyName("specFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
