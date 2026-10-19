package eu.kanade.tachiyomi.extension.all.mangaball

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// ---------------------------------------------------------------------------
// JSON helpers
// ---------------------------------------------------------------------------

internal val apiJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun String.parseJsonObject(): JsonObject? =
    runCatching { apiJson.parseToJsonElement(this) as? JsonObject }.getOrNull()

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

internal fun JsonObject?.obj(key: String): JsonObject? {
    val value = this?.get(key) ?: return null
    if (value is JsonNull) return null
    return value as? JsonObject
}

internal fun JsonObject?.arr(key: String): JsonArray? {
    val value = this?.get(key) ?: return null
    if (value is JsonNull) return null
    return value as? JsonArray
}

internal fun JsonObject?.int(key: String): Long? {
    val primitive = (this?.get(key) as? JsonPrimitive) ?: return null
    if (primitive is JsonNull) return null
    return primitive.content.toLongOrNull() ?: primitive.content.toDoubleOrNull()?.toLong()
}

internal fun JsonObject?.bool(key: String): Boolean? =
    (this?.get(key) as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

internal fun JsonObject?.strings(key: String): List<String> {
    val value = this?.get(key) ?: return emptyList()
    return when (value) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
        is JsonPrimitive -> if (value is JsonNull || value.content.isBlank()) emptyList() else listOf(value.content)
        else -> emptyList()
    }.filter { it.isNotBlank() }
}

internal fun JsonObject?.string(key: String): String? {
    val primitive = (this?.get(key) as? JsonPrimitive) ?: return null
    if (primitive is JsonNull) return null
    return primitive.content.takeIf { it.isNotBlank() }
}

// ---------------------------------------------------------------------------
// API model parsing
// ---------------------------------------------------------------------------

internal const val COVER_BASE_URL = "https://bulbasaur.poke-black-and-white.net/covers/"

internal fun coverUrl(title: JsonObject): String? {
    val image = title.obj("image") ?: return null
    val coverPath = image.obj("cover")?.string("path")
        ?.replace('\\', '/')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (coverPath != null) {
        return if (coverPath.startsWith("http")) coverPath else COVER_BASE_URL + coverPath
    }
    return listOf("file", "cdn_mangadex", "cdn_mangaupdate", "cdn_mangaupdates")
        .mapNotNull { image.string(it) }
        .firstOrNull { it.startsWith("http") }
}

internal fun JsonObject.tags(): List<MangaBallTag> =
    arr("tags")?.mapNotNull { element ->
        val tag = element.asObject() ?: return@mapNotNull null
        val id = tag.string("id") ?: tag.string("_id") ?: return@mapNotNull null
        val name = tag.string("name") ?: return@mapNotNull null
        MangaBallTag(name, id, TagGroup.fromKey(tag.string("group")))
    }.orEmpty()

internal fun JsonObject.availableLanguages(): List<String> = strings("availableTranslatedLanguages")
    .flatMap { it.split(',') }
    .map { it.trim().lowercase() }
    .filter { it.isNotEmpty() }

/** Converts a search-result entry into an [SManga]. */
internal fun JsonObject.toSManga(): SManga {
    val titleId = string("id") ?: string("_id").orEmpty()
    val slug = string("slug").orEmpty()
    val titleTags = tags()
    MangaBallCatalog.learn(titleTags)
    // Every alternative title is kept: a work must be findable by any of the names it is known
    // by ("mieruko", "The Girl Who Can See Them", "見える子ちゃん") - and the site
    // lists the one a reader happens to know surprisingly late (see [altTitlesToKeep]).
    val altTitles = altTitlesToKeep(strings("alternateName"))

    return SManga.create().apply {
        url = slug.ifBlank { titleId }
        title = string("name").orEmpty()
        thumbnail_url = coverUrl(this@toSManga)
        memo = buildJsonObject {
            put("id", titleId)
            put("views", int("views") ?: int("views_count") ?: 0L)
            put("chapters", obj("stats")?.int("chapters") ?: 0L)
            put("langs", buildJsonArray { availableLanguages().forEach { add(JsonPrimitive(it)) } })
            put("tags", buildJsonArray { titleTags.forEach { add(JsonPrimitive(it.id)) } })
            put("tagNames", buildJsonArray { titleTags.forEach { add(JsonPrimitive(it.name)) } })
            put("altTitles", buildJsonArray { altTitles.forEach { add(JsonPrimitive(it)) } })
            put("adult", bool("is18plus") ?: false)
        }
    }
}

