package io.github.arenoros.anime365.core.network

import io.github.arenoros.anime365.core.network.model.ApiException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Разбор ответов, записанных с живого сервера 23.10.2026 (src/test/resources/api). */
class Anime365ApiTest {
    private val server = MockWebServer()
    private lateinit var api: Anime365Api

    @Before
    fun setUp() {
        server.start()
        api = createAnime365Api(server.url("/api/").toString())
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun catalogCanUseAdultHostWithoutChangingOtherEndpoints() =
        runTest {
            server.enqueue(MockResponse.Builder().body("""{"data":[{"id":7,"isHentai":1}]}""").build())
            val items =
                api
                    .series(
                        fields = ApiFields.SERIES_SUMMARY,
                        limit = 48,
                        query = "example",
                        url = server.url("/adult/api/series").toString(),
                    ).unwrap()

            val request = server.takeRequest()
            assertEquals("/adult/api/series", request.url.encodedPath)
            assertEquals("example", request.url.queryParameter("query"))
            assertNull(request.url.queryParameter("access_token"))
            assertEquals(1, items.single().isHentai)
        }

    @Test
    fun seriesListSendsFieldsAndUserAgent() =
        runTest {
            enqueue("series-top.json")

            val series = api.series(fields = ApiFields.SERIES_SUMMARY, limit = 3).unwrap()

            assertEquals(listOf(30414, 2400, 39186), series.map { it.id })
            assertEquals("Провожающая в последний путь Фрирен", series[0].titles["ru"])
            assertEquals(9.24, series[0].myAnimeListScore!!, 0.0)
            val request = server.takeRequest()
            assertEquals(ApiFields.SERIES_SUMMARY, request.url.queryParameter("fields"))
            assertEquals("3", request.url.queryParameter("limit"))
            assertNull("пустые параметры не отправляются", request.url.queryParameter("query"))
            assertTrue(request.headers["User-Agent"]!!.startsWith("anime365-android"))
        }

    @Test
    fun emptyTitlesArrayParsesAsEmptyMap() =
        runTest {
            enqueue("series-879-no-titles.json")

            val series = api.series(879, ApiFields.SERIES_SUMMARY).unwrap()

            assertEquals(emptyMap<String, String>(), series.titles)
            assertEquals(0, series.year)
            assertEquals("", series.season)
        }

    @Test
    fun detailsContainGenresDescriptionsLinksAndEpisodes() =
        runTest {
            enqueue("series-30414-details.json")

            val series = api.series(30414, ApiFields.SERIES_DETAILS).unwrap()

            assertEquals(listOf(2, 10, 27, 8, 46), series.genres.map { it.id })
            assertEquals(listOf("shikimori.me", "world-art.ru"), series.descriptions.map { it.source })
            assertTrue(series.links.any { it.title == "MyAnimeList" })
            assertEquals(29, series.episodes.size)
            assertEquals("Трейлер", series.episodes.first().episodeFull)
            assertEquals("preview", series.episodes.first().episodeType)
            assertEquals("https://smotret-anime.co/catalog/sousou-no-frieren-30414", series.url)
        }

    @Test
    fun announcedSeriesHasNoEpisodes() =
        runTest {
            enqueue("series-42431-announced.json")

            val series = api.series(42431, ApiFields.SERIES_DETAILS).unwrap()

            assertEquals(0, series.isActive)
            assertEquals(emptyList<Any>(), series.episodes)
        }

    @Test
    fun feedContainsNestedEpisodes() =
        runTest {
            enqueue("feed-recent.json")

            val feed = api.translations(feed = "recent", fields = ApiFields.FEED, limit = 20).unwrap()

            assertEquals(20, feed.size)
            assertTrue(feed.all { it.episode != null && it.activeDateTime != null })
            assertEquals("recent", server.takeRequest().url.queryParameter("feed"))
        }

    @Test
    fun errorEnvelopeThrowsApiException() =
        runTest {
            enqueue("error-404.json")

            try {
                api.series(999999999, "id").unwrap()
                fail("ожидалась ApiException")
            } catch (e: ApiException) {
                assertEquals(404, e.code)
            }
        }

    @Test
    fun adultTranslationsAndEmbedUseAdultHost() =
        runTest {
            val hosts = mutableListOf<String>()
            val client =
                createOkHttpClient()
                    .newBuilder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        hosts += request.url.host
                        val redirected =
                            server
                                .url(request.url.encodedPath)
                                .newBuilder()
                                .encodedQuery(request.url.encodedQuery)
                                .build()
                        chain.proceed(request.newBuilder().url(redirected).build())
                    }.build()
            val adultApi = createAnime365Api(server.url("/api/").toString(), client)
            enqueue("translations-episode-291395.json")
            enqueue("embed-5987619.json")

            adultApi.adultEpisodeTranslations(281229).unwrap()
            adultApi.adultEmbed(5752471, "secret").unwrap()

            val translations = server.takeRequest()
            assertEquals("/api/translations", translations.url.encodedPath)
            assertEquals("281229", translations.url.queryParameter("episodeId"))
            assertEquals("1000", translations.url.queryParameter("limit"))
            val embed = server.takeRequest()
            assertEquals("/api/translations/embed/5752471", embed.url.encodedPath)
            assertEquals("secret", embed.url.queryParameter("access_token"))
            assertEquals(listOf("hentai365.org", "hentai365.org"), hosts)
        }

