package io.github.arenoros.anime365.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arenoros.anime365.core.data.repository.LibraryRepository
import io.github.arenoros.anime365.core.data.repository.RememberedTranslation
import io.github.arenoros.anime365.core.data.repository.SeriesRepository
import io.github.arenoros.anime365.core.datastore.SettingsStore
import io.github.arenoros.anime365.core.model.Episode
import io.github.arenoros.anime365.core.model.EpisodeDownload
import io.github.arenoros.anime365.core.model.LibraryEntry
import io.github.arenoros.anime365.core.model.LibraryStatus
import io.github.arenoros.anime365.core.model.WatchAction
import io.github.arenoros.anime365.core.model.watchAction
import io.github.arenoros.anime365.core.player.EpisodeDownloads
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** [entries] — `null`, пока База не прочитана. */
internal data class LibraryUiState(
    val entries: List<LibraryEntry>? = null,
    /** Пояснение «Список хранится только на этом устройстве» уже показали (ADR 0001). */
    val hintShown: Boolean = true,
    val downloads: List<EpisodeDownload> = emptyList(),
) {
    fun of(status: LibraryStatus): List<LibraryEntry> = entries.orEmpty().filter { it.status == status }
}

/** Куда ведёт кнопка «Продолжить» в строке Списка. */
internal sealed interface LibraryEvent {
    data class Play(
        val seriesId: Int,
        val episodeId: Int,
        val translationId: Int,
    ) : LibraryEvent

    data class ChooseTranslation(
        val seriesId: Int,
        val episodeId: Int,
    ) : LibraryEvent

    /** Продолжать нечего: всё просмотрено или страница аниме не загрузилась. */
    data class OpenSeries(
        val seriesId: Int,
    ) : LibraryEvent
}

@HiltViewModel
internal class LibraryViewModel
    @Inject
    constructor(
        private val library: LibraryRepository,
        private val series: SeriesRepository,
        private val rememberedTranslation: RememberedTranslation,
        private val episodeDownloads: EpisodeDownloads,
        settings: SettingsStore,
    ) : ViewModel() {
        private val eventChannel = Channel<LibraryEvent>(Channel.BUFFERED)
        val events: Flow<LibraryEvent> = eventChannel.receiveAsFlow()

        val uiState: StateFlow<LibraryUiState> =
            combine(
                library.entries(),
                library.hintShown,
                episodeDownloads.downloads,
                settings.settings,
            ) { entries, hint, downloads, settings ->
                LibraryUiState(entries, hint, downloads.filter { settings.adultContent || !it.isHentai })
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), LibraryUiState())

        fun retryDownload(download: EpisodeDownload) = episodeDownloads.retry(download.translation.id)

        fun removeDownloads(downloads: List<EpisodeDownload>) =
            episodeDownloads.remove(downloads.map { it.translation.id })

        fun dismissHint() {
            viewModelScope.launch { library.setHintShown() }
        }

        /** Как «ПРОДОЛЖИТЬ» на экране Аниме: недосмотренное — сразу в Плеер, иначе следующий Эпизод. */
        fun continueWatching(seriesId: Int) {
            viewModelScope.launch {
                val last = library.lastWatched(seriesId).first()
                val action =
                    watchAction(details = null, last) ?: run {
                        series.refresh(seriesId, force = false)
                        watchAction(series.series(seriesId).first()?.details, last)
                    }
                eventChannel.send(
                    when (action) {
                        is WatchAction.Resume -> {
                            LibraryEvent.Play(seriesId, action.lastWatched.episodeId, action.lastWatched.translationId)
                        }

                        is WatchAction.Next -> {
                            open(seriesId, action.episode)
                        }

                        is WatchAction.Start -> {
                            open(seriesId, action.episode)
                        }

                        null -> {
                            LibraryEvent.OpenSeries(seriesId)
                        }
                    },
                )
            }
        }

        private suspend fun open(
            seriesId: Int,
            episode: Episode,
        ): LibraryEvent =
            rememberedTranslation(seriesId, episode.id)?.let { LibraryEvent.Play(seriesId, episode.id, it.id) }
                ?: LibraryEvent.ChooseTranslation(seriesId, episode.id)

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
