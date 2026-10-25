package io.github.arenoros.anime365.feature.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arenoros.anime365.core.data.repository.LibraryRepository
import io.github.arenoros.anime365.core.data.repository.SeriesRepository
import io.github.arenoros.anime365.core.datastore.SettingsStore
import io.github.arenoros.anime365.core.model.Episode
import io.github.arenoros.anime365.core.model.EpisodeProgress
import io.github.arenoros.anime365.core.model.LastWatched
import io.github.arenoros.anime365.core.model.LibraryStatus
import io.github.arenoros.anime365.core.model.Series
import io.github.arenoros.anime365.core.model.WatchAction
import io.github.arenoros.anime365.core.model.watchAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class SeriesUiState(
    /** Из кэша: шапка видна сразу, если аниме уже встречалось в списке. */
    val series: Series? = null,
    val isRefreshing: Boolean = true,
    val error: Throwable? = null,
    /** Прогресс по `id` Эпизода. */
    val progress: Map<Int, EpisodeProgress> = emptyMap(),
    val lastWatched: LastWatched? = null,
    /** `null` — аниме не в Списке. */
    val status: LibraryStatus? = null,
    /** Новые Эпизоды на момент открытия страницы. */
    val newEpisodes: Set<Int> = emptySet(),
    val blocked: Boolean = false,
) {
    val watchAction: WatchAction? get() = watchAction(series?.details, lastWatched)
}

private data class SeriesLibrary(
    val progress: Map<Int, EpisodeProgress>,
    val lastWatched: LastWatched?,
    val status: LibraryStatus?,
    val newEpisodes: Set<Int>,
)

@HiltViewModel(assistedFactory = SeriesViewModel.Factory::class)
internal class SeriesViewModel
    @AssistedInject
    constructor(
        @Assisted private val seriesId: Int,
        private val repository: SeriesRepository,
        private val library: LibraryRepository,
        private val settings: SettingsStore,
    ) : ViewModel() {
        private val refreshing = MutableStateFlow(true)
        private val error = MutableStateFlow<Throwable?>(null)
        private val newAtOpen = MutableStateFlow(emptySet<Int>())

        val uiState: StateFlow<SeriesUiState> =
            combine(
                repository.series(seriesId),
                refreshing,
                error,
                combine(
                    library.progress(seriesId),
                    library.lastWatched(seriesId),
                    library.status(seriesId),
                    newAtOpen,
                    ::SeriesLibrary,
                ),
                settings.settings,
            ) { series, refreshing, error, library, settings ->
                val blocked = series?.summary?.isHentai == true && !settings.adultContent
                SeriesUiState(
                    series.takeUnless { blocked },
                    refreshing,
                    error,
                    library.progress,
                    library.lastWatched,
                    library.status,
                    library.newEpisodes,
                    blocked,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SeriesUiState())

        init {
            viewModelScope.launch {
                // Отметки Новых Эпизодов на сетке — какими они были до открытия: открытие их сбрасывает.
                newAtOpen.value = library.newEpisodeIds(seriesId)
                load(force = false)
            }
        }

        fun refresh(force: Boolean = true) {
            viewModelScope.launch { load(force) }
        }

        /**
         * Всё, что видно на странице после загрузки, — уже не новое. Только здесь, а не на каждое
         * обновление кэша: иначе фоновая проверка, обновив открытое аниме, сама бы гасила свои находки.
         */
        private suspend fun load(force: Boolean) {
            refreshing.value = true
            error.value = repository.refresh(seriesId, force).exceptionOrNull()
            refreshing.value = false
            repository
                .series(seriesId)
                .first()
                ?.takeIf { it.details != null }
                ?.takeIf { !it.summary.isHentai || settings.settings.first().adultContent }
                ?.let { library.markSeen(it) }
        }

        fun setWatched(
            episode: Episode,
            watched: Boolean,
        ) {
            val series = uiState.value.series ?: return
            viewModelScope.launch { library.setWatched(series.summary, episode, watched) }
        }

        /** `null` — убрать из Списка. */
        fun setStatus(status: LibraryStatus?) {
            val series = uiState.value.series ?: return
            viewModelScope.launch { library.setStatus(series, status) }
        }

        @AssistedFactory
        interface Factory {
            fun create(seriesId: Int): SeriesViewModel
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
