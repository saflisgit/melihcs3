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
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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
        
        // Vizyondakileri atlayıp sadece "Son Eklenenler" (Son Filmler) kısmındaki filmleri çekmek için
        val elements = doc.select("div.item-relative")
        
        val home = elements.mapNotNull { element ->
            val a = element.selectFirst("a.item") ?: return@mapNotNull null
            val href = a.attr("href")
            if (href.isEmpty() || href == "#") return@mapNotNull null
            
            val img = element.selectFirst("img")
            val title = a.attr("data-title").ifEmpty { img?.attr("alt") }?.ifEmpty { img?.attr("title") } ?: a.attr("title").ifEmpty { a.text() }
            val posterUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") }
            
            if (title.isBlank()) return@mapNotNull null
            
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1")?.text() ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        val iframes = doc.select("iframe").mapNotNull { it.attr("src").ifEmpty { it.attr("data-src") } }
        
        iframes.forEach { iframeUrl ->
            val fixedUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(fixedUrl, data, subtitleCallback, callback)
        }
        
        return true
    }
}