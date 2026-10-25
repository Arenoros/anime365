package io.github.arenoros.anime365.core.data.catalog

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator.InitializeAction
import androidx.paging.RemoteMediator.MediatorResult
import io.github.arenoros.anime365.core.data.FakeAnime365Api
import io.github.arenoros.anime365.core.data.MutableClock
import io.github.arenoros.anime365.core.data.inMemoryCacheDatabase
import io.github.arenoros.anime365.core.data.seriesDto
import io.github.arenoros.anime365.core.database.CacheDatabase
import io.github.arenoros.anime365.core.database.model.SeriesEntity
import io.github.arenoros.anime365.core.model.CatalogFilter
import io.github.arenoros.anime365.core.network.ApiFields
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@OptIn(ExperimentalPagingApi::class)
@RunWith(RobolectricTestRunner::class)
class CatalogRemoteMediatorTest {
    private val database: CacheDatabase = inMemoryCacheDatabase()
    private val dao = database.seriesListDao()
    private val api = FakeAnime365Api()
    private val clock = MutableClock()
    private val pagingState = PagingState<Int, SeriesEntity>(emptyList(), null, PagingConfig(CATALOG_PAGE_SIZE), 0)

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun adultSearchUsesSeparateCacheAndDomain() =
        runTest {
            api.seriesList = { listOf(seriesDto(7).copy(isHentai = 1), seriesDto(8)) }
            val adult = CatalogQuery("example", CatalogFilter(isHentai = true))
            val normal = CatalogQuery("example", CatalogFilter())

            assertTrue(mediator(adult).load(LoadType.REFRESH, pagingState) is MediatorResult.Success)
            assertEquals("https://hentai365.org/api/series", api.seriesRequests.single().url)
            assertEquals("example", api.seriesRequests.single().query)
            assertNull(api.seriesRequests.single().chips)
            assertEquals(listOf(7), dao.observe(adult.listKey).first().map { it.id })
            assertTrue(dao.observe(normal.listKey).first().isEmpty())
            assertTrue(
                dao
                    .observe(adult.listKey)
                    .first()
                    .single()
                    .isHentai,
            )

            mediator(normal).load(LoadType.REFRESH, pagingState)
            assertEquals("series", api.seriesRequests.last().url)
            assertEquals(listOf(8), dao.observe(normal.listKey).first().map { it.id })
            assertEquals(listOf(7), dao.observe(adult.listKey).first().map { it.id })
        }

    @Test
    fun refreshStoresFirstPageInServerOrder() =
        runTest {
            api.seriesList = { request -> page(from = request.offset + 1) }
            val query = CatalogQuery("", CatalogFilter())

            val result = mediator(query).load(LoadType.REFRESH, pagingState)

            assertTrue(result is MediatorResult.Success && !result.endOfPaginationReached)
            assertEquals((1..CATALOG_PAGE_SIZE).toList(), dao.observe(query.listKey).first().map { it.id })
            assertEquals(CATALOG_PAGE_SIZE, dao.state(query.listKey)!!.nextOffset)
            val request = api.seriesRequests.single()
            assertEquals(ApiFields.SERIES_SUMMARY, request.fields)
            assertNull("пустой фильтр не отправляется", request.chips)
        }

    @Test
    fun appendContinuesFromNextOffsetUntilShortPage() =
        runTest {
            api.seriesList = { request -> if (request.offset == 0) page(from = 1) else page(from = 100, size = 5) }
            val query = CatalogQuery("", CatalogFilter(ongoingOnly = true))
            val mediator = mediator(query)

            mediator.load(LoadType.REFRESH, pagingState)
            val append = mediator.load(LoadType.APPEND, pagingState)

            assertTrue(append is MediatorResult.Success && append.endOfPaginationReached)
            assertEquals(listOf(0, CATALOG_PAGE_SIZE), api.seriesRequests.map { it.offset })
            assertEquals("isAiring=1", api.seriesRequests.last().chips)
            assertEquals(CATALOG_PAGE_SIZE + 5, dao.observe(query.listKey).first().size)
            assertNull(dao.state(query.listKey)!!.nextOffset)
        }

    @Test
    fun searchFiltersOnDeviceAndSkipsPagesWithoutMatches() =
        runTest {
            api.seriesList = { request ->
                if (request.offset == 0) {
                    page(from = 1)
                } else {
                    page(from = 1000).mapIndexed { index, dto -> if (index == 3) dto.copy(isAiring = 1) else dto }
                }
            }
            val query = CatalogQuery("frieren", CatalogFilter(ongoingOnly = true))

            mediator(query).load(LoadType.REFRESH, pagingState)

            assertEquals(listOf(0, CATALOG_PAGE_SIZE), api.seriesRequests.map { it.offset })
            assertTrue(api.seriesRequests.all { it.query == "frieren" && it.chips == null })
            assertEquals(ApiFields.SERIES_SEARCH, api.seriesRequests.first().fields)
            assertEquals(listOf(1003), dao.observe(query.listKey).first().map { it.id })
            assertEquals(CATALOG_PAGE_SIZE * 2, dao.state(query.listKey)!!.nextOffset)
        }

    @Test
    fun searchStopsAfterTenPagesWithoutMatches() =
        runTest {
            api.seriesList = { request -> page(from = request.offset + 1) }
            val query = CatalogQuery("a", CatalogFilter(ongoingOnly = true))

            val result = mediator(query).load(LoadType.REFRESH, pagingState)

            assertTrue(result is MediatorResult.Success && result.endOfPaginationReached)
            assertEquals(10, api.seriesRequests.size)
        }

    @Test
    fun networkErrorIsReturnedNotThrown() =
        runTest {
            api.seriesList = { throw IOException("offline") }

            val result = mediator(CatalogQuery("", CatalogFilter())).load(LoadType.REFRESH, pagingState)

            assertTrue(result is MediatorResult.Error && result.throwable is IOException)
        }

    @Test
    fun freshListSkipsInitialRefreshForOneDay() =
        runTest {
            api.seriesList = { page(from = 1) }
            val query = CatalogQuery("", CatalogFilter())
            mediator(query).load(LoadType.REFRESH, pagingState)

            clock.advanceMinutes(23 * 60)
            assertEquals(InitializeAction.SKIP_INITIAL_REFRESH, mediator(query).initialize())
            clock.advanceMinutes(2 * 60)
            assertEquals(InitializeAction.LAUNCH_INITIAL_REFRESH, mediator(query).initialize())
        }

    private fun mediator(query: CatalogQuery) = CatalogRemoteMediator(query, api, dao, clock)

    private fun page(
        from: Int,
        size: Int = CATALOG_PAGE_SIZE,
    ) = (from until from + size).map { seriesDto(it) }
}
