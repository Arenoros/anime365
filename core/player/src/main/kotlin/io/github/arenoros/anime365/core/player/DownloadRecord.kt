package io.github.arenoros.anime365.core.player

import io.github.arenoros.anime365.core.model.DownloadState
import io.github.arenoros.anime365.core.model.Episode
import io.github.arenoros.anime365.core.model.EpisodeDownload
import io.github.arenoros.anime365.core.model.SeriesSummary
import io.github.arenoros.anime365.core.model.Translation
import io.github.arenoros.anime365.core.model.TranslationKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val recordJson = Json { ignoreUnknownKeys = true }

/**
 * Что скачано, — в самом запросе Media3: база закачек остаётся единственным местом, где о Скачивании
 * что-то записано. Ссылки без Токена.
 */
@Serializable
internal data class DownloadRecord(
    val seriesId: Int,
    val seriesTitle: String,
    val posterUrl: String? = null,
    val episodeId: Int,
    val episodeNumber: Double,
    val episodeLabel: String,
    val episodeType: String,
    val translationId: Int,
    val translationKind: String,
    val team: String,
    val source: String? = null,
    val translationHeight: Int,
    val height: Int,
    val subtitlesUrl: String? = null,
    val isHentai: Boolean = false,
) {
    fun encode(): ByteArray = recordJson.encodeToString(serializer(), this).encodeToByteArray()

    fun toModel(
        videoUrl: String,
        state: DownloadState,
        bytesDownloaded: Long,
        contentLength: Long?,
    ): EpisodeDownload =
        EpisodeDownload(
            seriesId = seriesId,
            seriesTitle = seriesTitle,
            posterUrl = posterUrl,
            episode = Episode(episodeId, episodeNumber, episodeLabel, episodeType),
            translation =
                Translation(
                    translationId,
                    episodeId,
                    TranslationKind.of(translationKind),
                    team,
                    source,
                    translationHeight,
                ),
            height = height,
            videoUrl = videoUrl,
            subtitlesUrl = subtitlesUrl,
            state = state,
            bytesDownloaded = bytesDownloaded,
            contentLength = contentLength,
            isHentai = isHentai,
        )

    companion object {
        fun of(
            series: SeriesSummary,
            episode: Episode,
            translation: Translation,
            height: Int,
            subtitlesUrl: String?,
        ): DownloadRecord =
            DownloadRecord(
                seriesId = series.id,
                isHentai = series.isHentai,
                seriesTitle = series.title,
                posterUrl = series.posterUrl,
                episodeId = episode.id,
                episodeNumber = episode.number,
                episodeLabel = episode.label,
                episodeType = episode.type,
                translationId = translation.id,
                translationKind = translation.kind.apiType,
                team = translation.team,
                source = translation.source,
                translationHeight = translation.height,
                height = height,
                subtitlesUrl = subtitlesUrl,
            )

        /** Запрос без записи или чужого формата — `null`: такую закачку в списке не показать. */
        fun decode(data: ByteArray): DownloadRecord? =
            try {
                recordJson.decodeFromString(serializer(), data.decodeToString())
            } catch (_: IllegalArgumentException) {
                // Сюда же — SerializationException.
                null
            }
    }
}
