plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

android {
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        // App-Review demo login (spec 01 §2.6/§8.7). Off in every normal build; a review build sets
        // -PappReviewSignin=true. Debug builds offer it regardless (BuildConfig.DEBUG).
        buildConfigField(
            "boolean",
            "APP_REVIEW_SIGNIN",
            (providers.gradleProperty("appReviewSignin").orNull == "true").toString(),
        )
    }
}

dependencies {
    // AppLogger, TimeSource, dispatchers, DartJson, DartUri, AppStartupTask.
    api(project(":core:common"))
    // KeyValueStore / SecureStore are only used internally.
    implementation(project(":core:data"))
    // NostrEvent (SignedNostrEvent), RelayConfig, RelayConnector; :core:crypto comes along as api.
    api(project(":core:nostr"))

    // Device authentication (BiometricPrompt needs a FragmentActivity) and passkeys (PRF).
    api(libs.androidx.biometric)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.play.services.fido)

    // JVM unit tests reach secp256k1 (signing, NIP-44, key derivation).
    testImplementation(libs.secp256k1.jvm)
}

// Tests read the shared vectors from spec/fixtures directly; rerun them when those change.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("spec/fixtures"))
        .withPropertyName("specFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
