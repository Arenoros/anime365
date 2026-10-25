package io.github.arenoros.anime365.feature.player

import androidx.media3.common.Player
import io.github.arenoros.anime365.core.data.repository.NotLoggedInException
import io.github.arenoros.anime365.core.data.repository.SubscriptionRequiredException
import io.github.arenoros.anime365.core.model.DownloadState
import io.github.arenoros.anime365.core.model.Episode
import io.github.arenoros.anime365.core.model.EpisodeDownload
import io.github.arenoros.anime365.core.model.EpisodeProgress
import io.github.arenoros.anime365.core.model.ReleaseStatus
import io.github.arenoros.anime365.core.model.Series
import io.github.arenoros.anime365.core.model.SeriesDetails
import io.github.arenoros.anime365.core.model.SeriesSummary
import io.github.arenoros.anime365.core.model.StreamQuality
import io.github.arenoros.anime365.core.model.Translation
import io.github.arenoros.anime365.core.model.TranslationKind
import io.github.arenoros.anime365.core.model.VideoStream
import io.github.arenoros.anime365.core.player.EpisodeSkip
import io.github.arenoros.anime365.core.player.PlayerMedia
import io.github.arenoros.anime365.core.player.VideoPlayer
import io.github.arenoros.anime365.core.testing.FakeEpisodeDownloads
import io.github.arenoros.anime365.core.testing.FakeLibraryRepository
import io.github.arenoros.anime365.core.testing.FakeSeriesRepository
import io.github.arenoros.anime365.core.testing.FakeSettingsStore
import io.github.arenoros.anime365.core.testing.FakeTranslationRepository
import io.github.arenoros.anime365.core.testing.MainDispatcherRule
import io.github.arenoros.anime365.feature.player.navigation.PlayerKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val first = Episode(101, 1.0, "1 серия", "tv")
    private val second = Episode(102, 2.0, "2 серия", "tv")
    private val series =
        Series(
            SeriesSummary(7, "Фрирен", null, "poster", "ТВ сериал", 28, 2023, null, 9.2, ReleaseStatus.Ongoing),
            SeriesDetails("tv", null, null, emptyList(), emptyList(), emptyList(), listOf(first, second)),
        )
    private val anilibria = Translation(1, first.id, TranslationKind.VoiceRu, "AniLibria", "tv", 1080)
    private val anilibriaNext = Translation(2, second.id, TranslationKind.VoiceRu, "AniLibria", "tv", 1080)
    private val stream =
        VideoStream(
            listOf(StreamQuality(1080, "https://cdn/1080"), StreamQuality(720, "https://cdn/720")),
            subtitlesUrl = null,
        )

    private val seriesRepository = FakeSeriesRepository().apply { series.value = this@PlayerViewModelTest.series }
    private val translations =
        FakeTranslationRepository().apply {
            stream =
                { Result.success(this@PlayerViewModelTest.stream) }
        }
    private val library = FakeLibraryRepository()
    private val settings = FakeSettingsStore()
    private val downloads = FakeEpisodeDownloads()
    private val player = FakeVideoPlayer()

    @Test
    fun turningOffAdultContentStopsPlaybackAndClearsMetadata() =
        runTest(mainDispatcherRule.dispatcher) {
            settings.updateSettings { it.copy(adultContent = true) }
            seriesRepository.series.value = series.copy(summary = series.summary.copy(isHentai = true))
            translations.translations.value = mapOf(first.id to listOf(anilibria))
            val viewModel = viewModel()
            assertEquals(1, player.played.size)
            player.playing.value = true
            player.positionMs = 1234
            library.saved.clear()
            player.onStop = {
                player.positionMs = 0
                player.playing.value = false
            }

            settings.updateSettings { it.copy(adultContent = false) }
            assertEquals(listOf(1), player.stopped)
            assertNull(viewModel.uiState.value.series)
            assertEquals(PlayerEvent.Close, viewModel.events.first())
            assertEquals(1234L, library.saved.last().positionMs)
            assertEquals(listOf(1234L), library.saved.map { it.positionMs })
        }

    @Test
    fun disabledAdultContentCannotStartFromDirectLink() =
        runTest(mainDispatcherRule.dispatcher) {
            seriesRepository.series.value = series.copy(summary = series.summary.copy(isHentai = true))
            val viewModel = viewModel()
            assertTrue(player.played.isEmpty())
            assertTrue(translations.streamRequests.isEmpty())
            assertEquals(PlayerEvent.Close, viewModel.events.first())
        }

    @Test
    fun startsFromSavedPositionAtResolutionForNetwork() =
        runTest(mainDispatcherRule.dispatcher) {
            translations.translations.value = mapOf(first.id to listOf(anilibria))
            library.progress.value = mapOf(first.id to EpisodeProgress(first.id, 300_000, 1_400_000, watched = false))
            player.isMeteredNetwork = true

            val viewModel = viewModel()

            val (media, start) = player.played.single()
            assertEquals("https://cdn/720", media.videoUrl)
            assertEquals("1 серия · AniLibria", media.subtitle)
            assertEquals(300_000L, start)
            assertEquals(PlayerStatus.Playing, viewModel.uiState.value.status)
            assertEquals(720, viewModel.uiState.value.quality)
            // Выбор Перевода запоминается сразу, ещё до первой 10-секундной записи.
            assertEquals(anilibria, library.saved.single().translation)
        }

    @Test
    fun progressIsSavedEveryTenSecondsWhilePlayingAndOnPause() =
        runTest(mainDispatcherRule.dispatcher) {
            translations.translations.value = mapOf(first.id to listOf(anilibria))
            viewModel()
            library.saved.clear()

            player.positionMs = 60_000
            player.durationMs = 1_400_000
            player.playing.value = true
            advanceTimeBy(20_001)
            assertEquals(3, library.saved.size)

            player.positionMs = 65_000
            player.playing.value = false
            advanceTimeBy(60_000)

            assertEquals(4, library.saved.size)
            assertEquals(65_000L, library.saved.last().positionMs)
        }

    @Test
    fun watchedEpisodeStartsOver() =
        runTest(mainDispatcherRule.dispatcher) {
            translations.translations.value = mapOf(first.id to listOf(anilibria))
            library.progress.value = mapOf(first.id to EpisodeProgress(first.id, 1_400_000, 1_400_000, watched = true))

            viewModel()

            assertEquals(0L, player.played.single().second)
        }

    @Test
    fun streamErrorsBecomeLoginAndSubscriptionScreens() =
        runTest(mainDispatcherRule.dispatcher) {
            translations.translations.value = mapOf(first.id to listOf(anilibria))
            translations.stream = { Result.failure(NotLoggedInException()) }
            val viewModel = viewModel()
            assertEquals(PlayerStatus.NeedsLogin, viewModel.uiState.value.status)

            translations.stream = { Result.failure(SubscriptionRequiredException()) }
            viewModel.retryIfBlocked()
            assertEquals(PlayerStatus.NeedsSubscription, viewModel.uiState.value.status)

            translations.stream = { Result.success(stream) }
            viewModel.retryIfBlocked()
            assertEquals(PlayerStatus.Playing, viewModel.uiState.value.status)
        }

    @Test
    fun endOfEpisodeCountsDownAndPlaysSameTeamNext() =
        runTest(mainDispatcherRule.dispatcher) {
            translations.translations.value = mapOf(first.id to listOf(anilibria), second.id to listOf(anilibriaNext))
            val viewModel = viewModel()

            player.endedEvents.emit(Unit)
            assertEquals(5, viewModel.uiState.value.nextCountdown)
            advanceTimeBy(5_001)

            assertEquals(2, player.played.size)
            assertEquals(second, viewModel.uiState.value.episode)
            assertEquals(anilibriaNext, viewModel.uiState.value.translation)
            assertNull(viewModel.uiState.value.nextCountdown)
        }

    @Test
    fun cancelledCountdownStaysOnEpisode() =
        runTest(mainDispatcherRule.dispatcher) {
            translations.translations.value = mapOf(first.id to listOf(anilibria), second.id to listOf(anilibriaNext))
            val viewModel = viewModel()

            player.endedEvents.emit(Unit)
            viewModel.cancelAutoplay()
            advanceTimeBy(10_000)

            assertEquals(1, player.played.size)
            assertNull(viewModel.uiState.value.nextCountdown)
        }

    @Test
    fun nextEpisodeWithoutSameTeamAsksForTranslation() =
        runTest(mainDispatcherRule.dispatcher) {
            val other = anilibriaNext.copy(id = 3, team = "SHIZA Project")
            translations.translations.value = mapOf(first.id to listOf(anilibria), second.id to listOf(other))
            val viewModel = viewModel()
            val events = mutableListOf<PlayerEvent>()
            backgroundScope.launch { viewModel.events.collect { events += it } }

            viewModel.playNext()

            assertEquals(listOf(PlayerEvent.ChooseTranslation(second.id)), events)
            assertEquals(1, player.played.size)
        }

    @Test
    fun movingToNextEpisodeMarksCurrentWatched() =
        runTest(mainDispatcherRule.dispatcher) {
            translations.translations.value = mapOf(first.id to listOf(anilibria), second.id to listOf(anilibriaNext))
            val viewModel = viewModel()

            // Эндинг не досмотрен: до порога далеко, но «Следующая серия» — явный знак, что эту закончили.
            viewModel.playNext()

            assertEquals(true, library.progress.value[first.id]?.watched)
        }

    @Test
    fun previousEpisodeKeepsCurrentUnwatched() =
        runTest(mainDispatcherRule.dispatcher) {
            val anilibriaFirst = anilibria.copy(id = 3)
            translations.translations.value =
                mapOf(first.id to listOf(anilibriaFirst), second.id to listOf(anilibriaNext))
            val viewModel = viewModel(PlayerKey(7, second.id, anilibriaNext.id))
            assertEquals(first, viewModel.uiState.value.previousEpisode)

            viewModel.playPrevious()

            assertEquals(first, viewModel.uiState.value.episode)
            assertEquals(anilibriaFirst, viewModel.uiState.value.translation)
            assertFalse(library.progress.value[second.id]?.watched == true)
            assertNull(viewModel.uiState.value.previousEpisode)
        }

    @Test
    fun notificationNextPlaysNextEpisodeAndKnowsItsNeighbours() =
        runTest(mainDispatcherRule.dispatcher) {
            translations.translations.value = mapOf(first.id to listOf(anilibria), second.id to listOf(anilibriaNext))
            val viewModel = viewModel()
            val (firstMedia) = player.played.single()
            assertEquals(false to true, firstMedia.hasPreviousEpisode to firstMedia.hasNextEpisode)

            player.skipEvents.emit(EpisodeSkip.Next)

            assertEquals(second, viewModel.uiState.value.episode)
            val secondMedia = player.played.last().first
            assertEquals(true to false, secondMedia.hasPreviousEpisode to secondMedia.hasNextEpisode)
        }

    @Test
    fun downloadedEpisodePlaysFromCacheWithoutNetwork() =
        runTest(mainDispatcherRule.dispatcher) {
            translations.translations.value = emptyMap()
            translations.stream = { error("Видеопоток без сети не запросить") }
            seriesRepository.result = Result.failure(java.io.IOException("Нет сети"))
            downloads.downloads.value = listOf(downloaded(first, anilibria))

            val viewModel = viewModel()

            val (media, _) = player.played.single()
            assertEquals("https://site/translations/mp4/1?download=1&height=720", media.videoUrl)
            assertEquals("download:1", media.cacheKey)
            assertEquals("https://site/1.ass", media.subtitlesUrl)
            assertEquals(PlayerStatus.Playing, viewModel.uiState.value.status)
            assertEquals(listOf(720), viewModel.uiState.value.qualities)
            assertEquals(emptyList<Pair<Int, Boolean>>(), seriesRepository.refreshCalls)
        }

    @Test
    fun nextEpisodeDownloadedWithSameTeamPlaysOffline() =
        runTest(mainDispatcherRule.dispatcher) {
            translations.translations.value = mapOf(first.id to listOf(anilibria))
            downloads.downloads.value = listOf(downloaded(second, anilibriaNext))
            val viewModel = viewModel()
            translations.translations.value = emptyMap()

            viewModel.playNext()

            assertEquals(second, viewModel.uiState.value.episode)
            assertEquals(
                "download:2",
                player.played
                    .last()
                    .first.cacheKey,
            )
        }

    @Test
    fun autoplayOffLeavesEndedEpisode() =
        runTest(mainDispatcherRule.dispatcher) {
            translations.translations.value = mapOf(first.id to listOf(anilibria), second.id to listOf(anilibriaNext))
            settings.settings.value = settings.settings.value.copy(autoplayNext = false)
            val viewModel = viewModel()

            player.endedEvents.emit(Unit)
            advanceTimeBy(10_000)

            assertNull(viewModel.uiState.value.nextCountdown)
            assertEquals(1, player.played.size)
        }

    @Test
    fun resolutionTargetComesFromSettings() =
        runTest(mainDispatcherRule.dispatcher) {
            translations.translations.value = mapOf(first.id to listOf(anilibria))
            settings.settings.value = settings.settings.value.copy(wifiHeight = 720)

            val viewModel = viewModel()

            assertEquals(
                "https://cdn/720",
                player.played
                    .single()
                    .first.videoUrl,
            )
            assertEquals(720, viewModel.uiState.value.quality)
        }

    private fun downloaded(
        episode: Episode,
        translation: Translation,
    ) = EpisodeDownload(
        7,
        "Фрирен",
        null,
        episode,
        translation,
        720,
        "https://site/translations/mp4/${translation.id}?download=1&height=720",
        "https://site/${translation.id}.ass",
        DownloadState.Completed,
        bytesDownloaded = 100,
        contentLength = 100,
    )

    private fun TestScope.viewModel(key: PlayerKey = PlayerKey(7, first.id, anilibria.id)): PlayerViewModel =
        PlayerViewModel(
            key,
            seriesRepository,
            translations,
            library,
            settings,
            downloads,
            player,
            backgroundScope,
        )

    private class FakeVideoPlayer : VideoPlayer {
        override val player: Player get() = error("ExoPlayer в unit-тестах нет")
        override var positionMs = 0L
        override val playing = MutableStateFlow(false)
        override var durationMs = 0L
        val endedEvents = MutableSharedFlow<Unit>()
        override val ended = endedEvents
        val skipEvents = MutableSharedFlow<EpisodeSkip>()
        override val episodeSkips = skipEvents
        override var isMeteredNetwork = false
        override var subtitlesEnabled = true
        override var speed = 1f
        val played = mutableListOf<Pair<PlayerMedia, Long>>()
        val stopped = mutableListOf<Int>()
        var onStop: () -> Unit = {}

        override fun play(
            media: PlayerMedia,
            startPositionMs: Long,
        ): Int {
            played += media to startPositionMs
            return played.size
        }

        override fun switchVideo(media: PlayerMedia) = Unit

        override fun stop(session: Int) {
            stopped += session
            onStop()
        }
    }
}
