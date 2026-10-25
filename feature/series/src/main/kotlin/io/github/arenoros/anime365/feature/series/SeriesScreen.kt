package io.github.arenoros.anime365.feature.series

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.arenoros.anime365.core.data.repository.SeriesUnavailableException
import io.github.arenoros.anime365.core.designsystem.component.EmptyState
import io.github.arenoros.anime365.core.designsystem.component.ErrorState
import io.github.arenoros.anime365.core.designsystem.component.KadrTopAppBar
import io.github.arenoros.anime365.core.designsystem.component.PosterStatus
import io.github.arenoros.anime365.core.designsystem.component.RatingCompact
import io.github.arenoros.anime365.core.designsystem.component.RatingLarge
import io.github.arenoros.anime365.core.designsystem.component.SkeletonLine
import io.github.arenoros.anime365.core.designsystem.component.StaleDataBanner
import io.github.arenoros.anime365.core.designsystem.component.StatusBadge
import io.github.arenoros.anime365.core.designsystem.component.errorMessage
import io.github.arenoros.anime365.core.designsystem.component.skeleton
import io.github.arenoros.anime365.core.designsystem.icon.KadrIcons
import io.github.arenoros.anime365.core.designsystem.theme.KadrTheme
import io.github.arenoros.anime365.core.designsystem.tokens.KadrLayout
import io.github.arenoros.anime365.core.designsystem.tokens.KadrSpacing
import io.github.arenoros.anime365.core.model.Episode
import io.github.arenoros.anime365.core.model.LibraryStatus
import io.github.arenoros.anime365.core.model.ReleaseStatus
import io.github.arenoros.anime365.core.model.Series
import io.github.arenoros.anime365.core.model.SeriesDetails
import io.github.arenoros.anime365.core.model.SeriesSummary
import io.github.arenoros.anime365.core.model.WatchAction
import io.github.arenoros.anime365.core.ui.EpisodeGrid
import io.github.arenoros.anime365.core.ui.rememberNotificationPermissionRequest

private val POSTER_WIDTH = 120.dp

private enum class SeriesTab { Description, Episodes }

