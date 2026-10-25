package io.github.arenoros.anime365.feature.player

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import io.github.arenoros.anime365.core.designsystem.component.errorMessage
import io.github.arenoros.anime365.core.designsystem.icon.KadrIcons
import io.github.arenoros.anime365.core.designsystem.theme.KadrTheme
import io.github.arenoros.anime365.core.designsystem.tokens.KadrSpacing
import io.github.arenoros.anime365.core.player.VideoPlayer
import io.github.arenoros.anime365.core.player.VideoSurface
import io.github.arenoros.anime365.core.ui.EpisodeGrid
import io.github.arenoros.anime365.feature.player.navigation.PlayerKey
import kotlinx.coroutines.launch
import io.github.arenoros.anime365.core.designsystem.R as DesignR

internal const val VIDEO_ASPECT_RATIO = 16f / 9f

/** Управление гаснет вчетверо быстрее, чем Плеер едет в окно. */
private const val OVERLAY_FADE_SPEED = 4f

@Composable
internal fun PlayerRoute(
    key: PlayerKey,
    onChooseTranslation: (episodeId: Int) -> Unit,
    onLogin: () -> Unit,
    onSubscription: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    miniPlayerBounds: () -> Rect?,
    viewModel: PlayerViewModel =
        hiltViewModel<PlayerViewModel, PlayerViewModel.Factory>(creationCallback = { it.create(key) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val noSameTeam = stringResource(R.string.feature_player_no_same_team)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                PlayerEvent.Close -> {
                    onClose()
                }

                is PlayerEvent.ChooseTranslation -> {
                    launch { snackbars.showSnackbar(noSameTeam) }
                    onChooseTranslation(event.episodeId)
                }
            }
        }
    }
    // Вернулись со Входа или Нужна Подписка — пробуем снова; свернули — сохраняем позицию.
    LifecycleResumeEffect(viewModel) {
        viewModel.retryIfBlocked()
        onPauseOrDispose { viewModel.saveNow() }
    }
    val minimize by rememberUpdatedState(onMinimize)
    val collapse = remember { PlayerCollapse(miniPlayerBounds) { minimize() } }
    val scope = rememberCoroutineScope()
    // «Назад» сворачивает Плеер в окно — тем же движением, что и свайп вниз; смотреть нечего — закрывает.
    val leave: () -> Unit = if (state.canMinimize) ({ scope.launch { collapse.collapse() } }) else onClose
    BackHandler(onBack = leave)
    PlayerScreen(
        state = state,
        player = viewModel.videoPlayer,
        snackbars = snackbars,
        collapse = collapse,
        actions =
            PlayerActions(
                onBack = leave,
                onChooseTranslation = onChooseTranslation,
                onPlayNext = viewModel::playNext,
                onPlayPrevious = viewModel::playPrevious,
                onCancelAutoplay = viewModel::cancelAutoplay,
                onRetry = viewModel::retry,
                onLogin = onLogin,
                onSubscription = onSubscription,
                onSelectQuality = viewModel::selectQuality,
                onSelectSpeed = viewModel::setSpeed,
                onSubtitlesChange = viewModel::setSubtitlesEnabled,
            ),
    )
}

// Все действия экрана одним объектом: иначе их пришлось бы протаскивать через каждую функцию.
@Suppress("LongParameterList")
internal class PlayerActions(
    val onBack: () -> Unit,
    val onChooseTranslation: (episodeId: Int) -> Unit,
    val onPlayNext: () -> Unit,
    val onPlayPrevious: () -> Unit,
    val onCancelAutoplay: () -> Unit,
    val onRetry: () -> Unit,
    val onLogin: () -> Unit,
    val onSubscription: () -> Unit,
    val onSelectQuality: (Int) -> Unit,
    val onSelectSpeed: (Float) -> Unit,
    val onSubtitlesChange: (Boolean) -> Unit,
)

