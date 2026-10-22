plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.compose")
}

dependencies {
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Camera preview + analysis (spec 14 §2: mobile_scanner replacement).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    // Bundled ML Kit model: works without Play services (GrapheneOS), QR only.
    implementation(libs.mlkit.barcode.scanning)
}
