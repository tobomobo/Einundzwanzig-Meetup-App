plugins {
    id("einundzwanzig.android.feature")
}

dependencies {
    // CoAttendanceRepository, MyNetwork, AttendanceKeyLabel (+ :data:badges as api: BadgeRepository, DartString).
    implementation(project(":data:socialgraph"))
    // NostrProfileService.cachedDisplayNames / fetchDisplayNames (+ :core:nostr/:core:crypto as api: Nip19).
    implementation(project(":data:profile"))
    // UserProfileRepository (profile npub, spec 05 §2.1 load step 1).
    implementation(project(":data:identity"))
}