@Composable
internal fun SeriesRoute(
    seriesId: Int,
    onOpenGenre: (Int) -> Unit,
    onOpenEpisode: (episodeId: Int) -> Unit,
    onDownloadEpisode: (episodeId: Int, following: Boolean) -> Unit,
    onPlay: (episodeId: Int, translationId: Int) -> Unit,
    onBack: () -> Unit,
    viewModel: SeriesViewModel =
        hiltViewModel<SeriesViewModel, SeriesViewModel.Factory>(creationCallback = { it.create(seriesId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.blocked) {
        if (state.blocked) onBack()
    }
    if (state.blocked) return
    val requestNotifications = rememberNotificationPermissionRequest()
    SeriesScreen(
        state = state,
        onRetry = viewModel::refresh,
        onOpenGenre = onOpenGenre,
        onOpenEpisode = onOpenEpisode,
        onPlay = onPlay,
        onSetStatus = { status ->
            if (status == LibraryStatus.Watching) requestNotifications()
            viewModel.setStatus(status)
        },
        onSetWatched = viewModel::setWatched,
        onDownload = { episode, following -> onDownloadEpisode(episode.id, following) },
        onBack = onBack,
    )
}

@Composable
internal fun SeriesScreen(
    state: SeriesUiState,
    onRetry: () -> Unit,
    onOpenGenre: (Int) -> Unit,
    onOpenEpisode: (episodeId: Int) -> Unit,
    onPlay: (episodeId: Int, translationId: Int) -> Unit,
    onSetStatus: (LibraryStatus?) -> Unit,
    onSetWatched: (Episode, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDownload: (Episode, following: Boolean) -> Unit = { _, _ -> },
) {
    val series = state.series
    Scaffold(
        modifier = modifier,
        topBar = {
            KadrTopAppBar(
                title = series?.summary?.title.orEmpty(),
                onBack = onBack,
                actions = { series?.details?.siteUrl?.let { SiteMenu(it) } },
            )
        },
        containerColor = KadrTheme.palette.background,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                series == null && state.error is SeriesUnavailableException -> {
                    EmptyState(stringResource(R.string.feature_series_unavailable))
                }

                series == null && state.error != null -> {
                    ErrorState(errorMessage(state.error), onRetry = onRetry)
                }

                series == null -> {
                    HeaderPlaceholder()
                }

                else -> {
                    SeriesContent(
                        series,
                        state,
                        onRetry,
                        onOpenGenre,
                        onOpenEpisode,
                        onPlay,
                        onSetStatus,
                        onSetWatched,
                        onDownload,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesContent(
    series: Series,
    state: SeriesUiState,
    onRetry: () -> Unit,
    onOpenGenre: (Int) -> Unit,
    onOpenEpisode: (episodeId: Int) -> Unit,
    onPlay: (episodeId: Int, translationId: Int) -> Unit,
    onSetStatus: (LibraryStatus?) -> Unit,
    onSetWatched: (Episode, Boolean) -> Unit,
    onDownload: (Episode, following: Boolean) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(SeriesTab.Description) }
    var editingStatus by rememberSaveable { mutableStateOf(false) }
    var episodeActions by remember { mutableStateOf<Episode?>(null) }
    episodeActions?.let { episode ->
        EpisodeActionsFor(episode, series, state, onDownload, onSetWatched, onDone = { episodeActions = null })
    }
    val details = series.details
    if (editingStatus) {
        StatusSheet(
            status = state.status,
            onSelect = {
                onSetStatus(it)
                editingStatus = false
            },
            onDismiss = { editingStatus = false },
        )
    }
    LazyColumn(contentPadding = PaddingValues(bottom = KadrSpacing.Space6)) {
        if (state.error != null && details != null) item { StaleDataBanner(state.error) }
        item { Header(series.summary, details) }
        item { MainActions(state, onOpenEpisode, onPlay, onEditStatus = { editingStatus = true }) }
        if (details != null && details.genres.isNotEmpty()) item { Genres(details, onOpenGenre) }
        item { Tabs(tab, onSelect = { tab = it }) }
        when {
            details == null && state.error != null -> {
                item { ErrorState(errorMessage(state.error), onRetry = onRetry) }
            }

            details == null -> {
                item { TextPlaceholder() }
            }

            tab == SeriesTab.Description -> {
                description(details)
            }

            else -> {
                item {
                    EpisodeGrid(
                        details = details,
                        episodeCount = series.summary.episodeCount,
                        ongoing = series.summary.status == ReleaseStatus.Ongoing,
                        progress = state.progress,
                        onEpisodeClick = { onOpenEpisode(it.id) },
                        modifier = Modifier.padding(top = KadrSpacing.Space2),
                        newEpisodes = state.newEpisodes,
                        onEpisodeLongClick = { episodeActions = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeActionsFor(
    episode: Episode,
    series: Series,
    state: SeriesUiState,
    onDownload: (Episode, following: Boolean) -> Unit,
    onSetWatched: (Episode, Boolean) -> Unit,
    onDone: () -> Unit,
) {
    EpisodeActionsSheet(
        episode = episode,
        watched = state.progress[episode.id]?.watched == true,
        hasFollowing = series.details?.hasFollowing(episode) == true,
        onDownload = { following ->
            onDownload(episode, following)
            onDone()
        },
        onSetWatched = { watched ->
            onSetWatched(episode, watched)
            onDone()
        },
        onDismiss = onDone,
    )
}

@Composable
private fun Tabs(
    selected: SeriesTab,
    onSelect: (SeriesTab) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal, containerColor = KadrTheme.palette.surface) {
        SeriesTab.entries.forEach { entry ->
            val label =
                if (entry ==
                    SeriesTab.Description
                ) {
                    R.string.feature_series_tab_description
                } else {
                    R.string.feature_series_tab_episodes
                }
            Tab(
                selected = selected == entry,
                onClick = { onSelect(entry) },
                text = { Text(stringResource(label).uppercase()) },
            )
        }
    }
}

/** «НАЧАТЬ ПРОСМОТР» или «ПРОДОЛЖИТЬ» и «В СПИСОК» — друг под другом: рядом подпись первой не помещается. */
@Composable
private fun MainActions(
    state: SeriesUiState,
    onOpenEpisode: (episodeId: Int) -> Unit,
    onPlay: (episodeId: Int, translationId: Int) -> Unit,
    onEditStatus: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(KadrTheme.palette.surface)
            .padding(horizontal = KadrSpacing.Space4, vertical = KadrSpacing.Space2),
        verticalArrangement = Arrangement.spacedBy(KadrSpacing.Space2),
    ) {
        state.watchAction?.let { WatchButton(it, onOpenEpisode, onPlay, Modifier.fillMaxWidth()) }
        LibraryButton(state.status, onClick = onEditStatus, Modifier.fillMaxWidth())
    }
}

@Composable
private fun WatchButton(
    action: WatchAction,
    onOpenEpisode: (episodeId: Int) -> Unit,
    onPlay: (episodeId: Int, translationId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label =
        when (action) {
            is WatchAction.Resume -> {
                stringResource(
                    R.string.feature_series_continue_watching,
                    action.lastWatched.episodeLabel,
                )
            }

            is WatchAction.Next -> {
                stringResource(R.string.feature_series_continue_watching, action.episode.label)
            }

            is WatchAction.Start -> {
                stringResource(R.string.feature_series_start_watching)
            }
        }
    Button(
        onClick = {
            when (action) {
                is WatchAction.Resume -> onPlay(action.lastWatched.episodeId, action.lastWatched.translationId)
                is WatchAction.Next -> onOpenEpisode(action.episode.id)
                is WatchAction.Start -> onOpenEpisode(action.episode.id)
            }
        },
        modifier = modifier,
    ) {
        Icon(painterResource(KadrIcons.PlayArrow), contentDescription = null)
        Text(
            label.uppercase(),
            Modifier.padding(start = KadrSpacing.Space2),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Header(
    summary: SeriesSummary,
    details: SeriesDetails?,
) {
    Row(
        Modifier.fillMaxWidth().background(KadrTheme.palette.surface).padding(KadrSpacing.Space4),
        horizontalArrangement = Arrangement.spacedBy(KadrSpacing.Space4),
    ) {
        AsyncImage(
            model = details?.largePosterUrl ?: summary.posterUrl,
            contentDescription = null,
            modifier =
                Modifier
                    .width(POSTER_WIDTH)
                    .aspectRatio(KadrLayout.PosterRatio)
                    .clip(MaterialTheme.shapes.small)
                    .background(KadrTheme.palette.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KadrSpacing.Space2)) {
            Text(
                summary.title,
                Modifier.semantics { heading() },
                // Длинные русские названия при крупном шрифте иначе рвутся посреди слова.
                style = MaterialTheme.typography.headlineSmall.copy(hyphens = Hyphens.Auto),
                maxLines = 3,
            )
            summary.originalTitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = KadrTheme.palette.inkMuted)
            }
            val episodes = summary.episodeCount?.let { stringResource(R.string.feature_series_episodes_count, it) }
            Text(
                listOfNotNull(summary.kind, episodes, summary.season ?: summary.year?.toString()).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = KadrTheme.palette.inkMuted,
            )
            StatusBadge(summary.status.toPosterStatus())
            val score = summary.score
            if (score != null) RatingLarge(score) else RatingCompact(null)
        }
    }
}

@Composable
private fun Genres(
    details: SeriesDetails,
    onOpenGenre: (Int) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().background(KadrTheme.palette.surface).padding(horizontal = KadrSpacing.Space4),
        horizontalArrangement = Arrangement.spacedBy(KadrSpacing.Space2),
    ) {
        details.genres.forEach { genre ->
            AssistChip(onClick = { onOpenGenre(genre.id) }, label = { Text(genre.title) })
        }
    }
}

private fun LazyListScope.description(details: SeriesDetails) {
    item {
        var source by rememberSaveable { mutableIntStateOf(0) }
        Column(Modifier.padding(KadrSpacing.Space4), verticalArrangement = Arrangement.spacedBy(KadrSpacing.Space3)) {
            if (details.descriptions.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(KadrSpacing.Space2)) {
                    details.descriptions.forEachIndexed { index, description ->
                        FilterChip(
                            selected = index == source,
                            onClick = { source = index },
                            label = { Text(description.source) },
                        )
                    }
                }
            }
            val text = details.descriptions.getOrNull(source)?.text
            Text(
                text ?: stringResource(R.string.feature_series_no_description),
                style = MaterialTheme.typography.bodyLarge,
                color = if (text == null) KadrTheme.palette.inkMuted else KadrTheme.palette.ink,
            )
        }
    }
    if (details.links.isEmpty()) return
    item {
        Text(
            stringResource(R.string.feature_series_links),
            Modifier.padding(horizontal = KadrSpacing.Space4).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
    }
    item {
        val uriHandler = LocalUriHandler.current
        FlowRow(Modifier.padding(horizontal = KadrSpacing.Space2)) {
            details.links.forEach { link ->
                TextButton(onClick = { uriHandler.openUri(link.url) }) { Text(link.title) }
            }
        }
    }
}

@Composable
private fun SiteMenu(siteUrl: String) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val shareTitle = stringResource(R.string.feature_series_share)
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(KadrIcons.MoreVert), stringResource(R.string.feature_series_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_series_open_on_site)) },
                onClick = {
                    expanded = false
                    uriHandler.openUri(siteUrl)
                },
            )
            DropdownMenuItem(
                text = { Text(shareTitle) },
                onClick = {
                    expanded = false
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, siteUrl)
                    context.startActivity(Intent.createChooser(send, shareTitle))
                },
            )
        }
    }
}

@Composable
private fun HeaderPlaceholder() {
    Row(Modifier.padding(KadrSpacing.Space4), horizontalArrangement = Arrangement.spacedBy(KadrSpacing.Space4)) {
        Box(Modifier.width(POSTER_WIDTH).aspectRatio(KadrLayout.PosterRatio).skeleton())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KadrSpacing.Space2)) {
            SkeletonLine(Modifier.fillMaxWidth(), height = 24.dp)
            SkeletonLine(Modifier.fillMaxWidth(fraction = 0.6f))
            SkeletonLine(Modifier.fillMaxWidth(fraction = 0.8f))
        }
    }
}

@Composable
private fun TextPlaceholder() {
    Column(Modifier.padding(KadrSpacing.Space4), verticalArrangement = Arrangement.spacedBy(KadrSpacing.Space2)) {
        repeat(PLACEHOLDER_LINES) { SkeletonLine(Modifier.fillMaxWidth()) }
        SkeletonLine(Modifier.fillMaxWidth(fraction = 0.5f))
        Spacer(Modifier.height(KadrSpacing.Space2))
    }
}

private const val PLACEHOLDER_LINES = 4

/** После серии есть ещё серии того же типа — есть что скачать «этой и следующими». */
private fun SeriesDetails.hasFollowing(episode: Episode): Boolean =
    episodes.drop(episodes.indexOf(episode) + 1).any { it.type == episode.type }

private fun ReleaseStatus.toPosterStatus(): PosterStatus =
    when (this) {
        ReleaseStatus.Ongoing -> PosterStatus.Ongoing
        ReleaseStatus.Released -> PosterStatus.Released
        ReleaseStatus.Announced -> PosterStatus.Announced
    }
