package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.content.CustomProviderEntryDialog
import com.niki914.zafiro.app.ui.content.SelectionOption
import com.niki914.zafiro.app.ui.content.SelectionPageContent
import com.niki914.zafiro.app.ui.model.ProviderSpecs
import com.niki914.zafiro.app.ui.nav.ConfigurePage
import com.niki914.zafiro.app.ui.nav.TextTitle
import com.niki914.zafiro.app.ui.nav.ZafiroPage

@Composable
internal fun ProviderPickPageRoute(
    onPush: (ZafiroPage) -> Unit,
) {
    var showCustomProviderDialog by rememberSaveable { mutableStateOf(false) }

    SelectionPageContent(
        options = ProviderSpecs.all.map { spec ->
            val colors = providerButtonColorsOrNull(spec)
            SelectionOption(
                id = spec.id,
                title = spec.brandName,
                leadingIconRes = spec.iconRes,
                tintLeadingIcon = spec.tintIcon,
                darkContainerColor = colors?.darkContainerColor,
                lightContainerColor = colors?.lightContainerColor,
                darkContentColor = colors?.darkContentColor,
                lightContentColor = colors?.lightContentColor,
                darkIconColor = colors?.darkIconColor,
                lightIconColor = colors?.lightIconColor,
                onClick = {
                    onPush(
                        ConfigurePage(
                            providerId = spec.id,
                            explicitTitleSpec = TextTitle(spec.brandName),
                        ),
                    )
                },
            )
        } + listOf(
            SelectionOption(
                id = "provider-custom",
                title = stringResource(R.string.ui_provider_custom_title),
                leadingIconRes = R.drawable.ic_provider_custom,
                tintLeadingIcon = true,
                onClick = { showCustomProviderDialog = true },
            ),
        ),
    )

    if (showCustomProviderDialog) {
        CustomProviderEntryDialog(onDismiss = { showCustomProviderDialog = false })
    }
}
