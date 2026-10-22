package space.einundzwanzig.meetup.feature.home.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.guide.GuideTargetRegistry
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.feature.home.HomeTour
import space.einundzwanzig.meetup.core.l10n.R as L10nR

private const val PHASE_CHECK = 0
private const val PHASE_ASK = 1
private const val PHASE_DONE = 2

/**
 * First-run tour prompt and home tour start (Flutter `AppShell._checkHomeTour`, spec 12 §2.4):
 * skip when the home tour was completed; ask once (`guide_onboarding_asked` is written before the
 * dialog shows; "Nein, danke" declines every tour); then wait up to 20 × 500 ms for the glossary
 * and home-meetup anchors and start the 21-step tour. The question survives a configuration
 * change (it is not asked twice and not answered implicitly).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeTourGate(controller: GuideController, registry: GuideTargetRegistry?) {
    var phase by rememberSaveable { mutableIntStateOf(PHASE_CHECK) }
    val scope = rememberCoroutineScope()

    suspend fun pollAndStart() {
        for (attempt in 1..HomeTour.POLL_ATTEMPTS) {
            delay(HomeTour.POLL_INTERVAL_MS)
            val ready = registry != null &&
                registry.isRegistered(HomeTour.GLOSSARY) &&
                registry.isRegistered(HomeTour.HOME_MEETUP)
            AppLogger.debug("Guide", "Dashboard-Tour, Versuch $attempt/${HomeTour.POLL_ATTEMPTS} — Ziele bereit: $ready")
            if (ready) {
                controller.startTour(GuideTour.Home, HomeTour.steps())
                return
            }
        }
        AppLogger.warn(
            "Guide",
            "Ziel-Widgets nach ${HomeTour.POLL_ATTEMPTS * HomeTour.POLL_INTERVAL_MS} ms nicht gefunden — Dashboard-Tour startet nicht.",
        )
    }

    LaunchedEffect(controller) {
        if (phase != PHASE_CHECK) return@LaunchedEffect
        withFrameNanos { }
        if (controller.wasTourCompleted(GuideTour.Home)) {
            phase = PHASE_DONE
            return@LaunchedEffect
        }
        if (controller.shouldAskForOnboarding()) {
            controller.markOnboardingAsked()
            phase = PHASE_ASK
            return@LaunchedEffect
        }
        phase = PHASE_DONE
        pollAndStart()
    }

    if (phase == PHASE_ASK) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.testTag(HomeTags.TOUR_PROMPT),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lightbulb, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(L10nR.string.guideWelcomeTitle), fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    stringResource(L10nR.string.guideWelcomeBody),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            dismissButton = {
                TextButton(onClick = {
                    phase = PHASE_DONE
                    scope.launch { controller.declineAllTours() }
                }) {
                    Text(stringResource(L10nR.string.guideNoThanks), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        phase = PHASE_DONE
                        scope.launch { pollAndStart() }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(L10nR.string.guideStart), fontWeight = FontWeight.Bold)
                }
            },
        )
    }
}
