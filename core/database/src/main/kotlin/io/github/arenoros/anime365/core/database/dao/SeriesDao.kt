package io.github.arenoros.anime365.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.arenoros.anime365.core.database.model.EpisodeEntity
import io.github.arenoros.anime365.core.database.model.SeriesDetailsEntity
import io.github.arenoros.anime365.core.database.model.SeriesEntity
import io.github.arenoros.anime365.core.database.model.SeriesWithDetails
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SeriesDao {
    @Query("SELECT id FROM series WHERE isHentai = 1")
    abstract fun adultIds(): Flow<List<Int>>

    @Transaction
    @Query("SELECT * FROM series WHERE id = :id")
    abstract fun observe(id: Int): Flow<SeriesWithDetails?>

    @Query("SELECT fetchedAt FROM series_details WHERE seriesId = :id")
    abstract suspend fun detailsFetchedAt(id: Int): Long?

    @Query("SELECT id FROM series WHERE id IN (:ids)")
    abstract suspend fun existingIds(ids: Collection<Int>): List<Int>

    @Upsert
    abstract suspend fun upsert(series: List<SeriesEntity>)

    @Transaction
    open suspend fun saveDetails(
        series: SeriesEntity,
        details: SeriesDetailsEntity,
        episodes: List<EpisodeEntity>,
    ) {
        upsert(listOf(series))
        upsertDetails(details)
        deleteEpisodes(series.id)
        insertEpisodes(episodes)
    }

    @Upsert
    protected abstract suspend fun upsertDetails(details: SeriesDetailsEntity)

    @Query("DELETE FROM episodes WHERE seriesId = :seriesId")
    protected abstract suspend fun deleteEpisodes(seriesId: Int)

    @Insert
    protected abstract suspend fun insertEpisodes(episodes: List<EpisodeEntity>)
}
