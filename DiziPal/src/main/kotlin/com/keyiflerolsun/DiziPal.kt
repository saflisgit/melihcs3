// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.
package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DiziPal : MainAPI() {
    override var mainUrl = "https://dizipal1586.com"
    override var name = "DiziPal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var sequentialMainPage = true
    private val mapper = jacksonObjectMapper()

    override val mainPage = mainPageOf(
        "$mainUrl/#latest" to "Son Bölümler",
        "$mainUrl/yabanci-dizi-izle" to "Yeni Diziler",
        "$mainUrl/hd-film-izle" to "Yeni Filmler",
        "$mainUrl/#series" to "Trend Diziler",
        "$mainUrl/#movies" to "Trend Filmler"
    )

    private suspend fun page(url: String): Document {
        val response = app.get(url, referer = "$mainUrl/")
        val document = response.document
        Log.d("DZP", "page=$url chars=${response.text.length} title=${document.title()}")
        return document
    }

    private fun Element.poster(): String? {
        val img = selectFirst("img") ?: return null
        return listOf(img.attr("data-src"), img.attr("data-srcset").substringBefore(" "), img.attr("src"))
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }?.let(::fixUrl)
    }

    private fun Element.card(): SearchResponse? {
        val href = attr("href").takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
        if (!listOf("/series/", "/movies/", "/bolum/").any { href.contains(it) }) return null
        val title = selectFirst("img[alt]")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: attr("title").removeSuffix(" izle").takeIf { it.isNotBlank() }
            ?: selectFirst("h2")?.text()?.takeIf { it.isNotBlank() } ?: return null
        val poster = poster()
        return if (href.contains("/movies/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        } else newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.substringBefore('#')
        val document = this.page(url)
        val selector = when (request.data.substringAfter('#', "")) {
            "latest" -> "#router-view a[href*='/bolum/']:has(img)"
            "series" -> "#router-view a[href*='/series/']:has(img)"
            "movies" -> "#router-view a[href*='/movies/']:has(img)"
            else -> "#router-view a[href*='/series/']:has(img), #router-view a[href*='/movies/']:has(img)"
        }
        val results = document.select(selector).mapNotNull { it.card() }.distinctBy { it.url }
        Log.d("DZP", "catalog=${request.name} items=${results.size}")
        if (results.isEmpty()) throw ErrorLoadingException("DiziPal içerik listesi okunamadı: ${request.name}")
        return newHomePageResponse(request.name, results, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = page(mainUrl)
        val form = doc.selectFirst("form[data-action='/bg/searchcontent']")
            ?: throw ErrorLoadingException("DiziPal arama formu bulunamadı")
        val fields = form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }.toMutableMap()
        fields["searchterm"] = query
        fields["type"] = "hepsi"
        val response = app.post(fixUrl(form.attr("data-action")),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"), referer = "$mainUrl/", data = fields)
        val payload = mapper.readTree(response.text).path("data")
        if (!payload.path("state").asBoolean()) throw ErrorLoadingException("DiziPal arama isteği başarısız")
        val results = Jsoup.parse(payload.path("html").asText()).select("a[href]").mapNotNull { it.card() }.distinctBy { it.url }
        Log.d("DZP", "search items=${results.size}")
        return results
    }

    override suspend fun quickSearch(query: String) = search(query)

    override suspend fun load(url: String): LoadResponse {
        var detailUrl = url
        var document = page(url)
        // Latest-episode cards retain the site's exact URL, including suffixes such as -c02.
        if (java.net.URI(url).path.startsWith("/bolum/")) {
            detailUrl = document.selectFirst("#router-view a[href*='/series/']")?.attr("href")?.let(::fixUrl)
                ?: throw ErrorLoadingException("DiziPal bölümünün dizi bağlantısı bulunamadı")
            document = page(detailUrl)
        }
        val schema = document.select("script[type='application/ld+json']").mapNotNull {
            runCatching { mapper.readTree(it.data()) }.getOrNull()
        }.firstOrNull { it.path("@type").asText() in listOf("TVSeries", "Movie") }
            ?: throw ErrorLoadingException("DiziPal detay verisi bulunamadı")
        val title = schema.path("name").asText().takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("DiziPal başlığı bulunamadı")
        val poster = schema.path("image").asText().takeIf { it.isNotBlank() }?.let(::fixUrl)
        val plot = schema.path("description").asText().takeIf { it.isNotBlank() }
        val year = document.selectXpath("//div[text()='Yapım Yılı']//following-sibling::div").text().trim().toIntOrNull()
        val rating = Score.from10(schema.path("aggregateRating").path("ratingValue").asText())
        val duration = Regex("PT(\\d+)M").find(schema.path("timeRequired").asText())?.groupValues?.get(1)?.toIntOrNull()
        if (schema.path("@type").asText() == "TVSeries") {
            fun nodes(node: com.fasterxml.jackson.databind.JsonNode) = when {
                node.isArray -> node.toList()
                node.isObject -> listOf(node)
                else -> emptyList()
            }
            val episodes = nodes(schema.path("containsSeason")).flatMap { season ->
                nodes(season.path("episode")).mapNotNull { ep ->
                    val href = ep.path("url").asText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    newEpisode(fixUrl(href)) {
                        this.name = ep.path("name").asText().takeIf { it.isNotBlank() }
                        this.season = season.path("seasonNumber").asInt().takeIf { it > 0 }
                        this.episode = ep.path("episodeNumber").asInt().takeIf { it > 0 }
                        this.description = ep.path("description").asText().takeIf { it.isNotBlank() }
                    }
                }
            }.ifEmpty {
                document.select(".season-lists a[href*='/bolum/']").mapNotNull { link ->
                    val href = link.attr("href").takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return@mapNotNull null
                    val numbers = Regex("""-(\d+)x(\d+)(?:\D|$)""").find(java.net.URI(href).path)
                    newEpisode(href) {
                        season = numbers?.groupValues?.get(1)?.toIntOrNull()
                        episode = numbers?.groupValues?.get(2)?.toIntOrNull()
                        name = link.selectFirst("div")?.text()?.takeIf { it.isNotBlank() }
                    }
                }
            }.distinctBy { it.data }
            if (episodes.isEmpty()) throw ErrorLoadingException("DiziPal bölüm listesi boş")
            Log.d("DZP", "detail=$title episodes=${episodes.size}")
            return newTvSeriesLoadResponse(title, detailUrl, TvType.TvSeries, episodes) {
                posterUrl = poster; this.plot = plot; this.year = year; score = rating; this.duration = duration
            }
        }
        return newMovieLoadResponse(title, detailUrl, TvType.Movie, detailUrl) {
            posterUrl = poster; this.plot = plot; this.year = year; score = rating; this.duration = duration
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean =
        DiziPalResolver(name).resolve(data, subtitleCallback, callback)
}
