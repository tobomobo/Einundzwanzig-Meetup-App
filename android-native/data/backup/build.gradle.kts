plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // DartJson, DartDateTime, TimeSource, DiagnosticLog/AppLogger, dispatchers.
    api(project(":core:common"))
    // KeyValueStore (humanity_verified read for the additive rule).
    api(project(":core:data"))
    // BackupEnvelope, Pbkdf2, AesGcm, DartBase64, DartUtf8, Nip19, Keys.backupKeyConsistent.
    api(project(":core:crypto"))
    // SigningService (mode, npub, restore*), SecureKeyStore, UserProfile(+Repository).
    api(project(":data:identity"))
    // MeetupBadge, BadgeRepository (load/save/stage/promote/discard), BadgeIdentity, DartString.
    api(project(":data:badges"))
    // AdminRegistry (getAdminList/addAdmin/forceRefresh), AdminEntry.
    implementation(project(":data:admin"))
    // PlatformProofRepository, PlatformProofBackup, HumanityProofRepository, HumanityKeys.
    implementation(project(":data:proofs"))
    // BadgeSync restore-hold API (beginRestoreHold/endRestore/…), BadgeSyncFormat.merge.
    implementation(project(":data:badgesync"))

    // N3 key-consistency tests derive real BIP-340 keys (secp256k1 JNI on the JVM).
    testImplementation(libs.secp256k1.jvm)
}

// The fixture tests read spec/fixtures/08-backup-sync-vectors.json directly.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("spec/fixtures"))
        .withPropertyName("specFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
