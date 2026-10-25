package io.github.arenoros.anime365.core.data.repository

import io.github.arenoros.anime365.core.data.toModel
import io.github.arenoros.anime365.core.database.library.EpisodeProgressEntity
import io.github.arenoros.anime365.core.database.library.LibraryDao
import io.github.arenoros.anime365.core.database.library.LibrarySeriesEntity
import io.github.arenoros.anime365.core.database.library.NewEpisodeEntity
import io.github.arenoros.anime365.core.datastore.SettingsStore
import io.github.arenoros.anime365.core.model.ContinueWatching
import io.github.arenoros.anime365.core.model.Episode
import io.github.arenoros.anime365.core.model.EpisodeProgress
import io.github.arenoros.anime365.core.model.LastWatched
import io.github.arenoros.anime365.core.model.LibraryEntry
import io.github.arenoros.anime365.core.model.LibraryStatus
import io.github.arenoros.anime365.core.model.Series
import io.github.arenoros.anime365.core.model.SeriesSummary
import io.github.arenoros.anime365.core.model.Translation
import io.github.arenoros.anime365.core.model.TranslationChoice
import io.github.arenoros.anime365.core.model.TranslationKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject

/** Что играет и где остановились: из этого пишется Прогресс. */
data class Playback(
    val series: SeriesSummary,
    val episode: Episode,
    val translation: Translation,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * Библиотека, Прогресс и запомненный выбор Перевода (ADR 0001: только на устройстве). Одна база и общие
 * транзакции: начатый Эпизод перестаёт быть новым, Статус переживает запись Прогресса.
 */
@Suppress("TooManyFunctions")
interface LibraryRepository {
    /** Аниме со Статусом, всех Статусов сразу. */
    fun entries(): Flow<List<LibraryEntry>>

    /** `null` — аниме не в Списке. */
    fun status(seriesId: Int): Flow<LibraryStatus?>

    /** Новые Эпизоды всех аниме со Статусом «Смотрю» — счётчик на табе «Список». */
    fun newEpisodeCount(): Flow<Int>

    /** Пояснение «Список хранится только на этом устройстве» уже показали. */
    val hintShown: Flow<Boolean>

    suspend fun setHintShown()

    /** `null` — убрать из Списка; Прогресс и запомненный Перевод остаются. */
    suspend fun setStatus(
        series: Series,
        status: LibraryStatus?,
    )

    /** Новые Эпизоды аниме — до того, как [markSeen] их сбросит. */
    suspend fun newEpisodeIds(seriesId: Int): Set<Int>

    /** Страницу аниме открыли: его Эпизоды больше не новые. */
    suspend fun markSeen(series: Series)

    /** `id` аниме со Статусом «Смотрю»: их проверяют на Новые Эпизоды. */
    suspend fun watching(): List<Int>

    /**
     * Записывает Эпизоды, вышедшие после последнего открытия аниме, и возвращает те из них, о которых
     * ещё не сообщали. Первая проверка аниме только запоминает, что уже вышло.
     */
    suspend fun recordNewEpisodes(series: Series): List<Episode>

    /** По `id` Эпизода. */
    fun progress(seriesId: Int): Flow<Map<Int, EpisodeProgress>>

    fun lastWatched(seriesId: Int): Flow<LastWatched?>

    fun continueWatching(): Flow<List<ContinueWatching>>

    suspend fun progressOf(episodeId: Int): EpisodeProgress?

    suspend fun choice(seriesId: Int): TranslationChoice?

    /**
     * Заодно запоминает Перевод: им будет отмечен Перевод следующего Эпизода. Аниме, которому Статус
     * ещё ни разу не выбирали, получает «Смотрю».
     */
    suspend fun save(playback: Playback)

    /** Отметка «просмотрен» вручную или при переходе к следующему Эпизоду; позиция не меняется. */
    suspend fun setWatched(
        series: SeriesSummary,
        episode: Episode,
        watched: Boolean,
    )
}

/**
 * С этой доли Эпизод считается просмотренным: дальше обычно эндинг и анонс следующей серии — в
 * серии на 24 минуты это последние 3,5 минуты.
 */
internal const val WATCHED_FRACTION = 0.85
private const val CONTINUE_WATCHING_LIMIT = 20

@Suppress("TooManyFunctions")
internal class LocalLibraryRepository
    @Inject
    constructor(
        private val dao: LibraryDao,
        private val settings: SettingsStore,
        private val clock: Clock,
    ) : LibraryRepository {
        override fun entries(): Flow<List<LibraryEntry>> =
            combine(dao.entries(), settings.settings) { rows, settings ->
                rows.filter { settings.adultContent || !it.isHentai }.mapNotNull { it.toModel() }
            }.distinctUntilChanged()

        override fun status(seriesId: Int): Flow<LibraryStatus?> =
            dao.status(seriesId).map(LibraryStatus::of).distinctUntilChanged()

        @OptIn(ExperimentalCoroutinesApi::class)
        override fun newEpisodeCount(): Flow<Int> =
            settings.settings
                .map { it.adultContent }
                .distinctUntilChanged()
                .flatMapLatest { dao.newEpisodeCount(LibraryStatus.Watching.key, it) }
                .distinctUntilChanged()

        override val hintShown: Flow<Boolean> = settings.libraryHintShown

        override suspend fun setHintShown() = settings.setLibraryHintShown()

        override suspend fun setStatus(
            series: Series,
            status: LibraryStatus?,
        ) {
            val summary = series.summary
            dao.setStatus(
                LibrarySeriesEntity(
                    seriesId = summary.id,
                    title = summary.title,
                    posterUrl = summary.posterUrl,
                    translationType = null,
                    team = null,
                    lastEpisodeId = null,
                    lastTranslationId = null,
                    lastWatchedAt = null,
                    status = status?.key,
                    statusChangedAt = clock.millis(),
                    episodeCount = summary.episodeCount,
                    isHentai = summary.isHentai,
                ),
                seenUploadedAt = series.latestUploadedAt,
            )
        }

        override suspend fun newEpisodeIds(seriesId: Int): Set<Int> = dao.newEpisodeIds(seriesId).toSet()

        override suspend fun markSeen(series: Series) {
            val summary = series.summary
            dao.markSeen(
                summary.id,
                summary.title,
                summary.posterUrl,
                summary.episodeCount,
                series.latestUploadedAt,
                summary.isHentai,
            )
        }

        override suspend fun watching(): List<Int> =
            dao.seriesWithStatus(LibraryStatus.Watching.key).map { it.seriesId }

        override suspend fun recordNewEpisodes(series: Series): List<Episode> {
            val seriesId = series.summary.id
            val row = dao.series(seriesId)
            val latest = series.latestUploadedAt
            val seen = row?.seenUploadedAt
            return when {
                row == null || latest == null -> {
                    emptyList()
                }

                seen == null -> {
                    dao.updateSeen(seriesId, latest)
                    emptyList()
                }

                else -> {
                    recordUploadedAfter(series, seen)
                }
            }
        }

        private suspend fun recordUploadedAfter(
            series: Series,
            seen: Long,
        ): List<Episode> {
            val seriesId = series.summary.id
            val started = dao.startedEpisodeIds(seriesId).toSet()
            val fresh =
                series.details
                    ?.episodes
                    .orEmpty()
                    .filter { episode ->
                        val uploadedAt = episode.uploadedAt?.toEpochMilli()
                        uploadedAt != null && uploadedAt > seen && episode.id !in started
                    }
            val inserted =
                dao.insertNewEpisodes(
                    fresh.map { NewEpisodeEntity(it.id, seriesId, it.label, it.uploadedAt!!.toEpochMilli()) },
                )
            return fresh.filterIndexed { index, _ -> inserted[index] != -1L }
        }

        override fun progress(seriesId: Int): Flow<Map<Int, EpisodeProgress>> =
            dao
                .progress(seriesId)
                .map { rows ->
                    rows.associate { it.episodeId to it.toModel() }
                }.distinctUntilChanged()

        override fun lastWatched(seriesId: Int): Flow<LastWatched?> =
            dao.lastWatched(seriesId).map { it?.toModel()?.lastWatched }.distinctUntilChanged()

        override fun continueWatching(): Flow<List<ContinueWatching>> =
            dao
                .continueWatching(CONTINUE_WATCHING_LIMIT)
                .map { rows ->
                    rows.map { it.toModel() }
                }.distinctUntilChanged()

        override suspend fun progressOf(episodeId: Int): EpisodeProgress? = dao.progressOf(episodeId)?.toModel()

        override suspend fun setWatched(
            series: SeriesSummary,
            episode: Episode,
            watched: Boolean,
        ) {
            val now = clock.millis()
            dao.setWatched(
                LibrarySeriesEntity(
                    seriesId = series.id,
                    title = series.title,
                    posterUrl = series.posterUrl,
                    translationType = null,
                    team = null,
                    lastEpisodeId = null,
                    lastTranslationId = null,
                    lastWatchedAt = null,
                    episodeCount = series.episodeCount,
                    isHentai = series.isHentai,
                ),
                EpisodeProgressEntity(episode.id, series.id, episode.label, 0, 0, watched, now),
            )
        }

        override suspend fun choice(seriesId: Int): TranslationChoice? {
            val series = dao.series(seriesId)
            return series?.translationType?.let { TranslationChoice(TranslationKind.of(it), series.team.orEmpty()) }
        }

        override suspend fun save(playback: Playback) {
            val now = clock.millis()
            val (series, episode, translation) = playback
            val reached = playback.durationMs > 0 && playback.positionMs >= playback.durationMs * WATCHED_FRACTION
            val watched = reached || dao.progressOf(episode.id)?.watched == true
            dao.savePlayback(
                LibrarySeriesEntity(
                    seriesId = series.id,
                    title = series.title,
                    posterUrl = series.posterUrl,
                    translationType = translation.kind.apiType,
                    team = translation.team,
                    lastEpisodeId = episode.id,
                    lastTranslationId = translation.id,
                    lastWatchedAt = now,
                    episodeCount = series.episodeCount,
                    isHentai = series.isHentai,
                    // Начатое аниме попадает в «Смотрю», если Статус ему ещё не выбирали.
                    status = LibraryStatus.Watching.key,
                    statusChangedAt = now,
                ),
                EpisodeProgressEntity(
                    episodeId = episode.id,
                    seriesId = series.id,
                    label = episode.label,
                    positionMs = playback.positionMs,
                    durationMs = playback.durationMs,
                    watched = watched,
                    updatedAt = now,
                ),
            )
        }
    }

/** Время выхода последнего Эпизода на странице аниме, в миллисекундах; `null` — страница не загружалась. */
private val Series.latestUploadedAt: Long?
    get() = details?.episodes?.mapNotNull { it.uploadedAt?.toEpochMilli() }?.maxOrNull()
