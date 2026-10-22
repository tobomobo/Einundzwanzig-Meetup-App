plugins {
    id("einundzwanzig.android.feature")
}

dependencies {
    // ReputationQrGenerator, ReputationPublisher, ReputationQrCodec, ReputationProofVerifier (spec 04).
    implementation(project(":data:reputation"))
    // TrustScoreCalculator, TrustLayersEvaluator, TrustLevelStyle.
    implementation(project(":data:trust"))
    // ZapVerificationService, Nip05Service, VerifyLevel/VerifyMessage.
    implementation(project(":data:proofs"))
    // SocialGraphService.analyze, CoAttendanceRepository.verifyPerson.
    implementation(project(":data:socialgraph"))
    // BadgeRepository, BadgeClaimService, BadgeReviewDemo, DartString.
    implementation(project(":data:badges"))
    // UserProfileRepository, AppReviewDemo (+ :core:nostr/:core:crypto as api: RelayConfig, Nip19).
    implementation(project(":data:identity"))
    // QrScanner, gallery QR picker (spec 04 §2.3, §2.7.1).
    implementation(project(":core:camera"))

    // The secp256k1 Android AAR only ships device ABIs; JVM tests verify the 21v3 fixture with the host library.
    testImplementation(libs.secp256k1.jvm)
}

// Tests read the shared vectors from spec/fixtures directly; rerun them when those change.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("spec/fixtures"))
        .withPropertyName("specFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
