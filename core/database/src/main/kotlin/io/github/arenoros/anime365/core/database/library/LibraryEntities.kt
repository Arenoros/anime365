package io.github.arenoros.anime365.core.database.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Аниме в Библиотеке или просто просмотренное. Название, постер и число Эпизодов — копией из кэша:
 * кэш можно стереть, Библиотеку — нет (ADR 0001). [translationType] и [team] — запомненный Вид
 * перевода и Команда. [status] — `LibraryStatus.key`, `null` — аниме смотрели, но в Список не добавили.
 * [seenUploadedAt] — время выхода (по сайту) последнего Эпизода, который был на странице аниме,
 * когда её открывали: всё, что вышло позже, — Новые Эпизоды.
 */
@Entity(tableName = "library_series")
data class LibrarySeriesEntity(
    @PrimaryKey val seriesId: Int,
    val title: String,
    val posterUrl: String?,
    val translationType: String?,
    val team: String?,
    val lastEpisodeId: Int?,
    val lastTranslationId: Int?,
    val lastWatchedAt: Long?,
    val status: String? = null,
    val statusChangedAt: Long? = null,
    val episodeCount: Int? = null,
    val seenUploadedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val isHentai: Boolean = false,
)

/** Новый Эпизод аниме со Статусом «Смотрю»: строка живёт, пока аниме не открыли или Эпизод не начали смотреть. */
@Entity(tableName = "new_episode", indices = [Index("seriesId")])
data class NewEpisodeEntity(
    @PrimaryKey val episodeId: Int,
    val seriesId: Int,
    val label: String,
    val uploadedAt: Long,
)

/** Строка Списка. */
data class LibraryEntryRow(
    val seriesId: Int,
    val title: String,
    val posterUrl: String?,
    val status: String,
    val episodeCount: Int?,
    val watchedEpisodes: Int,
    val newEpisodes: Int,
    val isHentai: Boolean = false,
)

/** Прогресс Эпизода. [label] — копией, по той же причине, что название аниме. */
@Entity(tableName = "episode_progress", indices = [Index("seriesId")])
data class EpisodeProgressEntity(
    @PrimaryKey val episodeId: Int,
    val seriesId: Int,
    val label: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val updatedAt: Long,
)

/** Последний Эпизод аниме вместе с его Прогрессом. */
data class LastWatchedRow(
    val seriesId: Int,
    val title: String,
    val posterUrl: String?,
    val episodeId: Int,
    val label: String,
    val translationId: Int,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
)
