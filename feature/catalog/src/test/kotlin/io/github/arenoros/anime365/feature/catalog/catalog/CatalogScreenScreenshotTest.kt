package io.github.arenoros.anime365.feature.catalog.catalog

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import io.github.arenoros.anime365.core.model.CatalogFilter
import io.github.arenoros.anime365.core.model.SeriesSummary
import io.github.arenoros.anime365.core.model.SeriesType
import io.github.arenoros.anime365.core.testing.captureScreen
import io.github.arenoros.anime365.feature.catalog.summary
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class CatalogScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val filter =
        CatalogFilter(types = setOf(SeriesType.Tv), year = 2026, genreIds = setOf(8), ongoingOnly = true)
    private val items = (1..9).map { summary(it, title = "Провожающая в последний путь Фрирен $it") }

    @Test
    fun grid() = capture("catalog", items, filter)

    @Test
    fun gridLargeFont() = capture("catalog_font200", items, filter, fontScale = 2f)

    @Test
    fun nothingFound() = capture("catalog_empty", emptyList(), filter, query = "абвгд")

    @Test
    fun adultSection() =
        capture(
            "catalog_hentai",
            items.map { it.copy(isHentai = true, title = "Пример тайтла ${it.id}") },
            filter.copy(isHentai = true),
            adultContent = true,
        )

    private fun capture(
        name: String,
        series: List<SeriesSummary>,
        filter: CatalogFilter,
        query: String = "",
        fontScale: Float = 1f,
        adultContent: Boolean = false,
    ) = compose.captureScreen(name, fontScale) {
        val complete = LoadState.NotLoading(endOfPaginationReached = true)
        val pages = flowOf(PagingData.from(series, LoadStates(complete, complete, complete))).collectAsLazyPagingItems()
        CatalogScreen(
            query = query,
            filter = filter,
            adultContent = adultContent,
            series = pages,
            focusSearch = false,
            onQueryChange = {},
            onFilterChange = {},
            onReset = {},
            onOpenSeries = {},
            onBack = null,
        )
    }
}
