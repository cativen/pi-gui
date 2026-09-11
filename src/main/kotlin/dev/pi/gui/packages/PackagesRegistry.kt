package dev.pi.gui.packages

import com.intellij.util.io.HttpRequests

data class PackageSearchResult(
    val name: String,
    val description: String,
    /** Space-separated resource kinds the package ships, e.g. "extension skill". */
    val types: String,
    val downloads: Long,
) {
    /** Install source. Everything listed on pi.dev is published to npm. */
    val installSource: String get() = "npm:$name"

    fun downloadsLabel(): String = when {
        downloads >= 1_000_000 -> String.format("%.1fM downloads", downloads / 1_000_000.0)
        downloads >= 1_000 -> String.format("%.1fK downloads", downloads / 1_000.0)
        downloads > 0 -> "$downloads downloads"
        else -> ""
    }
}

/**
 * Searches the pi package directory at pi.dev.
 *
 * pi.dev exposes no JSON API (its `/api` routes answer 501), so this reads the packages page and
 * pulls the `data-package-*` attributes the markup already carries for its own client-side
 * filtering. Those attributes are far more stable than the rendered text, but this is still
 * scraping: [search] returns an empty list rather than throwing if the markup changes, and the
 * panel always keeps a manual source field so installing never depends on this working.
 */
object PackagesRegistry {

    const val PACKAGES_URL = "https://pi.dev/packages"

    private val CARD = Regex("""<article[^>]*data-package-card="true"[^>]*>.*?</article>""", RegexOption.DOT_MATCHES_ALL)
    private val NAME = Regex("""data-package-name="([^"]*)"""")
    private val TYPES = Regex("""data-package-types="([^"]*)"""")
    private val DOWNLOADS = Regex("""data-package-downloads="([^"]*)"""")
    private val PARAGRAPH = Regex("""<p[^>]*>([^<]{5,400})</p>""")

    @Throws(Exception::class)
    fun search(query: String, limit: Int = 50): List<PackageSearchResult> {
        // The site's own filter parameter is `name`.
        val url = if (query.isBlank()) PACKAGES_URL
        else "$PACKAGES_URL?name=${java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8)}"

        val html = HttpRequests.request(url)
            .accept("text/html")
            .connectTimeout(20_000)
            .readTimeout(30_000)
            .readString()

        return parse(html).take(limit)
    }

    /** Exposed for testing against captured markup. */
    fun parse(html: String): List<PackageSearchResult> =
        CARD.findAll(html).mapNotNull { match ->
            val card = match.value
            val name = NAME.find(card)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            PackageSearchResult(
                name = unescape(name),
                description = PARAGRAPH.find(card)?.groupValues?.get(1)?.let { unescape(it).trim() }.orEmpty(),
                types = unescape(TYPES.find(card)?.groupValues?.get(1).orEmpty()),
                downloads = DOWNLOADS.find(card)?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
            )
        }.toList()

    private fun unescape(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
}
