plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // AppLogger/DiagnosticLog, TimeSource, DartJson, toStringAsFixed, AppStartupTask, dispatchers.
    api(project(":core:common"))
    // Shared KeyValueStore (reputation_last_publish / reputation_last_hash).
    implementation(project(":core:data"))
    // NostrEvent (parser input), RelayClient, RelayConfig, SubscriptionIds (+ :core:crypto as api).
    api(project(":core:nostr"))
    // SigningService (kind 30078 / 21001), UserProfile(Repository) (QR identity, nickname).
    api(project(":data:identity"))
    // Public API: MeetupBadge, BadgeRepository (generator input), BadgeSecurity (21v3 sign/verify).
    api(project(":data:badges"))
    // Public API: TrustScore (QR result); TrustScoreCalculator, RelayReputationMath.
    api(project(":data:trust"))
    // Public API: PlatformProof, ZapStats, ProofVerifyResult; PlatformProofVerifier seam,
    // HumanityProofRepository, ZapVerificationService.
    api(project(":data:proofs"))
    // Public API: SocialStats (ReputationEvent.socialStats); SocialGraphService.getMyStats.
    api(project(":data:socialgraph"))

    // JVM unit tests sign and verify events (BIP-340).
    testImplementation(libs.secp256k1.jvm)
}

// Tests read the shared vectors from spec/fixtures directly; rerun them when those change.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("spec/fixtures"))
        .withPropertyName("specFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
