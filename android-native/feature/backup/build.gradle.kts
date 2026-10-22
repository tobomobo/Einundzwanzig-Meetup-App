plugins {
    id("einundzwanzig.android.feature")
}

dependencies {
    // BackupExporter, RestoreEngine, BackupSession, BackupCrypto, BackupDocument, BackupFiles.
    implementation(project(":data:backup"))
    // rememberLauncherForActivityResult (SAF CreateDocument / OpenDocument).
    implementation(libs.androidx.activity.compose)
}
