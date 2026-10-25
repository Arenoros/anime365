package io.github.arenoros.anime365.core.network

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

const val ANIME365_SITE_URL = "https://smotret-anime.app/"
const val ANIME365_BASE_URL = "${ANIME365_SITE_URL}api/"
const val HENTAI365_SITE_URL = "https://hentai365.org/"
const val HENTAI365_BASE_URL = "${HENTAI365_SITE_URL}api/"

// Спецификация просит называть себя в User-Agent.
private const val USER_AGENT = "anime365-android (+https://github.com/Arenoros/anime365)"

private val anime365Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

/** Один клиент на приложение: API, Видеопоток и субтитры делят пул соединений. */
fun createOkHttpClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain
                    .request()
                    .newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build(),
            )
        }.build()

/** Отдельно от Hilt-модуля, чтобы тесты собирали тот же клиент против MockWebServer. */
fun createAnime365Api(
    baseUrl: String = ANIME365_BASE_URL,
    client: OkHttpClient = createOkHttpClient(),
): Anime365Api =
    Retrofit
        .Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(anime365Json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(Anime365Api::class.java)

fun createGitHubApi(
    baseUrl: String = GITHUB_API_URL,
    client: OkHttpClient = createOkHttpClient(),
): GitHubApi =
    Retrofit
        .Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(anime365Json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GitHubApi::class.java)
