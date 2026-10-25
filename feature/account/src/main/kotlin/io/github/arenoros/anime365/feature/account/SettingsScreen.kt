package io.github.arenoros.anime365.feature.account

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arenoros.anime365.core.datastore.AppSettings
import io.github.arenoros.anime365.core.designsystem.component.KadrTopAppBar
import io.github.arenoros.anime365.core.designsystem.theme.KadrTheme
import io.github.arenoros.anime365.core.designsystem.tokens.KadrLayout
import io.github.arenoros.anime365.core.designsystem.tokens.KadrSpacing
import io.github.arenoros.anime365.core.ui.rememberNotificationPermissionRequest

// Разрешения Переводов (docs/api-notes.md); 2160p — если есть у Перевода.
private val HEIGHTS = listOf(2160, 1080, 720, 480, 360)

@Composable
internal fun SettingsRoute(
    onBack: () -> Unit,
    crashReportsAvailable: Boolean,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsScreen(
        settings = settings,
        onUpdate = viewModel::update,
        onEnableNotifications = rememberNotificationPermissionRequest(),
        onBack = onBack,
        crashReportsAvailable = crashReportsAvailable,
    )
}

/**
 * Уведомления, Плеер, Скачивание и отчёты о сбоях (docs/screens.md, «Настройки»); остальные пункты — вместе
 * со своими фичами. Отчётов нет в сборках без DSN Sentry: dev, форки, локальные (ADR 0006).
 */
@Composable
internal fun SettingsScreen(
    settings: AppSettings?,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onEnableNotifications: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    crashReportsAvailable: Boolean = false,
) {
    Scaffold(
        modifier = modifier,
        topBar = { KadrTopAppBar(stringResource(R.string.feature_account_settings), onBack = onBack) },
        containerColor = KadrTheme.palette.background,
    ) { padding ->
        // Файл настроек читается мгновенно: заглушка на время чтения не нужна.
        if (settings == null) return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = KadrSpacing.Space2),
        ) {
            SectionTitle(R.string.feature_account_settings_catalog)
            SwitchRow(
                title = stringResource(R.string.feature_account_settings_adult_content),
                summary = stringResource(R.string.feature_account_settings_adult_content_summary),
                checked = settings.adultContent,
                onCheckedChange = { enabled -> onUpdate { it.copy(adultContent = enabled) } },
            )
            HorizontalDivider(Modifier.padding(vertical = KadrSpacing.Space2))
            SectionTitle(R.string.feature_account_settings_notifications)
            SwitchRow(
                title = stringResource(R.string.feature_account_settings_new_episodes),
                summary = stringResource(R.string.feature_account_settings_new_episodes_summary),
                checked = settings.newEpisodeNotifications,
                onCheckedChange = { enabled ->
                    if (enabled) onEnableNotifications()
                    onUpdate { it.copy(newEpisodeNotifications = enabled) }
                },
            )
            HorizontalDivider(Modifier.padding(vertical = KadrSpacing.Space2))
            PlayerSettings(settings, onUpdate)
            HorizontalDivider(Modifier.padding(vertical = KadrSpacing.Space2))
            SectionTitle(R.string.feature_account_settings_downloads)
            SwitchRow(
                title = stringResource(R.string.feature_account_settings_download_wifi_only),
                summary = stringResource(R.string.feature_account_settings_download_wifi_only_summary),
                checked = settings.downloadWifiOnly,
                onCheckedChange = { enabled -> onUpdate { it.copy(downloadWifiOnly = enabled) } },
            )
            HeightChoice(R.string.feature_account_settings_download_height, settings.downloadHeight) { height ->
                onUpdate { it.copy(downloadHeight = height) }
            }
            if (crashReportsAvailable) {
                HorizontalDivider(Modifier.padding(vertical = KadrSpacing.Space2))
                SwitchRow(
                    title = stringResource(R.string.feature_account_settings_crash_reports),
                    summary = stringResource(R.string.feature_account_settings_crash_reports_summary),
                    checked = settings.crashReports,
                    onCheckedChange = { enabled -> onUpdate { it.copy(crashReports = enabled) } },
                )
            }
        }
    }
}

@Composable
private fun PlayerSettings(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    SectionTitle(R.string.feature_account_settings_player)
    SwitchRow(
        title = stringResource(R.string.feature_account_settings_autoplay),
        summary = stringResource(R.string.feature_account_settings_autoplay_summary),
        checked = settings.autoplayNext,
        onCheckedChange = { enabled -> onUpdate { it.copy(autoplayNext = enabled) } },
    )
    HeightChoice(R.string.feature_account_settings_wifi_height, settings.wifiHeight) { height ->
        onUpdate { it.copy(wifiHeight = height) }
    }
    HeightChoice(R.string.feature_account_settings_mobile_height, settings.mobileHeight) { height ->
        onUpdate { it.copy(mobileHeight = height) }
    }
    Text(
        stringResource(R.string.feature_account_settings_height_hint),
        Modifier.padding(horizontal = KadrSpacing.Space4, vertical = KadrSpacing.Space2),
        style = MaterialTheme.typography.bodySmall,
        color = KadrTheme.palette.inkMuted,
    )
}

@Composable
private fun SectionTitle(
    @StringRes text: Int,
) {
    Text(
        stringResource(text),
        Modifier
            .padding(horizontal = KadrSpacing.Space4, vertical = KadrSpacing.Space2)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = KadrTheme.palette.primary,
    )
}

/** Вся строка — один переключатель: TalkBack читает название, пояснение и состояние вместе. */
@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = KadrLayout.TouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = KadrSpacing.Space4, vertical = KadrSpacing.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KadrSpacing.Space4),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = KadrTheme.palette.inkMuted)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun HeightChoice(
    @StringRes title: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.padding(horizontal = KadrSpacing.Space4, vertical = KadrSpacing.Space2)) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(KadrSpacing.Space2)) {
            HEIGHTS.forEach { height ->
                FilterChip(
                    selected = height == selected,
                    onClick = { onSelect(height) },
                    label = { Text(stringResource(R.string.feature_account_settings_height, height)) },
                )
            }
        }
    }
}