/**
 * Портрет: видео 16:9 сверху, под ним — что играет, «Перевод», следующая серия и сетка Эпизодов.
 * Ландшафт — во весь экран без системных панелей. В «картинке в картинке» — только видео.
 * Свайп вниз по видео в портрете сворачивает Плеер в окно ([collapse]).
 * [player] — `null` в скриншот-тестах: видео там не нарисовать.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PlayerScreen(
    state: PlayerUiState,
    player: VideoPlayer?,
    snackbars: SnackbarHostState,
    collapse: PlayerCollapse?,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val inPictureInPicture = rememberIsInPictureInPicture()
    val playing = !rememberPlayPauseButtonState(player?.player).showPlay
    val toggleFullscreen = rememberOrientationToggle(landscape)
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // Сворачивается — фон гаснет, под Плеером видно приложение.
    val shown = { 1f - (collapse?.fraction ?: 0f) }
    Box(
        modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black, alpha = shown()) }
            .then(if (playing) Modifier.keepScreenOn() else Modifier),
    ) {
        when {
            inPictureInPicture -> {
                SideEffect { collapse?.videoBounds = null }
                if (player != null) VideoSurface(player, Modifier.fillMaxSize())
            }

            landscape -> {
                SideEffect { collapse?.videoBounds = null }
                HideSystemBars()
                Video(
                    state,
                    player,
                    fullscreen = true,
                    actions,
                    toggleFullscreen,
                    { settingsOpen = true },
                    Modifier.fillMaxSize(),
                )
            }

            else -> {
                PortraitPlayer(state, player, collapse, actions, toggleFullscreen) { settingsOpen = true }
            }
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
    }
    if (settingsOpen) {
        PlayerSettingsSheet(
            state = state,
            onSelectQuality = actions.onSelectQuality,
            onSelectSpeed = actions.onSelectSpeed,
            onSubtitlesChange = actions.onSubtitlesChange,
            onDismiss = { settingsOpen = false },
        )
    }
}

/** Видео сверху, под ним — что играет; сворачиваясь, видео едет в окно, остальное гаснет. */
@Composable
private fun PortraitPlayer(
    state: PlayerUiState,
    player: VideoPlayer?,
    collapse: PlayerCollapse?,
    actions: PlayerActions,
    onToggleFullscreen: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val shown = { 1f - (collapse?.fraction ?: 0f) }
    val background = KadrTheme.palette.background
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer { collapse?.transform(this) }
            .drawBehind { drawRect(background, alpha = shown()) },
    ) {
        // Чёрная полоса под статус-баром гаснет вместе с остальным: в окно уезжает только видео.
        Spacer(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .drawBehind { drawRect(Color.Black, alpha = shown()) },
        )
        Video(
            state,
            player,
            fullscreen = false,
            actions,
            onToggleFullscreen,
            onOpenSettings,
            Modifier
                .background(Color.Black)
                .fillMaxWidth()
                .aspectRatio(VIDEO_ASPECT_RATIO)
                .then(collapse?.let { Modifier.collapsibleVideo(it, state.canMinimize) } ?: Modifier),
            overlayAlpha = { (1f - (collapse?.fraction ?: 0f) * OVERLAY_FADE_SPEED).coerceAtLeast(0f) },
        )
        PortraitDetails(state, actions, Modifier.graphicsLayer { alpha = shown() })
    }
}

