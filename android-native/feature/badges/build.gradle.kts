plugins {
    id("einundzwanzig.android.feature")
}

dependencies {
    // BadgeScanProcessor, BadgeSaveUseCase, RollingQrService, NfcBadgePayload, IssuanceFeatures.
    // Exposes :data:badges (MeetupBadge, ExpiryInfo, VerifyResult) and :data:meetups (MeetupService).
    implementation(project(":data:issuance"))
    implementation(project(":data:badges"))
    implementation(project(":data:meetups"))
    // UserProfileRepository (organizer npub, home meetup), AppReviewDemo (demo dialog texts).
    implementation(project(":data:identity"))
    // ReputationPublisher.publishInBackground after a saved badge (spec 02 §2.1 step 12).
    implementation(project(":data:reputation"))
    // QrScanner, QrImagePicker (badge camera, spec 02 §2.2).
    implementation(project(":core:camera"))
}
