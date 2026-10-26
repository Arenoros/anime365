package io.github.arenoros.anime365.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import io.github.arenoros.anime365.CHECKS_UPDATES
import io.github.arenoros.anime365.CrashReports
import io.github.arenoros.anime365.R
import io.github.arenoros.anime365.core.designsystem.component.BottomSheetSceneStrategy
import io.github.arenoros.anime365.core.designsystem.component.KadrNavigationBar
import io.github.arenoros.anime365.core.designsystem.component.KadrNavigationBarItem
import io.github.arenoros.anime365.core.designsystem.component.KadrNavigationRail
import io.github.arenoros.anime365.core.designsystem.component.KadrNavigationRailItem
import io.github.arenoros.anime365.core.designsystem.component.LocalTvContentCovered
import io.github.arenoros.anime365.core.designsystem.component.isTelevision
import io.github.arenoros.anime365.core.player.VideoPlayer
import io.github.arenoros.anime365.feature.account.navigation.LoginKey
import io.github.arenoros.anime365.feature.account.navigation.SettingsKey
import io.github.arenoros.anime365.feature.account.navigation.SubscriptionKey
import io.github.arenoros.anime365.feature.account.navigation.accountEntries
import io.github.arenoros.anime365.feature.catalog.navigation.CatalogKey
import io.github.arenoros.anime365.feature.catalog.navigation.catalogEntries
import io.github.arenoros.anime365.feature.library.navigation.libraryEntries
import io.github.arenoros.anime365.feature.library.notifications.EpisodeLink
import io.github.arenoros.anime365.feature.player.AutoPictureInPicture
import io.github.arenoros.anime365.feature.player.MiniPlayer
import io.github.arenoros.anime365.feature.player.miniPlayerBounds
import io.github.arenoros.anime365.feature.player.navigation.PlayerKey
import io.github.arenoros.anime365.feature.player.navigation.playerEntries
import io.github.arenoros.anime365.feature.player.rememberIsInPictureInPicture
import io.github.arenoros.anime365.feature.series.navigation.PickerPurpose
import io.github.arenoros.anime365.feature.series.navigation.SeriesKey
import io.github.arenoros.anime365.feature.series.navigation.TranslationsKey
import io.github.arenoros.anime365.feature.series.navigation.seriesEntries
import io.github.arenoros.anime365.navigation.TopLevelDestination
import kotlinx.coroutines.flow.StateFlow

/** [link] — касание уведомления о Новом Эпизоде; после перехода вызывается [onLinkHandled]. */
@Composable
internal fun Anime365App(
    link: EpisodeLink?,
    onLinkHandled: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppViewModel = hiltViewModel(),
) {
    val backStacks = TopLevelDestination.entries.associateWith { rememberNavBackStack(it.key) }
    var current by rememberSaveable { mutableStateOf(TopLevelDestination.Home) }
    val backStack = backStacks.getValue(current)
    val newEpisodeCount by viewModel.newEpisodeCount.collectAsStateWithLifecycle()

    val player = rememberPlayerHost()
    val hiddenSeriesIds = rememberHiddenSeriesIds(viewModel.hiddenSeriesIds, backStacks.values, player)

    LaunchedEffect(link) {
        if (link == null) return@LaunchedEffect
        openLink(link, backStacks.getValue(TopLevelDestination.Library), viewModel, player)
        current = TopLevelDestination.Library
        onLinkHandled()
    }

    val decorators = rememberNavDecorators()
    // Записи всех табов живут всё время: так переключение таба не сбрасывает ни прокрутку, ни ViewModel.
    val entries =
        TopLevelDestination.entries.associateWith { destination ->
            rememberEntries(backStacks.getValue(destination), destination.name, decorators, player, hiddenSeriesIds)
        }
    val playerEntries = rememberEntries(player.stack, "Player", decorators, player, hiddenSeriesIds)
    val sceneStrategies = remember { listOf(BottomSheetSceneStrategy<NavKey>(), SinglePaneSceneStrategy()) }

    Box(modifier) {
        // Под развёрнутым Плеером приложение остаётся на месте: свайп вниз сворачивает Плеер и
        // открывает его.
        CompositionLocalProvider(LocalTvContentCovered provides player.expanded) {
            TabsScaffold(
                current = current,
                backStack = backStack,
                newEpisodeCount = newEpisodeCount,
                covered = player.expanded,
                onSelect = { current = it },
            ) { padding ->
                NavDisplay(
                    entries = entries.getValue(current),
                    modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                    sceneStrategies = sceneStrategies,
                    onBack = { backStack.removeLastOrNull() },
                )
                PlayerWindow(player, viewModel.videoPlayer, hiddenSeriesIds, Modifier.padding(padding))
            }
        }
        PlayerLayer(player, playerEntries, sceneStrategies)
    }
}

