package space.einundzwanzig.meetup.feature.settings

import space.einundzwanzig.meetup.core.ui.guide.GuideStep
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/**
 * Tour through the settings sheet (port of `lib/tours/settings_tour.dart`, spec 12 §2.9.1).
 * It runs inside the sheet's own window ([space.einundzwanzig.meetup.core.ui.guide.GuideWindowHost]).
 * Seven steps in sheet order, all with default options (6 s wait, scroll into view).
 */
object SettingsTour {
    const val PROFILE = "settings.profile"
    const val BACKUP = "settings.backup"
    const val RELAYS = "settings.relays"
    const val LANGUAGE = "settings.language"
    const val HAPTIC = "settings.haptic"
    const val RESTART = "settings.restart"
    const val RESET = "settings.reset"

    /** Delay between opening the sheet and starting the tour (spec 12 §2.6, §8.8). */
    const val START_DELAY_MS: Long = 650

    /** The seven steps (order follows the sheet from top to bottom). */
    fun steps(): List<GuideStep> = listOf(
        GuideStep(PROFILE, L10nR.string.guideSettingsProfileTitle, L10nR.string.guideSettingsProfileBody),
        GuideStep(
            BACKUP,
            L10nR.string.guideSettingsBackupTitle,
            L10nR.string.guideSettingsBackupBody,
            hint = L10nR.string.guideHintBackup,
        ),
        GuideStep(RELAYS, L10nR.string.guideSettingsRelaysTitle, L10nR.string.guideSettingsRelaysBody),
        GuideStep(LANGUAGE, L10nR.string.guideSettingsLanguageTitle, L10nR.string.guideSettingsLanguageBody),
        GuideStep(HAPTIC, L10nR.string.guideSettingsHapticTitle, L10nR.string.guideSettingsHapticBody),
        GuideStep(RESTART, L10nR.string.guideSettingsRestartTitle, L10nR.string.guideSettingsRestartBody),
        GuideStep(RESET, L10nR.string.guideSettingsResetTitle, L10nR.string.guideSettingsResetBody),
    )
}
