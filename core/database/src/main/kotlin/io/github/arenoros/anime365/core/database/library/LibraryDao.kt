package io.github.arenoros.anime365.core.database.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val LAST_WATCHED_SELECT = """
    SELECT s.seriesId, s.title, s.posterUrl, p.episodeId, p.label, s.lastTranslationId AS translationId,
        p.positionMs, p.durationMs, p.watched
    FROM library_series s JOIN episode_progress p ON p.episodeId = s.lastEpisodeId
"""

@Dao
abstract class LibraryDao {
    @Query("SELECT seriesId FROM library_series WHERE isHentai = 1")
    abstract fun adultIds(): Flow<List<Int>>

    @Query("SELECT * FROM episode_progress WHERE seriesId = :seriesId")
    abstract fun progress(seriesId: Int): Flow<List<EpisodeProgressEntity>>

    @Query("SELECT * FROM episode_progress WHERE episodeId = :episodeId")
    abstract suspend fun progressOf(episodeId: Int): EpisodeProgressEntity?

    @Query("$LAST_WATCHED_SELECT WHERE s.seriesId = :seriesId")
    abstract fun lastWatched(seriesId: Int): Flow<LastWatchedRow?>

    /** Недосмотренные Эпизоды, по одному на аниме, от последних к давним. */
    @Query(
        """$LAST_WATCHED_SELECT
        WHERE s.isHentai = 0 AND p.watched = 0 AND p.positionMs > 0
        ORDER BY s.lastWatchedAt DESC LIMIT :limit
        """,
    )
    abstract fun continueWatching(limit: Int): Flow<List<LastWatchedRow>>

    @Query("SELECT * FROM library_series WHERE seriesId = :seriesId")
    abstract suspend fun series(seriesId: Int): LibrarySeriesEntity?

    /** Аниме со Статусом: сначала те, где есть Новые Эпизоды, потом — что трогали последним. */
    @Query(
        """
        SELECT s.seriesId, s.title, s.posterUrl, s.status, s.episodeCount, s.isHentai,
            (SELECT COUNT(*) FROM episode_progress p WHERE p.seriesId = s.seriesId AND p.watched = 1)
                AS watchedEpisodes,
            (SELECT COUNT(*) FROM new_episode n WHERE n.seriesId = s.seriesId) AS newEpisodes
        FROM library_series s WHERE s.status IS NOT NULL
        ORDER BY newEpisodes > 0 DESC, MAX(IFNULL(s.lastWatchedAt, 0), IFNULL(s.statusChangedAt, 0)) DESC
        """,
    )
    abstract fun entries(): Flow<List<LibraryEntryRow>>

    @Query("SELECT status FROM library_series WHERE seriesId = :seriesId")
    abstract fun status(seriesId: Int): Flow<String?>

    /** Счётчик на табе «Список»: Новые Эпизоды бывают только у аниме со Статусом «Смотрю». */
    @Query(
        """
        SELECT COUNT(*) FROM new_episode n JOIN library_series s ON s.seriesId = n.seriesId
        WHERE s.status = :status AND (:includeAdult OR s.isHentai = 0)
        """,
    )
    abstract fun newEpisodeCount(
        status: String,
        includeAdult: Boolean = false,
    ): Flow<Int>

    @Query("SELECT episodeId FROM new_episode WHERE seriesId = :seriesId")
    abstract suspend fun newEpisodeIds(seriesId: Int): List<Int>

    @Query("SELECT * FROM library_series WHERE status = :status")
    abstract suspend fun seriesWithStatus(status: String): List<LibrarySeriesEntity>

    @Query("SELECT episodeId FROM episode_progress WHERE seriesId = :seriesId")
    abstract suspend fun startedEpisodeIds(seriesId: Int): List<Int>

    /** Статус и копии из кэша; строка появляется, если аниме ещё не смотрели. */
    @Transaction
    open suspend fun setStatus(
        series: LibrarySeriesEntity,
        seenUploadedAt: Long?,
    ) {
        insertSeries(series)
        updateStatus(series.seriesId, series.status, series.statusChangedAt)
        updateCopies(series.seriesId, series.title, series.posterUrl, series.episodeCount, series.isHentai)
        if (seenUploadedAt != null) updateSeen(series.seriesId, seenUploadedAt)
        if (series.status == null) deleteNewEpisodes(series.seriesId)
    }

    /** Страницу аниме открыли: всё, что на ней было, — уже не новое. Аниме не в Библиотеке — ничего не делает. */
    @Transaction
    open suspend fun markSeen(
        seriesId: Int,
        title: String,
        posterUrl: String?,
        episodeCount: Int?,
        seenUploadedAt: Long?,
        isHentai: Boolean = false,
    ) {
        updateCopies(seriesId, title, posterUrl, episodeCount, isHentai)
        if (seenUploadedAt != null) updateSeen(seriesId, seenUploadedAt)
        deleteNewEpisodes(seriesId)
    }

