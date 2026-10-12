package tv.own.owntv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Persistent LIVE membership intent. No catalog FK: a missing group/channel may return later.
 * GROUP references a provider category; CHANNEL includes one channel; EXCLUDE overrides both.
 * Inactive rows are deletion markers, retained for backup/sync conflict resolution. */
@Entity(
    tableName = "custom_category_live_rules",
    primaryKeys = ["profileId", "contextKey", "sourceId", "ruleType", "referenceKey"],
    foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("profileId"), Index(value = ["profileId", "contextKey"]), Index(value = ["sourceId", "ruleType", "referenceKey"])],
)
data class CustomCategoryLiveRuleEntity(
    val profileId: Long,
    val contextKey: String,
    val sourceId: Long,
    val ruleType: String,
    /** id:<provider id>, or name:<original name> when the provider supplies no stable id. */
    val referenceKey: String,
    val displayName: String,
    val included: Boolean = true,
    val active: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
)
