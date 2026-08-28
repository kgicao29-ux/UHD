package com.uhdmovies.cloudstream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

/**
 * CloudStream provider for UHDMovies (https://uhdmovies.autos).
 *
 * WordPress (gridlove theme) download index. Everything needed lives in plain
 * HTML; the interesting part is the download chain (verified live):
 *
 *   1. post buttons are <a class="maxbutton …" href="cloud.unblockedgames.world/?sid=<b64>">
 *   2. GET  sid url        -> <form id="landing"> with _wp_http
 *      POST _wp_http       -> page with another landing form (_wp_http2)
 *      POST _wp_http2      -> page whose script calls
 *                             s_NNN('<cookieName>', '<cookieValue>', 60)
 *      GET  ?go=<cookieName> with cookie <cookieName>=<cookieValue>
 *                           -> meta refresh -> driveseed.org/r?key=…&id=…
 *   3. GET /r?…            -> window.location.replace("/file/<KEY>")
 *      GET /file/<KEY>     -> file page:
 *                             a.btn-danger  = Instant Download
 *                                 -> cdn.video-gen.xyz/<hex>::<sig>
 *                                 -> 302 video-seed.dev/?url=<googleusercontent direct>
 *                             a.btn-warning = Resume Cloud (/zfile/<KEY>)
 *                                 -> page with a workers.dev direct .mkv link
 */
class UHDMoviesProvider : MainAPI() {
    override var mainUrl = "https://uhdmovies.autos"
    override var name = "UHDMovies"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val cloudBase = "https://cloud.unblockedgames.world"
    private val driveseedBase = "https://driveseed.org"

    // ------------------------------------------------------------------ //
    // Payload models
    // ------------------------------------------------------------------ //

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SidLink(
        val label: String? = null,
        val sid: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Payload(val links: List<SidLink> = emptyList())

    // ------------------------------------------------------------------ //
    // Regexes / constants
    // ------------------------------------------------------------------ //

    companion object {
        private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
        private val SEASON_IN_TITLE = Regex("""\(?\bSeason\s?(\d{1,2})\)?""", RegexOption.IGNORE_CASE)
        private val EPISODE_REGEX = Regex("""S(\d{1,2})E(\d{1,3})""", RegexOption.IGNORE_CASE)
        private val QUALITY_REGEX = Regex("""(2160|1440|1080|720|480)[pP]""")
        private val PEPE_COOKIE_REGEX = Regex("""s_\d+\('([^']+)',\s*'([^']+)'""")
        private val REFRESH_URL_REGEX = Regex("""url=(.+)""", RegexOption.IGNORE_CASE)
        private val FILE_KEY_REGEX = Regex("""window\.location\.replace\("([^"]+)"\)""")
        private val QUERY_URL_REGEX = Regex("""[?&]url=([^&]+)""")
    }

    override val mainPage = mainPageOf(
        "/" to "Latest",
        "movies/" to "Movies",
        "web-series/" to "Web Series",
        "tv-series/" to "TV Series",
        "movies/collection-movies/" to "Hollywood",
        "4k-hdr/" to "4K HDR",
        "2160p-hevc/" to "2160p HEVC",
        "imax/" to "IMAX",
    )

    private fun headers(referer: String? = null): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        if (referer != null) put("Referer", referer)
    }

    private suspend fun getDocument(url: String, referer: String? = null): Document? =
        runCatching { Jsoup.parse(app.get(url, headers = headers(referer)).text) }.getOrNull()

    // ------------------------------------------------------------------ //
    // Title helpers
    // ------------------------------------------------------------------ //