    /** `-1` в ответе — Эпизод уже был записан новым раньше. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertNewEpisodes(episodes: List<NewEpisodeEntity>): List<Long>

    @Query(
        """
        UPDATE library_series SET seenUploadedAt = MAX(IFNULL(seenUploadedAt, 0), :uploadedAt)
        WHERE seriesId = :seriesId
        """,
    )
    abstract suspend fun updateSeen(
        seriesId: Int,
        uploadedAt: Long,
    )

    @Query("SELECT * FROM library_series")
    abstract suspend fun allSeries(): List<LibrarySeriesEntity>

    @Query("SELECT * FROM episode_progress")
    abstract suspend fun allProgress(): List<EpisodeProgressEntity>

    /** Импорт с заменой: Новые Эпизоды прежнего Списка к новому не относятся. */
    @Transaction
    open suspend fun replaceAll(
        series: List<LibrarySeriesEntity>,
        progress: List<EpisodeProgressEntity>,
    ) {
        deleteAllNewEpisodes()
        deleteAllProgress()
        deleteAllSeries()
        upsertAll(series, progress)
    }

    @Transaction
    open suspend fun upsertAll(
        series: List<LibrarySeriesEntity>,
        progress: List<EpisodeProgressEntity>,
    ) {
        upsertSeries(series)
        upsertProgressList(progress)
    }

    /**
     * Строку аниме обновляем по полям, а не заменяем: запись Прогресса не должна затирать Статус.
     * [LibrarySeriesEntity.status] из [series] ставится, только если Статус аниме ещё ни разу не
     * выбирали: убранное из Списка само туда не возвращается. Начатый Эпизод — уже не новый.
     */
    @Transaction
    open suspend fun savePlayback(
        series: LibrarySeriesEntity,
        progress: EpisodeProgressEntity,
    ) {
        insertSeries(series)
        updateCopies(series.seriesId, series.title, series.posterUrl, series.episodeCount, series.isHentai)
        updateSeries(
            series.seriesId,
            series.title,
            series.posterUrl,
            series.episodeCount,
            series.translationType,
            series.team,
            series.lastEpisodeId,
            series.lastTranslationId,
            series.lastWatchedAt,
        )
        if (series.status != null) setFirstStatus(series.seriesId, series.status, series.statusChangedAt)
        upsertProgress(progress)
        deleteNewEpisode(progress.episodeId)
    }

    @Query(
        """
        UPDATE library_series SET status = :status, statusChangedAt = :changedAt
        WHERE seriesId = :seriesId AND status IS NULL AND statusChangedAt IS NULL
        """,
    )
    protected abstract suspend fun setFirstStatus(
        seriesId: Int,
        status: String,
        changedAt: Long?,
    )

    /** Отметка вручную: позиция Эпизода, если он начат, остаётся; новым он больше не считается. */
    @Transaction
    open suspend fun setWatched(
        series: LibrarySeriesEntity,
        progress: EpisodeProgressEntity,
    ) {
        insertSeries(series)
        updateCopies(series.seriesId, series.title, series.posterUrl, series.episodeCount, series.isHentai)
        insertProgress(progress)
        updateWatched(progress.episodeId, progress.watched, progress.updatedAt)
        deleteNewEpisode(progress.episodeId)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSeries(series: LibrarySeriesEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertProgress(progress: EpisodeProgressEntity)

    @Query("UPDATE episode_progress SET watched = :watched, updatedAt = :updatedAt WHERE episodeId = :episodeId")
    protected abstract suspend fun updateWatched(
        episodeId: Int,
        watched: Boolean,
        updatedAt: Long,
    )

    @Query("UPDATE library_series SET status = :status, statusChangedAt = :changedAt WHERE seriesId = :seriesId")
    protected abstract suspend fun updateStatus(
        seriesId: Int,
        status: String?,
        changedAt: Long?,
    )

    @Query(
        """
        UPDATE library_series SET title = :title, posterUrl = :posterUrl, episodeCount = :episodeCount, isHentai = :isHentai
        WHERE seriesId = :seriesId
        """,
    )
    protected abstract suspend fun updateCopies(
        seriesId: Int,
        title: String,
        posterUrl: String?,
        episodeCount: Int?,
        isHentai: Boolean,
    )

    @Query("DELETE FROM new_episode WHERE seriesId = :seriesId")
    protected abstract suspend fun deleteNewEpisodes(seriesId: Int)

    @Query("DELETE FROM new_episode WHERE episodeId = :episodeId")
    protected abstract suspend fun deleteNewEpisode(episodeId: Int)

    @Query("DELETE FROM new_episode")
    protected abstract suspend fun deleteAllNewEpisodes()

    @Query("DELETE FROM episode_progress")
    protected abstract suspend fun deleteAllProgress()

    @Query("DELETE FROM library_series")
    protected abstract suspend fun deleteAllSeries()

    @Upsert
    protected abstract suspend fun upsertSeries(series: List<LibrarySeriesEntity>)

    @Upsert
    protected abstract suspend fun upsertProgressList(progress: List<EpisodeProgressEntity>)

    @Suppress("LongParameterList")
    @Query(
        """
        UPDATE library_series SET title = :title, posterUrl = :posterUrl, episodeCount = :episodeCount,
            translationType = :translationType,
            team = :team, lastEpisodeId = :lastEpisodeId, lastTranslationId = :lastTranslationId,
            lastWatchedAt = :lastWatchedAt
        WHERE seriesId = :seriesId
        """,
    )
    protected abstract suspend fun updateSeries(
        seriesId: Int,
        title: String,
        posterUrl: String?,
        episodeCount: Int?,
        translationType: String?,
        team: String?,
        lastEpisodeId: Int?,
        lastTranslationId: Int?,
        lastWatchedAt: Long?,
    )

    @Upsert
    protected abstract suspend fun upsertProgress(progress: EpisodeProgressEntity)
}