@Suppress("LongParameterList")
@Composable
private fun Video(
    state: PlayerUiState,
    player: VideoPlayer?,
    fullscreen: Boolean,
    actions: PlayerActions,
    onToggleFullscreen: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
    overlayAlpha: () -> Float = { 1f },
) {
    Box(modifier) {
        if (player != null) VideoSurface(player, Modifier.fillMaxSize())
        // Управление и сообщения гаснут первыми, когда Плеер сворачивается.
        Box(Modifier.matchParentSize().graphicsLayer { alpha = overlayAlpha() }) {
            when (val status = state.status) {
                PlayerStatus.Loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                }

                // Эпизод доигран и идёт отсчёт: вместо управления — только карточка следующей серии.
                PlayerStatus.Playing -> {
                    if (state.nextCountdown == null) {
                        PlayerControls(
                            state = state,
                            player = player?.player,
                            fullscreen = fullscreen,
                            onBack = actions.onBack,
                            onChooseTranslation = { state.episode?.let { actions.onChooseTranslation(it.id) } },
                            onOpenSettings = onOpenSettings,
                            onToggleFullscreen = onToggleFullscreen,
                            onPlayPrevious = actions.onPlayPrevious,
                            onPlayNext = actions.onPlayNext,
                        )
                    }
                }

                PlayerStatus.NeedsLogin -> {
                    Message(
                        stringResource(R.string.feature_player_needs_login),
                        stringResource(R.string.feature_player_log_in),
                        actions.onLogin,
                    )
                }

                PlayerStatus.NeedsSubscription -> {
                    Message(
                        stringResource(R.string.feature_player_needs_subscription),
                        stringResource(R.string.feature_player_more),
                        actions.onSubscription,
                    )
                }

                is PlayerStatus.Failed -> {
                    Message(
                        errorMessage(status.error),
                        stringResource(DesignR.string.core_designsystem_retry),
                        actions.onRetry,
                    )
                }
            }
            state.nextCountdown?.let { seconds ->
                NextEpisodeCard(
                    seconds = seconds,
                    onPlayNow = actions.onPlayNext,
                    onCancel = actions.onCancelAutoplay,
                    modifier = Modifier.align(Alignment.Center).padding(KadrSpacing.Space4),
                )
            }
        }
    }
}

/** Плеер всегда чёрный (docs/screens.md): сообщения — белым поверх. */
@Composable
private fun BoxScope.Message(
    text: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(
        Modifier.align(Alignment.Center).padding(KadrSpacing.Space6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KadrSpacing.Space4),
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onAction) { Text(action.uppercase()) }
    }
}

@Composable
private fun PortraitDetails(
    state: PlayerUiState,
    actions: PlayerActions,
    modifier: Modifier,
) {
    // Табов на Плеере нет: отступ от системной навигации — сам.
    Column(
        modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(vertical = KadrSpacing.Space4),
        verticalArrangement = Arrangement.spacedBy(KadrSpacing.Space3),
    ) {
        Column(Modifier.padding(horizontal = KadrSpacing.Space4)) {
            Text(
                state.series
                    ?.summary
                    ?.title
                    .orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = KadrTheme.palette.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    state.episode?.label,
                    state.translation?.team?.takeIf { it.isNotEmpty() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = KadrTheme.palette.inkMuted,
            )
        }
        // FlowRow: при крупном шрифте вторая кнопка уходит на новую строку, а не рвётся по слогам.
        FlowRow(
            Modifier.padding(horizontal = KadrSpacing.Space4),
            horizontalArrangement = Arrangement.spacedBy(KadrSpacing.Space2),
        ) {
            OutlinedButton(onClick = { state.episode?.let { actions.onChooseTranslation(it.id) } }) {
                Icon(painterResource(KadrIcons.Translate), contentDescription = null)
                Text(
                    stringResource(R.string.feature_player_translation).uppercase(),
                    Modifier.padding(start = KadrSpacing.Space2),
                )
            }
            if (state.nextEpisode != null) {
                OutlinedButton(onClick = actions.onPlayNext) {
                    Icon(painterResource(KadrIcons.SkipNext), contentDescription = null)
                    Text(
                        stringResource(R.string.feature_player_next_episode).uppercase(),
                        Modifier.padding(start = KadrSpacing.Space2),
                    )
                }
            }
        }
        val details = state.series?.details
        if (details != null) {
            EpisodeGrid(
                details = details,
                episodeCount = state.series.summary.episodeCount,
                ongoing = false,
                progress = state.progress,
                onEpisodeClick = { actions.onChooseTranslation(it.id) },
                currentEpisodeId = state.episode?.id,
            )
        }
    }
}
