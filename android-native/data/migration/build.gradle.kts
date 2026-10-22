plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

android {
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}

dependencies {
    // AppLogger/DiagnosticLog, TimeSource, dispatchers, DartJson.
    implementation(project(":core:common"))
    // KeyValueStore / SecureStore (targets of the import), PrefValue.
    api(project(":core:data"))
    // AES-GCM/CBC, hex, NIP-19, key derivation, Schnorr (cleanup check), badge blobs.
    implementation(project(":core:crypto"))
    // Read-only decryption of flutter_secure_storage 9.x EncryptedSharedPreferences (E1, spec 14 F1).
    implementation(libs.tink.android)

    // Cleanup verification signs with secp256k1.
    testImplementation(libs.secp256k1.jvm)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.collection:collection:1.5.0")
}

// Tests read the shared vectors from spec/fixtures directly; rerun them when those change.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("spec/fixtures"))
        .withPropertyName("specFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
