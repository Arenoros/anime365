package io.github.arenoros.anime365.core.model

import java.time.Instant

/** Аниме в списках: столько, сколько нужно карточке и строке рейтинга. */
data class SeriesSummary(
    val id: Int,
    val title: String,
    val originalTitle: String?,
    val posterUrl: String?,
    val kind: String?,
    val episodeCount: Int?,
    val year: Int?,
    val season: String?,
    val score: Double?,
    val status: ReleaseStatus,
    val isHentai: Boolean = false,
)

enum class ReleaseStatus { Ongoing, Released, Announced }

/**
 * Экран Аниме. Шапку можно показать сразу из [summary], закэшированного списком;
 * [details] — `null`, пока страница аниме ни разу не загружалась.
 */
data class Series(
    val summary: SeriesSummary,
    val details: SeriesDetails?,
)

data class SeriesDetails(
    val type: String?,
    val largePosterUrl: String?,
    val siteUrl: String?,
    val genres: List<Genre>,
    val descriptions: List<Description>,
    val links: List<ExternalLink>,
    val episodes: List<Episode>,
)

data class Genre(
    val id: Int,
    val title: String,
)

/** Описание из одного Источника: Шикимори, World Art и других. */
data class Description(
    val source: String,
    val text: String,
)

data class ExternalLink(
    val title: String,
    val url: String,
)

/**
 * [label] — подпись сайта: «12 серия», «ONA 4 серия», «Трейлер».
 * [number] дробный: бывают «11.5 серия».
 * [uploadedAt] — когда Эпизод появился на сайте; по нему узнаются Новые Эпизоды: `id` идут не по порядку выхода.
 */
data class Episode(
    val id: Int,
    val number: Double,
    val label: String,
    val type: String,
    val uploadedAt: Instant? = null,
)

/** Следующий Эпизод в порядке сайта: после 1-й серии — 2-я, после трейлера — 1-я. */
fun SeriesDetails.episodeAfter(episodeId: Int): Episode? =
    episodes.indexOfFirst { it.id == episodeId }.takeIf { it >= 0 }?.let { episodes.getOrNull(it + 1) }

/** Предыдущий Эпизод в порядке сайта: перед 1-й серией — трейлер, если он есть. */
fun SeriesDetails.episodeBefore(episodeId: Int): Episode? =
    episodes.indexOfFirst { it.id == episodeId }.takeIf { it > 0 }?.let { episodes[it - 1] }

/** С чего начинать: первый Эпизод основного типа аниме, а не трейлер перед ним. */
val SeriesDetails.firstEpisode: Episode? get() = episodes.firstOrNull { it.type == type } ?: episodes.firstOrNull()
