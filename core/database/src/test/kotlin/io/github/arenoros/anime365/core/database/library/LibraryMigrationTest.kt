package io.github.arenoros.anime365.core.database.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Библиотека не кэш: Прогресс и запомненный Перевод переживают смену схемы (ADR 0001).
 * База прошлой версии строится по её выгруженной схеме, открывает её тот же построитель, что и
 * приложение: миграция и сверка схемы — его. MigrationTestHelper под Robolectric не открывает базу.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun version2ListAndProgressSurviveAdultFlagMigration() {
        createVersion1(version = 2).use { db ->
            db.execSQL(
                """
                INSERT INTO library_series (seriesId, title, translationType, team, lastEpisodeId,
                    lastTranslationId, lastWatchedAt, status, statusChangedAt, episodeCount, seenUploadedAt)
                VALUES (7, 'Example', 'voiceRu', 'Team', 101, 1, 1000, 'watching', 2000, 12, 3000)
            """,
            )
            db.execSQL("INSERT INTO episode_progress VALUES (101, 7, 'Episode 1', 500, 1000, 0, 1000)")
            db.execSQL("INSERT INTO new_episode VALUES (102, 7, 'Episode 2', 4000)")
        }
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, NAME).allowMainThreadQueries().build()
        runBlocking {
            val row = database.libraryDao().allSeries().single()
            assertEquals("watching", row.status)
            assertEquals("Team", row.team)
            assertEquals(3000L, row.seenUploadedAt)
            assertEquals(false, row.isHentai)
            assertEquals(
                500L,
                database
                    .libraryDao()
                    .allProgress()
                    .single()
                    .positionMs,
            )
            assertEquals(listOf(102), database.libraryDao().newEpisodeIds(7))
        }
        database.close()
    }

    @Test
    fun version1RowsSurviveAndAreNotInList() {
        createVersion1().use { db ->
            db.execSQL(
                """
                INSERT INTO library_series
                    (seriesId, title, posterUrl, translationType, team, lastEpisodeId, lastTranslationId, lastWatchedAt)
                VALUES (30414, 'Фрирен', NULL, 'voiceRu', 'AniLibria', 101, 1, 1000)
                """,
            )
            db.execSQL("INSERT INTO episode_progress VALUES (101, 30414, '1 серия', 500, 1000, 0, 1000)")
        }

        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, NAME).allowMainThreadQueries().build()
        val dao = database.libraryDao()
        runBlocking {
            val series = dao.allSeries().single()
            assertEquals("AniLibria", series.team)
            assertNull(series.status)
            assertNull(series.seenUploadedAt)
            assertEquals(500, dao.allProgress().single().positionMs)
        }
        database.close()
    }

    private fun createVersion1(version: Int = 1): SQLiteDatabase {
        val schema =
            JSONObject(
                File("schemas/${LibraryDatabase::class.java.name}/$version.json").readText(),
            ).getJSONObject("database")
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(NAME).apply { parentFile?.mkdirs() }, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = version
        return db
    }

    private companion object {
        const val NAME = "library-migration-test"
    }
}
