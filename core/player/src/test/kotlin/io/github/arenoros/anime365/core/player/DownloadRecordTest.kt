package io.github.arenoros.anime365.core.player

import io.github.arenoros.anime365.core.model.DownloadState
import io.github.arenoros.anime365.core.model.Episode
import io.github.arenoros.anime365.core.model.ReleaseStatus
import io.github.arenoros.anime365.core.model.SeriesSummary
import io.github.arenoros.anime365.core.model.Translation
import io.github.arenoros.anime365.core.model.TranslationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRecordTest {
    private val series =
        SeriesSummary(7, "Фрирен", null, "https://poster", "ТВ сериал", 28, 2023, null, 9.2, ReleaseStatus.Ongoing)
    private val episode = Episode(101, 1.0, "1 серия", "tv")
    private val translation = Translation(5, 101, TranslationKind.SubRu, "YakuSub Studio (Aero)", "bd", 1080)

    @Test
    fun adultClassificationSurvivesDownloadStorage() {
        val record = DownloadRecord.of(series.copy(isHentai = true), episode, translation, 720, null)
        val restored = DownloadRecord.decode(record.encode())!!.toModel("url", DownloadState.Completed, 10, 10)
        assertEquals(true, restored.isHentai)
        assertEquals(false, DownloadRecord.of(series, episode, translation, 720, null).isHentai)
    }

    @Test
    fun recordSurvivesRoundTripAndBuildsDownload() {
        val record = DownloadRecord.of(series, episode, translation, 720, "https://site/5.ass")

        val download =
            DownloadRecord.decode(record.encode())!!.toModel(
                videoUrl = "https://site/translations/mp4/5?download=1&height=720",
                state = DownloadState.Downloading,
                bytesDownloaded = 50,
                contentLength = 200,
            )

        assertEquals(episode, download.episode)
        assertEquals(translation, download.translation)
        assertEquals("Фрирен", download.seriesTitle)
        assertEquals(720, download.height)
        assertEquals("https://site/5.ass", download.subtitlesUrl)
        assertEquals(0.25f, download.progress)
        assertEquals("download:5", download.cacheKey)
    }

    @Test
    fun foreignDataIsSkipped() {
        assertNull(DownloadRecord.decode(ByteArray(0)))
        assertNull(DownloadRecord.decode("""{"seriesId": "x"}""".encodeToByteArray()))
    }
}
