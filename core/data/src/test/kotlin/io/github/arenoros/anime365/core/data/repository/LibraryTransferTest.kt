package io.github.arenoros.anime365.core.data.repository

import androidx.test.core.app.ApplicationProvider
import io.github.arenoros.anime365.core.data.FakeSettingsStore
import io.github.arenoros.anime365.core.data.MutableClock
import io.github.arenoros.anime365.core.data.inMemoryLibraryDatabase
import io.github.arenoros.anime365.core.database.library.EpisodeProgressEntity
import io.github.arenoros.anime365.core.database.library.LibraryDatabase
import io.github.arenoros.anime365.core.database.library.LibrarySeriesEntity
import io.github.arenoros.anime365.core.model.Episode
import io.github.arenoros.anime365.core.model.LibraryStatus
import io.github.arenoros.anime365.core.model.ReleaseStatus
import io.github.arenoros.anime365.core.model.Series
import io.github.arenoros.anime365.core.model.SeriesSummary
import io.github.arenoros.anime365.core.model.Translation
import io.github.arenoros.anime365.core.model.TranslationKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class LibraryTransferTest {
    private val source: LibraryDatabase = inMemoryLibraryDatabase()
    private val target: LibraryDatabase = inMemoryLibraryDatabase()

    private val frieren =
        SeriesSummary(30414, "Фрирен", null, "poster", null, 28, 2023, null, 9.2, ReleaseStatus.Ongoing)
    private val first = Episode(101, 1.0, "1 серия", "tv")
    private val anilibria = Translation(1, 101, TranslationKind.VoiceRu, "AniLibria", "tv", 1080)

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    @Test
    fun adultFlagSurvivesExportImportAndMergeWithOlderRecord() =
        runTest {
            val adult = row(7, status = "planned").copy(isHentai = true)
            source.libraryDao().upsertAll(listOf(adult), emptyList())
            val file = ByteArrayOutputStream()
            transfer(source).write(file)
            transfer(target).read(file.toByteArray().inputStream(), ImportMode.Replace)
            assertTrue(
                target
                    .libraryDao()
                    .allSeries()
                    .single()
                    .isHentai,
            )
            assertTrue(mergeSeries(listOf(adult), listOf(adult.copy(isHentai = false))).single().isHentai)
        }

    @Test
    fun replaceRestoresStatusProgressAndChoice() =
        runTest {
            val library = LocalLibraryRepository(source.libraryDao(), FakeSettingsStore(), MutableClock())
            library.save(Playback(frieren, first, anilibria, positionMs = 950, durationMs = 1000))
            library.setStatus(Series(frieren, details = null), LibraryStatus.Planned)
            // Аниме убрали из Списка, но Прогресс и запомненный Перевод остались — они тоже в файле.
            val bebop = frieren.copy(id = 1414, title = "Ковбой Бибоп")
            library.save(Playback(bebop, first.copy(id = 201), anilibria.copy(id = 2, episodeId = 201), 300, 1000))
            library.setStatus(Series(bebop, details = null), null)
            // В Списке, куда импортируют, уже есть другое аниме: замена его убирает.
            target.libraryDao().upsertAll(listOf(row(1, status = "planned")), emptyList())

            val file = ByteArrayOutputStream()
            val summary = LibraryFileSummary(inList = 1, total = 2)
            assertEquals(summary, transfer(source).write(file))
            assertEquals(summary, transfer(target).read(file.toByteArray().inputStream(), ImportMode.Replace))

            assertEquals(source.libraryDao().allSeries(), target.libraryDao().allSeries())
            assertEquals(source.libraryDao().allProgress(), target.libraryDao().allProgress())
        }

    @Test
    fun mergeTakesLaterStatusAndLaterPlaybackSeparately() {
        val current = row(1, status = "watching", statusChangedAt = 10, team = "AniLibria", lastWatchedAt = 50)
        val imported = row(1, status = "completed", statusChangedAt = 20, team = "Amazing Dubbing", lastWatchedAt = 40)

        val merged = mergeSeries(listOf(current), listOf(imported)).single()

        assertEquals("completed", merged.status)
        assertEquals("AniLibria", merged.team)
    }

    @Test
    fun mergeKeepsWatchedMarkFromEitherSide() {
        val current = progress(101, positionMs = 900, watched = true, updatedAt = 10)
        val imported = progress(101, positionMs = 100, watched = false, updatedAt = 20)

        val merged = mergeProgress(listOf(current), listOf(imported)).single()

        assertEquals(100, merged.positionMs)
        assertTrue(merged.watched)
    }

    @Test
    fun newerFileFormatIsRejectedAndLibraryUntouched() =
        runTest {
            target.libraryDao().upsertAll(listOf(row(1, status = "planned")), emptyList())

            val error =
                runCatching {
                    transfer(target).read("""{"schemaVersion":2,"series":[]}""".byteInputStream(), ImportMode.Replace)
                }.exceptionOrNull()

            assertEquals(2, (error as UnsupportedLibraryFileException).schemaVersion)
            assertEquals(1, target.libraryDao().allSeries().size)
        }

    @Test
    fun foreignJsonIsRejected() =
        runTest {
            val error = runCatching { transfer(target).read("""{"items":[]}""".byteInputStream(), ImportMode.Merge) }

            assertTrue(error.exceptionOrNull() is SerializationException)
        }

    private fun transfer(database: LibraryDatabase) =
        JsonLibraryTransfer(ApplicationProvider.getApplicationContext(), database.libraryDao())

    private fun row(
        seriesId: Int,
        status: String? = null,
        statusChangedAt: Long? = null,
        team: String? = null,
        lastWatchedAt: Long? = null,
    ) = LibrarySeriesEntity(
        seriesId,
        "Аниме $seriesId",
        null,
        "voiceRu",
        team,
        null,
        null,
        lastWatchedAt,
        status,
        statusChangedAt,
    )

    private fun progress(
        episodeId: Int,
        positionMs: Long,
        watched: Boolean,
        updatedAt: Long,
    ) = EpisodeProgressEntity(episodeId, 1, "1 серия", positionMs, 1000, watched, updatedAt)
}
