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
 * Catalog: WordPress (gridlove) — lists, search and post pages are plain HTML.
 *
 * Stream resolution is a faithful port of the flows used by phisher98's
 * UHDmoviesProvider v38 (decompiled reference), each step verified live:
 *
 *   post buttons (a[class*=maxbutton]) → cloud.unblockedgames.world/?sid=<b64>
 *     GET sid → <form id="landing"> (POST all inputs)
 *     POST  → second hidden landing form (POST again, incl. _wp_http2)
 *     POST  → page whose script carries "?go=<token>"
 *     GET /?go=<token>  with cookie {<token>: _wp_http2}   (bypassHrefli)
 *            → meta refresh → driveseed.org/r?…
 *     GET   → window.location.replace("/file/KEY") → driveseed.org/file/KEY
 *
 *   /file/KEY page:
 *     a.btn-danger "Instant Download" → cdn.video-gen.xyz → redirects to
 *        video-seed.dev/?url=<enc> → POST https://video-seed.xyz/api
 *        {keys: …} with x-token → {url: <fresh direct GDrive>}
 *        (fallback: URL-decode the ?url= param — also a direct GDrive file)
 *     a.btn-warning "Resume Cloud" → /zfile/KEY → POST action=cloud →
 *        {url: /zfile/KEY?token=…} → a.btn-success / workers.dev direct link
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
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

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

    companion object {
        private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
        private val SEASON_IN_TITLE = Regex("""\(?\bSeason\s?(\d{1,2})\)?""", RegexOption.IGNORE_CASE)
        private val EPISODE_REGEX = Regex("""S(\d{1,2})E(\d{1,3})""", RegexOption.IGNORE_CASE)
        private val QUALITY_REGEX = Regex("""(\d{3,4})[pP]""")
        private val GO_SCRIPT_REGEX = Regex("""\?go=([^"'\s\\]+)""")
        private val REPLACE_PATH_REGEX = Regex("""window\.location\.replace\("([^"]+)"\)""")
        private val REFRESH_URL_REGEX = Regex("""url=(.+)""", RegexOption.IGNORE_CASE)
        private val ZFILE_KEY_REGEX = Regex("""formData\.append\(\s*["']key["']\s*,\s*["']([^"']+)["']\s*\)""")
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
        val cut = listOf(
            "Dual Audio", "Multi Audio", "Triple Audio", "Hindi Dubbed", "English Audio",
            "||", "{", "(", "2160p", "1080p", "720p", "4k", "Season",
        ).mapNotNull { marker ->
            Regex(Regex.escape(marker), RegexOption.IGNORE_CASE).find(t)?.range?.first()
        }.minOrNull()
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

    /** Walks previous siblings to find a label for a download button. */
    private fun labelFor(el: Element): String {
        var node: Element? = el
        repeat(6) {
            node = node?.previousElementSibling() ?: return@repeat
            val txt = node!!.selectFirst("strong")?.text() ?: node!!.text()
            if (txt.isNotBlank()) return txt.trim().take(120)
        }
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
    // Link resolution (ported from phisher98's UHDmoviesProvider v38)
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
                val fileUrl = bypassCloud(sidUrl) ?: return@runCatching
                emitDriveseed(fileUrl, link.label, callback)
                emitted = true
            }
        }
        return emitted
    }

    private fun formInputs(form: Element): Map<String, String> =
        form.select("input").associate { it.attr("name") to it.attr("value") }

    /**
     * cloud.unblockedgames.wtf landing dance (phisher98's bypassHrefli):
     * GET sid → POST landing inputs → POST second landing inputs →
     * script "?go=<token>" → GET /?go=<token> with cookie {token: _wp_http2} →
     * meta refresh target → follow → window.location.replace path → file URL.
     */
    private suspend fun bypassCloud(sidUrl: String): String? {
        // 1. initial landing form
        val r1 = runCatching { app.get(sidUrl, headers = headers(mainUrl)) }.getOrNull() ?: return null
        val form1 = Jsoup.parse(r1.text).selectFirst("form#landing") ?: return null
        val action1 = form1.attr("abs:action").takeIf { it.startsWith("http") } ?: return null
        val data1 = formInputs(form1).ifEmpty { return null }

        // 2. first POST -> second hidden landing form
        val r2 = runCatching {
            app.post(action1, headers = headers(sidUrl), data = data1).text
        }.getOrNull() ?: return null
        val form2 = Jsoup.parse(r2).selectFirst("form#landing") ?: return null
        val action2 = form2.attr("abs:action").takeIf { it.startsWith("http") } ?: return null
        val data2 = formInputs(form2).ifEmpty { return null }

        // 3. second POST -> script with ?go=<token>
        val r3 = runCatching {
            app.post(action2, headers = headers(action1), data = data2).text
        }.getOrNull() ?: return null
        val goToken = GO_SCRIPT_REGEX.find(r3)?.groupValues?.get(1) ?: return null
        val cookieValue = data2["_wp_http2"] ?: data2.values.lastOrNull() ?: return null

        // 4. GET /?go=<token> with the cookie -> meta refresh
        val r4 = runCatching {
            app.get(
                "$cloudBase/?go=$goToken",
                headers = headers(action2) + ("Cookie" to "$goToken=$cookieValue"),
            ).text
        }.getOrNull() ?: return null
        val refresh = Jsoup.parse(r4).selectFirst("meta[http-equiv=refresh]")?.attr("content") ?: return null
        val target = REFRESH_URL_REGEX.find(refresh)?.groupValues?.get(1)?.trim() ?: return null
        if (!target.startsWith("http")) return null

        // 5. follow target (driveseed.org/r?…) -> window.location.replace("/file/KEY")
        val r5 = runCatching { app.get(target, headers = headers(cloudBase)).text }.getOrNull() ?: return null
        val replacePath = REPLACE_PATH_REGEX.find(r5)?.groupValues?.get(1) ?: return target
        if (replacePath.contains("/404")) return null
        val base = Regex("""^(https?://[^/]+)""").find(target)?.groupValues?.get(1) ?: return null
        return if (replacePath.startsWith("http")) replacePath else base + replacePath
    }

    /** driveseed.org/file/KEY -> direct links. */
    private suspend fun emitDriveseed(
        fileUrl: String,
        label: String?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val body = runCatching { app.get(fileUrl, headers = headers(driveseedBase)).text }.getOrNull() ?: return
        var doc = Jsoup.parse(body)
        // /r?… entries answer with a replace() to the real file page
        if (doc.selectFirst("a.btn-danger") == null) {
            REPLACE_PATH_REGEX.find(body)?.groupValues?.get(1)?.takeIf { it.startsWith("/") }?.let { path ->
                runCatching {
                    Jsoup.parse(app.get(driveseedBase + path, headers = headers(driveseedBase)).text)
                }.getOrNull()?.let { doc = it }
            }
        }

        val fileName = doc.selectFirst("li.list-group-item:contains(Name:)")?.text()
            ?.substringAfter("Name :")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.title().takeIf { it.isNotBlank() } ?: "UHDMovies"
        val sizeText = doc.selectFirst("li.list-group-item:contains(Size:)")?.text()
            ?.substringAfter("Size :")?.trim()
        val quality = qualityOf("$fileName $label")

        fun linkName(vararg parts: String?): String =
            parts.filterNotNull().joinToString(" ").take(110)

        // ---- Instant Download (a.btn-danger) ----
        doc.selectFirst("a.btn-danger")?.attr("abs:href")?.takeIf { it.startsWith("http") }?.let { cdn ->
            runCatching {
                val res = app.get(cdn, headers = headers(driveseedBase))
                val final = res.url.takeIf { it.startsWith("http") } ?: return@runCatching
                val host = if (final.contains("video-leech")) "video-leech.xyz" else "video-seed.xyz"
                val delim = "https://$host/?url="
                val keys = if (final.contains(delim)) final.substringAfter(delim) else final

                // primary: POST /api (mints a fresh direct link) — exact port
                var direct: String? = null
                runCatching {
                    val api = app.post(
                        "https://$host/api",
                        headers = mapOf(
                            "x-token" to host,
                            "User-Agent" to userAgent,
                            "Referer" to final,
                        ),
                        data = mapOf("keys" to keys),
                    ).text
                    val obj = org.json.JSONObject(api)
                    direct = obj.optString("url").replace("\\/", "/").takeIf { it.startsWith("http") }
                }
                // fallback: decode the ?url= parameter of the redirect (also a
                // working direct Google-Drive file, verified live)
                if (direct == null) {
                    direct = QUERY_URL_REGEX.find(final)?.groupValues?.get(1)
                        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                        ?.takeIf { it.startsWith("http") }
                }
                direct?.let { url ->
                    callback(
                        newExtractorLink(
                            source = name,
                            name = linkName("UHDMovies • Instant", sizeText?.let { "($it)" }, fileName.substringBeforeLast('.')),
                            url = url,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.quality = quality
                        }
                    )
                }
            }
        }

        // ---- Resume Cloud (a.btn-warning -> /zfile/KEY) ----
        doc.selectFirst("a.btn-warning")?.attr("abs:href")?.takeIf { it.startsWith("http") }?.let { zhref ->
            runCatching {
                val zBody = app.get(zhref, headers = headers(driveseedBase)).text
                val zDoc = Jsoup.parse(zBody)

                var workerUrl: String? = null
                // primary: POST action=cloud -> {url: /zfile/KEY?token=…} -> btn-success
                ZFILE_KEY_REGEX.find(zBody)?.groupValues?.get(1)?.let { key ->
                    runCatching {
                        val post = app.post(
                            zhref,
                            headers = mapOf(
                                "x-token" to URI_HOST.find(zhref)?.groupValues?.get(1).orEmpty(),
                                "X-Requested-With" to "XMLHttpRequest",
                                "User-Agent" to userAgent,
                                "Referer" to zhref,
                            ),
                            data = mapOf("action" to "cloud", "key" to key, "action_token" to ""),
                        ).text
                        val tokenUrl = org.json.JSONObject(post).optString("url").replace("\\/", "/")
                        if (tokenUrl.startsWith("http")) {
                            val tBody = app.get(tokenUrl, headers = headers(driveseedBase)).text
                            workerUrl = Jsoup.parse(tBody).selectFirst("a.btn-success")?.attr("abs:href")
                                ?.takeIf { it.startsWith("http") }
                        }
                    }
                }
                // fallback: the workers.dev direct link printed on the zfile page
                if (workerUrl == null) {
                    workerUrl = zDoc.selectFirst("a[href*=workers.dev]")?.attr("abs:href")?.takeIf { it.startsWith("http") }
                }
                workerUrl?.let { url ->
                    callback(
                        newExtractorLink(
                            source = name,
                            name = linkName("UHDMovies • Resume Cloud", sizeText?.let { "($it)" }, fileName.substringBeforeLast('.')),
                            url = url,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.quality = quality
                        }
                    )
                }
            }
        }
    }

    private val URI_HOST = Regex("""^(https?://[^/]+)""")
}
