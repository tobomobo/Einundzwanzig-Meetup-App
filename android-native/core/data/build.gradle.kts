plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.hilt")
}

dependencies {
    implementation(project(":core:common"))
    // Preferences DataStore backs both KeyValueStore ("app_prefs") and SecureStore ("secure_prefs").
    // It is an implementation detail: the public API only exposes the store interfaces.
    implementation(libs.androidx.datastore.preferences)
}
