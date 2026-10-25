package io.github.arenoros.anime365.core.data.repository

import io.github.arenoros.anime365.core.data.catchingFailures
import io.github.arenoros.anime365.core.data.currentSiteSeason
import io.github.arenoros.anime365.core.data.parseSiteDateTime
import io.github.arenoros.anime365.core.data.toEntity
import io.github.arenoros.anime365.core.data.toModel
import io.github.arenoros.anime365.core.data.toSummary
import io.github.arenoros.anime365.core.database.dao.FeedDao
import io.github.arenoros.anime365.core.database.dao.SeriesDao
import io.github.arenoros.anime365.core.database.dao.SeriesListDao
import io.github.arenoros.anime365.core.database.model.FeedEpisodeEntity
import io.github.arenoros.anime365.core.database.model.ListStateEntity
import io.github.arenoros.anime365.core.database.model.SeriesEntity
import io.github.arenoros.anime365.core.database.model.SeriesListItemEntity
import io.github.arenoros.anime365.core.model.FeedEpisode
import io.github.arenoros.anime365.core.model.SeriesSummary
import io.github.arenoros.anime365.core.network.Anime365Api
import io.github.arenoros.anime365.core.network.ApiFields
import io.github.arenoros.anime365.core.network.model.SeriesDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Блоки Главной (docs/screens.md, «Главная»). */
interface HomeRepository {
    val feed: Flow<List<FeedEpisode>>
    val ongoing: Flow<List<SeriesSummary>>
    val seasonTop: Flow<List<SeriesSummary>>

    /** Без [force] обновляет только устаревшие блоки. Блоки обновляются независимо: ошибка одного не мешает другим. */
    suspend fun refresh(force: Boolean): Result<Unit>
}

internal const val ONGOING_KEY = "ongoing"
internal const val FEED_KEY = "feed"
internal const val SEASON_TOP_KEY_PREFIX = "season-top:"
internal const val NEW_EPISODES_SHOWN = 20
internal const val SEASON_TOP_SIZE = 10

private val FEED_TTL_MILLIS = TimeUnit.MINUTES.toMillis(15)
private val SEASON_TOP_TTL_MILLIS = TimeUnit.HOURS.toMillis(24)

// Онгоингов около двухсот (docs/api-notes.md), запас — на сезонный пик.
private const val ONGOING_LIMIT = 500

// Около 40 Эпизодов: с запасом на NEW_EPISODES_SHOWN строк после склейки Переводов.
private const val FEED_LIMIT = 150

internal class OfflineFirstHomeRepository
    @Inject
    constructor(
        private val api: Anime365Api,
        private val seriesDao: SeriesDao,
        private val listDao: SeriesListDao,
        private val feedDao: FeedDao,
        private val clock: Clock,
    ) : HomeRepository {
        override val feed: Flow<List<FeedEpisode>> =
            feedDao.observe(NEW_EPISODES_SHOWN).map { rows -> rows.mapNotNull { it.toModel() } }

        override val ongoing: Flow<List<SeriesSummary>> =
            listDao.observe(ONGOING_KEY).map { rows -> rows.filterNot { it.isHentai }.map { it.toSummary() } }

        // Сезон вычисляется при подписке: приложение может прожить смену сезона.
        override val seasonTop: Flow<List<SeriesSummary>> =
            flow { emitAll(listDao.observe(SEASON_TOP_KEY_PREFIX + currentSiteSeason(clock))) }
                .map { rows -> rows.filterNot { it.isHentai }.map { it.toSummary() } }

        override suspend fun refresh(force: Boolean): Result<Unit> =
            coroutineScope {
                val seasonTop = async { catchingFailures { refreshSeasonTop(force) } }
                // Лента после Онгоингов: почти все её аниме — онгоинги, и тогда их не нужно запрашивать по одному.
                val ongoing = catchingFailures { refreshOngoing(force) }
                val feed = catchingFailures { refreshFeed(force) }
                listOf(ongoing, feed, seasonTop.await()).firstOrNull { it.isFailure } ?: Result.success(Unit)
            }

        private suspend fun refreshOngoing(force: Boolean) {
            if (!force && isFresh(ONGOING_KEY, FEED_TTL_MILLIS)) return
            val series =
                api
                    .series(fields = ApiFields.SERIES_SUMMARY, limit = ONGOING_LIMIT, isAiring = 1, isActive = 1)
                    .unwrap()
            saveList(ONGOING_KEY, series)
        }

        private suspend fun refreshSeasonTop(force: Boolean) {
            val season = currentSiteSeason(clock)
            val key = SEASON_TOP_KEY_PREFIX + season
            if (!force && isFresh(key, SEASON_TOP_TTL_MILLIS)) return
            val series =
                api
                    .series(
                        fields = ApiFields.SERIES_SUMMARY,
                        limit = SEASON_TOP_SIZE,
                        season = season,
                    ).unwrap()
            saveList(key, series.filter { (it.myAnimeListScore ?: 0.0) > 0 })
        }

        /**
         * Лента Переводов склеивается в Эпизоды. Время строки — выход Эпизода, а не Перевода, и в ленту
         * попадают только Эпизоды, вышедшие в пределах полученного окна: иначе новый Перевод
         * к давней серии выглядел бы как новая серия.
         */
        private suspend fun refreshFeed(force: Boolean) {
            if (!force && isFresh(FEED_KEY, FEED_TTL_MILLIS)) return
            val translations = api.translations(feed = "recent", fields = ApiFields.FEED, limit = FEED_LIMIT).unwrap()
            val windowStart = translations.mapNotNull { parseSiteDateTime(it.activeDateTime) }.minOrNull()
            val episodes =
                translations
                    .mapNotNull { it.episode }
                    .filter { it.isActive == 1 }
                    .distinctBy { it.id }
                    .mapNotNull { episode ->
                        val releasedAt = parseSiteDateTime(episode.firstUploadedDateTime)
                        if (releasedAt == null || windowStart == null ||
                            releasedAt < windowStart
                        ) {
                            return@mapNotNull null
                        }
                        FeedEpisodeEntity(episode.id, episode.seriesId, episode.episodeFull, releasedAt.toEpochMilli())
                    }
            feedDao.replace(
                state = ListStateEntity(FEED_KEY, nextOffset = null, fetchedAt = clock.millis()),
                series = missingSeries(episodes.mapTo(HashSet()) { it.seriesId }),
                episodes = episodes,
            )
        }

        /** Аниме Ленты, которых ещё нет в кэше. Не загрузилось — его Эпизоды просто не покажутся. */
        private suspend fun missingSeries(ids: Set<Int>): List<SeriesEntity> =
            coroutineScope {
                (ids - seriesDao.existingIds(ids).toSet())
                    .map { id ->
                        async { catchingFailures { api.series(id, ApiFields.SERIES_SUMMARY).unwrap() }.getOrNull() }
                    }.awaitAll()
                    .mapNotNull { it?.toEntity() }
            }

        private suspend fun saveList(
            key: String,
            series: List<SeriesDto>,
        ) {
            val entities = series.mapNotNull { it.toEntity() }
            listDao.save(
                state = ListStateEntity(key, nextOffset = null, fetchedAt = clock.millis()),
                series = entities,
                items = entities.mapIndexed { index, entity -> SeriesListItemEntity(key, entity.id, index) },
                replace = true,
            )
        }

        private suspend fun isFresh(
            key: String,
            ttlMillis: Long,
        ): Boolean {
            val fetchedAt = listDao.state(key)?.fetchedAt ?: return false
            return clock.millis() - fetchedAt < ttlMillis
        }
    }
