plugins {
    id("einundzwanzig.android.feature")
}

dependencies {
    // MeetupBadge, BadgeRepository, BadgeIdentity, BadgeProofs, DartString/DartSort (+ :data:identity as api).
    implementation(project(":data:badges"))
    // ReputationPublisher.publishInBackground after cleanup / merge (spec 03 §6).
    implementation(project(":data:reputation"))
    // UserProfileRepository: npub, nickname, telegram, twitter for the share text / JSON export.
    implementation(project(":data:identity"))
    // OsmMap, markers and snapshot for the badge world map (spec 03 §2.4).
    implementation(project(":core:map"))
    // PortalLogoRegistry: crest URLs for MeetupCrestWatermark / MeetupCrestFace (spec 03 §2.5).
    implementation(project(":data:meetups"))
}
