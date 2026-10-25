package io.github.arenoros.anime365.core.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arenoros.anime365.core.data.catchingFailures
import io.github.arenoros.anime365.core.database.library.EpisodeProgressEntity
import io.github.arenoros.anime365.core.database.library.LibraryDao
import io.github.arenoros.anime365.core.database.library.LibrarySeriesEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/** Что импорт делает с текущим Списком (docs/screens.md, «Импорт Списка»). */
enum class ImportMode { Replace, Merge }

/** Файл записан более новой версией приложения: его формат этой неизвестен. */
class UnsupportedLibraryFileException(
    val schemaVersion: Int,
) : IllegalArgumentException("Library file schema $schemaVersion is newer than $LIBRARY_SCHEMA_VERSION")

/** Аниме в файле: [inList] — со Статусом, [total] — все, вместе с теми, у которых только Прогресс. */
data class LibraryFileSummary(
    val inList: Int,
    val total: Int,
)

/**
 * Экспорт и импорт Библиотеки файлом — единственный способ переезда (ADR 0001): Статусы, Прогресс,
 * запомненные Вид перевода и Команда, ключ — `id` аниме на сайте.
 */
interface LibraryTransfer {
    suspend fun export(uri: Uri): Result<LibraryFileSummary>

    suspend fun import(
        uri: Uri,
        mode: ImportMode,
    ): Result<LibraryFileSummary>
}

/** Версия формата файла. Новые необязательные поля её не меняют: чтение пропускает незнакомые. */
internal const val LIBRARY_SCHEMA_VERSION = 1

@OptIn(ExperimentalSerializationApi::class)
private val LibraryJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }

internal class JsonLibraryTransfer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dao: LibraryDao,
    ) : LibraryTransfer {
        override suspend fun export(uri: Uri): Result<LibraryFileSummary> =
            catchingFailures {
                withContext(Dispatchers.IO) {
                    val output =
                        context.contentResolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("$uri")
                    output.use { write(it) }
                }
            }

        override suspend fun import(
            uri: Uri,
            mode: ImportMode,
        ): Result<LibraryFileSummary> =
            catchingFailures {
                withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("$uri")
                    input.use { read(it, mode) }
                }
            }

        @OptIn(ExperimentalSerializationApi::class)
        internal suspend fun write(output: OutputStream): LibraryFileSummary {
            val progress = dao.allProgress().groupBy { it.seriesId }
            val series = dao.allSeries().map { it.toRecord(progress[it.seriesId].orEmpty()) }
            LibraryJson.encodeToStream(LibraryFile(LIBRARY_SCHEMA_VERSION, series), output)
            return series.summary()
        }

        @OptIn(ExperimentalSerializationApi::class)
        internal suspend fun read(
            input: InputStream,
            mode: ImportMode,
        ): LibraryFileSummary {
            val file = LibraryJson.decodeFromStream<LibraryFile>(input)
            if (file.schemaVersion > LIBRARY_SCHEMA_VERSION) throw UnsupportedLibraryFileException(file.schemaVersion)
            val series = file.series.map { it.toEntity() }
            val progress = file.series.flatMap { record -> record.episodes.map { it.toEntity(record.id) } }
            when (mode) {
                ImportMode.Replace -> {
                    dao.replaceAll(series, progress)
                }

                ImportMode.Merge -> {
                    dao.upsertAll(mergeSeries(dao.allSeries(), series), mergeProgress(dao.allProgress(), progress))
                }
            }
            return file.series.summary()
        }
    }

private fun List<SeriesRecord>.summary() = LibraryFileSummary(inList = count { it.status != null }, total = size)

/** Аниме из обоих Списков: Статус — откуда его меняли позже, запомненный Перевод — где позже смотрели. */
internal fun mergeSeries(
    current: List<LibrarySeriesEntity>,
    imported: List<LibrarySeriesEntity>,
): List<LibrarySeriesEntity> {
    val byId = current.associateBy { it.seriesId }
    return imported.map { new ->
        val old = byId[new.seriesId] ?: return@map new
        val status = if ((new.statusChangedAt ?: 0) > (old.statusChangedAt ?: 0)) new else old
        val playback = if ((new.lastWatchedAt ?: 0) > (old.lastWatchedAt ?: 0)) new else old
        old.copy(
            isHentai = old.isHentai || new.isHentai,
            status = status.status,
            statusChangedAt = status.statusChangedAt,
            translationType = playback.translationType,
            team = playback.team,
            lastEpisodeId = playback.lastEpisodeId,
            lastTranslationId = playback.lastTranslationId,
            lastWatchedAt = playback.lastWatchedAt,
        )
    }
}

/** Позиция — более поздняя; отметка «просмотрен» не снимается, как и при просмотре. */
internal fun mergeProgress(
    current: List<EpisodeProgressEntity>,
    imported: List<EpisodeProgressEntity>,
): List<EpisodeProgressEntity> {
    val byId = current.associateBy { it.episodeId }
    return imported.map { new ->
        val old = byId[new.episodeId] ?: return@map new
        val latest = if (new.updatedAt > old.updatedAt) new else old
        latest.copy(watched = old.watched || new.watched)
    }
}

@Serializable
internal data class LibraryFile(
    val schemaVersion: Int,
    val series: List<SeriesRecord> = emptyList(),
)

/** Время — миллисекунды Unix. [status] — `LibraryStatus.key`. */
@Serializable
internal data class SeriesRecord(
    val id: Int,
    val title: String,
    val posterUrl: String? = null,
    val episodeCount: Int? = null,
    val status: String? = null,
    val statusChangedAt: Long? = null,
    val translationType: String? = null,
    val team: String? = null,
    val lastEpisodeId: Int? = null,
    val lastTranslationId: Int? = null,
    val lastWatchedAt: Long? = null,
    val episodes: List<EpisodeRecord> = emptyList(),
    val isHentai: Boolean = false,
)

@Serializable
internal data class EpisodeRecord(
    val id: Int,
    val label: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val updatedAt: Long,
)

private fun LibrarySeriesEntity.toRecord(progress: List<EpisodeProgressEntity>) =
    SeriesRecord(
        id = seriesId,
        isHentai = isHentai,
        title = title,
        posterUrl = posterUrl,
        episodeCount = episodeCount,
        status = status,
        statusChangedAt = statusChangedAt,
        translationType = translationType,
        team = team,
        lastEpisodeId = lastEpisodeId,
        lastTranslationId = lastTranslationId,
        lastWatchedAt = lastWatchedAt,
        episodes =
            progress.map {
                EpisodeRecord(it.episodeId, it.label, it.positionMs, it.durationMs, it.watched, it.updatedAt)
            },
    )

private fun SeriesRecord.toEntity() =
    LibrarySeriesEntity(
        seriesId = id,
        isHentai = isHentai,
        title = title,
        posterUrl = posterUrl,
        translationType = translationType,
        team = team,
        lastEpisodeId = lastEpisodeId,
        lastTranslationId = lastTranslationId,
        lastWatchedAt = lastWatchedAt,
        status = status,
        statusChangedAt = statusChangedAt,
        episodeCount = episodeCount,
    )

private fun EpisodeRecord.toEntity(seriesId: Int) =
    EpisodeProgressEntity(id, seriesId, label, positionMs, durationMs, watched, updatedAt)