/**
 * Экран таба [current] и сами табы: внизу, а на телевизоре — колонкой слева, иначе до них пультом
 * пришлось бы листать весь экран. В «картинке в картинке» табов нет. [covered] — приложение под
 * развёрнутым Плеером.
 */
@Composable
private fun TabsScaffold(
    current: TopLevelDestination,
    backStack: MutableList<NavKey>,
    newEpisodeCount: Int,
    covered: Boolean,
    onSelect: (TopLevelDestination) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val shown = !rememberIsInPictureInPicture()
    val atStart = isTelevision()
    val focus = remember { TabsFocus() }
    val select: (TopLevelDestination) -> Unit = { destination ->
        // Повторное нажатие на активный таб возвращает к его корню.
        if (destination == current) {
            while (backStack.size > 1) backStack.removeLastOrNull()
        } else {
            onSelect(destination)
        }
    }
    // «Назад» с корня таба на телевизоре сначала переводит фокус на табы. Дальше — с любого таба,
    // кроме Главной, на Главную, а не закрывает приложение.
    val backToTabs = shown && atStart && !focus.hasFocus
    val backToHome = current != TopLevelDestination.Home
    BackHandler(enabled = !covered && backStack.size == 1 && (backToTabs || backToHome)) {
        if (backToTabs) focus.focus(current) else onSelect(TopLevelDestination.Home)
    }
    Scaffold(
        modifier = Modifier.coveredByPlayer(covered),
        bottomBar = { if (shown && !atStart) AppNavigationBar(current, newEpisodeCount, select) },
        // Верхний отступ у каждого экрана свой: зелёная шапка заходит под статус-бар.
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Row {
            if (shown && atStart) AppNavigationRail(current, newEpisodeCount, focus, select)
            Box(Modifier.weight(1f)) { content(padding) }
        }
    }
}

/** Пока Плеер развёрнут, TalkBack не видит того, что под ним, и пульт не уводит туда фокус. */
private fun Modifier.coveredByPlayer(covered: Boolean): Modifier =
    this
        .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)
        .focusProperties { onEnter = { if (covered) cancelFocusChange() } }
        .focusGroup()

@Composable
private fun rememberNavDecorators(): List<NavEntryDecorator<NavKey>> =
    listOf(
        rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
        rememberViewModelStoreNavEntryDecorator(),
    )

@Composable
private fun rememberHiddenSeriesIds(
    ids: StateFlow<Set<Int>?>,
    backStacks: Collection<NavBackStack<NavKey>>,
    player: PlayerHost,
): Set<Int>? {
    val hiddenSeriesIds by ids.collectAsStateWithLifecycle()
    LaunchedEffect(hiddenSeriesIds, backStacks.flatMap { it }, player.stack.toList()) {
        val hidden = hiddenSeriesIds ?: return@LaunchedEffect
        if (player.stack.any { it.seriesIdOrNull() in hidden }) player.close()
        backStacks.forEach { stack -> stack.removeAll { it.seriesIdOrNull() in hidden } }
    }
    return hiddenSeriesIds
}

/** Развёрнутый Плеер и то, что открыто поверх него, — во весь экран над табами (docs/screens.md). */
@Composable
private fun PlayerLayer(
    player: PlayerHost,
    entries: List<NavEntry<NavKey>>,
    sceneStrategies: List<SceneStrategy<NavKey>>,
) {
    // Свёрнутый Плеер уже доехал до окна анимацией: слой исчезает сразу.
    AnimatedVisibility(player.expanded && entries.isNotEmpty(), enter = fadeIn(), exit = ExitTransition.None) {
        // Касания не проходят к приложению, даже где Плеер прозрачен посреди сворачивания.
        Box(Modifier.fillMaxSize().pointerInput(Unit) {}) {
            // AnimatedVisibility может держать содержимое ещё кадр после того, как стек Плеера опустел.
            if (entries.isNotEmpty()) {
                NavDisplay(entries = entries, sceneStrategies = sceneStrategies, onBack = player::back)
            }
        }
    }
}

/**
 * Плеер — в своём стеке поверх табов (ADR 0011): развёрнут, свёрнут в окно или закрыт. Над ним в
 * том же стеке — Выбор Перевода, Вход и Нужна Подписка, открытые из Плеера.
 */
