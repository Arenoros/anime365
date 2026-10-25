package io.github.arenoros.anime365.core.testing

import android.net.Uri
import io.github.arenoros.anime365.core.data.repository.AccountRepository
import io.github.arenoros.anime365.core.data.repository.AppUpdate
import io.github.arenoros.anime365.core.data.repository.ImportMode
import io.github.arenoros.anime365.core.data.repository.LibraryFileSummary
import io.github.arenoros.anime365.core.data.repository.LibraryRepository
import io.github.arenoros.anime365.core.data.repository.LibraryTransfer
import io.github.arenoros.anime365.core.data.repository.Playback
import io.github.arenoros.anime365.core.data.repository.SeriesRepository
import io.github.arenoros.anime365.core.data.repository.TranslationRepository
import io.github.arenoros.anime365.core.data.repository.UpdateRepository
import io.github.arenoros.anime365.core.datastore.AppSettings
import io.github.arenoros.anime365.core.datastore.SettingsStore
import io.github.arenoros.anime365.core.model.Account
import io.github.arenoros.anime365.core.model.ContinueWatching
import io.github.arenoros.anime365.core.model.DownloadState
import io.github.arenoros.anime365.core.model.Episode
import io.github.arenoros.anime365.core.model.EpisodeDownload
import io.github.arenoros.anime365.core.model.EpisodeProgress
import io.github.arenoros.anime365.core.model.LastWatched
import io.github.arenoros.anime365.core.model.LibraryEntry
import io.github.arenoros.anime365.core.model.LibraryStatus
import io.github.arenoros.anime365.core.model.Series
import io.github.arenoros.anime365.core.model.SeriesSummary
import io.github.arenoros.anime365.core.model.Translation
import io.github.arenoros.anime365.core.model.TranslationChoice
import io.github.arenoros.anime365.core.model.VideoStream
import io.github.arenoros.anime365.core.player.EpisodeDownloads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

class FakeSeriesRepository : SeriesRepository {
    val series = MutableStateFlow<Series?>(null)
    val refreshCalls = mutableListOf<Pair<Int, Boolean>>()
    var result: Result<Unit> = Result.success(Unit)

    override fun series(id: Int): Flow<Series?> = series

    override suspend fun refresh(
        id: Int,
        force: Boolean,
    ): Result<Unit> {
        refreshCalls += id to force
        return result
    }
}

/** Переводы по `id` Эпизода; [stream] — ответ Видеопотока по `id` Перевода. */
class FakeTranslationRepository : TranslationRepository {
    val translations = MutableStateFlow<Map<Int, List<Translation>>>(emptyMap())
    var refreshResult: Result<Unit> = Result.success(Unit)
    var stream: (Int) -> Result<VideoStream> = { error("stream $it not stubbed") }
    val streamRequests = mutableListOf<Int>()

    override fun translations(episodeId: Int): Flow<List<Translation>> = translations.map { it[episodeId].orEmpty() }

    override suspend fun refresh(
        episodeId: Int,
        force: Boolean,
    ): Result<Unit> = refreshResult

    override suspend fun load(episodeId: Int): Result<List<Translation>> =
        refreshResult.map { translations.value[episodeId].orEmpty() }

    override suspend fun stream(translationId: Int): Result<VideoStream> {
        streamRequests += translationId
        return stream.invoke(translationId)
    }
}

@Suppress("TooManyFunctions")
class FakeLibraryRepository : LibraryRepository {
    val progress = MutableStateFlow<Map<Int, EpisodeProgress>>(emptyMap())
    val lastWatched = MutableStateFlow<LastWatched?>(null)
    val continueWatching = MutableStateFlow<List<ContinueWatching>>(emptyList())
    var choice: TranslationChoice? = null
    val saved = mutableListOf<Playback>()
    val entries = MutableStateFlow<List<LibraryEntry>>(emptyList())
    val status = MutableStateFlow<LibraryStatus?>(null)
    val newEpisodeCount = MutableStateFlow(0)
    override val hintShown = MutableStateFlow(false)
    var newEpisodeIds: Set<Int> = emptySet()
    val seen = mutableListOf<Series>()

    override fun entries(): Flow<List<LibraryEntry>> = entries

    override fun status(seriesId: Int): Flow<LibraryStatus?> = status

    override fun newEpisodeCount(): Flow<Int> = newEpisodeCount

    override suspend fun setHintShown() {
        hintShown.value = true
    }

