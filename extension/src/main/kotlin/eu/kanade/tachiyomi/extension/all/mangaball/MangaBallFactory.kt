package eu.kanade.tachiyomi.extension.all.mangaball

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource

/**
 * Komikku / Mihon instantiate this class and call [createSources].
 *
 * The reader app hides the extension completely when this call throws *any* throwable
 * (`LoadResult.Error`), and it also hides an extension whose factory returns an empty list.
 * That makes a load-time failure impossible to diagnose from the phone, so this factory:
 *
 *  * builds each language source inside its own try/catch, and
 *  * falls back to [MangaBallDiagnostics] instead of returning nothing.
 *
 * The diagnostics source appears in Browse as "MangaBall Diagnostics" and reports what went
 * wrong on screen, without needing a log file.
 */
class MangaBallFactory : SourceFactory {

    override fun createSources(): List<Source> {
        val failures = mutableListOf<String>()

        val sources = try {
            MangaBallLanguages.SOURCES.mapNotNull { (code, label) ->
                try {
                    MangaBallSource(code, label) as Source
                } catch (error: Throwable) {
                    failures += describe(error)
                    null
                }
            }
        } catch (error: Throwable) {
            failures += describe(error)
            emptyList()
        }

        // Whenever anything failed - even if other sources still work - publish the diagnostics
        // source next to them. A partially working extension must never look healthy, otherwise
        // a missing language (for example "MangaBall (English)") is impossible to explain.
        if (sources.isEmpty() || failures.isNotEmpty()) {
            return try {
                sources + MangaBallDiagnostics(failures, sources.map { it.name })
            } catch (error: Throwable) {
                failures += describe(error)
                sources
            }
        }

        return sources
    }

    private fun describe(error: Throwable): String {
        val cause = generateSequence(error) { it.cause }.last()
        val name = cause.javaClass.simpleName.ifBlank { cause.javaClass.name }
        return "$name${cause.message?.take(90)?.let { ": $it" } ?: ""}"
    }
}

internal object MangaBallLanguages {

    /** Site language code to the label shown in the app. */
    val SOURCES: List<Pair<String, String>> = listOf(
        "en" to "English",
        "es" to "Español",
        "pt-BR" to "Português (Brasil)",
        "fr" to "Français",
        "de" to "Deutsch",
        "it" to "Italiano",
        "nl" to "Nederlands",
        "ru" to "Русский",
        "uk" to "Українська",
        "pl" to "Polski",
        "cs" to "Čeština",
        "sk" to "Slovenčina",
        "ro" to "Română",
        "hu" to "Magyar",
        "bg" to "Български",
        "sr" to "Српски",
        "tr" to "Türkçe",
        "ar" to "العربية",
        "he" to "עברית",
        "fa" to "فارسی",
        "el" to "Ελληνικά",
        "id" to "Indonesia",
        "ms" to "Melayu",
        "vi" to "Tiếng Việt",
        "th" to "ไทย",
        "hi" to "हिन्दी",
        "bn" to "বাংলা",
        "ta" to "தமிழ்",
        "ml" to "മലയാളം",
        "ne" to "नेपाली",
        "ja" to "日本語",
        "ko" to "한국어",
        "zh" to "中文",
        "da" to "Dansk",
        "fi" to "Suomi",
        "no" to "Norsk",
        "sv" to "Svenska",
        "is" to "Íslenska",
        "sq" to "Shqip",
        "ca" to "Català",
    )
}

/**
 * A deliberately dependency-free source (no preferences, no Injekt, no JSON parsing) whose
 * only job is to stay loadable and report what is wrong. It performs a live request to the
 * site so connectivity problems are visible too.
 */
internal class MangaBallDiagnostics(
    private val failures: List<String>,
    private val created: List<String> = emptyList(),
) : HttpSource() {

    override val name: String = "MangaBall Diagnostics"
    override val baseUrl: String = BASE_URL

    // Use the phone's language so the app's language filter does not hide this source.
    override val lang: String = runCatching { java.util.Locale.getDefault().language }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: "en"

    override val supportsLatest: Boolean = false

    override fun headersBuilder() = super.headersBuilder()
        .add("Accept", "application/json, text/plain, */*")

    private fun entry(title: String, detail: String): SManga = SManga.create().apply {
        url = "diag-" + title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        this.title = title
        description = detail
        initialized = true
    }

    private suspend fun report(): List<SManga> {
        val entries = mutableListOf<SManga>()

        entries += entry(
            "1. Extension API loaded",
            "The reader app loaded this extension's factory and created this source, so the " +
                "APK, its signature, its lib version and its metadata are all fine.\n\n" +
                "If you can read this, the problem is not the extension being missing: it is " +
                "either the app hiding NSFW sources, the app's language filter, or the " +
                "language sources failing to build (see next entry).",
        )

        entries += entry(
            "2. Language sources",
            buildString {
                append("Created: ")
                append(created.size)
                append(" of ")
                append(MangaBallLanguages.SOURCES.size)
                append(" language sources.")
                if (created.isNotEmpty()) {
                    append("\n\n")
                    append(created.joinToString(", "))
                }
                append("\n\n")
                if (failures.isEmpty()) {
                    append(
                        "No source failed to build, so this diagnostics source would normally not " +
                            "appear. If you can see it, the app loaded the extension but hid some " +
                            "of its sources: check the extension's source list and the app's " +
                            "Languages setting.",
                    )
                } else {
                    append("Failed to build:\n")
                    append(failures.distinct().joinToString("\n"))
                }
            },
        )

        val reachability = try {
            val response = client.newCall(GET("$baseUrl/api/v1/title/search-advanced?page=1&limit=1", headers)).await()
            val code = response.code
            val body = response.body?.string().orEmpty()
            response.close()
            when {
                code == 200 && body.trimStart().startsWith("{") ->
                    "OK - the site answered HTTP 200 with JSON (${body.length} characters). " +
                        "Reading and searching work with this network."
                code == 200 ->
                    "HTTP 200 but the answer is not JSON, so a Cloudflare challenge or an " +
                        "interstitial page is being served.\n\nTurn on \"Use Cloudflare bypass " +
                        "client\" in the source's settings."
                code == 403 ->
                    "HTTP 403 - the site is blocking this connection (Cloudflare).\n\nTurn on " +
                        "\"Use Cloudflare bypass client\" in the source's settings."
                else -> "Unexpected HTTP $code.\n\n${body.take(300)}"
            }
        } catch (error: Throwable) {
            "Request failed: ${error.javaClass.simpleName} ${error.message.orEmpty().take(200)}\n\n" +
                "Check the phone's internet connection, then try again."
        }

        entries += entry("3. Site reachability", reachability)
        entries += entry(
            "4. What to do with this information",
            "Send these three entries back to the developer:\n\n" +
                "- If entry 2 lists errors, that is the bug.\n" +
                "- If entry 3 fails, it is a network/Cloudflare problem.\n" +
                "- If you never see this diagnostics source in Browse, the extension was not " +
                "loaded by the app at all: check Komikku/Mihon settings for NSFW sources and " +
                "enabled languages, and confirm the app version supports extension lib 1.6.",
        )

        return entries
    }

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(report(), false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        getPopularManga(page)

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = SMangaUpdate(manga, emptyList())

    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
}
