package io.github.arenoros.anime365.core.data

import io.github.arenoros.anime365.core.database.model.DescriptionEntry
import io.github.arenoros.anime365.core.database.model.EpisodeEntity
import io.github.arenoros.anime365.core.database.model.FeedEpisodeWithSeries
import io.github.arenoros.anime365.core.database.model.GenreEntry
import io.github.arenoros.anime365.core.database.model.LinkEntry
import io.github.arenoros.anime365.core.database.model.SeriesDetailsEntity
import io.github.arenoros.anime365.core.database.model.SeriesEntity
import io.github.arenoros.anime365.core.database.model.SeriesWithDetails
import io.github.arenoros.anime365.core.model.Description
import io.github.arenoros.anime365.core.model.Episode
import io.github.arenoros.anime365.core.model.ExternalLink
import io.github.arenoros.anime365.core.model.FeedEpisode
import io.github.arenoros.anime365.core.model.Genre
import io.github.arenoros.anime365.core.model.ReleaseStatus
import io.github.arenoros.anime365.core.model.Series
import io.github.arenoros.anime365.core.model.SeriesDetails
import io.github.arenoros.anime365.core.model.SeriesSummary
import io.github.arenoros.anime365.core.network.model.SeriesDto
import java.time.Instant

private val MAIN_TITLE_LANGUAGES = listOf("ru", "romaji", "en", "ja")
private val ORIGINAL_TITLE_LANGUAGES = listOf("romaji", "en")

/** `null` — запись не для показа: скрыта на сайте или у неё нет ни одного названия. */
internal fun SeriesDto.toEntity(): SeriesEntity? {
    val title =
        MAIN_TITLE_LANGUAGES
            .firstNotNullOfOrNull { titles[it]?.takeIf(String::isNotBlank) }
            ?.takeIf { isActive >= 0 }
            ?: return null
    return SeriesEntity(
        id = id,
        title = title,
        originalTitle =
            ORIGINAL_TITLE_LANGUAGES.firstNotNullOfOrNull { lang ->
                titles[lang]?.takeIf { it.isNotBlank() && it != title }
            },
        posterUrl = posterUrlSmall,
        kind = typeTitle,
        type = type,
        episodeCount = numberOfEpisodes.takeIf { it > 0 },
        year = year?.takeIf { it > 0 },
        season = season?.takeIf { it.isNotBlank() },
        // -1 — оценки нет.
        score = myAnimeListScore?.takeIf { it > 0 },
        isAiring = isAiring == 1,
        isActive = isActive,
        isHentai = isHentai == 1,
    )
}

internal fun SeriesDto.toDetailsEntity(fetchedAt: Long): SeriesDetailsEntity =
    SeriesDetailsEntity(
        seriesId = id,
        largePosterUrl = posterUrl,
        siteUrl = url,
        genres = genres.map { GenreEntry(it.id, it.title) },
        descriptions =
            descriptions
                .filter { it.value.isNotBlank() }
                .map { DescriptionEntry(it.source.trim(), it.value.trim()) },
        // В links бывают дубли: две «википедия (ja)».
        links =
            links
                .filter { it.url.isNotBlank() }
                .distinctBy { it.url }
                .map { LinkEntry(it.title.trim(), it.url) },
        fetchedAt = fetchedAt,
    )

internal fun SeriesDto.toEpisodeEntities(): List<EpisodeEntity> =
    episodes
        .filter { it.isActive == 1 }
        .mapIndexed { index, episode ->
            EpisodeEntity(
                id = episode.id,
                seriesId = id,
                number = episode.episodeInt,
                label = episode.episodeFull,
                type = episode.episodeType,
                position = index,
                uploadedAt = parseSiteDateTime(episode.firstUploadedDateTime)?.toEpochMilli(),
            )
        }

internal fun SeriesEntity.toSummary(): SeriesSummary =
    SeriesSummary(
        id = id,
        title = title,
        originalTitle = originalTitle,
        posterUrl = posterUrl,
        kind = kind,
        episodeCount = episodeCount,
        year = year,
        season = season,
        score = score,
        isHentai = isHentai,
        status =
            when {
                isActive == 0 -> ReleaseStatus.Announced
                isAiring -> ReleaseStatus.Ongoing
                else -> ReleaseStatus.Released
            },
    )

internal fun SeriesWithDetails.toModel(): Series =
    Series(
        summary = series.toSummary(),
        details =
            details?.let { details ->
                SeriesDetails(
                    type = series.type,
                    largePosterUrl = details.largePosterUrl,
                    siteUrl = details.siteUrl,
                    genres = details.genres.map { Genre(it.id, it.title) },
                    descriptions = details.descriptions.map { Description(it.source, it.text) },
                    links = details.links.map { ExternalLink(it.title, it.url) },
                    episodes =
                        episodes
                            .sortedBy { it.position }
                            .map {
                                Episode(
                                    it.id,
                                    it.number,
                                    it.label,
                                    it.type,
                                    it.uploadedAt?.let(Instant::ofEpochMilli),
                                )
                            },
                )
            },
    )

/** `null`, пока аниме этого Эпизода не загружено. */
internal fun FeedEpisodeWithSeries.toModel(): FeedEpisode? =
    series?.takeUnless { it.isHentai }?.let { series ->
        FeedEpisode(
            episodeId = episode.episodeId,
            seriesId = series.id,
            seriesTitle = series.title,
            posterUrl = series.posterUrl,
            label = episode.label,
            releasedAt = Instant.ofEpochMilli(episode.releasedAt),
        )
    }
