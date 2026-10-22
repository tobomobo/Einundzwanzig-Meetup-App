package space.einundzwanzig.meetup.feature.reputation.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.ReputationVerify
import space.einundzwanzig.meetup.core.navigation.VerifyPerson
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.motion.Pressable
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.feature.reputation.R
import space.einundzwanzig.meetup.feature.reputation.common.cyanColor
import space.einundzwanzig.meetup.feature.reputation.common.tertiaryTextColor
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the hub. */
internal object TrustNetworkHubTags {
    const val NETWORK_CARD = "hub_network"
    const val VERIFY_CARD = "hub_verify"
}

internal sealed interface TrustNetworkHubAction {
    data object OpenVerifyPerson : TrustNetworkHubAction
    data object OpenReputationVerify : TrustNetworkHubAction
}

/** Stateful entry of [TrustNetworkHub][space.einundzwanzig.meetup.core.navigation.TrustNetworkHub] (spec 04 §2.8). */
@Composable
internal fun TrustNetworkHubRoute() {
    val navigator = LocalNavigator.current
    TrustNetworkHubScreen(
        onAction = { action ->
            when (action) {
                TrustNetworkHubAction.OpenVerifyPerson -> navigator.navigate(VerifyPerson())
                TrustNetworkHubAction.OpenReputationVerify -> navigator.navigate(ReputationVerify())
            }
        },
        onBack = navigator::back,
    )
}

/**
 * Stateless "Vertrauensnetzwerk" hub (spec 04 §2.8): intro and the "Netzwerk-Analyse" tool card.
 * The second card ("Reputation prüfen") wires the orphan verify screen as spec 04 §2.4 recommends.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrustNetworkHubScreen(
    onAction: (TrustNetworkHubAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { AppLargeTopBar(title = stringResource(L10nR.string.tnHubTitle), onBack = onBack, scrollBehavior = scrollBehavior) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(stringResource(L10nR.string.tnHubIntro), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            ToolCard(
                icon = Icons.Rounded.Hub,
                color = MaterialTheme.colorScheme.primary,
                title = stringResource(L10nR.string.tnHubNetTitle),
                subtitle = stringResource(L10nR.string.tnHubNetSub),
                onClick = { onAction(TrustNetworkHubAction.OpenVerifyPerson) },
                modifier = Modifier.testTag(TrustNetworkHubTags.NETWORK_CARD),
            )
            Spacer(Modifier.height(10.dp))
            ToolCard(
                icon = Icons.Rounded.VerifiedUser,
                color = cyanColor,
                title = stringResource(R.string.native_tnHubVerifyTitle),
                subtitle = stringResource(R.string.native_tnHubVerifySub),
                onClick = { onAction(TrustNetworkHubAction.OpenReputationVerify) },
                modifier = Modifier.testTag(TrustNetworkHubTags.VERIFY_CARD),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToolCard(
    icon: ImageVector,
    color: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Pressable(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, shape)
                .border(0.5.dp, color.copy(alpha = 0.3f), shape)
                .padding(18.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .background(color.copy(alpha = 0.12f), MaterialShapes.Cookie7Sided.toShape()),
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = tertiaryTextColor, fontSize = 12.sp)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = tertiaryTextColor)
        }
    }
}

@Preview
@Composable
private fun TrustNetworkHubPreview() {
    EinundzwanzigTheme { TrustNetworkHubScreen(onAction = {}, onBack = {}) }
}
