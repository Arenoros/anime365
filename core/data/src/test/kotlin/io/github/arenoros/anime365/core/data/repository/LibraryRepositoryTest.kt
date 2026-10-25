package io.github.arenoros.anime365.core.data.repository

import io.github.arenoros.anime365.core.data.FakeSettingsStore
import io.github.arenoros.anime365.core.data.MutableClock
import io.github.arenoros.anime365.core.data.inMemoryLibraryDatabase
import io.github.arenoros.anime365.core.database.library.LibraryDatabase
import io.github.arenoros.anime365.core.model.Episode
import io.github.arenoros.anime365.core.model.EpisodeProgress
import io.github.arenoros.anime365.core.model.LibraryStatus
import io.github.arenoros.anime365.core.model.ReleaseStatus
import io.github.arenoros.anime365.core.model.Series
import io.github.arenoros.anime365.core.model.SeriesDetails
import io.github.arenoros.anime365.core.model.SeriesSummary
import io.github.arenoros.anime365.core.model.Translation
import io.github.arenoros.anime365.core.model.TranslationChoice
import io.github.arenoros.anime365.core.model.TranslationKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryTest {
    private val database: LibraryDatabase = inMemoryLibraryDatabase()
    private val clock = MutableClock()
    private val repository = LocalLibraryRepository(database.libraryDao(), FakeSettingsStore(), clock)

    private val series =
        SeriesSummary(30414, "Фрирен", null, "poster", null, 28, 2023, null, 9.2, ReleaseStatus.Ongoing)
    private val first = Episode(101, 1.0, "1 серия", "tv", Instant.parse("2026-10-01T10:00:00Z"))
    private val second = Episode(102, 2.0, "2 серия", "tv", Instant.parse("2026-10-08T10:00:00Z"))
    private val anilibria = Translation(1, 101, TranslationKind.VoiceRu, "AniLibria", "tv", 1080)

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun adultNewEpisodeCountIsHiddenButProgressAndNewMarkersRemain() =
        runTest {
            val settings = FakeSettingsStore()
            val library = LocalLibraryRepository(database.libraryDao(), settings, clock)
            val adult = frieren(first).copy(summary = series.copy(isHentai = true))
            library.setStatus(adult, LibraryStatus.Watching)
            library.recordNewEpisodes(adult.copy(details = adult.details!!.copy(episodes = listOf(first, second))))
            assertEquals(0, library.newEpisodeCount().first())
            assertEquals(setOf(102), library.newEpisodeIds(series.id))
            settings.updateSettings { it.copy(adultContent = true) }
            assertEquals(1, library.newEpisodeCount().first())
            library.save(Playback(adult.summary, first, anilibria, 300, 1000))
            assertEquals(emptyList<Any>(), library.continueWatching().first())
            settings.updateSettings { it.copy(adultContent = false) }
            assertEquals(emptyList<Any>(), library.entries().first())
            assertEquals(300L, library.progressOf(first.id)!!.positionMs)
        }

    @Test
    fun episodeIsWatchedFromEightyFivePercentAndStaysWatched() =
        runTest {
            repository.save(Playback(series, first, anilibria, positionMs = 849, durationMs = 1000))
            assertFalse(repository.progressOf(101)!!.watched)

            repository.save(Playback(series, first, anilibria, positionMs = 850, durationMs = 1000))
            repository.save(Playback(series, first, anilibria, positionMs = 10, durationMs = 1000))

            assertTrue(repository.progressOf(101)!!.watched)
        }

    @Test
    fun continueWatchingShowsLastUnfinishedEpisodePerSeries() =
        runTest {
            repository.save(Playback(series, first, anilibria, positionMs = 1000, durationMs = 1000))
            assertEquals(emptyList<Any>(), repository.continueWatching().first())

            clock.advanceMinutes(1)
            repository.save(Playback(series, second, anilibria.copy(id = 2, episodeId = 102), 300, 1000))

            val cards = repository.continueWatching().first()
            assertEquals(1, cards.size)
            assertEquals("Фрирен", cards[0].seriesTitle)
            assertEquals(102, cards[0].lastWatched.episodeId)
            assertEquals("2 серия", cards[0].lastWatched.episodeLabel)
            assertEquals(2, cards[0].lastWatched.translationId)
            assertEquals(0.3f, cards[0].lastWatched.progress.fraction, 0.001f)
            assertEquals(setOf(101, 102), repository.progress(30414).first().keys)
        }

    @Test
    fun rememberedChoiceIsKindAndTeam() =
        runTest {
            repository.save(Playback(series, first, anilibria, 0, 1000))

            assertEquals(TranslationChoice(TranslationKind.VoiceRu, "AniLibria"), repository.choice(30414))
        }

    @Test
    fun entryCountsWatchedEpisodesAndKeepsStatusThroughPlayback() =
        runTest {
            repository.setStatus(frieren(first, second), LibraryStatus.Watching)
            repository.save(Playback(series, first, anilibria, positionMs = 1000, durationMs = 1000))

            val entry = repository.entries().first().single()
            assertEquals(LibraryStatus.Watching, entry.status)
            assertEquals(1, entry.watchedEpisodes)
            assertEquals(28, entry.episodeCount)
            assertEquals(LibraryStatus.Watching, repository.status(30414).first())
        }

    @Test
    fun startedSeriesGoesToWatchingUnlessStatusWasChosen() =
        runTest {
            repository.save(Playback(series, first, anilibria, 10, 1000))
            assertEquals(LibraryStatus.Watching, repository.status(30414).first())
            // «0 из 28 эп.»: число Эпизодов приходит вместе с Прогрессом, страницу аниме не открывали.
            assertEquals(
                28,
                repository
                    .entries()
                    .first()
                    .single()
                    .episodeCount,
            )

            repository.setStatus(frieren(first), LibraryStatus.Planned)
            repository.save(Playback(series, second, anilibria.copy(id = 2, episodeId = 102), 10, 1000))
            assertEquals(LibraryStatus.Planned, repository.status(30414).first())
        }

    @Test
    fun manualMarkKeepsPositionAndCanBeUndone() =
        runTest {
            repository.save(Playback(series, first, anilibria, positionMs = 300, durationMs = 1000))

            repository.setWatched(series, first, watched = true)
            assertEquals(EpisodeProgress(101, 300, 1000, watched = true), repository.progressOf(101))

            repository.setWatched(series, first, watched = false)
            assertFalse(repository.progressOf(101)!!.watched)
        }

    @Test
    fun episodeMarkedWithoutWatchingIsNoLongerNew() =
        runTest {
            repository.setStatus(frieren(first), LibraryStatus.Watching)
            repository.recordNewEpisodes(frieren(first, second))

            repository.setWatched(series, second, watched = true)

            assertTrue(repository.progressOf(102)!!.watched)
            assertEquals(emptySet<Int>(), repository.newEpisodeIds(30414))
        }

    @Test
    fun removedFromListDoesNotReturnByItself() =
        runTest {
            repository.save(Playback(series, first, anilibria, 10, 1000))
            repository.setStatus(frieren(first), null)

            repository.save(Playback(series, second, anilibria.copy(id = 2, episodeId = 102), 10, 1000))

            assertNull(repository.status(30414).first())
        }

    @Test
    fun removingFromListKeepsProgressAndChoice() =
        runTest {
            repository.save(Playback(series, first, anilibria, 0, 1000))
            repository.setStatus(frieren(first), LibraryStatus.Planned)
            repository.setStatus(frieren(first), null)

            assertEquals(emptyList<Any>(), repository.entries().first())
            assertNull(repository.status(30414).first())
            assertEquals(TranslationChoice(TranslationKind.VoiceRu, "AniLibria"), repository.choice(30414))
        }

    @Test
    fun episodeUploadedAfterLastVisitIsNewAndReportedOnce() =
        runTest {
            repository.setStatus(frieren(first), LibraryStatus.Watching)

            val withSecond = frieren(first, second)
            assertEquals(listOf(second), repository.recordNewEpisodes(withSecond))
            assertEquals(emptyList<Episode>(), repository.recordNewEpisodes(withSecond))
            assertEquals(setOf(102), repository.newEpisodeIds(30414))
            assertEquals(1, repository.newEpisodeCount().first())
            assertEquals(
                1,
                repository
                    .entries()
                    .first()
                    .single()
                    .newEpisodes,
            )
        }

    @Test
    fun openingSeriesOrStartingEpisodeClearsNewEpisodes() =
        runTest {
            val third = Episode(103, 3.0, "3 серия", "tv", Instant.parse("2026-10-15T10:00:00Z"))
            repository.setStatus(frieren(first), LibraryStatus.Watching)
            repository.recordNewEpisodes(frieren(first, second, third))

            repository.save(Playback(series, second, anilibria.copy(id = 2, episodeId = 102), 10, 1000))
            assertEquals(setOf(103), repository.newEpisodeIds(30414))

            repository.markSeen(frieren(first, second, third))
            assertEquals(emptySet<Int>(), repository.newEpisodeIds(30414))
            assertEquals(emptyList<Episode>(), repository.recordNewEpisodes(frieren(first, second, third)))
        }

    @Test
    fun firstCheckOfImportedSeriesOnlyRemembersWhatIsOut() =
        runTest {
            // Аниме попало в Список без открытия страницы — например, импортом.
            repository.setStatus(Series(series, details = null), LibraryStatus.Watching)

            assertEquals(emptyList<Episode>(), repository.recordNewEpisodes(frieren(first, second)))
            assertEquals(emptySet<Int>(), repository.newEpisodeIds(30414))
        }

    @Test
    fun newEpisodesCountOnlyForWatching() =
        runTest {
            repository.setStatus(frieren(first), LibraryStatus.Watching)
            repository.recordNewEpisodes(frieren(first, second))
            repository.setStatus(frieren(first), LibraryStatus.Dropped)

            assertEquals(0, repository.newEpisodeCount().first())
        }

    private fun frieren(vararg episodes: Episode) =
        Series(series, SeriesDetails("tv", null, null, emptyList(), emptyList(), emptyList(), episodes.toList()))
}
