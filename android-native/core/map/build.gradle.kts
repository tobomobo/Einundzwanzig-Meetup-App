plugins {
    id("einundzwanzig.android.library")
    id("einundzwanzig.android.compose")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))

    // OSM raster map (flutter_map replacement, spec 14 §2). Its types stay internal: the public
    // API speaks LatLng / OsmCameraState only.
    implementation(libs.osmdroid)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
