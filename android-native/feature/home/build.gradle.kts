plugins {
    id("einundzwanzig.android.feature")
}

dependencies {
    // KeyValueStore (tile layout prefs, reset wipe).
    implementation(project(":core:data"))
    // UserProfile, SigningService, DeviceIntegrityService, IdentityRepository (reset).
    implementation(project(":data:identity"))
    // nostr_last_seen, LocaleController (reset).
    implementation(project(":data:settings"))
    // MeetupService, PortalApiService, PortalLogoRegistry (favorites, crests, organizer check).
    implementation(project(":data:meetups"))
    // Favorite cards, portal RSVPs, my events, widget store, events-today counter.
    implementation(project(":data:calendar"))
    // BadgeRepository, BadgeClaimService.
    implementation(project(":data:badges"))
    // TrustScoreCalculator.
    implementation(project(":data:trust"))
    // AdminStatusVerifier, PromotionClaimService, AdminRegistry.
    implementation(project(":data:admin"))
    // ChatService (room lookup, unread), EventChatService (my-events unread).
    implementation(project(":data:chat"))
    // NewsService (tile counter, latest headline).
    implementation(project(":data:news"))
    // PlebrapPlayer (mini player tile).
    implementation(project(":data:community"))
    // MempoolService, CurrencyRepository, BitcoinWidgetBridge (BTC + converter tiles).
    implementation(project(":data:mempool"))
    // HumanityProofRepository (re-verification), SatoshiDuellClient (tile).
    implementation(project(":data:proofs"))
    // RollingQrService (live session card).
    implementation(project(":data:issuance"))
    // NostrProfileService, LocalPictureImporter (avatar).
    implementation(project(":data:profile"))
    // ReputationPublisher.publishInBackground after a readable badge load.
    implementation(project(":data:reputation"))
    // FlutterMigration.onAppReset() (reset flow).
    implementation(project(":data:migration"))
    // Settings sheet (gear icon) and the backup export of the reset flow.
    implementation(project(":feature:settings"))
    implementation(project(":feature:backup"))
    // Photo picker (PickVisualMedia) for the local avatar.
    implementation(libs.androidx.activity.compose)
}
