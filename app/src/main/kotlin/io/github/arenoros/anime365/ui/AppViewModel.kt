package io.github.arenoros.anime365.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arenoros.anime365.core.data.ContentVisibility
import io.github.arenoros.anime365.core.data.repository.LibraryRepository
import io.github.arenoros.anime365.core.data.repository.RememberedTranslation
import io.github.arenoros.anime365.core.datastore.SettingsStore
import io.github.arenoros.anime365.core.datastore.ThemeMode
import io.github.arenoros.anime365.core.player.EpisodeDownloads
import io.github.arenoros.anime365.core.player.VideoPlayer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * То, что нужно оболочке поверх всех табов: тема, счётчик на табе «Список», переход из уведомления,
 * видео свёрнутого Плеера и незаконченное Скачивание — оно продолжается, как только приложение
 * открыли.
 */
@HiltViewModel
internal class AppViewModel
    @Inject
    constructor(
        library: LibraryRepository,
        private val rememberedTranslation: RememberedTranslation,
        private val downloads: EpisodeDownloads,
        val videoPlayer: VideoPlayer,
        visibility: ContentVisibility,
        settings: SettingsStore,
    ) : ViewModel() {
        /** Запускаем сервис только после onResume Activity, когда приложение уже на экране. */
        fun resumeDownloads() {
            downloads.resume()
        }

        /** `null` — Настройки ещё читаются. Значение переживает поворот: после него тема известна сразу. */
        val themeMode: StateFlow<ThemeMode?> =
            settings.settings.map { it.themeMode }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

        /** Без сети: признак 18+ — из кэша, копии в Библиотеке и записей Скачивания. */
        suspend fun canOpenSeries(id: Int): Boolean = id !in hiddenSeriesIds.filterNotNull().first()

        /** `id` Перевода запомненной Команды; `null` — нужен Выбор Перевода. */
        suspend fun rememberedTranslationId(
            seriesId: Int,
            episodeId: Int,
        ): Int? = rememberedTranslation(seriesId, episodeId)?.id

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
