package io.github.arenoros.anime365.core.data.catalog

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.github.arenoros.anime365.core.data.catchingFailures
import io.github.arenoros.anime365.core.data.toEntity
import io.github.arenoros.anime365.core.database.dao.SeriesListDao
import io.github.arenoros.anime365.core.database.model.ListStateEntity
import io.github.arenoros.anime365.core.database.model.SeriesEntity
import io.github.arenoros.anime365.core.database.model.SeriesListItemEntity
import io.github.arenoros.anime365.core.network.Anime365Api
import io.github.arenoros.anime365.core.network.ApiFields
import io.github.arenoros.anime365.core.network.model.SeriesDto
import java.time.Clock
import java.util.concurrent.TimeUnit

internal const val CATALOG_PAGE_SIZE = 48
internal val CATALOG_TTL_MILLIS = TimeUnit.HOURS.toMillis(24)

// ponytail: дальше 10 страниц поиска без единого совпадения с фильтром не ищем — считаем, что результатов нет.
private const val MAX_EMPTY_SEARCH_PAGES = 10

/**
 * Страницы Каталога по `offset`: курсора `afterId` у отсортированного по рейтингу списка нет.
 * Позиция в списке — место на сервере, поэтому отфильтрованные на устройстве результаты поиска
 * сохраняют порядок сайта.
 */
@OptIn(ExperimentalPagingApi::class)
internal class CatalogRemoteMediator(
    private val query: CatalogQuery,
    private val api: Anime365Api,
    private val dao: SeriesListDao,
    private val clock: Clock,
) : RemoteMediator<Int, SeriesEntity>() {
    override suspend fun initialize(): InitializeAction {
        val fetchedAt = dao.state(query.listKey)?.fetchedAt
        val fresh = fetchedAt != null && clock.millis() - fetchedAt < CATALOG_TTL_MILLIS
        return if (fresh) InitializeAction.SKIP_INITIAL_REFRESH else InitializeAction.LAUNCH_INITIAL_REFRESH
    }

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, SeriesEntity>,
    ): MediatorResult {
        // Список растёт только вниз; null — догружать нечего.
        val offset =
            when (loadType) {
                LoadType.REFRESH -> 0
                LoadType.PREPEND -> null
                LoadType.APPEND -> dao.state(query.listKey)?.nextOffset
            } ?: return MediatorResult.Success(endOfPaginationReached = true)
        return catchingFailures {
            val page = if (query.isSearch) search(offset) else filter(offset)
            val now = clock.millis()
            if (loadType == LoadType.REFRESH) dao.deleteStale(CATALOG_KEY_PREFIX, now - CATALOG_TTL_MILLIS)
            val positioned = page.series.mapNotNull { (position, dto) -> dto.toEntity()?.let { position to it } }
            dao.save(
                state = ListStateEntity(query.listKey, page.nextOffset, now),
                series = positioned.map { it.second },
                items =
                    positioned.map { (position, entity) ->
                        SeriesListItemEntity(query.listKey, entity.id, position)
                    },
                replace = loadType == LoadType.REFRESH,
            )
            page.nextOffset == null
        }.fold(
            onSuccess = { MediatorResult.Success(endOfPaginationReached = it) },
            onFailure = { MediatorResult.Error(it) },
        )
    }

    private suspend fun filter(offset: Int): Page {
        val series =
            api
                .series(
                    fields = ApiFields.SERIES_SUMMARY,
                    limit = CATALOG_PAGE_SIZE,
                    offset = offset,
                    chips = query.chips.ifEmpty { null },
                    url = query.url,
                ).unwrap()
        return Page(
            series.withPositions(offset).filter { (it.second.isHentai == 1) == query.filter.isHentai },
            series.nextOffset(offset),
        )
    }

    /** Догружает страницы, пока не найдётся хоть одно совпадение с фильтром: иначе Paging встанет. */
    private suspend fun search(offset: Int): Page {
        var next: Int? = offset
        var pages = 0
        val matched = mutableListOf<Pair<Int, SeriesDto>>()
        while (matched.isEmpty()) {
            val current = next ?: break
            if (pages++ == MAX_EMPTY_SEARCH_PAGES) return Page(matched, null)
            val series =
                api
                    .series(
                        fields = ApiFields.SERIES_SEARCH,
                        limit = CATALOG_PAGE_SIZE,
                        offset = current,
                        query = query.text,
                        url = query.url,
                    ).unwrap()
            series.withPositions(current).filterTo(matched) { query.matches(it.second) }
            next = series.nextOffset(current)
        }
        return Page(matched, next)
    }

    private fun List<SeriesDto>.withPositions(offset: Int) = mapIndexed { index, dto -> offset + index to dto }

    private fun List<SeriesDto>.nextOffset(offset: Int) = if (size < CATALOG_PAGE_SIZE) null else offset + size

    private class Page(
        val series: List<Pair<Int, SeriesDto>>,
        val nextOffset: Int?,
    )
}
