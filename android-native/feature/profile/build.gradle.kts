plugins {
    id("einundzwanzig.android.feature")
}

dependencies {
    // SigningService, SecureKeyStore, LocalKeyVault, NostrKeyService, UserProfileRepository,
    // DeviceAuthenticator (spec 01 §2.5, §2.7). Exposes :core:nostr (RelayConfig) and :core:crypto (Nip49).
    implementation(project(":data:identity"))
    // NostrProfileService: locally picked profile picture for the avatar header (spec 07 §8.8).
    implementation(project(":data:profile"))
    // MeetupService: directory for the home-meetup / favourites picker (spec 09 §2.5).
    implementation(project(":data:meetups"))
    // PlatformProofRepository, HumanityProofRepository: identity tile status (spec 07 §2.2.5).
    implementation(project(":data:proofs"))
    // Photo picker launcher, BackHandler.
    implementation(libs.androidx.activity.compose)
}
