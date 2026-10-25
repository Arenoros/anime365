package io.github.arenoros.anime365.core.network

import io.github.arenoros.anime365.core.network.model.AccessTokenDto
import io.github.arenoros.anime365.core.network.model.ApiResponse
import io.github.arenoros.anime365.core.network.model.EmbedDto
import io.github.arenoros.anime365.core.network.model.SeriesDto
import io.github.arenoros.anime365.core.network.model.TranslationDto
import io.github.arenoros.anime365.core.network.model.UserDto
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Публичное API anime365 (docs/anime365-openapi.yaml, поведение сверх спецификации — docs/api-notes.md).
 * Ошибки приходят с HTTP 200 и полем `error`, поэтому каждый ответ разворачивается через [ApiResponse.unwrap].
 */
interface Anime365Api {
    /**
     * Сортировка — по рейтингу MyAnimeList. [chips] и [query] не сочетаются: при `chips` сервер
     * игнорирует `query`.
     */
    @GET
    suspend fun series(
        @Url url: String = "series",
        @Query("fields") fields: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int = 0,
        @Query("query") query: String? = null,
        @Query("chips") chips: String? = null,
        @Query("isAiring") isAiring: Int? = null,
        @Query("isActive") isActive: Int? = null,
        @Query("season") season: String? = null,
    ): ApiResponse<List<SeriesDto>>

    @GET("series/{id}")
    suspend fun series(
        @Path("id") id: Int,
        @Query("fields") fields: String,
    ): ApiResponse<SeriesDto>

    /** Лента `recent` отсортирована по `activeDateTime` от новых к старым; `afterId` она игнорирует. */
    @GET("translations")
    suspend fun translations(
        @Query("feed") feed: String,
        @Query("fields") fields: String,
        @Query("limit") limit: Int,
    ): ApiResponse<List<TranslationDto>>

    @GET("translations")
    suspend fun episodeTranslations(
        @Query("episodeId") episodeId: Int,
        @Query("fields") fields: String = ApiFields.TRANSLATION,
        @Query("limit") limit: Int = EPISODE_TRANSLATIONS_LIMIT,
    ): ApiResponse<List<TranslationDto>>

    @GET("${HENTAI365_BASE_URL}translations")
    suspend fun adultEpisodeTranslations(
        @Query("episodeId") episodeId: Int,
        @Query("fields") fields: String = ApiFields.TRANSLATION,
        @Query("limit") limit: Int = EPISODE_TRANSLATIONS_LIMIT,
    ): ApiResponse<List<TranslationDto>>

    /**
     * Видеопоток. Без входа и без Подписки — ошибка 403. Токен API принимает только параметром
     * запроса (ADR 0005).
     */
    @GET("translations/embed/{id}")
    suspend fun embed(
        @Path("id") translationId: Int,
        @Query("access_token") accessToken: String,
    ): ApiResponse<EmbedDto>

    @GET("${HENTAI365_BASE_URL}translations/embed/{id}")
    suspend fun adultEmbed(
        @Path("id") translationId: Int,
        @Query("access_token") accessToken: String,
    ): ApiResponse<EmbedDto>

    /** С недействительным Токеном отвечает гостем, а не ошибкой. */
    @GET("me")
    suspend fun me(
        @Query("access_token") accessToken: String,
    ): ApiResponse<UserDto>

    /** Токен для сессии сайта: [cookie] — из WebView, где пользователь только что вошёл (ADR 0005). */
    @GET("accessToken")
    suspend fun accessToken(
        @Header("Cookie") cookie: String,
        @Query("app") app: String = BuildConfig.ANIME365_APP,
    ): ApiResponse<AccessTokenDto>
}

// У одного Эпизода бывает до сотни Переводов (docs/api-notes.md); 1000 — максимум API.
private const val EPISODE_TRANSLATIONS_LIMIT = 1000

/** Без `fields` в каждом элементе приходят все внешние ссылки и описания (docs/api-notes.md). */
object ApiFields {
    const val SERIES_SUMMARY =
        "id,titles,posterUrlSmall,typeTitle,type,year,season,myAnimeListScore,isAiring,isActive," +
            "numberOfEpisodes,isHentai"

    /** Для поиска: фильтры к результатам `query` применяются на устройстве, им нужны жанры. */
    const val SERIES_SEARCH = "$SERIES_SUMMARY,genres"

    const val SERIES_DETAILS = "$SERIES_SUMMARY,posterUrl,url,genres,descriptions,links,episodes"

    const val FEED = "id,activeDateTime,seriesId,episode"

    const val TRANSLATION = "id,episodeId,type,authorsSummary,qualityType,height,priority"
}
