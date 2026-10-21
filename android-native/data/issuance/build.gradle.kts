plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // Public API types: MeetupBadge, VerifyResult, ExpiryInfo (badges); NostrCalendarEvent (calendar);
    // Meetup, LocationStatus (meetups); DartDateTime, JsonObject, TimeSource (core:common via badges).
    api(project(":core:common"))
    api(project(":data:badges"))
    api(project(":data:calendar"))
    api(project(":data:meetups"))

    // KeyValueStore / SecureStore (session persistence, exact Flutter keys).
    implementation(project(":core:data"))
    // Nip19, Sha256/HmacSha256, CryptoRandom, DartUtf8.
    implementation(project(":core:crypto"))
    // SigningService (npub / pubkeyHex), UserProfileRepository (EventBadgeAuthService).
    implementation(project(":data:identity"))
    // AdminRegistry (signer recognition, event creator check).
    implementation(project(":data:admin"))
    // CoAttendanceRepository (organizer marker, opt-in publish).
    implementation(project(":data:socialgraph"))
    // MempoolService.getBlockHeight (session + scan block height).
    implementation(project(":data:mempool"))

    // JVM unit tests sign and verify kind-21000/21002 events (BIP-340).
    testImplementation(libs.secp256k1.jvm)
}

// Tests read the shared vectors from spec/fixtures directly; rerun them when those change.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("spec/fixtures"))
        .withPropertyName("specFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
