plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    implementation(project(":core:common"))
    // OkHttpClient, Headers and Json are part of this module's public API.
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
}
