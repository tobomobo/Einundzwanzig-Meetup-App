plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.compose")
}

dependencies {
    api(project(":core:l10n"))
    implementation(project(":core:common"))

    implementation(libs.androidx.core.ktx)
    // LocalLifecycleOwner for the guide anchors (target "covered" detection).
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Process foreground state for SecretClipboard's background retry (spec 14 §6).
    implementation(libs.androidx.lifecycle.process)

    // Avatars, crests and Markdown images. Coil types appear in public signatures (models).
    api(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // QR matrix generation (rendering is ours, see QrCode.kt).
    implementation(libs.zxing.core)
}
