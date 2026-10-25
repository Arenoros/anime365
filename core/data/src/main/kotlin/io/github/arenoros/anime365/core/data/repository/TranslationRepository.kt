package io.github.arenoros.anime365.core.data.repository

import io.github.arenoros.anime365.core.data.catchingFailures
import io.github.arenoros.anime365.core.data.toEntity
import io.github.arenoros.anime365.core.data.toModel
import io.github.arenoros.anime365.core.database.dao.TranslationDao
import io.github.arenoros.anime365.core.database.model.ListStateEntity
import io.github.arenoros.anime365.core.datastore.AccountStore
import io.github.arenoros.anime365.core.model.Account
import io.github.arenoros.anime365.core.model.Translation
import io.github.arenoros.anime365.core.model.VideoStream
import io.github.arenoros.anime365.core.network.ANIME365_SITE_URL
import io.github.arenoros.anime365.core.network.Anime365Api
import io.github.arenoros.anime365.core.network.HENTAI365_SITE_URL
import io.github.arenoros.anime365.core.network.model.ApiException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject

interface TranslationRepository {
    /** Переводы Эпизода в порядке сайта, из кэша. */
    fun translations(episodeId: Int): Flow<List<Translation>>

    /** Без [force] запрашивает сервер, только если список старше часа. */
    suspend fun refresh(
        episodeId: Int,
        force: Boolean,
    ): Result<Unit>

    /** Переводы Эпизода сразу: из кэша, если он свежий, иначе с сервера. */
    suspend fun load(episodeId: Int): Result<List<Translation>>

    /**
     * Свежий Видеопоток: ссылки живут сутки, поэтому без кэша. Без входа —
     * [NotLoggedInException], без Подписки — [SubscriptionRequiredException].
     */
    suspend fun stream(translationId: Int): Result<VideoStream>
}

/** Вход выполнен, но Видеопоток выдаётся только с Подпиской. */
class SubscriptionRequiredException : IllegalStateException("Subscription required")

// Сроки кэша — docs/roadmap.md, M1.
private val TRANSLATIONS_TTL_MILLIS = TimeUnit.HOURS.toMillis(1)
private const val HTTP_FORBIDDEN = 403

internal class OfflineFirstTranslationRepository
    @Inject
    constructor(
        private val api: Anime365Api,
        private val dao: TranslationDao,
        private val store: AccountStore,
        private val accountRepository: AccountRepository,
        private val clock: Clock,
    ) : TranslationRepository {
        override fun translations(episodeId: Int): Flow<List<Translation>> =
            dao.observe(episodeId).map { rows -> rows.map { it.toModel() } }.distinctUntilChanged()

        override suspend fun refresh(
            episodeId: Int,
            force: Boolean,
        ): Result<Unit> =
            catchingFailures {
                val adult = dao.isAdultEpisode(episodeId) == true
                val key = if (adult) "hentai-translations/$episodeId" else "translations/$episodeId"
                val fetchedAt = dao.fetchedAt(key)
                if (!force && fetchedAt != null && clock.millis() - fetchedAt < TRANSLATIONS_TTL_MILLIS) {
                    return@catchingFailures
                }
                val response =
                    if (adult) {
                        api.adultEpisodeTranslations(
                            episodeId,
                        )
                    } else {
                        api.episodeTranslations(episodeId)
                    }
                val translations =
                    response
                        .unwrap()
                        .sortedByDescending { it.priority }
                        .mapIndexed { position, dto -> dto.toEntity(episodeId, position) }
                dao.replace(episodeId, translations, ListStateEntity(key, nextOffset = null, clock.millis()))
            }

        override suspend fun load(episodeId: Int): Result<List<Translation>> =
            refresh(episodeId, force = false).map { dao.get(episodeId).map { it.toModel() } }

        override suspend fun stream(translationId: Int): Result<VideoStream> =
            catchingFailures {
                val token = store.account.first()?.accessToken ?: throw NotLoggedInException()
                val adult = dao.isAdultTranslation(translationId) == true
                val embed =
                    try {
                        (if (adult) api.adultEmbed(translationId, token) else api.embed(translationId, token)).unwrap()
                    } catch (e: ApiException) {
                        if (e.code != HTTP_FORBIDDEN) throw e
                        // 403 и без входа, и без Подписки: различаем по свежему ответу /api/me.
                        accountRepository.refresh().getOrThrow()
                        throw if (accountRepository.account.first() is Account.User) {
                            SubscriptionRequiredException()
                        } else {
                            NotLoggedInException()
                        }
                    }
                embed.toModel(if (adult) HENTAI365_SITE_URL else ANIME365_SITE_URL)
            }
    }
