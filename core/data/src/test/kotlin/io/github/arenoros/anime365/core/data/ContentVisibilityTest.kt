package io.github.arenoros.anime365.core.data

import io.github.arenoros.anime365.core.data.repository.LocalLibraryRepository
import io.github.arenoros.anime365.core.model.LibraryStatus
import io.github.arenoros.anime365.core.model.Series
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContentVisibilityTest {
    @Test
    fun hidingKeepsLibraryAndSurvivesCacheClear() =
        runTest {
            val cache = inMemoryCacheDatabase()
            val database = inMemoryLibraryDatabase()
            try {
                val settings = FakeSettingsStore()
                val library = LocalLibraryRepository(database.libraryDao(), settings, MutableClock())
                val adult = seriesDto(7).copy(isHentai = 1).toEntity()!!
                val regular = seriesDto(8).toEntity()!!
                cache.seriesDao().upsert(listOf(adult, regular))
                library.setStatus(Series(adult.toSummary(), null), LibraryStatus.Planned)
                library.setStatus(Series(regular.toSummary(), null), LibraryStatus.Planned)
                val visibility = ContentVisibility(settings, cache.seriesDao(), database.libraryDao())

                assertEquals(setOf(7), visibility.hiddenSeriesIds.first())
                assertEquals(listOf(8), library.entries().first().map { it.seriesId })
                settings.updateSettings { it.copy(adultContent = true) }
                assertEquals(emptySet<Int>(), visibility.hiddenSeriesIds.first())
                assertEquals(
                    setOf(7, 8),
                    library
                        .entries()
                        .first()
                        .map { it.seriesId }
                        .toSet(),
                )

                cache.clearAllTables()
                settings.updateSettings { it.copy(adultContent = false) }
                assertEquals(setOf(7), visibility.hiddenSeriesIds.first())
                assertEquals(listOf(8), library.entries().first().map { it.seriesId })
                assertEquals(2, database.libraryDao().allSeries().size)
                settings.updateSettings { it.copy(adultContent = true) }
                assertEquals(
                    setOf(7, 8),
                    library
                        .entries()
                        .first()
                        .map { it.seriesId }
                        .toSet(),
                )
            } finally {
                cache.close()
                database.close()
            }
        }
}
