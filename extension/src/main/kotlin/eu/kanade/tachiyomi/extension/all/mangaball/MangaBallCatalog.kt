package eu.kanade.tachiyomi.extension.all.mangaball

import android.app.Application
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The site groups every tag into one of these buckets. The API accepts tag *ids* only,
 * so the catalog below maps human readable names to those ids.
 */
internal enum class TagGroup(val key: String, val label: String) {
    GENRE("genre", "Genres"),
    THEME("theme", "Themes"),
    FORMAT("format", "Formats"),
    CONTENT("content", "Content"),
    UNKNOWN("unknown", "Other"),
    ;

    companion object {
        fun fromKey(key: String?): TagGroup = entries.firstOrNull { it.key.equals(key, true) } ?: UNKNOWN
    }
}

internal data class MangaBallTag(
    val name: String,
    val id: String,
    val group: TagGroup,
) {
    /** Name without punctuation/spaces, used for forgiving lookups. */
    val normalized: String = name.lowercase().filter { it.isLetterOrDigit() }
}

/**
 * Tag catalog used to build the include/exclude filters and to resolve names typed inside
 * the search box (e.g. `tag:romance -tag:ecchi`).
 *
 * A base catalog is bundled with the extension. In addition, every tag seen in an API
 * response is remembered, so brand new site tags show up in the filters automatically
 * even before an extension update ships.
 */
internal object MangaBallCatalog {

