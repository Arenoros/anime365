package io.github.arenoros.anime365.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arenoros.anime365.core.data.ContentVisibility
import io.github.arenoros.anime365.core.data.repository.LibraryRepository
import io.github.arenoros.anime365.core.data.repository.RememberedTranslation
import io.github.arenoros.anime365.core.data.repository.SeriesRepository
import io.github.arenoros.anime365.core.player.EpisodeDownloads
import io.github.arenoros.anime365.core.player.VideoPlayer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * То, что нужно оболочке поверх всех табов: счётчик на табе «Список», переход из уведомления,
 * видео свёрнутого Плеера и незаконченное Скачивание — оно продолжается, как только приложение
 * открыли.
 */
@HiltViewModel
internal class AppViewModel
    @Inject
    constructor(
        library: LibraryRepository,
        private val rememberedTranslation: RememberedTranslation,
        downloads: EpisodeDownloads,
        val videoPlayer: VideoPlayer,
        private val visibility: ContentVisibility,
        private val series: SeriesRepository,
    ) : ViewModel() {
        init {
            downloads.resume()
        }

        val newEpisodeCount: StateFlow<Int> =
            library.newEpisodeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

        val hiddenSeriesIds: StateFlow<Set<Int>?> =
            combine(
                visibility.hiddenSeriesIds,
                visibility.adultContent,
                downloads.downloads,
            ) { hidden, allowed, downloads ->
                hidden + if (allowed) emptySet() else downloads.filter { it.isHentai }.map { it.seriesId }.toSet()
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        suspend fun canOpenSeries(id: Int): Boolean {
            series.refresh(id, force = false)
            val summary = series.series(id).first()?.summary ?: return false
            return !summary.isHentai || visibility.adultContent.first()
        }

        /** `id` Перевода запомненной Команды; `null` — нужен Выбор Перевода. */
        suspend fun rememberedTranslationId(
            seriesId: Int,
            episodeId: Int,
        ): Int? = rememberedTranslation(seriesId, episodeId)?.id

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
