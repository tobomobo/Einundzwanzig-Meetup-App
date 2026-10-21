plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    // TourProgressStore, GuideTour, GuideController and ThemeMode live in :core:ui. They are part
    // of this module's public API, hence `api`.
    api(project(":core:ui"))
    // GuideController's signatures reference Compose runtime/unit types (no compiler plugin needed:
    // this module contains no @Composable code).
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
}