    private const val PREFS_NAME = "mangaball_enhanced_catalog"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences(PREFS_NAME, 0)
    }

    private val learned = linkedMapOf<String, MangaBallTag>()
    private var loaded = false

    val base: List<MangaBallTag> = listOf(
        // ---------------------------------------------------------------- genres
        MangaBallTag("Action", "685146c5f3ed681c80f257e3", TagGroup.GENRE),
        MangaBallTag("Adult", "689371f0a943baf927094f03", TagGroup.GENRE),
        MangaBallTag("Adventure", "685146c5f3ed681c80f257e6", TagGroup.GENRE),
        MangaBallTag("Boys' Love", "685148ef15e8b86aae68e573", TagGroup.GENRE),
        MangaBallTag("Comedy", "685146c5f3ed681c80f257e5", TagGroup.GENRE),
        MangaBallTag("Comics", "68ecab8507ec62d87e62780f", TagGroup.GENRE),
        MangaBallTag("Crime", "685148da15e8b86aae68e51f", TagGroup.GENRE),
        MangaBallTag("Drama", "685148cf15e8b86aae68e4dd", TagGroup.GENRE),
        MangaBallTag("Ecchi", "6892a73ba943baf927094e37", TagGroup.GENRE),
        MangaBallTag("Fantasy", "685146c5f3ed681c80f257ea", TagGroup.GENRE),
        MangaBallTag("Girls' Love", "685148da15e8b86aae68e524", TagGroup.GENRE),
        MangaBallTag("Historical", "685148db15e8b86aae68e527", TagGroup.GENRE),
        MangaBallTag("Horror", "685148da15e8b86aae68e520", TagGroup.GENRE),
        MangaBallTag("Isekai", "685146c5f3ed681c80f257e9", TagGroup.GENRE),
        MangaBallTag("Josei(W)", "694cc2d9f8014f5e0a63ac73", TagGroup.GENRE),
        MangaBallTag("Magical Girls", "6851490d15e8b86aae68e5d4", TagGroup.GENRE),
        MangaBallTag("Manga", "68ecab1e07ec62d87e627806", TagGroup.GENRE),
        MangaBallTag("Manhwa", "68ecab3b07ec62d87e627809", TagGroup.GENRE),
        MangaBallTag("Manhua", "68ecab4807ec62d87e62780b", TagGroup.GENRE),
        MangaBallTag("Mature", "68932d11a943baf927094e7b", TagGroup.GENRE),
        MangaBallTag("Mecha", "6851490c15e8b86aae68e5d2", TagGroup.GENRE),
        MangaBallTag("Medical", "6851494e15e8b86aae68e66e", TagGroup.GENRE),
        MangaBallTag("Mystery", "685148d215e8b86aae68e4f4", TagGroup.GENRE),
        MangaBallTag("Philosophical", "685148e215e8b86aae68e544", TagGroup.GENRE),
        MangaBallTag("Psychological", "685148d715e8b86aae68e507", TagGroup.GENRE),
        MangaBallTag("Revenge", "694cc2d9f8014f5e0a63ac75", TagGroup.GENRE),
        MangaBallTag("Romance", "685148cf15e8b86aae68e4db", TagGroup.GENRE),
        MangaBallTag("Sci-Fi", "685148cf15e8b86aae68e4da", TagGroup.GENRE),
        MangaBallTag("Shoujo(G)", "694cc2d9f8014f5e0a63ac74", TagGroup.GENRE),
        MangaBallTag("Shounen Ai", "689f0ab1f2e66744c6091524", TagGroup.GENRE),
        MangaBallTag("Slice of Life", "685148d015e8b86aae68e4e3", TagGroup.GENRE),
        MangaBallTag("Smut", "689371f2a943baf927094f04", TagGroup.GENRE),
        MangaBallTag("Sports", "685148f515e8b86aae68e588", TagGroup.GENRE),
        MangaBallTag("Superhero", "6851492915e8b86aae68e61c", TagGroup.GENRE),
        MangaBallTag("Thriller", "685148d915e8b86aae68e51e", TagGroup.GENRE),
        MangaBallTag("Tragedy", "685148db15e8b86aae68e529", TagGroup.GENRE),
        MangaBallTag("User Created", "68932c3ea943baf927094e77", TagGroup.GENRE),
        MangaBallTag("Wuxia", "6851490715e8b86aae68e5c3", TagGroup.GENRE),
        MangaBallTag("Yaoi", "68932f68a943baf927094eaa", TagGroup.GENRE),
        MangaBallTag("Yuri", "6896a885a943baf927094f66", TagGroup.GENRE),
        // ---------------------------------------------------------------- themes
        MangaBallTag("3D", "6a0026ba63a8d384c0a4be13", TagGroup.THEME),
        MangaBallTag("Aliens", "6851490d15e8b86aae68e5d5", TagGroup.THEME),
        MangaBallTag("Animals", "685148e715e8b86aae68e54b", TagGroup.THEME),
        MangaBallTag("Cooking", "685148d215e8b86aae68e4f8", TagGroup.THEME),
        MangaBallTag("Crossdressing", "685148df15e8b86aae68e534", TagGroup.THEME),
        MangaBallTag("Delinquents", "685148d915e8b86aae68e519", TagGroup.THEME),
        MangaBallTag("Demons", "685146c5f3ed681c80f257e4", TagGroup.THEME),
        MangaBallTag("Genderswap", "685148d715e8b86aae68e505", TagGroup.THEME),
        MangaBallTag("Ghosts", "685148d615e8b86aae68e501", TagGroup.THEME),
        MangaBallTag("Gyaru", "685148d015e8b86aae68e4e8", TagGroup.THEME),
        MangaBallTag("Harem", "685146c5f3ed681c80f257e8", TagGroup.THEME),
        MangaBallTag("Hentai", "68bfceaf4dbc442a26519889", TagGroup.THEME),
        MangaBallTag("Incest", "685148f215e8b86aae68e584", TagGroup.THEME),
        MangaBallTag("Loli", "685148d715e8b86aae68e506", TagGroup.THEME),
        MangaBallTag("Mafia", "685148d915e8b86aae68e518", TagGroup.THEME),
        MangaBallTag("Magic", "685148d715e8b86aae68e509", TagGroup.THEME),
        MangaBallTag("Manhwa 18+", "68f5f5ce5f29d3c1863dec3a", TagGroup.THEME),
        MangaBallTag("Martial Arts", "6851490615e8b86aae68e5c2", TagGroup.THEME),
        MangaBallTag("Military", "685148e215e8b86aae68e541", TagGroup.THEME),
        MangaBallTag("Monster Girls", "685148db15e8b86aae68e52c", TagGroup.THEME),
        MangaBallTag("Monsters", "685146c5f3ed681c80f257e2", TagGroup.THEME),
        MangaBallTag("Music", "685148d015e8b86aae68e4e4", TagGroup.THEME),
        MangaBallTag("Ninja", "685148d715e8b86aae68e508", TagGroup.THEME),
        MangaBallTag("Office Workers", "685148d315e8b86aae68e4fd", TagGroup.THEME),
        MangaBallTag("Police", "6851498815e8b86aae68e714", TagGroup.THEME),
        MangaBallTag("Post-Apocalyptic", "685148e215e8b86aae68e540", TagGroup.THEME),
        MangaBallTag("Reincarnation", "685146c5f3ed681c80f257e1", TagGroup.THEME),
        MangaBallTag("Reverse Harem", "685148df15e8b86aae68e533", TagGroup.THEME),
        MangaBallTag("Samurai", "6851490415e8b86aae68e5b9", TagGroup.THEME),
        MangaBallTag("School Life", "685148d015e8b86aae68e4e7", TagGroup.THEME),
        MangaBallTag("Shota", "685148d115e8b86aae68e4ed", TagGroup.THEME),
        MangaBallTag("Supernatural", "685148db15e8b86aae68e528", TagGroup.THEME),
        MangaBallTag("Survival", "685148cf15e8b86aae68e4dc", TagGroup.THEME),
        MangaBallTag("Time Travel", "6851490c15e8b86aae68e5d1", TagGroup.THEME),
        MangaBallTag("Traditional Games", "6851493515e8b86aae68e645", TagGroup.THEME),
        MangaBallTag("Vampires", "685148f915e8b86aae68e597", TagGroup.THEME),
        MangaBallTag("Video Games", "685148e115e8b86aae68e53c", TagGroup.THEME),
        MangaBallTag("Villainess", "6851492115e8b86aae68e602", TagGroup.THEME),
        MangaBallTag("Virtual Reality", "68514a1115e8b86aae68e83e", TagGroup.THEME),
        MangaBallTag("Zombies", "6851490c15e8b86aae68e5d3", TagGroup.THEME),
        // --------------------------------------------------------------- formats
        MangaBallTag("4-Koma", "685148d115e8b86aae68e4ec", TagGroup.FORMAT),
        MangaBallTag("Adaptation", "685148cf15e8b86aae68e4de", TagGroup.FORMAT),
        MangaBallTag("Anthology", "685148e915e8b86aae68e558", TagGroup.FORMAT),
        MangaBallTag("Award Winning", "685148fe15e8b86aae68e5a7", TagGroup.FORMAT),
        MangaBallTag("Doujinshi", "6851490e15e8b86aae68e5da", TagGroup.FORMAT),
        MangaBallTag("Fan Colored", "6851498215e8b86aae68e704", TagGroup.FORMAT),
        MangaBallTag("Full Color", "685148d615e8b86aae68e502", TagGroup.FORMAT),
        MangaBallTag("Long Strip", "685148d915e8b86aae68e517", TagGroup.FORMAT),
        MangaBallTag("Official Colored", "6851493515e8b86aae68e64a", TagGroup.FORMAT),
        MangaBallTag("Oneshot", "685148eb15e8b86aae68e56c", TagGroup.FORMAT),
        MangaBallTag("Self-Published", "6851492e15e8b86aae68e633", TagGroup.FORMAT),
        MangaBallTag("Web Comic", "685148d715e8b86aae68e50d", TagGroup.FORMAT),
        // --------------------------------------------------------------- content
        MangaBallTag("Gore", "685148d115e8b86aae68e4f3", TagGroup.CONTENT),
        MangaBallTag("Sexual Violence", "685146c5f3ed681c80f257e7", TagGroup.CONTENT),
    )

    /** Every known tag: bundled catalog plus everything learned from API responses. */
    fun all(): List<MangaBallTag> {
        ensureLoaded()
        return (base + learned.values).distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    fun ofGroup(group: TagGroup): List<MangaBallTag> = all().filter { it.group == group }

    /** Resolves a user supplied token (id, slug or name) to a tag. */
    fun resolve(token: String): MangaBallTag? {
        val raw = token.trim()
        if (raw.isEmpty()) return null
        val pool = all()
        pool.firstOrNull { it.id.equals(raw, true) }?.let { return it }
        val normalized = raw.lowercase().filter { it.isLetterOrDigit() }
        return pool.firstOrNull { it.normalized == normalized }
            ?: pool.firstOrNull { it.normalized.startsWith(normalized) && normalized.length >= 3 }
            ?: pool.firstOrNull { it.normalized.contains(normalized) && normalized.length >= 4 }
    }

    /** Remembers tags returned by the API so new site tags become selectable. */
    fun learn(tags: List<MangaBallTag>) {
        ensureLoaded()
        var changed = false
        tags.forEach { tag ->
            if (tag.id.isBlank()) return@forEach
            val known = base.any { it.id == tag.id } || learned.containsKey(tag.id)
            if (!known) {
                learned[tag.id] = tag
                changed = true
            }
        }
        if (changed) persist()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val raw = preferences.getString(KEY_LEARNED, null) ?: return@runCatching
            Json.parseToJsonElement(raw).let { element ->
                (element as? JsonArray)?.forEach { item ->
                    val obj = item as? JsonObject ?: return@forEach
                    val id = obj.stringOrNull("id") ?: return@forEach
                    val name = obj.stringOrNull("name") ?: return@forEach
                    if (base.any { it.id == id }) return@forEach
                    learned[id] = MangaBallTag(name, id, TagGroup.fromKey(obj.stringOrNull("group")))
                }
            }
        }
    }

    private fun persist() {
        runCatching {
            val array = buildJsonArray {
                learned.values.forEach { tag ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(tag.id))
                            put("name", JsonPrimitive(tag.name))
                            put("group", JsonPrimitive(tag.group.key))
                        },
                    )
                }
            }
            preferences.edit().putString(KEY_LEARNED, array.toString()).apply()
        }
    }

    private const val KEY_LEARNED = "learned_tags"
}

internal fun JsonObject.stringOrNull(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.content.takeIf { it.isNotBlank() }
}
