package io.github.arenoros.anime365.feature.catalog.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.arenoros.anime365.core.designsystem.component.EmptyState
import io.github.arenoros.anime365.core.designsystem.component.ErrorState
import io.github.arenoros.anime365.core.designsystem.component.KadrSearchField
import io.github.arenoros.anime365.core.designsystem.component.KadrTopAppBar
import io.github.arenoros.anime365.core.designsystem.component.PosterCardPlaceholder
import io.github.arenoros.anime365.core.designsystem.component.StaleDataBanner
import io.github.arenoros.anime365.core.designsystem.component.errorMessage
import io.github.arenoros.anime365.core.designsystem.theme.KadrTheme
import io.github.arenoros.anime365.core.designsystem.theme.posterColumns
import io.github.arenoros.anime365.core.designsystem.tokens.KadrSpacing
import io.github.arenoros.anime365.core.model.CatalogFilter
import io.github.arenoros.anime365.core.model.SeriesSummary
import io.github.arenoros.anime365.feature.catalog.R
import io.github.arenoros.anime365.feature.catalog.SeriesPosterCard
import io.github.arenoros.anime365.feature.catalog.navigation.CatalogKey

private const val PLACEHOLDER_ROWS = 4

@Composable
internal fun CatalogRoute(
    key: CatalogKey,
    onOpenSeries: (Int) -> Unit,
    onBack: (() -> Unit)?,
    viewModel: CatalogViewModel =
        hiltViewModel<CatalogViewModel, CatalogViewModel.Factory>(creationCallback = { it.create(key) }),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val adultContent by viewModel.adultContent.collectAsStateWithLifecycle()
    CatalogScreen(
        query = query,
        filter = filter,
        series = viewModel.series.collectAsLazyPagingItems(),
        focusSearch = key.focusSearch,
        onQueryChange = viewModel::onQueryChange,
        onFilterChange = viewModel::onFilterChange,
        onReset = viewModel::reset,
        onOpenSeries = onOpenSeries,
        onBack = onBack,
        adultContent = adultContent,
    )
}

@Composable
internal fun CatalogScreen(
    query: String,
    filter: CatalogFilter,
    series: LazyPagingItems<SeriesSummary>,
    focusSearch: Boolean,
    onQueryChange: (String) -> Unit,
    onFilterChange: (CatalogFilter) -> Unit,
    onReset: () -> Unit,
    onOpenSeries: (Int) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    adultContent: Boolean = false,
) {
    Scaffold(
        modifier = modifier,
        topBar = { KadrTopAppBar(stringResource(R.string.feature_catalog_catalog_title), onBack = onBack) },
        containerColor = KadrTheme.palette.background,
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (adultContent) {
                SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = KadrSpacing.Space4)) {
                    listOf(R.string.feature_catalog_section_anime, R.string.feature_catalog_section_hentai)
                        .forEachIndexed { index, label ->
                            SegmentedButton(
                                selected = filter.isHentai == (index == 1),
                                onClick = { onFilterChange(filter.copy(isHentai = index == 1)) },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                            ) { Text(stringResource(label)) }
                        }
                }
            }
            val focusRequester = remember { FocusRequester() }
            // Фокус — один раз: при возврате с экрана Аниме клавиатура не должна выскакивать снова.
            var focusRequested by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(focusSearch) {
                if (focusSearch && !focusRequested) {
                    focusRequested = true
                    focusRequester.requestFocus()
                }
            }
            KadrSearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.feature_catalog_search_placeholder),
                modifier =
                    Modifier
                        .padding(horizontal = KadrSpacing.Space4, vertical = KadrSpacing.Space2)
                        .focusRequester(focusRequester),
            )
            CatalogFilterChips(filter = filter, onFilterChange = onFilterChange, onReset = onReset)
            val mediatorRefresh = series.loadState.mediator?.refresh
            if (mediatorRefresh is LoadState.Error && series.itemCount > 0) StaleDataBanner(mediatorRefresh.error)
            CatalogGrid(
                series = series,
                canReset = query.isNotEmpty() || !filter.isEmpty,
                onReset = onReset,
                onOpenSeries = onOpenSeries,
                isHentai = adultContent && filter.isHentai,
            )
        }
    }
}

@Composable
private fun CatalogGrid(
    series: LazyPagingItems<SeriesSummary>,
    canReset: Boolean,
    onReset: () -> Unit,
    onOpenSeries: (Int) -> Unit,
    isHentai: Boolean,
) {
    val refresh = series.loadState.refresh
    when {
        series.itemCount == 0 && refresh is LoadState.Error -> {
            ErrorState(errorMessage(refresh.error), onRetry = series::retry)
        }

        series.itemCount == 0 && refresh is LoadState.NotLoading && series.loadState.append.endOfPaginationReached -> {
            EmptyState(
                message = stringResource(R.string.feature_catalog_nothing_found),
                actionLabel = stringResource(R.string.feature_catalog_reset_filters).takeIf { canReset },
                onAction = onReset,
            )
        }

        else -> {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = posterColumns(maxWidth)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(KadrSpacing.Space4),
                    horizontalArrangement = Arrangement.spacedBy(KadrSpacing.Space2),
                    verticalArrangement = Arrangement.spacedBy(KadrSpacing.Space4),
                ) {
                    if (series.itemCount == 0) items(columns * PLACEHOLDER_ROWS) { PosterCardPlaceholder() }
                    items(series.itemCount, key = series.itemKey { it.id }) { index ->
                        series[index]?.takeIf { it.isHentai == isHentai }?.let { item ->
                            SeriesPosterCard(item, onClick = { onOpenSeries(item.id) })
                        }
                    }
                    val append = series.loadState.append
                    if (append is LoadState.Loading) items(columns) { PosterCardPlaceholder() }
                    if (append is LoadState.Error) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ErrorState(errorMessage(append.error), onRetry = series::retry)
                        }
                    }
                }
            }
        }
    }
}