/** Parses the `title/detail/{id}` payload. */
internal fun JsonObject.toMangaDetails(): SManga {
    val titleTags = tags()
    MangaBallCatalog.learn(titleTags)
    val altNames = strings("alternateName")
    val altTitles = altTitlesToKeep(altNames)
    val descriptionParts = strings("description")
    val authors = (arr("author") ?: arr("authors"))?.mapNotNull { it.asObject()?.string("name") }.orEmpty()

    return SManga.create().apply {
        url = string("slug") ?: string("id").orEmpty()
        title = string("name").orEmpty()
        thumbnail_url = coverUrl(this@toMangaDetails)
        author = authors.joinToString(", ").takeIf { it.isNotBlank() }
        artist = authors.joinToString(", ").takeIf { it.isNotBlank() }
        genre = titleTags.joinToString(", ") { it.name }
        status = when (string("status")?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled", "canceled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        this.description = buildString {
            append(descriptionParts.joinToString("\n\n"))
            if (altNames.isNotEmpty()) {
                append("\n\nAlternative titles:\n")
                append(altNames.joinToString("\n") { "- $it" })
            }
        }.trim()
        memo = buildJsonObject {
            put("id", string("id") ?: string("_id").orEmpty())
            put("views", int("views") ?: int("views_count") ?: 0L)
            put("chapters", obj("stats")?.int("chapters") ?: 0L)
            put("langs", buildJsonArray { availableLanguages().forEach { add(JsonPrimitive(it)) } })
            put("tags", buildJsonArray { titleTags.forEach { add(JsonPrimitive(it.id)) } })
            put("tagNames", buildJsonArray { titleTags.forEach { add(JsonPrimitive(it.name)) } })
            put("altTitles", buildJsonArray { altTitles.forEach { add(JsonPrimitive(it)) } })
            put("adult", bool("is18plus") ?: false)
        }
    }
}

/** Converts a chapter entry into an [SChapter], or null when its language is filtered out. */
internal fun JsonObject.toSChapter(allowedLanguages: Set<String>): SChapter? {
    val chapterId = string("id") ?: string("_id") ?: return null
    val lang = string("lang")?.lowercase()
    if (allowedLanguages.isNotEmpty() && lang != null && lang !in allowedLanguages) return null

    val rawName = string("name").orEmpty().trim()
    val number = (string("number") ?: string("chapter_number"))?.toFloatOrNull()
    val volume = string("volume")?.toFloatOrNull() ?: 0f
    val group = obj("group")?.string("name") ?: string("group_name")

    return SChapter.create().apply {
        url = chapterId
        name = buildString {
            if (volume > 0f) append("Vol. ", volume.toDisplayNumber(), " ")
            val numberText = number?.toDisplayNumber()
            when {
                numberText == null -> append(rawName.ifEmpty { "Chapter" })
                rawName.isBlank() -> append("Ch. ", numberText)
                rawName.contains(numberText) -> append(rawName)
                else -> append("Ch. ", numberText, " - ", rawName)
            }
        }.trim()
        if (number != null) chapter_number = number
        scanlator = group
        date_upload = parseDate(string("created_at") ?: string("updated_at"))
    }
}

internal fun Float.toDisplayNumber(): String =
    if (this % 1f == 0f) toInt().toString() else toString()

private val DATE_PATTERNS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
    "yyyy-MM-dd'T'HH:mm:ss.SSS",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd",
)

internal fun parseDate(raw: String?): Long {
    val value = raw?.trim()?.removeSuffix("Z")?.takeIf { it.isNotEmpty() } ?: return 0L
    DATE_PATTERNS.forEach { pattern ->
        val parsed = runCatching {
            SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = true
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(value)?.time
        }.getOrNull()
        if (parsed != null && parsed > 0L) return parsed
    }
    return 0L
}

// ---------------------------------------------------------------------------
// Reading values back out of SManga.memo
// ---------------------------------------------------------------------------

internal fun SManga.titleId(): String? = memo.string("id")?.takeIf { it.isNotBlank() }

internal fun SManga.memoTagIds(): List<String> = memo.strings("tags")

internal fun SManga.memoTagNames(): List<String> = memo.strings("tagNames")

internal fun SManga.memoAltTitles(): List<String> = memo.strings("altTitles")

internal fun SManga.memoLanguages(): List<String> = memo.strings("langs")

internal fun SManga.memoViews(): Long = memo.int("views") ?: 0L

internal fun SManga.memoChapterCount(): Long = memo.int("chapters") ?: 0L