@Stable
private class PlayerHost(
    val stack: NavBackStack<NavKey>,
    minimized: MutableState<Boolean>,
) {
    var minimized by minimized
        private set

    /** Где появится окно: туда Плеер сворачивается анимацией. */
    var miniWindow: Rect? = null

    val expanded: Boolean get() = stack.isNotEmpty() && !minimized

    /** Новый Плеер заменяет прежний, развёрнутый или свёрнутый. */
    fun open(key: PlayerKey) {
        stack.clear()
        stack.add(key)
        minimized = false
    }

    fun minimize() {
        minimized = true
    }

    fun expand() {
        minimized = false
    }

    fun close() {
        stack.clear()
        minimized = false
    }

    /** «Назад» на развёрнутом: закрыть то, что открыто поверх Плеера, или свернуть его. */
    fun back() {
        if (stack.size > 1) stack.removeLastOrNull() else minimize()
    }
}

@Composable
private fun rememberPlayerHost(): PlayerHost {
    val stack = rememberNavBackStack()
    val minimized = rememberSaveable { mutableStateOf(false) }
    return remember(stack, minimized) { PlayerHost(stack, minimized) }
}

/** Свёрнутый Плеер — окном поверх приложения; играет видео — «картинка в картинке» при выходе. */
@Composable
private fun PlayerWindow(
    player: PlayerHost,
    videoPlayer: VideoPlayer,
    hiddenSeriesIds: Set<Int>?,
    modifier: Modifier,
) {
    val playing by videoPlayer.playing.collectAsStateWithLifecycle()
    val visible = player.stack.none { it.isHidden(hiddenSeriesIds) }
    AutoPictureInPicture(playing = visible && player.stack.isNotEmpty() && playing)
    val density = LocalDensity.current
    Box(
        modifier.fillMaxSize().onGloballyPositioned {
            player.miniWindow =
                miniPlayerBounds(
                    it.boundsInRoot(),
                    density,
                )
        },
    )
    if (visible && player.stack.isNotEmpty() && player.minimized) {
        MiniPlayer(videoPlayer, onExpand = player::expand, onClose = player::close, modifier = modifier)
    }
}

/**
 * Записи одного стека. Ключ записи — с именем стека: одно и то же Аниме, открытое в двух табах, —
 * два разных экрана.
 */
@Composable
private fun rememberEntries(
    stack: NavBackStack<NavKey>,
    name: String,
    decorators: List<NavEntryDecorator<NavKey>>,
    player: PlayerHost,
    hiddenSeriesIds: Set<Int>?,
): List<NavEntry<NavKey>> {
    val provider = remember(stack) { entryProvider(stack, player) }
    val hidden by rememberUpdatedState(hiddenSeriesIds)
    return rememberDecoratedNavEntries(stack, decorators) { key ->
        val entry = provider(key)
        NavEntry(key, "$name/${entry.contentKey}", entry.metadata) {
            if (!key.isHidden(hidden)) entry.Content()
        }
    }
}

private fun NavKey.seriesIdOrNull(): Int? =
    when (this) {
        is SeriesKey -> id
        is TranslationsKey -> seriesId
        is PlayerKey -> seriesId
        else -> null
    }

private fun NavKey.isHidden(hiddenSeriesIds: Set<Int>?): Boolean {
    val id = seriesIdOrNull() ?: return false
    return hiddenSeriesIds == null || id in hiddenSeriesIds
}

/** Табы; на «Списке» — счётчик Новых Эпизодов. */
@Composable
private fun AppNavigationBar(
    current: TopLevelDestination,
    newEpisodeCount: Int,
    onSelect: (TopLevelDestination) -> Unit,
) {
    KadrNavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            val selected = destination == current
            KadrNavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = if (selected) destination.selectedIcon else destination.unselectedIcon,
                label = stringResource(destination.label),
                badge = if (destination == TopLevelDestination.Library) newEpisodeCount else 0,
                badgeDescription = pluralStringResource(R.plurals.new_episodes_badge, newEpisodeCount, newEpisodeCount),
            )
        }
    }
}

