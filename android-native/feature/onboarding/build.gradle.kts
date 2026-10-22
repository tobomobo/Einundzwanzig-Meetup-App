plugins {
    id("einundzwanzig.android.feature")
}

dependencies {
    // SigningService, SecureKeyStore, LocalKeyVault, LocalEasyAuth, PasskeyPrfService,
    // NostrKeyService, UserProfileRepository, AppReviewSignIn (spec 01 §2.1–§2.6).
    implementation(project(":data:identity"))
    // MeetupService: directory for the meetup step (spec 01 §2.3.10, spec 09 §2.5).
    implementation(project(":data:meetups"))
    // LocaleController / AppLocales: Intro language chip (spec 01 §2.2).
    implementation(project(":data:settings"))
    // MigrationGate: the splash waits for the Flutter import (spec 01 §2.1, spec 14 §4).
    implementation(project(":data:migration"))
    // BackupLauncher: "BACKUP LADEN" and the "secure your identity" sheet (spec 08 §2.1).
    implementation(project(":feature:backup"))
    // QrScanner: scan a bunker:// QR in the remote-signer sheet.
    implementation(project(":core:camera"))
    // BackHandler, CreateDocument launcher.
    implementation(libs.androidx.activity.compose)
}
