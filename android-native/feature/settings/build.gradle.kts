plugins {
    id("einundzwanzig.android.feature")
}

dependencies {
    // SettingsRepository (haptics, theme mode), LocaleController/AppLocales, GuideController.
    implementation(project(":data:settings"))
    // SigningService.canNip44() for the NIP-44 probe of the diagnostic log (spec 01 §2.9).
    implementation(project(":data:identity"))
    implementation(project(":data:migration"))
    // CurrencyRepository / CurrencyFormat for the currency row and dialog (spec 12 §2.6).
    implementation(project(":data:mempool"))
    // Backup export / restore launched from the settings sheet (spec 08 via BackupLauncher).
    implementation(project(":feature:backup"))
    // LocalActivity: the settings tour must survive configuration changes (language, rotation).
    implementation(libs.androidx.activity.compose)
}
