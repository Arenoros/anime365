package io.github.arenoros.anime365.core.network.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

/** [isActive]: 1 — есть Эпизоды, 0 — анонс, отрицательные — скрытые записи. */
@Serializable
data class SeriesDto(
    val id: Int,
    @Serializable(with = TitlesSerializer::class)
    val titles: Map<String, String> = emptyMap(),
    val posterUrlSmall: String? = null,
    val posterUrl: String? = null,
    val typeTitle: String? = null,
    val type: String? = null,
    val year: Int? = null,
    val season: String? = null,
    val myAnimeListScore: Double? = null,
    val isAiring: Int = 0,
    val isActive: Int = 1,
    val numberOfEpisodes: Int = 0,
    val url: String? = null,
    val genres: List<GenreDto> = emptyList(),
    val descriptions: List<DescriptionDto> = emptyList(),
    val links: List<LinkDto> = emptyList(),
    val episodes: List<EpisodeDto> = emptyList(),
    val isHentai: Int = 0,
)

@Serializable
data class GenreDto(
    val id: Int,
    val title: String,
)

@Serializable
data class DescriptionDto(
    val source: String = "",
    val value: String = "",
)

@Serializable
data class LinkDto(
    val title: String = "",
    val url: String = "",
)

@Serializable
data class EpisodeDto(
    val id: Int,
    val seriesId: Int = 0,
    val episodeFull: String = "",
    val episodeInt: Double = 0.0,
    val episodeType: String = "",
    val firstUploadedDateTime: String? = null,
    val isActive: Int = 1,
)

/** У аниме без названий сервер отдаёт `"titles": []` вместо объекта: так PHP кодирует пустой массив. */
internal object TitlesSerializer :
    JsonTransformingSerializer<Map<String, String>>(MapSerializer(String.serializer(), String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) JsonObject(emptyMap()) else element
}
