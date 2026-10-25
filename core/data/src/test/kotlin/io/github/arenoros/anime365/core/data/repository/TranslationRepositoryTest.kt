package io.github.arenoros.anime365.core.data.repository

import io.github.arenoros.anime365.core.data.FakeAccountStore
import io.github.arenoros.anime365.core.data.FakeAnime365Api
import io.github.arenoros.anime365.core.data.MutableClock
import io.github.arenoros.anime365.core.data.inMemoryCacheDatabase
import io.github.arenoros.anime365.core.data.seriesDto
import io.github.arenoros.anime365.core.data.toDetailsEntity
import io.github.arenoros.anime365.core.data.toEntity
import io.github.arenoros.anime365.core.data.toEpisodeEntities
import io.github.arenoros.anime365.core.database.CacheDatabase
import io.github.arenoros.anime365.core.database.model.ListStateEntity
import io.github.arenoros.anime365.core.datastore.StoredAccount
import io.github.arenoros.anime365.core.model.StreamQuality
import io.github.arenoros.anime365.core.model.TranslationKind
import io.github.arenoros.anime365.core.network.model.ApiErrorDto
import io.github.arenoros.anime365.core.network.model.ApiResponse
import io.github.arenoros.anime365.core.network.model.DownloadDto
import io.github.arenoros.anime365.core.network.model.EmbedDto
import io.github.arenoros.anime365.core.network.model.StreamDto
import io.github.arenoros.anime365.core.network.model.TranslationDto
import io.github.arenoros.anime365.core.network.model.UserDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranslationRepositoryTest {
    private val database: CacheDatabase = inMemoryCacheDatabase()
    private val api = FakeAnime365Api()
    private val store = FakeAccountStore()
    private val clock = MutableClock()
    private val repository =
        OfflineFirstTranslationRepository(
            api,
            database.translationDao(),
            store,
            OfflineFirstAccountRepository(api, store),
            clock,
        )

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun adultTranslationsIgnoreOldEmptyOrdinaryCache() =
        runTest {
            cacheAdultEpisode()
            database.translationDao().replace(
                281229,
                emptyList(),
                ListStateEntity("translations/281229", null, clock.millis()),
            )
            api.adultTranslationsOfEpisode = { listOf(TranslationDto(id = 5752471, type = "voiceRu")) }

            val translations = repository.load(281229).getOrThrow()

            assertEquals(listOf(5752471), translations.map { it.id })
            assertEquals(1, api.adultEpisodeTranslationRequests)
            assertEquals(0, api.episodeTranslationRequests)
        }

    @Test
    fun adultStreamUsesAdultEndpointAndResolvesRelativeLinksOnAdultSite() =
        runTest {
            cacheAdultEpisode()
            store.save(StoredAccount("token", "User", null))
            api.adultTranslationsOfEpisode = { listOf(TranslationDto(id = 5752471, type = "voiceRu")) }
            api.adultEmbed = { id, token ->
                assertEquals(5752471, id)
                assertEquals("token", token)
                ApiResponse(
                    EmbedDto(
                        stream = listOf(StreamDto(720, listOf("https://cdn/video"))),
                        subtitlesUrl = "/episodeTranslations/5752471.ass",
                        download = listOf(DownloadDto(720, "/translations/mp4/5752471?download=1")),
                    ),
                )
            }
            repository.load(281229).getOrThrow()

            val stream = repository.stream(5752471).getOrThrow()

            assertEquals("https://hentai365.org/episodeTranslations/5752471.ass", stream.subtitlesUrl)
            assertEquals("https://hentai365.org/translations/mp4/5752471?download=1", stream.downloads.single().url)
        }

    private suspend fun cacheAdultEpisode() {
        val dto =
            seriesDto(24504).copy(
                isHentai = 1,
                episodes =
                    listOf(
                        io.github.arenoros.anime365.core.network.model
                            .EpisodeDto(id = 281229, episodeInt = 1.0),
                    ),
            )
        database.seriesDao().saveDetails(dto.toEntity()!!, dto.toDetailsEntity(clock.millis()), dto.toEpisodeEntities())
    }

    @Test
    fun translationsAreInSiteOrderAndCachedForAnHour() =
        runTest {
            api.translationsOfEpisode = {
                listOf(
                    TranslationDto(
                        id = 1,
                        type = "voiceRu",
                        authorsSummary = "AniLibria ",
                        priority = 10,
                        height = 720,
                    ),
                    TranslationDto(id = 2, type = "subRu", authorsSummary = "", qualityType = "bd", priority = 30),
                    TranslationDto(id = 3, type = "voiceOther", priority = 20),
                )
            }

            repository.refresh(7, force = false)
            clock.advanceMinutes(59)
            repository.refresh(7, force = false)

            val translations = repository.translations(7).first()
            assertEquals(listOf(2, 3, 1), translations.map { it.id })
            assertEquals(TranslationKind.SubRu, translations[0].kind)
            assertEquals("bd", translations[0].source)
            assertEquals("AniLibria", translations[2].team)
            assertEquals(7, translations[2].episodeId)
            assertEquals(1, api.episodeTranslationRequests)

            clock.advanceMinutes(2)
            repository.load(7)
            assertEquals(2, api.episodeTranslationRequests)
        }

    @Test
    fun streamWithoutLoginDoesNotAskServer() =
        runTest {
            val result = repository.stream(1)

            assertTrue(result.exceptionOrNull() is NotLoggedInException)
        }

    @Test
    fun streamResolvesSubtitlesAgainstSiteAndSortsQualities() =
        runTest {
            store.save(StoredAccount("token", "Фрирен", null))
            api.embed = { id, token ->
                assertEquals(5987619, id)
                assertEquals("token", token)
                ApiResponse(
                    EmbedDto(
                        stream =
                            listOf(
                                StreamDto(720, listOf("https://cdn/720", "https://mirror/720")),
                                StreamDto(1080, listOf("https://cdn/1080")),
                                StreamDto(360, emptyList()),
                            ),
                        subtitlesUrl = "/episodeTranslations/5987619.ass?willcache",
                        download =
                            listOf(
                                DownloadDto(
                                    720,
                                    "https://smotret-anime.co/translations/mp4/5987619?download=1&height=720",
                                ),
                                DownloadDto(
                                    1080,
                                    "https://smotret-anime.co/translations/mp4/5987619?download=1&height=1080",
                                ),
                            ),
                    ),
                )
            }

            val stream = repository.stream(5987619).getOrThrow()

            assertEquals(
                listOf(StreamQuality(1080, "https://cdn/1080"), StreamQuality(720, "https://cdn/720")),
                stream.qualities,
            )
            assertEquals("https://smotret-anime.app/episodeTranslations/5987619.ass?willcache", stream.subtitlesUrl)
            assertEquals(listOf(1080, 720), stream.downloads.map { it.height })
        }

    @Test
    fun forbiddenStreamForLoggedInUserMeansNoSubscription() =
        runTest {
            store.save(StoredAccount("token", "Фрирен", null))
            api.users["token"] = UserDto(isLogined = true, name = "Фрирен")
            api.embed = { _, _ -> ApiResponse(error = ApiErrorDto(403, "Forbidden")) }

            val result = repository.stream(1)

            assertTrue(result.exceptionOrNull() is SubscriptionRequiredException)
        }

    @Test
    fun forbiddenStreamWithRevokedTokenLogsOut() =
        runTest {
            store.save(StoredAccount("revoked", "Фрирен", null))
            api.embed = { _, _ -> ApiResponse(error = ApiErrorDto(403, "You should login first.")) }

            val result = repository.stream(1)

            assertTrue(result.exceptionOrNull() is NotLoggedInException)
            assertNull(store.account.value)
        }
}
