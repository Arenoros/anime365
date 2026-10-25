package io.github.arenoros.anime365.core.model

/**
 * Скачивание одного Перевода (GLOSSARY.md, «Скачивание»). Аниме и Эпизод записаны вместе с ним:
 * скачанное играет без сети, даже когда кэша страницы аниме уже нет.
 * [videoUrl] — ссылка `download[]` без Токена: Токен подставляется в момент запроса (docs/roadmap.md, M4).
 * [contentLength] — `null`, пока сервер не назвал размер.
 */
data class EpisodeDownload(
    val seriesId: Int,
    val seriesTitle: String,
    val posterUrl: String?,
    val episode: Episode,
    val translation: Translation,
    val height: Int,
    val videoUrl: String,
    val subtitlesUrl: String?,
    val state: DownloadState,
    val bytesDownloaded: Long,
    val contentLength: Long?,
    val isHentai: Boolean = false,
) {
    /** От 0 до 1; `null`, пока размер неизвестен. */
    val progress: Float?
        get() = contentLength?.takeIf { it > 0 }?.let { bytesDownloaded.toFloat() / it }

    /** Сколько места занимает или займёт. */
    val size: Long get() = contentLength ?: bytesDownloaded
}

/** «Пауза» из docs/screens.md — это ожидание сети или Wi-Fi: Скачивание продолжится само. */
enum class DownloadState { Queued, Downloading, WaitingForNetwork, WaitingForWifi, Completed, Failed }

/** Самое большое Разрешение не выше [target]; если все выше — самое маленькое. */
fun pickHeight(
    heights: List<Int>,
    target: Int,
): Int? = heights.filter { it <= target }.maxOrNull() ?: heights.minOrNull()
