plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    // AppLogger/DiagnosticLog, TimeSource, dispatchers, DartJson.
    implementation(project(":core:common"))
    // KeyValueStore / SecureStore (targets of the import), PrefValue.
    api(project(":core:data"))
    // AES-GCM/CBC, hex, NIP-19, key derivation, Schnorr (cleanup check), badge blobs.
    implementation(project(":core:crypto"))
    // Recovery-name prefixes shared with IdentityRecovery (spec 14 §4.8).
    implementation(project(":data:identity"))
    // Read-only decryption of flutter_secure_storage 9.x EncryptedSharedPreferences (E1, spec 14 F1).
    implementation(libs.tink.android)

    // Cleanup verification signs with secp256k1.
    testImplementation(libs.secp256k1.jvm)
}

// Tests read the shared vectors from spec/fixtures directly; rerun them when those change.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("spec/fixtures"))
        .withPropertyName("specFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