    @Test
    fun episodeTranslationsParse() =
        runTest {
            enqueue("translations-episode-291395.json")

            val translations = api.episodeTranslations(291395).unwrap()

            assertEquals(listOf(5987618, 5987619, 5938574), translations.map { it.id })
            assertEquals("", translations[0].authorsSummary)
            assertEquals("subEn", translations[1].type)
            assertEquals("bd", translations[1].qualityType)
            assertEquals(1080, translations[1].height)
            assertEquals(113831, translations[1].priority)
            assertEquals(ApiFields.TRANSLATION, server.takeRequest().url.queryParameter("fields"))
        }

    @Test
    fun embedSendsTokenAndParsesStream() =
        runTest {
            enqueue("embed-5987619.json")

            val embed = api.embed(5987619, accessToken = "secret").unwrap()

            assertEquals(listOf(1080, 720, 360), embed.stream.map { it.height })
            assertEquals("/episodeTranslations/5987619.ass?willcache", embed.subtitlesUrl)
            assertEquals(
                "https://smotret-anime.co/translations/mp4/5987619?download=1&height=720",
                embed.download.single { it.height == 720 }.url,
            )
            val request = server.takeRequest()
            assertEquals("/api/translations/embed/5987619", request.url.encodedPath)
            assertEquals("secret", request.url.queryParameter("access_token"))
        }

    @Test
    fun embedWithoutLoginIsApiError() =
        runTest {
            enqueue("embed-403.json")

            try {
                api.embed(5987619, accessToken = "").unwrap()
                fail("ожидалась ошибка 403")
            } catch (e: ApiException) {
                assertEquals(403, e.code)
            }
        }

    @Test
    fun meParsesGuestAndUser() =
        runTest {
            enqueue("me-guest.json")
            enqueue("me-premium.json")

            val guest = api.me("bad").unwrap()
            val user = api.me("good").unwrap()

            assertEquals(false, guest.isLogined)
            assertEquals(true, user.isLogined)
            assertEquals("Тестовый Пользователь", user.name)
            assertEquals("2028-03-19 18:48:30", user.premiumUntil)
        }

    @Test
    fun accessTokenSendsSessionCookie() =
        runTest {
            enqueue("access-token.json")

            val token = api.accessToken(cookie = "PHPSESSID=abc", app = "app-id").unwrap()

            assertEquals("test-token-not-real", token.accessToken)
            val request = server.takeRequest()
            assertEquals("PHPSESSID=abc", request.headers["Cookie"])
            assertEquals("app-id", request.url.queryParameter("app"))
        }

    private fun enqueue(fixture: String) {
        val body = javaClass.getResource("/api/$fixture")!!.readText()
        server.enqueue(
            MockResponse
                .Builder()
                .body(body)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .build(),
        )
    }
}