    /** "Download Rush (2013) Dual Audio {Hindi-English} 2160p || …" -> ("Rush", 2013) */
    private fun parseTitle(raw: String): Pair<String, Int?> {
        var t = raw.trim().removePrefix("Download").trim()
        val year = YEAR_REGEX.find(t)?.value?.toIntOrNull()
        val cut = listOf("Dual Audio", "Multi Audio", "Triple Audio", "Hindi Dubbed", "English Audio", "||", "{", "(", "2160p", "1080p", "720p", "4k", "Season")
            .mapNotNull { marker ->
                Regex(Regex.escape(marker), RegexOption.IGNORE_CASE).find(t)?.range?.first()
            }
            .minOrNull()
        if (cut != null && cut > 0) t = t.substring(0, cut).trim()
        t = t.trim(' ', '-', ':', '|')
        if (t.length < 2) t = raw.trim().removePrefix("Download").trim()
        return t to year
    }

    private fun qualityOf(text: String): Int {
        val m = QUALITY_REGEX.find(text) ?: return Qualities.Unknown.value
        return when (m.groupValues[1].toInt()) {
            2160 -> Qualities.P2160.value
            1440 -> Qualities.P1440.value
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun searchQualityOf(text: String): SearchQuality? = when (qualityOf(text)) {
        Qualities.P2160.value -> SearchQuality.FourK
        Qualities.P1080.value, Qualities.P720.value -> SearchQuality.HD
        else -> null
    }

    // ------------------------------------------------------------------ //
    // Main page / cards
    // ------------------------------------------------------------------ //

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trimEnd('/')
        val url = if (path.isEmpty()) {
            if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        } else {
            "$mainUrl/$path/page/$page/"
        }
        val doc = getDocument(url)
        val results = doc?.let { parseCards(it) }.orEmpty()
        return newHomePageResponse(HomePageList(request.name, results), hasNext = results.isNotEmpty())
    }

    private fun parseCards(doc: Document): List<SearchResponse> =
        doc.select("article.gridlove-post").mapNotNull { art ->
            val a = art.selectFirst(".entry-image a") ?: art.selectFirst("a[href*=download-]") ?: return@mapNotNull null
            val href = a.attr("abs:href").takeIf { it.contains("/download-") } ?: return@mapNotNull null
            val rawTitle = art.selectFirst(".box-inner-p a")?.attr("title")?.takeIf { it.isNotBlank() }
                ?: art.selectFirst("h2, h3")?.text()
                ?: a.attr("title").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val (title, year) = parseTitle(rawTitle)
            if (title.length < 2) return@mapNotNull null
            val poster = art.selectFirst("img.wp-post-image")?.attr("src")
            val isSeries = rawTitle.contains("Season", true) || href.contains("season", true) ||
                rawTitle.contains("web-series", true)
            val badge = searchQualityOf(rawTitle)
            if (isSeries) {
                newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = badge
                }
            } else {
                newMovieSearchResponse(title, href) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = badge
                }
            }
        }

    // ------------------------------------------------------------------ //
    // Search
    // ------------------------------------------------------------------ //

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val doc = getDocument("$mainUrl/?s=" + java.net.URLEncoder.encode(q, "UTF-8"))
        return doc?.let { parseCards(it) }.orEmpty()
    }

    // ------------------------------------------------------------------ //
    // Detail
    // ------------------------------------------------------------------ //

    /** Walks previous siblings/ancestors to find a label for a download button. */
    private fun labelFor(el: Element): String {
        var node: Element? = el
        repeat(6) {
            node = node?.previousElementSibling() ?: return@repeat
            val txt = node!!.selectFirst("strong")?.text() ?: node!!.text()
            if (txt.isNotBlank()) return txt.trim().take(120)
            node = node
        }
        // fall back to the closest heading
        return el.parents().firstOrNull { it.tag().name.startsWith("h") }?.text()?.take(120)
            ?: el.attr("title").takeIf { it.isNotBlank() } ?: "Download"
    }

    private fun Element.sidHref(): String? =
        attr("abs:href").takeIf { it.contains("unblockedgames.world") && it.contains("sid=") }

    override suspend fun load(url: String): LoadResponse? {
        val doc = getDocument(url) ?: return null
        val h1 = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim() ?: return null
        val (title, year) = parseTitle(h1)

        val poster = doc.selectFirst(".entry-content img[srcset]")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { !it.contains("logo") }
        val tags = doc.select(".entry-category a").eachText().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        // All real download buttons on the page.
        val buttons = doc.select("a[class*=maxbutton]").mapNotNull { btn ->
            btn.sidHref()?.let { btn to it }
        }

        val seasonFromTitle = SEASON_IN_TITLE.find(h1)?.groupValues?.get(1)?.toIntOrNull()
        val isSeries = buttons.any { EPISODE_REGEX.containsMatchIn(labelFor(it.first)) } ||
            (seasonFromTitle != null && buttons.isNotEmpty())

        if (!isSeries) {
            val links = buttons.map { (btn, sid) -> SidLink(labelFor(btn), sid) }
            return newMovieLoadResponse(title, url, TvType.Movie, Payload(links)) {
                this.posterUrl = poster
                this.year = year
                this.tags = tags
            }
        }

        // ---- series: group buttons by SxxEyy from their label ----
        data class Ep(val season: Int, val number: Int, val label: String?, val sid: String)

        val episodes = mutableListOf<Ep>()
        var fallbackIndex = 0
        buttons.forEach { (btn, sid) ->
            val label = labelFor(btn)
            val m = EPISODE_REGEX.find(label)
            if (m != null) {
                val season = m.groupValues[1].toIntOrNull() ?: seasonFromTitle ?: 1
                val number = m.groupValues[2].toIntOrNull() ?: ++fallbackIndex
                episodes += Ep(season, number, label, sid)
            } else {
                // season packs / unlabelled buttons become extra numbered entries
                episodes += Ep(seasonFromTitle ?: 1, ++fallbackIndex, label, sid)
            }
        }
        val grouped = episodes.groupBy({ it.season to it.number })
        val sortedKeys = grouped.keys.sortedWith(compareBy({ it.first }, { it.second }))
        val epList = sortedKeys.mapNotNull { key ->
            val items = grouped[key].orEmpty()
            if (items.isEmpty()) return@mapNotNull null
            val sample = items.first()
            newEpisode(Payload(items.map { SidLink(it.label, it.sid) })) {
                this.name = "S%02dE%02d".format(key.first, key.second) +
                    (sample.label?.let { " • " + EPISODE_REGEX.replace(it, "").trim().take(60) } ?: "")
                this.season = key.first
                this.episode = key.second
                this.posterUrl = poster
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, epList) {
            this.posterUrl = poster
            this.year = year
            this.tags = tags
        }
    }

    // ------------------------------------------------------------------ //
    // Link resolution
    // ------------------------------------------------------------------ //

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = runCatching { parseJson<Payload>(data) }.getOrNull() ?: return false
        if (payload.links.isEmpty()) return false

        var emitted = false
        for (link in payload.links) {
            val sidUrl = link.sid ?: continue
            runCatching {
                val driveSeedUrl = resolveCloud(sidUrl) ?: return@runCatching
                emitDriveseed(driveSeedUrl, link.label, callback)
                emitted = true
            }
        }
        return emitted
    }

    /**
     * cloud.unblockedgames.wtf landing dance:
     * GET sid -> POST _wp_http -> POST _wp_http2 -> cookie from s_NNN(...) ->
     * GET ?go=<cookieName> -> meta refresh target (driveseed.org/r?…).
     */
    private suspend fun resolveCloud(sidUrl: String): String? {
        // 1. initial landing form
        val r1 = runCatching { app.get(sidUrl, headers = headers(mainUrl)) }.getOrNull() ?: return null
        val d1 = Jsoup.parse(r1.text)
        val form1 = d1.selectFirst("form#landing") ?: return null
        val action1 = form1.attr("abs:action").takeIf { it.startsWith("http") } ?: return null
        val wp1 = form1.selectFirst("input[name=_wp_http]")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: form1.selectFirst("input")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: return null

        // 2. first POST -> second landing form (hidden inside a blog page)
        val r2 = runCatching {
            app.post(action1, headers = headers(), data = mapOf("_wp_http" to wp1)).text
        }.getOrNull() ?: return null
        val d2 = Jsoup.parse(r2)
        val form2 = d2.selectFirst("form#landing") ?: return null
        val action2 = form2.attr("abs:action").takeIf { it.startsWith("http") } ?: return null
        val wp2 = form2.selectFirst("input[name=_wp_http2]")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: return null

        // 3. second POST -> script sets the pepe cookie
        val r3 = runCatching {
            app.post(action2, headers = headers(), data = mapOf("_wp_http2" to wp2)).text
        }.getOrNull() ?: return null
        val cookie = PEPE_COOKIE_REGEX.find(r3) ?: return null
        val cookieName = cookie.groupValues[1]
        val cookieValue = cookie.groupValues[2]

        // 4. go url with the cookie -> meta refresh
        val r4 = runCatching {
            app.get(
                "$cloudBase/?go=$cookieName",
                headers = headers(action2) + ("Cookie" to "$cookieName=$cookieValue"),
            ).text
        }.getOrNull() ?: return null
        val meta = Jsoup.parse(r4).selectFirst("meta[http-equiv=refresh]")?.attr("content") ?: return null
        val target = REFRESH_URL_REGEX.find(meta)?.groupValues?.get(1)?.trim() ?: return null
        return target.takeIf { it.startsWith("http") }
    }

    /** driveseed.org/r?key=… -> /file/KEY page -> direct links. */
    private suspend fun emitDriveseed(
        entryUrl: String,
        label: String?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val pageBody = runCatching {
            app.get(entryUrl, headers = headers(mainUrl)).text
        }.getOrNull() ?: return

        // "/r?…" answers with window.location.replace("/file/KEY")
        var fileUrl = entryUrl
        FILE_KEY_REGEX.find(pageBody)?.groupValues?.get(1)?.let { path ->
            fileUrl = if (path.startsWith("http")) path else "$driveseedBase$path"
        }

        val page = runCatching {
            Jsoup.parse(app.get(fileUrl, headers = headers(driveseedBase)).text)
        }.getOrNull() ?: return

        val fileName = page.title().takeIf { it.isNotBlank() } ?: "UHDMovies"
        val sizeText = page.select("li.list-group-item").eachText()
            .firstOrNull { it.startsWith("Size") }?.substringAfter("Size :")?.trim()
        val quality = qualityOf("$fileName $label")

        fun shortName(): String = fileName.substringBeforeLast('.').take(70)

        // Instant Download -> cdn.video-gen.xyz -> video-seed.dev?url=<googleusercontent>
        page.selectFirst("a.btn-danger")?.attr("abs:href")?.takeIf { it.startsWith("http") }?.let { cdn ->
            runCatching {
                val res = app.get(cdn, headers = headers(driveseedBase))
                val finalUrl = res.url.takeIf { it.startsWith("http") } ?: return@runCatching
                val direct = QUERY_URL_REGEX.find(finalUrl)?.groupValues?.get(1)
                    ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                    ?: finalUrl
                callback(
                    newExtractorLink(
                        source = name,
                        name = listOfNotNull("Instant", sizeText?.let { "($it)" }, label?.let { "• ${EPISODE_REGEX.replace(it, "").take(30)}" })
                            .joinToString(" "),
                        url = direct,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.quality = quality
                    }
                )
            }
        }

        // Resume Cloud -> /zfile/KEY -> workers.dev direct file
        page.selectFirst("a.btn-warning")?.attr("abs:href")?.takeIf { it.startsWith("http") }?.let { zfile ->
            runCatching {
                val z = Jsoup.parse(app.get(zfile, headers = headers(driveseedBase)).text)
                z.selectFirst("a[href*=workers.dev]")?.attr("abs:href")?.takeIf { it.startsWith("http") }
                    ?.let { workerUrl ->
                        callback(
                            newExtractorLink(
                                source = name,
                                name = listOfNotNull("Resume Cloud", sizeText?.let { "($it)" })
                                    .joinToString(" ") + " • ${shortName()}",
                                url = workerUrl,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                this.quality = quality
                            }
                        )
                    }
            }
        }
    }
}
