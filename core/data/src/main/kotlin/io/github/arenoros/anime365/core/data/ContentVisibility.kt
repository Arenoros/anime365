package io.github.arenoros.anime365.core.data

import io.github.arenoros.anime365.core.database.dao.SeriesDao
import io.github.arenoros.anime365.core.database.library.LibraryDao
import io.github.arenoros.anime365.core.datastore.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Копия признака в Библиотеке сохраняет скрытие после очистки кэша и без сети. */
class ContentVisibility
    @Inject
    internal constructor(
        settings: SettingsStore,
        cache: SeriesDao,
        library: LibraryDao,
    ) {
        val adultContent: Flow<Boolean> = settings.settings.map { it.adultContent }.distinctUntilChanged()

        val hiddenSeriesIds: Flow<Set<Int>> =
            combine(adultContent, cache.adultIds(), library.adultIds()) { allowed, cached, saved ->
                if (allowed) emptySet() else (cached + saved).toSet()
            }.distinctUntilChanged()
    }
