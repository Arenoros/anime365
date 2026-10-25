package io.github.arenoros.anime365.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arenoros.anime365.core.data.di.ApplicationScope
import io.github.arenoros.anime365.core.data.repository.LibraryRepository
import io.github.arenoros.anime365.core.data.repository.NotLoggedInException
import io.github.arenoros.anime365.core.data.repository.Playback
import io.github.arenoros.anime365.core.data.repository.SeriesRepository
import io.github.arenoros.anime365.core.data.repository.SubscriptionRequiredException
import io.github.arenoros.anime365.core.data.repository.TranslationRepository
import io.github.arenoros.anime365.core.datastore.SettingsStore
import io.github.arenoros.anime365.core.model.DownloadState
import io.github.arenoros.anime365.core.model.Episode
import io.github.arenoros.anime365.core.model.EpisodeDownload
import io.github.arenoros.anime365.core.model.EpisodeProgress
import io.github.arenoros.anime365.core.model.ReleaseStatus
import io.github.arenoros.anime365.core.model.Series
import io.github.arenoros.anime365.core.model.SeriesSummary
import io.github.arenoros.anime365.core.model.StreamQuality
import io.github.arenoros.anime365.core.model.Translation
import io.github.arenoros.anime365.core.model.TranslationChoice
import io.github.arenoros.anime365.core.model.VideoStream
import io.github.arenoros.anime365.core.model.episodeAfter
import io.github.arenoros.anime365.core.model.episodeBefore
import io.github.arenoros.anime365.core.model.ofTeam
import io.github.arenoros.anime365.core.model.pickHeight
import io.github.arenoros.anime365.core.player.EpisodeDownloads
import io.github.arenoros.anime365.core.player.EpisodeSkip
import io.github.arenoros.anime365.core.player.PlayerMedia
import io.github.arenoros.anime365.core.player.VideoPlayer
import io.github.arenoros.anime365.core.player.cacheKey
import io.github.arenoros.anime365.feature.player.navigation.PlayerKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface PlayerStatus {
    data object Loading : PlayerStatus

    data object Playing : PlayerStatus

    data object NeedsLogin : PlayerStatus

    data object NeedsSubscription : PlayerStatus

    data class Failed(
        val error: Throwable,
    ) : PlayerStatus
}

internal sealed interface PlayerEvent {
    data object Close : PlayerEvent

    /** У соседнего Эпизода нет Перевода той же Команды: снекбар и Выбор Перевода (docs/screens.md). */
    data class ChooseTranslation(
        val episodeId: Int,
    ) : PlayerEvent
}

/** [qualities] — доступные Разрешения, от большего к меньшему. [nextCountdown] — секунды до следующей серии. */
internal data class PlayerUiState(
    val series: Series? = null,
    val episode: Episode? = null,
    val translation: Translation? = null,
    val status: PlayerStatus = PlayerStatus.Loading,
    val qualities: List<Int> = emptyList(),
    val quality: Int? = null,
    val hasSubtitles: Boolean = false,
    val subtitlesEnabled: Boolean = true,
    val speed: Float = 1f,
    val progress: Map<Int, EpisodeProgress> = emptyMap(),
    val nextCountdown: Int? = null,
) {
    val nextEpisode: Episode? get() = episode?.let { series?.details?.episodeAfter(it.id) }
    val previousEpisode: Episode? get() = episode?.let { series?.details?.episodeBefore(it.id) }

    /** Видео есть или вот-вот будет — Плеер сворачивается в окно; смотреть нечего — закрывается. */
    val canMinimize: Boolean get() = status == PlayerStatus.Playing || status == PlayerStatus.Loading
}

private const val SAVE_INTERVAL_MS = 10_000L
private const val NEXT_EPISODE_COUNTDOWN_SECONDS = 5
private const val SECOND_MS = 1_000L

