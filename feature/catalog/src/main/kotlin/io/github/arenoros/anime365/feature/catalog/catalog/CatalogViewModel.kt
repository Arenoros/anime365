package io.github.arenoros.anime365.feature.catalog.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arenoros.anime365.core.data.repository.CatalogRepository
import io.github.arenoros.anime365.core.datastore.SettingsStore
import io.github.arenoros.anime365.core.model.CatalogFilter
import io.github.arenoros.anime365.core.model.SeriesSummary
import io.github.arenoros.anime365.feature.catalog.navigation.CatalogKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = CatalogViewModel.Factory::class)
internal class CatalogViewModel
    @AssistedInject
    constructor(
        @Assisted key: CatalogKey,
        repository: CatalogRepository,
        settings: SettingsStore,
    ) : ViewModel() {
        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        private val _filter =
            MutableStateFlow(CatalogFilter(genreIds = setOfNotNull(key.genreId), ongoingOnly = key.ongoingOnly))
        val filter: StateFlow<CatalogFilter> = _filter.asStateFlow()

        val adultContent =
            settings.settings
                .map { it.adultContent }
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        private val adultSection =
            combine(adultContent, _filter) { allowed, filter -> allowed && filter.isHentai }
                .distinctUntilChanged()

        init {
            viewModelScope.launch {
                adultContent.collect { allowed ->
                    if (!allowed) _filter.value = _filter.value.copy(isHentai = false)
                }
            }
        }

        /** Поиск по мере ввода: запрос уходит, когда пользователь замер на [SEARCH_DEBOUNCE_MILLIS]. */
        @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
        val series: Flow<PagingData<SeriesSummary>> =
            combine(
                _query
                    .debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MILLIS }
                    .map { it.trim() }
                    .distinctUntilChanged(),
                _filter,
                adultContent,
            ) { query, filter, allowed -> query to filter.copy(isHentai = filter.isHentai && allowed) }
                .distinctUntilChanged()
                .flatMapLatest { (query, filter) -> repository.catalog(query, filter) }
                .cachedIn(viewModelScope)
                .combine(adultSection) { page, adult -> page.filter { it.isHentai == adult } }

        fun onQueryChange(query: String) {
            _query.value = query
        }

        /** Сезон без года API не ищет (docs/api-notes.md): снимаем год — снимается и сезон. */
        fun onFilterChange(filter: CatalogFilter) {
            val permitted = filter.copy(isHentai = filter.isHentai && adultContent.value)
            _filter.value = if (permitted.year == null) permitted.copy(season = null) else permitted
        }

        fun reset() {
            _query.value = ""
            _filter.value = CatalogFilter(isHentai = _filter.value.isHentai)
        }

        @AssistedFactory
        interface Factory {
            fun create(key: CatalogKey): CatalogViewModel
        }

        private companion object {
            const val SEARCH_DEBOUNCE_MILLIS = 300L
        }
    }
