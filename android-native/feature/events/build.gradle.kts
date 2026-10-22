plugins {
    id("einundzwanzig.android.feature")
}

dependencies {
    // Meetup dates, NIP-52 events, RSVPs, merge/format helpers (exposes :data:identity and :data:meetups as api).
    implementation(project(":data:calendar"))
    // MeetupService, PortalApiService, Meetup model, PortalResult.
    implementation(project(":data:meetups"))
    // UserProfileRepository (favorites), SigningService (own pubkey).
    implementation(project(":data:identity"))
    // BlossomUploadService (badge image), NostrProfileService (issuer display names).
    implementation(project(":data:profile"))
    // EventBadgeSessionService, EventBadgeAuthService, RollingQrService (event badge sessions).
    implementation(project(":data:issuance"))
    // EventChatService.unreadCounts, ChatService room lookup for "Meine Termine" (spec 12 §8.6).
    implementation(project(":data:chat"))
    // Nip19 (npub chips of the issuer list).
    implementation(project(":core:crypto"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    // EXIF orientation of the gallery pick before the badge image is re-encoded.
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.androidx.compose.ui.test.junit4)
}