@HiltViewModel(assistedFactory = PlayerViewModel.Factory::class)
internal class PlayerViewModel
    @AssistedInject
    constructor(
        @Assisted private val key: PlayerKey,
        private val seriesRepository: SeriesRepository,
        private val translationRepository: TranslationRepository,
        private val libraryRepository: LibraryRepository,
        private val settingsStore: SettingsStore,
        private val downloads: EpisodeDownloads,
        val videoPlayer: VideoPlayer,
        @param:ApplicationScope private val applicationScope: CoroutineScope,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(PlayerUiState())
        val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

        private val eventChannel = Channel<PlayerEvent>(Channel.BUFFERED)
        val events: Flow<PlayerEvent> = eventChannel.receiveAsFlow()

        private var episodeId = key.episodeId
        private var translationId = key.translationId
        private var stream: VideoStream? = null

        /** Ключ кэша, если играет скачанное. */
        private var cacheKey: String? = null
        private var session = 0
        private var countdown: Job? = null

        init {
            viewModelScope.launch {
                combine(
                    settingsStore.settings,
                    _uiState
                        .map {
                            it.series?.summary?.isHentai
                        }.distinctUntilChanged(),
                ) { settings, adult ->
                    adult == true && !settings.adultContent
                }.distinctUntilChanged().collect { blocked ->
                    if (blocked) {
                        saveNow()
                        cancelAutoplay()
                        videoPlayer.stop(session)
                        _uiState.value = PlayerUiState()
                        eventChannel.send(PlayerEvent.Close)
                    }
                }
            }
            viewModelScope.launch {
                libraryRepository.progress(key.seriesId).collect { progress ->
                    _uiState.update { it.copy(progress = progress) }
                }
            }
            viewModelScope.launch { videoPlayer.ended.collect { onEnded() } }
            viewModelScope.launch {
                videoPlayer.episodeSkips.collect { skip ->
                    when (skip) {
                        EpisodeSkip.Previous -> playPrevious()
                        EpisodeSkip.Next -> playNext()
                    }
                }
            }
            viewModelScope.launch {
                // Пока играет — раз в 10 секунд; пауза — тоже повод записать позицию.
                videoPlayer.playing.collectLatest { playing ->
                    snapshot()?.let { libraryRepository.save(it) }
                    while (playing) {
                        delay(SAVE_INTERVAL_MS)
                        snapshot()?.let { libraryRepository.save(it) }
                    }
                }
            }
            start()
        }

        fun retry() = start()

        /** После Входа или оформления Подписки экран снова видим — пробуем ещё раз. */
        fun retryIfBlocked() {
            val status = _uiState.value.status
            if (status == PlayerStatus.NeedsLogin || status == PlayerStatus.NeedsSubscription) start()
        }

        /** Кнопкой или автопереходом: текущий Эпизод отмечается просмотренным — к нему уже не вернутся. */
        fun playNext() = switchEpisode(_uiState.value.nextEpisode, markWatched = true)

        /** Назад — не знак, что текущий досмотрен: остаётся его позиция. */
        fun playPrevious() = switchEpisode(_uiState.value.previousEpisode, markWatched = false)

        /** Тот же Перевод — по Команде; у соседнего Эпизода её нет — Выбор Перевода. */
        private fun switchEpisode(
            target: Episode?,
            markWatched: Boolean,
        ) {
            cancelAutoplay()
            val episode = target ?: return
            val translation = _uiState.value.translation ?: return
            val playing = _uiState.value.playing()?.takeIf { markWatched }
            val playback = snapshot()
            viewModelScope.launch {
                // По порядку: запись позиции не должна перетереть отметку.
                playback?.let { libraryRepository.save(it) }
                playing?.let { (series, current) ->
                    libraryRepository.setWatched(series.summary, current, watched = true)
                }
                // Скачанная серия той же Команды играет и без сети.
                val downloaded =
                    completedDownload {
                        it.episode.id == episode.id &&
                            it.translation.kind == translation.kind &&
                            it.translation.team == translation.team
                    }
                if (downloaded != null) {
                    episodeId = episode.id
                    translationId = downloaded.translation.id
                    return@launch start()
                }
                val translations = translationRepository.load(episode.id).getOrElse { return@launch fail(it) }
                val match =
                    translations.ofTeam(
                        TranslationChoice(translation.kind, translation.team),
                        translation.source,
                    )
                if (match == null) {
                    eventChannel.send(PlayerEvent.ChooseTranslation(episode.id))
                } else {
                    episodeId = episode.id
                    translationId = match.id
                    start()
                }
            }
        }

        fun cancelAutoplay() {
            countdown?.cancel()
            _uiState.update { it.copy(nextCountdown = null) }
        }

        fun selectQuality(height: Int) {
            val url = stream?.qualities?.firstOrNull { it.height == height }?.url
            val media = url?.let { mediaOf(_uiState.value, it) } ?: return
            videoPlayer.switchVideo(media)
            _uiState.update { it.copy(quality = height) }
        }

        fun setSubtitlesEnabled(enabled: Boolean) {
            videoPlayer.subtitlesEnabled = enabled
            _uiState.update { it.copy(subtitlesEnabled = enabled) }
        }

        fun setSpeed(speed: Float) {
            videoPlayer.speed = speed
            _uiState.update { it.copy(speed = speed) }
        }

        /** Свёрнуто или закрыто: позицию на диск сразу, не дожидаясь очередных 10 секунд. */
        fun saveNow() {
            val playback = snapshot() ?: return
            applicationScope.launch { libraryRepository.save(playback) }
        }

        override fun onCleared() {
            saveNow()
            videoPlayer.stop(session)
        }

        private fun start() {
            cancelAutoplay()
            _uiState.update { it.copy(status = PlayerStatus.Loading) }
            viewModelScope.launch {
                val downloaded = completedDownload { it.translation.id == translationId }
                // Скачанное играет без сети: страницу аниме, Переводы и Видеопоток не запрашиваем.
                if (downloaded == null) seriesRepository.refresh(key.seriesId, force = false)
                // Без названия и Эпизода нечего показать ни на экране, ни в уведомлении.
                val series = seriesRepository.series(key.seriesId).first() ?: downloaded?.let(::seriesOf)
                if (series?.summary?.isHentai == true && !settingsStore.settings.first().adultContent) {
                    eventChannel.send(PlayerEvent.Close)
                    return@launch
                }
                val episode = series?.details?.episodes?.firstOrNull { it.id == episodeId } ?: downloaded?.episode
                val (translation, stream) =
                    downloaded?.let(::sourceOf) ?: streamSource().getOrElse { return@launch fail(it) }
                if (series == null || episode == null) {
                    return@launch fail(IllegalStateException("Episode $episodeId of ${key.seriesId} is gone"))
                }
                val settings = settingsStore.settings.first()
                if (series.summary.isHentai && !settings.adultContent) {
                    eventChannel.send(PlayerEvent.Close)
                    return@launch
                }
                val target = if (videoPlayer.isMeteredNetwork) settings.mobileHeight else settings.wifiHeight
                val height = pickHeight(stream.qualities.map { it.height }, target)
                val url = stream.qualities.first { it.height == height }.url
                // Досмотренный Эпизод открывается с начала.
                val start = libraryRepository.progressOf(episodeId)?.takeUnless { it.watched }?.positionMs ?: 0
                this@PlayerViewModel.stream = stream
                cacheKey = downloaded?.cacheKey
                session = videoPlayer.play(media(series, episode, translation, url), start)
                videoPlayer.subtitlesEnabled = _uiState.value.subtitlesEnabled
                videoPlayer.speed = _uiState.value.speed
                _uiState.update {
                    it.copy(
                        series = series,
                        episode = episode,
                        translation = translation,
                        status = PlayerStatus.Playing,
                        qualities = stream.qualities.map { quality -> quality.height },
                        quality = height,
                        hasSubtitles = stream.subtitlesUrl != null,
                    )
                }
                // Сразу запоминает выбранный Перевод — им будет отмечен Перевод следующего Эпизода.
                libraryRepository.save(Playback(series.summary, episode, translation, start, 0))
            }
        }

        private suspend fun streamSource(): Result<Pair<Translation, VideoStream>> =
            translationRepository.load(episodeId).fold(
                onSuccess = { translations ->
                    val translation = translations.firstOrNull { it.id == translationId }
                    if (translation == null) {
                        Result.failure(IllegalStateException("Translation $translationId of $episodeId is gone"))
                    } else {
                        translationRepository.stream(translationId).map { translation to it }
                    }
                },
                onFailure = { Result.failure(it) },
            )

        private suspend fun completedDownload(match: (EpisodeDownload) -> Boolean): EpisodeDownload? =
            downloads.downloads.first().firstOrNull { it.state == DownloadState.Completed && match(it) }

        /** Скачанное: одно Разрешение, ссылка без Токена — видео читается из кэша по ключу. */
        private fun sourceOf(download: EpisodeDownload): Pair<Translation, VideoStream> =
            download.translation to
                VideoStream(listOf(StreamQuality(download.height, download.videoUrl)), download.subtitlesUrl)

        /** Кэш страницы аниме стёрт, а сети нет: название и постер — из записи Скачивания. */
        private fun seriesOf(download: EpisodeDownload): Series =
            Series(
                SeriesSummary(
                    download.seriesId,
                    download.seriesTitle,
                    null,
                    download.posterUrl,
                    null,
                    null,
                    null,
                    null,
                    null,
                    ReleaseStatus.Released,
                    isHentai = download.isHentai,
                ),
                details = null,
            )

        private fun onEnded() {
            saveNow()
            if (_uiState.value.nextEpisode == null) return
            countdown =
                viewModelScope.launch {
                    if (!settingsStore.settings.first().autoplayNext) return@launch
                    for (seconds in NEXT_EPISODE_COUNTDOWN_SECONDS downTo 1) {
                        _uiState.update { it.copy(nextCountdown = seconds) }
                        delay(SECOND_MS)
                    }
                    playNext()
                }
        }

        private fun fail(error: Throwable) {
            val status =
                when (error) {
                    is NotLoggedInException -> PlayerStatus.NeedsLogin
                    is SubscriptionRequiredException -> PlayerStatus.NeedsSubscription
                    else -> PlayerStatus.Failed(error)
                }
            _uiState.update { it.copy(status = status) }
        }

        private fun snapshot(): Playback? {
            val state = _uiState.value.takeIf { it.status == PlayerStatus.Playing }
            val (series, episode, translation) = state?.playing() ?: return null
            return Playback(series.summary, episode, translation, videoPlayer.positionMs, videoPlayer.durationMs)
        }

        private fun mediaOf(
            state: PlayerUiState,
            url: String,
        ): PlayerMedia? =
            state.playing()?.let { (series, episode, translation) ->
                media(series, episode, translation, url)
            }

        /** Что играет: аниме, Эпизод и Перевод — когда известно всё три. */
        private fun PlayerUiState.playing(): Triple<Series, Episode, Translation>? =
            if (series != null && episode != null && translation != null) Triple(series, episode, translation) else null

        private fun media(
            series: Series,
            episode: Episode,
            translation: Translation,
            url: String,
        ): PlayerMedia =
            PlayerMedia(
                videoUrl = url,
                subtitlesUrl = stream?.subtitlesUrl,
                title = series.summary.title,
                subtitle = listOf(episode.label, translation.team).filter { it.isNotEmpty() }.joinToString(" · "),
                artworkUrl = series.details?.largePosterUrl ?: series.summary.posterUrl,
                cacheKey = cacheKey,
                hasPreviousEpisode = series.details?.episodeBefore(episode.id) != null,
                hasNextEpisode = series.details?.episodeAfter(episode.id) != null,
            )

        @AssistedFactory
        interface Factory {
            fun create(key: PlayerKey): PlayerViewModel
        }
    }
