package io.github.arenoros.anime365.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import kotlinx.serialization.Serializable

/** Поля, которые приходят и в списках, и на экране Аниме. [isActive] — как в API: 0 — анонс. */
@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val originalTitle: String?,
    val posterUrl: String?,
    val kind: String?,
    val type: String?,
    val episodeCount: Int?,
    val year: Int?,
    val season: String?,
    val score: Double?,
    val isAiring: Boolean,
    val isActive: Int,
    @ColumnInfo(defaultValue = "0") val isHentai: Boolean = false,
)

/** То, что приходит только с `/series/{id}`. Жанры, описания и ссылки всегда читаются целиком — хранятся JSON. */
@Entity(tableName = "series_details")
data class SeriesDetailsEntity(
    @PrimaryKey val seriesId: Int,
    val largePosterUrl: String?,
    val siteUrl: String?,
    val genres: List<GenreEntry>,
    val descriptions: List<DescriptionEntry>,
    val links: List<LinkEntry>,
    val fetchedAt: Long,
)

@Serializable
data class GenreEntry(
    val id: Int,
    val title: String,
)

@Serializable
data class DescriptionEntry(
    val source: String,
    val text: String,
)

@Serializable
data class LinkEntry(
    val title: String,
    val url: String,
)

/** [position] — порядок сайта. */
@Entity(tableName = "episodes", indices = [Index("seriesId")])
data class EpisodeEntity(
    @PrimaryKey val id: Int,
    val seriesId: Int,
    val number: Double,
    val label: String,
    val type: String,
    val position: Int,
    val uploadedAt: Long?,
)

data class SeriesWithDetails(
    @Embedded val series: SeriesEntity,
    @Relation(parentColumn = "id", entityColumn = "seriesId")
    val details: SeriesDetailsEntity?,
    @Relation(parentColumn = "id", entityColumn = "seriesId")
    val episodes: List<EpisodeEntity>,
)

/**
 * Состояние одного серверного списка: Каталог с конкретным запросом, Онгоинги, Новые серии.
 * [nextOffset] — с какого места догружать; `null` — список кончился.
 */
@Entity(tableName = "list_state")
data class ListStateEntity(
    @PrimaryKey val key: String,
    val nextOffset: Int?,
    val fetchedAt: Long,
)

@Entity(
    tableName = "series_list_items",
    primaryKeys = ["listKey", "seriesId"],
    indices = [Index("listKey", "position")],
)
data class SeriesListItemEntity(
    val listKey: String,
    val seriesId: Int,
    val position: Int,
)

/** Строка «Новых серий»: один Эпизод из Ленты Переводов. */
@Entity(tableName = "feed_episodes")
data class FeedEpisodeEntity(
    @PrimaryKey val episodeId: Int,
    val seriesId: Int,
    val label: String,
    val releasedAt: Long,
)

data class FeedEpisodeWithSeries(
    @Embedded val episode: FeedEpisodeEntity,
    @Relation(parentColumn = "seriesId", entityColumn = "id")
    val series: SeriesEntity?,
)

/** Переводы одного Эпизода. [position] — порядок сайта (`priority` по убыванию). */
@Entity(tableName = "translations", indices = [Index("episodeId", "position")])
data class TranslationEntity(
    @PrimaryKey val id: Int,
    val episodeId: Int,
    val type: String,
    val team: String,
    val source: String?,
    val height: Int,
    val position: Int,
)
