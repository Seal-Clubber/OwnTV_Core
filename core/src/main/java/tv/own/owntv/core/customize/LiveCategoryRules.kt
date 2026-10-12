package tv.own.owntv.core.customize

import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.database.entity.CustomCategoryLiveRuleEntity

fun providerReference(remoteId: String?, originalName: String): String =
    remoteId?.let { "id:$it" } ?: "name:$originalName"

/** Same-device source IDs are stable across refresh; backups remap them using existing source identity. */
fun Iterable<CustomCategoryLiveRuleEntity>.toBackupRules(): JSONArray = JSONArray().also { array ->
    forEach { r -> array.put(JSONObject().put("p", r.profileId).put("ctx", r.contextKey)
        .put("src", r.sourceId).put("type", r.ruleType).put("ref", r.referenceKey)
        .put("name", r.displayName).put("included", r.included).put("active", r.active).put("at", r.updatedAt)) }
}

/** Never attach an unmapped source/profile to an unrelated local row. */
fun JSONArray.restoreLiveRules(profiles: Map<Long, Long>, sources: Map<Long, Long>): List<CustomCategoryLiveRuleEntity> =
    (0 until length()).mapNotNull { index ->
        val o = optJSONObject(index) ?: return@mapNotNull null
        val pid = profiles[o.optLong("p", -1)] ?: return@mapNotNull null
        val sid = sources[o.optLong("src", -1)] ?: return@mapNotNull null
        val ctx = o.optString("ctx")
        val type = o.optString("type")
        val ref = o.optString("ref")
        if (!CustomizeKeys.isCustom(ctx) || type !in setOf("GROUP", "CHANNEL", "EXCLUDE") ||
            !(ref.startsWith("id:") || ref.startsWith("name:")) || o.optLong("at") <= 0) return@mapNotNull null
        CustomCategoryLiveRuleEntity(pid, ctx, sid, type, ref, o.optString("name"),
            o.optBoolean("included", true), o.optBoolean("active", true), o.getLong("at"))
    }

/** Only quoted Unicode word tokens enter MATCH; punctuation cannot become an FTS operator. */
fun customCategorySearchQuery(query: String): String {
    val words = Regex("[\\p{L}\\p{N}]+").findAll(query).map { it.value }.toList()
    return if (words.isEmpty()) "\"__no_search_token__\"" else words.joinToString(" AND ") { "\"$it\"*" }
}