/** Табы на телевизоре: влево от края любого экрана, и фокус встаёт на открытый таб, а не на ближайший. */
@Composable
private fun AppNavigationRail(
    current: TopLevelDestination,
    newEpisodeCount: Int,
    focus: TabsFocus,
    onSelect: (TopLevelDestination) -> Unit,
) {
    // «Назад» переключил на Главную, пока фокус на табах: фокус переходит вместе с выбором.
    LaunchedEffect(current) {
        if (focus.hasFocus) focus.focus(current)
    }
    KadrNavigationRail(
        Modifier
            .onFocusChanged { focus.hasFocus = it.hasFocus }
            .focusProperties { onEnter = { focus.focus(current) } }
            .focusGroup(),
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = destination == current
            KadrNavigationRailItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = if (selected) destination.selectedIcon else destination.unselectedIcon,
                label = stringResource(destination.label),
                modifier = Modifier.focusRequester(focus.requester(destination)),
                badge = if (destination == TopLevelDestination.Library) newEpisodeCount else 0,
                badgeDescription = pluralStringResource(R.plurals.new_episodes_badge, newEpisodeCount, newEpisodeCount),
            )
        }
    }
}

/** Фокус пульта на табах слева. */
@Stable
private class TabsFocus {
    private val requesters = TopLevelDestination.entries.associateWith { FocusRequester() }

    var hasFocus by mutableStateOf(false)

    fun requester(destination: TopLevelDestination): FocusRequester = requesters.getValue(destination)

    fun focus(destination: TopLevelDestination) {
        requester(destination).requestFocus()
    }
}

/** Плеер — поверх страницы аниме в табе «Список»: «Назад» ведёт туда, где видны и остальные Новые Эпизоды. */
private suspend fun openLink(
    link: EpisodeLink,
    stack: MutableList<NavKey>,
    viewModel: AppViewModel,
    player: PlayerHost,
) {
    if (!viewModel.canOpenSeries(link.seriesId)) return
    while (stack.size > 1) stack.removeLastOrNull()
    stack.add(SeriesKey(link.seriesId))
    val translationId = viewModel.rememberedTranslationId(link.seriesId, link.episodeId)
    if (translationId != null) {
        player.open(PlayerKey(link.seriesId, link.episodeId, translationId))
    } else {
        stack.add(TranslationsKey(link.seriesId, link.episodeId))
    }
}

/**
 * Экраны всех фич. Переходы кладут ключ в стек того таба или Плеера, где открыт экран (ADR 0009,
 * ADR 0011).
 */
private fun entryProvider(
    backStack: MutableList<NavKey>,
    player: PlayerHost,
): (NavKey) -> NavEntry<NavKey> {
    val openEpisode: (Int, Int) -> Unit = { seriesId, episodeId -> backStack.add(TranslationsKey(seriesId, episodeId)) }
    // Плеер открывается вместо листа Выбора Перевода и вместо прежнего Плеера: «Назад» ведёт туда,
    // откуда начали смотреть, а не к прошлому Переводу.
    val play: (Int, Int, Int) -> Unit = { seriesId, episodeId, translationId ->
        if (backStack.lastOrNull() is TranslationsKey) backStack.removeLastOrNull()
        player.open(PlayerKey(seriesId, episodeId, translationId))
    }
    val login: () -> Unit = { backStack.add(LoginKey) }
    val subscription: () -> Unit = { backStack.add(SubscriptionKey) }
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeLastOrNull() }
    return entryProvider {
        catalogEntries(
            onOpenSeries = { backStack.add(SeriesKey(it)) },
            onOpenEpisode = openEpisode,
            onPlay = play,
            onOpenCatalog = { backStack.add(it) },
            onBack = back,
        )
        seriesEntries(
            onOpenGenre = { backStack.add(CatalogKey(genreId = it)) },
            onOpenEpisode = openEpisode,
            onDownloadEpisode = { seriesId, episodeId, following ->
                val purpose = if (following) PickerPurpose.DownloadFollowing else PickerPurpose.DownloadEpisode
                backStack.add(TranslationsKey(seriesId, episodeId, purpose))
            },
            onPlay = play,
            onLogin = login,
            onSubscription = subscription,
            onBack = back,
        )
        playerEntries(
            onChooseTranslation = openEpisode,
            onLogin = login,
            onSubscription = subscription,
            onMinimize = player::minimize,
            onClose = player::close,
            miniPlayerBounds = { player.miniWindow },
        )
        libraryEntries(
            onOpenSeries = { backStack.add(SeriesKey(it)) },
            onOpenEpisode = openEpisode,
            onPlay = play,
        )
        accountEntries(
            onLogin = login,
            onOpenSettings = { backStack.add(SettingsKey) },
            onBack = back,
            checksUpdates = CHECKS_UPDATES,
            crashReportsAvailable = CrashReports.available,
        )
    }
}
