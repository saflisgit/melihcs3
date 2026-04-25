package com.example

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.syncproviders.providers.OpenSubtitlesApi.Companion.headers

class ExampleProvider : MainAPI() { 
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "Film Makinesi"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var lang = "tr"

    override val mainPage = mainPageOf(
        "" to "Anasayfa",
        "en-yeni-filmler" to "En Yeni Filmler",
        "en-cok-izlenen-filmler" to "En Çok İzlenen Filmler"
    )

    override val hasMainPage = true

    override suspend fun search(query: String): List<SearchResponse> {
        return listOf()
    }

    override suspend fun getMainPage(
        page: Int,
        request : MainPageRequest
    ): HomePageResponse {
        // Build the URL based on page
        val url = if (page == 1) {
            if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        } else {
            if (request.data.isEmpty()) "$mainUrl/page/$page" else "$mainUrl/${request.data}/page/$page"
        }
        
        val doc = app.get(url).document
        
        // Use generic selectors that are common on movie sites, or specific ones if known
        val elements = doc.select("div.poster, div.movie-poster, div.movie-box, article, .item, .post, .movie")
        
        val home = elements.mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href")
            if (href.isEmpty() || href == "#") return@mapNotNull null
            
            val img = element.selectFirst("img")
            val title = img?.attr("alt")?.ifEmpty { img.attr("title") } ?: a.attr("title").ifEmpty { a.text() }
            val posterUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") }
            
            if (title.isBlank()) return@mapNotNull null
            
            com.lagradost.cloudstream3.newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }
}