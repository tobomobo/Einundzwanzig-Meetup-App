plugins {
    id("einundzwanzig.android.library")
}

dependencies {
    // Dart-compatible JSON (DartJson) used for event ids and canonical content.
    implementation(project(":core:common"))
    // BIP-340 Schnorr, key derivation and the point multiplication behind NIP-44 ECDH.
    implementation(libs.secp256k1.android)
    // Lightweight API only (ChaCha20, Poly1305, HKDF, scrypt, bcrypt, GCM, PBKDF2).
    // Android's own copy lives under com.android.org.bouncycastle, so there is no clash.
    implementation(libs.bouncycastle)

    // The Android AAR only ships device ABIs; JVM unit tests load the host library from here.
    testImplementation(libs.secp256k1.jvm)
}

// Tests read the shared vectors from spec/fixtures directly; rerun them when those change.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("spec/fixtures"))
        .withPropertyName("specFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
