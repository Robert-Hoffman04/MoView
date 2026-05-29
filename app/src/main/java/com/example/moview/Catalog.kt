package com.example.moview

import kotlinx.serialization.Serializable
import java.util.SortedMap

@Serializable
data class Settings(
    var ip : String,
    val recent : List<String>
)

@Serializable
sealed class Media {
    abstract val Title: String
    abstract val Type: String
    abstract val Date: String          // Format: yyyy-MM-dd
    abstract val Description: String
    abstract val ContentRating: String
    abstract val OverallRating: Float
    abstract val Keywords: List<String>
    abstract val Genre: List<String>
    abstract val Actor: List<String>
    abstract val Creator: List<String>
    abstract val LocalPoster: String
    abstract val LocalBanner: String
    abstract val LocalFolder: String
    abstract val LocalFile: String
    abstract val LocalTitle: String
}

@Serializable
data class Film(
    override val Title: String,
    override val Type: String = "Movie",
    override val Date: String,
    override val Description: String,
    override val ContentRating: String,
    override val OverallRating: Float,
    override val Keywords: List<String>,
    override val Genre: List<String>,
    override val Actor: List<String>,
    override val Creator: List<String>,
    override val LocalPoster: String,
    override val LocalBanner: String,
    override val LocalFolder: String,
    override val LocalFile: String,
    override val LocalTitle: String,

    // Film-specific
    val Duration: String
) : Media()

@Serializable
data class Show(
    override val Title: String,
    override val Type: String = "TVSeries",
    override val Date: String,
    override val Description: String,
    override val ContentRating: String,
    override val OverallRating: Float,
    override val Keywords: List<String>,
    override val Genre: List<String>,
    override val Actor: List<String>,
    override val Creator: List<String>,
    override val LocalPoster: String,
    override val LocalBanner: String,
    override val LocalFolder: String,
    override val LocalFile: String,
    override val LocalTitle: String,

    // Show-specific
    val EpisodeNumber: Int,
    val Episodes: List<List<Episode>>
) : Media()

@Serializable
data class Game(
    override val Title: String,
    override val Type: String = "Game",
    override val Date: String,
    override val Description: String,

    // Optional/common metadata
    override val ContentRating: String = "",
    override val OverallRating: Float = 0f,
    override val Keywords: List<String> = emptyList(),
    override val Genre: List<String> = emptyList(),
    override val Actor: List<String> = emptyList(),
    override val Creator: List<String> = emptyList(),

    override val LocalPoster: String,
    override val LocalBanner: String = "",
    override val LocalFolder: String,
    override val LocalFile: String,
    override val LocalTitle: String,

    // Game-specific
    val System: String
) : Media()

@Serializable
data class Episode(
    val Title: String,
    val Type: String,
    val Date: String,
    val Description: String,
    val ContentRating: String,
    val OverallRating: Float,
    val Actor: List<String>,
    val Creator: List<String>,
    val LocalImage: String,
    val LocalFile: String,
    //val IntroStart: Int,
    //val IntroEnd: Int,
    val EpisodeNumber: Int
)

@Serializable
data class Filters(
    val Genres : List<String>,
    val Years  : List<String>
)

class FinalCatalog (
    val Films: Map<String, Film>,
    val Shows: Map<String, Show>,
    val Games: Map<String, Game>,
    val Collections: Map<String, List<String>>,

    val collectionOrder : List<String>
)

@Serializable
data class Catalog(
    val Films: Map<String, Film>,
    val Shows: Map<String, Show>,
    val Games: Map<String, Game>,
    val Collections: Map<String, List<String>>,
    val Filters : Filters
)

@Serializable
data class ExternalResult(
    val name: String? = null,
    val date: String? = null,
    val type: String? = null,
    val poster: String? = null,
    val tmdb_id: Int? = null,
    val source: String? = null,
    val source_folder_url: String? = null,
    val source_url: String? = null,
    val sources: List<String> = emptyList(),
    val source_folder_urls: List<String> = emptyList(),
)

@Serializable
data class SearchResponse(
    val query: String,
    val results: List<ExternalResult>
)