package io.github.arenoros.anime365.core.data.catalog

import io.github.arenoros.anime365.core.data.siteSeason
import io.github.arenoros.anime365.core.model.CatalogFilter
import io.github.arenoros.anime365.core.model.GenreMatch
import io.github.arenoros.anime365.core.model.Season
import io.github.arenoros.anime365.core.model.SeriesType
import io.github.arenoros.anime365.core.network.HENTAI365_BASE_URL
import io.github.arenoros.anime365.core.network.model.SeriesDto

internal const val CATALOG_KEY_PREFIX = "catalog"

/**
 * Запрос Каталога. Без текста фильтр уходит на сервер строкой `chips`. С текстом — только `query`:
 * сервер не сочетает их (при `chips` он игнорирует `query`), поэтому к результатам поиска фильтр
 * применяется на устройстве через [matches].
 */
internal data class CatalogQuery(
    val text: String,
    val filter: CatalogFilter,
) {
    val chips: String = filter.toChips()
    val isSearch: Boolean = text.isNotEmpty()
    val listKey: String =
        (if (isSearch) "$CATALOG_KEY_PREFIX?query=${text.lowercase()}&$chips" else "$CATALOG_KEY_PREFIX?$chips") +
            if (filter.isHentai) "&hentai=1" else ""

    val url: String = if (filter.isHentai) "${HENTAI365_BASE_URL}series" else "series"

    fun matches(series: SeriesDto): Boolean =
        (series.isHentai == 1) == filter.isHentai &&
            matchesType(series) &&
            matchesTime(series) &&
            (!filter.ongoingOnly || series.isAiring == 1) &&
            matchesGenres(series)

    private fun matchesType(series: SeriesDto): Boolean {
        val types = filter.types.flatMap { it.apiValues }
        return types.isEmpty() || series.type in types
    }

    /** Год — по полю `year`, сезон — по строке сайта: «Зима 2016» у сайта бывает с `year = 2015`. */
    private fun matchesTime(series: SeriesDto): Boolean {
        val year = filter.year ?: return true
        val season = filter.season
        return if (season == null) series.year == year else series.season == siteSeason(season, year)
    }

    private fun matchesGenres(series: SeriesDto): Boolean {
        val genres = series.genres.mapTo(HashSet()) { it.id }
        return when (filter.genreMatch) {
            GenreMatch.All -> genres.containsAll(filter.genreIds)
            GenreMatch.Any -> filter.genreIds.isEmpty() || filter.genreIds.any { it in genres }
        }
    }
}

/** Синтаксис расширенного фильтра сайта (`/catalog/filter/...`): `genre@=8,35;type=tv;genre_op=and`. */
internal fun CatalogFilter.toChips(): String =
    buildList {
        if (genreIds.isNotEmpty()) {
            val operator = if (genreMatch == GenreMatch.All) "@=" else "="
            add("genre$operator${genreIds.sorted().joinToString(",")}")
        }
        if (types.isNotEmpty()) add("type=${types.sorted().flatMap { it.apiValues }.joinToString(",")}")
        if (year != null) add("yearseason=${season?.let { "${it.slug}_" }.orEmpty()}$year")
        if (ongoingOnly) add("isAiring=1")
        if (genreIds.size > 1) add("genre_op=${if (genreMatch == GenreMatch.All) "and" else "or"}")
    }.joinToString(";")

private val SeriesType.apiValues: List<String>
    get() =
        when (this) {
            SeriesType.Tv -> listOf("tv")
            SeriesType.Movie -> listOf("movie")
            SeriesType.Ova -> listOf("ova")
            SeriesType.Ona -> listOf("ona")
            SeriesType.Special -> listOf("special", "tv_special")
            SeriesType.Music -> listOf("music")
        }

private val Season.slug: String get() = name.lowercase()
