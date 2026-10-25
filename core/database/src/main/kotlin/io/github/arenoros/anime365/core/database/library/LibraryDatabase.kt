package io.github.arenoros.anime365.core.database.library

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Библиотека и Прогресс (ADR 0001): существуют только на устройстве и входят в резервную копию
 * Android. Не кэш — смена схемы только через миграцию, пересоздавать её нельзя.
 */
@Database(
    entities = [LibrarySeriesEntity::class, EpisodeProgressEntity::class, NewEpisodeEntity::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        const val FILE_NAME = "library.db"
    }
}
