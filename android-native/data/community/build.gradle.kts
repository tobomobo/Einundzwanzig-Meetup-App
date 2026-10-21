plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    // Glossary entries reference the generated ARB strings (gl*Title / gl*Body / glCat*).
    implementation(project(":core:l10n"))

    // PlebRap playback: ExoPlayer + MediaSessionService (media notification), OkHttp upstream
    // so audio requests share the app client (User-Agent, cleartext policy, connection pool).
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource.okhttp)

    testImplementation(libs.okhttp.mockwebserver)
}
