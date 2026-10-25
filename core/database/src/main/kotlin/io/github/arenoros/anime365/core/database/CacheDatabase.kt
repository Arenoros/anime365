package io.github.arenoros.anime365.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import io.github.arenoros.anime365.core.database.dao.FeedDao
import io.github.arenoros.anime365.core.database.dao.SeriesDao
import io.github.arenoros.anime365.core.database.dao.SeriesListDao
import io.github.arenoros.anime365.core.database.dao.TranslationDao
import io.github.arenoros.anime365.core.database.model.EpisodeEntity
import io.github.arenoros.anime365.core.database.model.FeedEpisodeEntity
import io.github.arenoros.anime365.core.database.model.ListStateEntity
import io.github.arenoros.anime365.core.database.model.SeriesDetailsEntity
import io.github.arenoros.anime365.core.database.model.SeriesEntity
import io.github.arenoros.anime365.core.database.model.SeriesListItemEntity
import io.github.arenoros.anime365.core.database.model.TranslationEntity

/**
 * Кэш данных сайта (ADR 0004). Отдельный файл от базы Библиотеки (`library.db`): его можно стереть
 * целиком («Очистить кэш» в Настройках), и он не попадает в резервную копию Android.
 */
@Database(
    entities = [
        SeriesEntity::class,
        SeriesDetailsEntity::class,
        EpisodeEntity::class,
        ListStateEntity::class,
        SeriesListItemEntity::class,
        FeedEpisodeEntity::class,
        TranslationEntity::class,
    ],
    version = 4,
    autoMigrations = [AutoMigration(from = 3, to = 4)],
)
@TypeConverters(Converters::class)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun seriesDao(): SeriesDao

    abstract fun seriesListDao(): SeriesListDao

    abstract fun feedDao(): FeedDao

    abstract fun translationDao(): TranslationDao

    companion object {
        const val FILE_NAME = "cache.db"
    }
}
