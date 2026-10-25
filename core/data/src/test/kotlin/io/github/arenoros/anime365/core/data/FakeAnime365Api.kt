package io.github.arenoros.anime365.core.data

import io.github.arenoros.anime365.core.network.Anime365Api
import io.github.arenoros.anime365.core.network.model.AccessTokenDto
import io.github.arenoros.anime365.core.network.model.ApiErrorDto
import io.github.arenoros.anime365.core.network.model.ApiResponse
import io.github.arenoros.anime365.core.network.model.EmbedDto
import io.github.arenoros.anime365.core.network.model.SeriesDto
import io.github.arenoros.anime365.core.network.model.TranslationDto
import io.github.arenoros.anime365.core.network.model.UserDto

/** Параметры запроса `/series`, как их увидел бы сервер. */
data class SeriesRequest(
    val fields: String,
    val limit: Int,
    val offset: Int,
    val query: String?,
    val chips: String?,
    val isAiring: Int?,
    val season: String?,
    val url: String,
)

class FakeAnime365Api : Anime365Api {
    val seriesRequests = mutableListOf<SeriesRequest>()
    val detailsRequests = mutableListOf<Int>()
    var feedRequests = 0

    var seriesList: (SeriesRequest) -> List<SeriesDto> = { emptyList() }
    var seriesById: (Int) -> SeriesDto = { error("series $it not stubbed") }
    var feed: () -> List<TranslationDto> = { emptyList() }
    var translationsOfEpisode: (Int) -> List<TranslationDto> = { emptyList() }
    var episodeTranslationRequests = 0
    var adultEpisodeTranslationRequests = 0
    var adultTranslationsOfEpisode: (Int) -> List<TranslationDto> = { emptyList() }
    var adultEmbed: (Int, String) -> ApiResponse<EmbedDto> = { _, _ -> error("adult embed not stubbed") }

    /** По Токену: ответ `/me`. Незнакомый Токен — гость, как у настоящего сервера. */
    val users = mutableMapOf<String, UserDto>()
    var embed: (translationId: Int, token: String) -> ApiResponse<EmbedDto> = { _, _ -> error("embed not stubbed") }
    var sessionTokens = mutableMapOf<String, String>()

    override suspend fun series(
        url: String,
        fields: String,
        limit: Int,
        offset: Int,
        query: String?,
        chips: String?,
        isAiring: Int?,
        isActive: Int?,
        season: String?,
    ): ApiResponse<List<SeriesDto>> {
        val request = SeriesRequest(fields, limit, offset, query, chips, isAiring, season, url)
        seriesRequests += request
        return ApiResponse(seriesList(request))
    }

    override suspend fun series(
        id: Int,
        fields: String,
    ): ApiResponse<SeriesDto> {
        detailsRequests += id
        return ApiResponse(seriesById(id))
    }

    override suspend fun translations(
        feed: String,
        fields: String,
        limit: Int,
    ): ApiResponse<List<TranslationDto>> {
        feedRequests++
        return ApiResponse(feed())
    }

    override suspend fun episodeTranslations(
        episodeId: Int,
        fields: String,
        limit: Int,
    ): ApiResponse<List<TranslationDto>> {
        episodeTranslationRequests++
        return ApiResponse(translationsOfEpisode(episodeId))
    }

    override suspend fun embed(
        translationId: Int,
        accessToken: String,
    ): ApiResponse<EmbedDto> = embed.invoke(translationId, accessToken)

    override suspend fun adultEpisodeTranslations(
        episodeId: Int,
        fields: String,
        limit: Int,
    ): ApiResponse<List<TranslationDto>> {
        adultEpisodeTranslationRequests++
        return ApiResponse(adultTranslationsOfEpisode(episodeId))
    }

    override suspend fun adultEmbed(
        translationId: Int,
        accessToken: String,
    ): ApiResponse<EmbedDto> = adultEmbed.invoke(translationId, accessToken)

    override suspend fun me(accessToken: String): ApiResponse<UserDto> =
        ApiResponse(users[accessToken] ?: UserDto(isLogined = false, name = "Гость"))

    override suspend fun accessToken(
        cookie: String,
        app: String,
    ): ApiResponse<AccessTokenDto> =
        sessionTokens[cookie]?.let { ApiResponse(AccessTokenDto(it)) }
            ?: ApiResponse(error = ApiErrorDto(403, "Authorization required."))
}

fun seriesDto(
    id: Int,
    title: String = "Аниме $id",
    type: String = "tv",
    year: Int = 2026,
    season: String = "Осень 2026",
    score: Double = 8.0,
    isAiring: Int = 0,
    genres: List<Int> = emptyList(),
): SeriesDto =
    SeriesDto(
        id = id,
        titles = mapOf("ru" to title, "romaji" to "Anime $id"),
        type = type,
        typeTitle = "ТВ сериал",
        year = year,
        season = season,
        myAnimeListScore = score,
        isAiring = isAiring,
        genres =
            genres.map {
                io.github.arenoros.anime365.core.network.model
                    .GenreDto(it, "Жанр $it")
            },
    )
