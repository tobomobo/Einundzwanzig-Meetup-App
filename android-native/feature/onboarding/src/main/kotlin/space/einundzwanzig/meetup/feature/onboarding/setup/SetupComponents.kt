package space.einundzwanzig.meetup.feature.onboarding.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.ui.motion.pressScale
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.onboarding.R
import space.einundzwanzig.meetup.feature.onboarding.common.UiText
import space.einundzwanzig.meetup.feature.onboarding.common.resolve

/** Flutter `_card(icon, title, subtitle, onTap, primary)` (spec 01 §2.3.2). */
@Composable
internal fun SetupCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accent = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(14.dp),
        color = if (primary) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, if (primary) accent.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth().pressScale(interaction),
    ) {
        Row(Modifier.padding(16.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (primary) accent else MaterialTheme.extendedColors.community,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.size(4.dp))
                Text(subtitle, fontSize = 13.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.extendedColors.textTertiary,
            )
        }
    }
}

/** Flutter `_pwBorder(ok, touched)`: green/red once the field is non-empty. */
@Composable
internal fun passwordBorder(ok: Boolean, touched: Boolean): Color? = when {
    !touched -> null
    ok -> MaterialTheme.extendedColors.success
    else -> MaterialTheme.colorScheme.error
}

/** Flutter `_field` (spec 01 §2.3.2): filled card colour, radius 12, per-field eye toggle. */
@Composable
internal fun SetupField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    revealed: Boolean = false,
    onToggleReveal: () -> Unit = {},
    borderColor: Color? = null,
    maxLines: Int = 1,
    enabled: Boolean = true,
    autocorrect: Boolean = true,
) {
    val enabledBorder = borderColor ?: MaterialTheme.colorScheme.outlineVariant
    val focusedBorder = borderColor ?: MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.surfaceContainer
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        trailingIcon = if (password) {
            {
                IconButton(onClick = onToggleReveal) {
                    Icon(
                        if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(
                            if (revealed) R.string.native_onboarding_hide_password else R.string.native_onboarding_show_password,
                        ),
                    )
                }
            }
        } else {
            null
        },
        singleLine = password || maxLines == 1,
        maxLines = if (password) 1 else maxLines,
        visualTransformation = if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (password) {
            KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
        } else {
            KeyboardOptions(autoCorrectEnabled = autocorrect)
        },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container,
            unfocusedBorderColor = enabledBorder,
            focusedBorderColor = focusedBorder,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Flutter `_rule(ok, text)`. */
@Composable
internal fun RuleRow(ok: Boolean, text: String, modifier: Modifier = Modifier) {
    val green = MaterialTheme.extendedColors.success
    Row(modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (ok) green else MaterialTheme.extendedColors.textTertiary,
            modifier = Modifier.size(15.dp).padding(top = 1.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = if (ok) green else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Red error box at the top of the list. */
@Composable
internal fun ErrorBox(error: UiText, modifier: Modifier = Modifier) {
    val red = MaterialTheme.colorScheme.error
    Text(
        error.resolve(),
        color = red,
        lineHeight = 18.sp,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .fillMaxWidth()
            .background(red.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag(SetupTags.ERROR),
    )
}

@Composable
internal fun VSpace(height: Dp) = Spacer(Modifier.size(width = 0.dp, height = height))
