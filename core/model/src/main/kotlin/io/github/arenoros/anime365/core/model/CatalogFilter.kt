package io.github.arenoros.anime365.core.model

/** Фильтры Каталога. [season] без [year] не применяется: API ищет только по сезону конкретного года. */
data class CatalogFilter(
    val types: Set<SeriesType> = emptySet(),
    val year: Int? = null,
    val season: Season? = null,
    val genreIds: Set<Int> = emptySet(),
    val genreMatch: GenreMatch = GenreMatch.All,
    val ongoingOnly: Boolean = false,
    val isHentai: Boolean = false,
) {
    val isEmpty: Boolean get() = copy(isHentai = false) == CatalogFilter()
}

enum class SeriesType { Tv, Movie, Ova, Ona, Special, Music }

enum class Season { Winter, Spring, Summer, Autumn }

/** Жанры: «все выбранные» или «любой из». */
enum class GenreMatch { All, Any }