    override suspend fun setStatus(
        series: Series,
        status: LibraryStatus?,
    ) {
        this.status.value = status
    }

    override suspend fun newEpisodeIds(seriesId: Int): Set<Int> = newEpisodeIds

    override suspend fun markSeen(series: Series) {
        seen += series
        newEpisodeIds = emptySet()
    }

    override suspend fun watching(): List<Int> =
        entries.value
            .filter {
                it.status == LibraryStatus.Watching
            }.map { it.seriesId }

    override suspend fun recordNewEpisodes(series: Series): List<Episode> = emptyList()

    override fun progress(seriesId: Int): Flow<Map<Int, EpisodeProgress>> = progress

    override fun lastWatched(seriesId: Int): Flow<LastWatched?> = lastWatched

    override fun continueWatching(): Flow<List<ContinueWatching>> = continueWatching

    override suspend fun progressOf(episodeId: Int): EpisodeProgress? = progress.value[episodeId]

    override suspend fun choice(seriesId: Int): TranslationChoice? = choice

    override suspend fun save(playback: Playback) {
        saved += playback
    }

    override suspend fun setWatched(
        series: SeriesSummary,
        episode: Episode,
        watched: Boolean,
    ) {
        val old = progress.value[episode.id] ?: EpisodeProgress(episode.id, 0, 0, watched)
        progress.value += episode.id to old.copy(watched = watched)
    }
}

class FakeAccountRepository : AccountRepository {
    override val account = MutableStateFlow<Account>(Account.Guest)
    val logInCookies = mutableListOf<String>()
    var logInResult: Result<Unit> = Result.success(Unit)
    var refreshCalls = 0

    override suspend fun logIn(sessionCookie: String): Result<Unit> {
        logInCookies += sessionCookie
        return logInResult
    }

    override suspend fun refresh(): Result<Unit> {
        refreshCalls++
        return Result.success(Unit)
    }

    override suspend fun logOut() {
        account.value = Account.Guest
    }
}

/** Экспорт и импорт без файлов: результат задаёт тест, импорты записываются. */
class FakeLibraryTransfer : LibraryTransfer {
    var exportResult: Result<LibraryFileSummary> = Result.success(LibraryFileSummary(0, 0))
    var importResult: Result<LibraryFileSummary> = Result.success(LibraryFileSummary(0, 0))
    val imports = mutableListOf<Pair<Uri, ImportMode>>()

    override suspend fun export(uri: Uri): Result<LibraryFileSummary> = exportResult

    override suspend fun import(
        uri: Uri,
        mode: ImportMode,
    ): Result<LibraryFileSummary> {
        imports += uri to mode
        return importResult
    }
}

class FakeUpdateRepository : UpdateRepository {
    var result: Result<AppUpdate?> = Result.success(null)
    var checks = 0

    override suspend fun check(): Result<AppUpdate?> {
        checks++
        return result
    }

    override suspend fun checkDaily(): AppUpdate? = check().getOrNull()
}

class FakeSettingsStore : SettingsStore {
    override val libraryHintShown = MutableStateFlow(false)
    override val settings = MutableStateFlow(AppSettings())

    override suspend fun setLibraryHintShown() {
        libraryHintShown.value = true
    }

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settings.value = transform(settings.value)
    }

    override val updateCheckedAt = MutableStateFlow<Instant?>(null)

    override suspend fun setUpdateCheckedAt(instant: Instant) {
        updateCheckedAt.value = instant
    }
}

class FakeEpisodeDownloads : EpisodeDownloads {
    override val downloads = MutableStateFlow<List<EpisodeDownload>>(emptyList())
    val retried = mutableListOf<Int>()

    override suspend fun download(
        series: SeriesSummary,
        episode: Episode,
        translation: Translation,
        stream: VideoStream,
    ) {
        if (downloads.value.any { it.translation.id == translation.id }) return
        val quality = stream.downloads.first()
        downloads.value +=
            EpisodeDownload(
                series.id,
                series.title,
                series.posterUrl,
                episode,
                translation,
                quality.height,
                quality.url,
                stream.subtitlesUrl,
                DownloadState.Queued,
                bytesDownloaded = 0,
                contentLength = null,
                isHentai = series.isHentai,
            )
    }

    override fun retry(translationId: Int) {
        retried += translationId
    }

    override fun remove(translationIds: Collection<Int>) {
        downloads.value = downloads.value.filterNot { it.translation.id in translationIds }
    }

    override fun resume() = Unit
}
