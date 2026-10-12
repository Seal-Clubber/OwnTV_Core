package tv.own.owntv.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.CustomCategoryLiveRuleEntity
import tv.own.owntv.core.customize.providerReference
import tv.own.owntv.core.customize.customCategorySearchQuery
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.CustomCategoryMemberEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.model.MediaType

/**
 * Membership rows of the user's custom combined categories (issue #87). Each row pins one content
 * item to a [position] inside a custom category, identified by its stable DataStore key
 * (`"custom:<uuid>"`, `CustomizeKeys.CUSTOM_PREFIX`) as [CustomCategoryMemberEntity.contextKey].
 * The table is modeled on `content_order`: membership defines the SET, while the user's manual
 * reorder WITHIN a custom category still rides the existing `content_order` rows (same contextKey —
 * the browse queries below LEFT JOIN both). See [CustomCategoryMemberEntity].
 */
private const val LIVE_RULE_MEMBERSHIP = "((m.itemId IS NOT NULL OR EXISTS (SELECT 1 FROM categories g JOIN custom_category_live_rules r ON r.sourceId = g.sourceId AND r.referenceKey = CASE WHEN g.remoteId IS NULL THEN 'name:' || g.name ELSE 'id:' || g.remoteId END WHERE g.id = c.categoryId AND g.mediaType = 'LIVE' AND r.profileId = :profileId AND r.contextKey = :contextKey AND r.ruleType = 'GROUP' AND r.active = 1) OR EXISTS (SELECT 1 FROM custom_category_live_rules r WHERE r.profileId = :profileId AND r.contextKey = :contextKey AND r.sourceId = c.sourceId AND r.ruleType = 'CHANNEL' AND r.referenceKey = CASE WHEN c.remoteId IS NULL THEN 'name:' || c.name ELSE 'id:' || c.remoteId END AND r.active = 1 AND r.included = 1)) AND NOT EXISTS (SELECT 1 FROM custom_category_live_rules r WHERE r.profileId = :profileId AND r.contextKey = :contextKey AND r.sourceId = c.sourceId AND r.ruleType = 'EXCLUDE' AND r.referenceKey = CASE WHEN c.remoteId IS NULL THEN 'name:' || c.name ELSE 'id:' || c.remoteId END AND r.active = 1))"
private const val LIVE_CONTEXT_MEMBERSHIP = "((m.itemId IS NOT NULL OR EXISTS (SELECT 1 FROM categories g JOIN custom_category_live_rules r ON r.sourceId = g.sourceId AND r.referenceKey = CASE WHEN g.remoteId IS NULL THEN 'name:' || g.name ELSE 'id:' || g.remoteId END WHERE g.id = c.categoryId AND g.mediaType = 'LIVE' AND r.profileId = :profileId AND r.contextKey = k.contextKey AND r.ruleType = 'GROUP' AND r.active = 1) OR EXISTS (SELECT 1 FROM custom_category_live_rules r WHERE r.profileId = :profileId AND r.contextKey = k.contextKey AND r.sourceId = c.sourceId AND r.ruleType = 'CHANNEL' AND r.referenceKey = CASE WHEN c.remoteId IS NULL THEN 'name:' || c.name ELSE 'id:' || c.remoteId END AND r.active = 1 AND r.included = 1)) AND NOT EXISTS (SELECT 1 FROM custom_category_live_rules r WHERE r.profileId = :profileId AND r.contextKey = k.contextKey AND r.sourceId = c.sourceId AND r.ruleType = 'EXCLUDE' AND r.referenceKey = CASE WHEN c.remoteId IS NULL THEN 'name:' || c.name ELSE 'id:' || c.remoteId END AND r.active = 1))"

@Dao
interface CustomCategoryDao {

    // --- write side (mirrors ContentOrderDao) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CustomCategoryMemberEntity>)

    @Query("DELETE FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey")
    suspend fun clearContext(profileId: Long, type: MediaType, contextKey: String)

    /** Context keys that actually have membership rows for this profile/section. */
    @Query("SELECT DISTINCT contextKey FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type UNION SELECT contextKey FROM custom_category_live_rules WHERE :type = 'LIVE' AND profileId = :profileId AND active = 1")
    fun observeContextKeys(profileId: Long, type: MediaType): Flow<List<String>>

    /** Replaces a context's entire membership in one transaction (commit point of a Move). */
    @Transaction
    suspend fun replaceContext(profileId: Long, type: MediaType, contextKey: String, rows: List<CustomCategoryMemberEntity>) {
        clearContext(profileId, type, contextKey)
        if (rows.isNotEmpty()) insertAll(rows)
    }

    /** Highest existing position for a context — append-point for "Move to…". */
    @Query("SELECT COALESCE(MAX(position), -1) FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey")
    suspend fun maxPosition(profileId: Long, type: MediaType, contextKey: String): Int

    /** Appends one member atomically so two fast Move actions cannot receive the same position. */
    @Transaction
    suspend fun appendItem(profileId: Long, type: MediaType, contextKey: String, itemId: Long) {
        if (type == MediaType.LIVE) setLiveChannelOverride(profileId, contextKey, itemId, included = true)
        if (type == MediaType.LIVE && exists(profileId, type, contextKey, itemId)) return
        insertAll(
            listOf(
                CustomCategoryMemberEntity(
                    profileId = profileId,
                    mediaType = type,
                    contextKey = contextKey,
                    itemId = itemId,
                    position = maxPosition(profileId, type, contextKey) + 1,
                ),
            ),
        )
    }

    /** Does this row already exist? The dry run before a sync counts what is genuinely new. */
    @Query("SELECT EXISTS(SELECT 1 FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey AND itemId = :itemId)")
    suspend fun exists(profileId: Long, type: MediaType, contextKey: String, itemId: Long): Boolean

    /** Removes ONE membership row — "Move to…" away from a custom-category origin without keeping it. */
    /** The custom categories [itemId] belongs to — a channel kept in one stays visible when its own
     *  provider folder is hidden. */
    @Query("SELECT DISTINCT contextKey FROM custom_category_members WHERE :type != 'LIVE' AND profileId = :profileId AND mediaType = :type AND itemId = :itemId UNION SELECT k.contextKey FROM (SELECT contextKey FROM custom_category_members WHERE profileId = :profileId AND mediaType = 'LIVE' UNION SELECT contextKey FROM custom_category_live_rules WHERE profileId = :profileId AND active = 1) k JOIN channels c ON c.id = :itemId LEFT JOIN custom_category_members m ON m.itemId = c.id AND m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = k.contextKey WHERE :type = 'LIVE' AND " + LIVE_CONTEXT_MEMBERSHIP)
    suspend fun contextsOf(profileId: Long, type: MediaType, itemId: Long): List<String>

    @Query("DELETE FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey AND itemId = :itemId")
    suspend fun deleteItem(profileId: Long, type: MediaType, contextKey: String, itemId: Long)

    /**
     * Resolvable member counts for the active sources — the "Move to…" dialog's per-row badges.
     * A re-sync temporarily leaves stale membership ids until relinking finishes, and a profile may
     * disable/remove a source. Neither kind of row is visible in the category, so neither may inflate
     * the badge.
     */
    @Query(
        "SELECT contextKey, COUNT(*) AS count FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey IN (:contextKeys) AND ((:type = 'MOVIE' AND itemId IN (SELECT id FROM movies WHERE sourceId IN (:sourceIds))) OR (:type = 'SERIES' AND itemId IN (SELECT id FROM series WHERE sourceId IN (:sourceIds)))) GROUP BY contextKey " +
            "UNION ALL SELECT k.contextKey, COUNT(*) AS count FROM (SELECT contextKey FROM custom_category_members WHERE profileId = :profileId AND mediaType = 'LIVE' UNION SELECT contextKey FROM custom_category_live_rules WHERE profileId = :profileId AND active = 1) k CROSS JOIN channels c LEFT JOIN custom_category_members m ON m.itemId = c.id AND m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = k.contextKey WHERE :type = 'LIVE' AND k.contextKey IN (:contextKeys) AND c.sourceId IN (:sourceIds) AND " + LIVE_CONTEXT_MEMBERSHIP + " GROUP BY k.contextKey",
    )
    fun observeCountsByContexts(
        profileId: Long,
        type: MediaType,
        contextKeys: List<String>,
        sourceIds: List<Long>,
    ): Flow<List<CustomCategoryCount>>

    /** Everything, for Backup & Restore / re-sync snapshotting. */
    @Query("SELECT * FROM custom_category_members")
    suspend fun getAllOnce(): List<CustomCategoryMemberEntity>

    /** Stable customization keys for every live member of one custom category. Used before delete
     *  to restore items that had been moved out of their provider folder. */
    @Query(
        "SELECT CAST(c.sourceId AS TEXT) || ':' || COALESCE(c.remoteId, c.name) " +
            "FROM custom_category_members m INNER JOIN channels c ON m.mediaType = 'LIVE' AND m.itemId = c.id " +
            "WHERE m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = :contextKey " +
            "UNION ALL " +
            "SELECT CAST(mv.sourceId AS TEXT) || ':' || COALESCE(mv.remoteId, mv.name) " +
            "FROM custom_category_members m INNER JOIN movies mv ON m.mediaType = 'MOVIE' AND m.itemId = mv.id " +
            "WHERE m.profileId = :profileId AND m.mediaType = 'MOVIE' AND m.contextKey = :contextKey " +
            "UNION ALL " +
            "SELECT CAST(s.sourceId AS TEXT) || ':' || COALESCE(s.remoteId, s.name) " +
            "FROM custom_category_members m INNER JOIN series s ON m.mediaType = 'SERIES' AND m.itemId = s.id " +
            "WHERE m.profileId = :profileId AND m.mediaType = 'SERIES' AND m.contextKey = :contextKey",
    )
    suspend fun stableItemKeys(profileId: Long, contextKey: String): List<String>

    /**
     * Membership rows tied to one source, joined to stable content keys — the per-source re-sync
     * snapshot. Mirrors [ContentOrderDao.exportRowsForSource]: without this, a resync renumbered
     * the content ids and every Move the user had made was silently lost.
     */
    @Query(
        "SELECT m.profileId AS profileId, m.mediaType AS mediaType, m.itemId AS itemId, " +
            "m.contextKey AS contextKey, m.position AS position, " +
            "COALESCE(c.sourceId, mv.sourceId, s.sourceId) AS sourceId, " +
            "COALESCE(c.remoteId, mv.remoteId, s.remoteId) AS remoteId, " +
            "COALESCE(c.name, mv.name, s.name) AS name " +
            "FROM custom_category_members m " +
            "LEFT JOIN channels c ON m.mediaType = 'LIVE' AND m.itemId = c.id " +
            "LEFT JOIN movies mv ON m.mediaType = 'MOVIE' AND m.itemId = mv.id " +
            "LEFT JOIN series s ON m.mediaType = 'SERIES' AND m.itemId = s.id " +
            "WHERE c.sourceId = :sourceId OR mv.sourceId = :sourceId OR s.sourceId = :sourceId",
    )
    suspend fun exportRowsForSource(sourceId: Long): List<CustomCategoryMemberExportRow>

    /** Snapshot-scoped orphan drop, mirroring FavoriteDao.purgeSnapshotOrphan. */
    @Query(
        "DELETE FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId AND (" +
            "(:type = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(:type = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(:type = 'SERIES' AND itemId NOT IN (SELECT id FROM series))" +
            ")",
    )
    suspend fun purgeSnapshotOrphan(profileId: Long, type: MediaType, itemId: Long)

    /**
     * Drops membership rows whose content row no longer exists — content is clear-then-insert on
     * every sync, so an item's itemId goes stale. Called after a re-sync's relink
     * (UserDataResolver), mirroring FavoriteDao.purgeOrphans.
     */
    @Query(
        "DELETE FROM custom_category_members WHERE " +
            "(mediaType = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(mediaType = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(mediaType = 'SERIES' AND itemId NOT IN (SELECT id FROM series))",
    )
    suspend fun purgeOrphans()



    @Query("SELECT COUNT(*) FROM channels c LEFT JOIN custom_category_members m ON m.itemId = c.id AND m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = :contextKey WHERE c.sourceId IN (:sourceIds) AND (c.id IN (:itemIds) OR ((c.categoryId IN (:categoryIds) OR ((m.itemId IS NOT NULL OR EXISTS (SELECT 1 FROM categories g JOIN custom_category_live_rules r ON r.sourceId = g.sourceId AND r.referenceKey = CASE WHEN g.remoteId IS NULL THEN 'name:' || g.name ELSE 'id:' || g.remoteId END WHERE g.id = c.categoryId AND g.mediaType = 'LIVE' AND r.profileId = :profileId AND r.contextKey = :contextKey AND r.ruleType = 'GROUP' AND r.active = 1) OR EXISTS (SELECT 1 FROM custom_category_live_rules r WHERE r.profileId = :profileId AND r.contextKey = :contextKey AND r.sourceId = c.sourceId AND r.ruleType = 'CHANNEL' AND r.referenceKey = CASE WHEN c.remoteId IS NULL THEN 'name:' || c.name ELSE 'id:' || c.remoteId END AND r.active = 1 AND r.included = 1)) AND NOT EXISTS (SELECT 1 FROM custom_category_live_rules r WHERE r.profileId = :profileId AND r.contextKey = :contextKey AND r.sourceId = c.sourceId AND r.ruleType = 'EXCLUDE' AND r.referenceKey = CASE WHEN c.remoteId IS NULL THEN 'name:' || c.name ELSE 'id:' || c.remoteId END AND r.active = 1))) AND NOT EXISTS (SELECT 1 FROM custom_category_live_rules r WHERE r.profileId = :profileId AND r.contextKey = :contextKey AND r.sourceId = c.sourceId AND r.ruleType = 'EXCLUDE' AND r.referenceKey = CASE WHEN c.remoteId IS NULL THEN 'name:' || c.name ELSE 'id:' || c.remoteId END AND r.active = 1)))")
    suspend fun previewLiveAddition(profileId: Long, contextKey: String, sourceIds: List<Long>, categoryIds: List<Long>, itemIds: List<Long>): Int

    @Query("SELECT COUNT(*) FROM channels c LEFT JOIN custom_category_members m ON m.itemId = c.id AND m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = :contextKey WHERE c.sourceId IN (:sourceIds) AND (c.id IN (:itemIds) OR ((c.categoryId IN (:categoryIds) OR ((m.itemId IS NOT NULL OR EXISTS (SELECT 1 FROM categories g JOIN custom_category_live_rules r ON r.sourceId = g.sourceId AND r.referenceKey = CASE WHEN g.remoteId IS NULL THEN 'name:' || g.name ELSE 'id:' || g.remoteId END WHERE g.id = c.categoryId AND g.mediaType = 'LIVE' AND r.profileId = :profileId AND r.contextKey = :contextKey AND r.ruleType = 'GROUP' AND r.active = 1) OR EXISTS (SELECT 1 FROM custom_category_live_rules r WHERE r.profileId = :profileId AND r.contextKey = :contextKey AND r.sourceId = c.sourceId AND r.ruleType = 'CHANNEL' AND r.referenceKey = CASE WHEN c.remoteId IS NULL THEN 'name:' || c.name ELSE 'id:' || c.remoteId END AND r.active = 1 AND r.included = 1)) AND NOT EXISTS (SELECT 1 FROM custom_category_live_rules r WHERE r.profileId = :profileId AND r.contextKey = :contextKey AND r.sourceId = c.sourceId AND r.ruleType = 'EXCLUDE' AND r.referenceKey = CASE WHEN c.remoteId IS NULL THEN 'name:' || c.name ELSE 'id:' || c.remoteId END AND r.active = 1))) AND NOT EXISTS (SELECT 1 FROM custom_category_live_rules r WHERE r.profileId = :profileId AND r.contextKey = :contextKey AND r.sourceId = c.sourceId AND r.ruleType = 'EXCLUDE' AND r.referenceKey = CASE WHEN c.remoteId IS NULL THEN 'name:' || c.name ELSE 'id:' || c.remoteId END AND r.active = 1))) AND c.id NOT IN (:excludedIds)")
    fun observeLiveAdditionCount(profileId: Long, contextKey: String, sourceIds: List<Long>, categoryIds: List<Long>, itemIds: List<Long>, excludedIds: List<Long> = listOf(-1L)): Flow<Int>

    @Query("SELECT c.* FROM channels c LEFT JOIN custom_category_members m ON m.itemId = c.id AND m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = :contextKey WHERE c.sourceId IN (:sourceIds) AND (c.id IN (:itemIds) OR ((c.categoryId IN (:categoryIds) OR ((m.itemId IS NOT NULL OR EXISTS (SELECT 1 FROM categories g JOIN custom_category_live_rules r ON r.sourceId = g.sourceId AND r.referenceKey = CASE WHEN g.remoteId IS NULL THEN 'name:' || g.name ELSE 'id:' || g.remoteId END WHERE g.id = c.categoryId AND g.mediaType = 'LIVE' AND r.profileId = :profileId AND r.contextKey = :contextKey AND r.ruleType = 'GROUP' AND r.active = 1) OR EXISTS (SELECT 1 FROM custom_category_live_rules r WHERE r.profileId = :profileId AND r.contextKey = :contextKey AND r.sourceId = c.sourceId AND r.ruleType = 'CHANNEL' AND r.referenceKey = CASE WHEN c.remoteId IS NULL THEN 'name:' || c.name ELSE 'id:' || c.remoteId END AND r.active = 1 AND r.included = 1)) AND NOT EXISTS (SELECT 1 FROM custom_category_live_rules r WHERE r.profileId = :profileId AND r.contextKey = :contextKey AND r.sourceId = c.sourceId AND r.ruleType = 'EXCLUDE' AND r.referenceKey = CASE WHEN c.remoteId IS NULL THEN 'name:' || c.name ELSE 'id:' || c.remoteId END AND r.active = 1))) AND NOT EXISTS (SELECT 1 FROM custom_category_live_rules r WHERE r.profileId = :profileId AND r.contextKey = :contextKey AND r.sourceId = c.sourceId AND r.ruleType = 'EXCLUDE' AND r.referenceKey = CASE WHEN c.remoteId IS NULL THEN 'name:' || c.name ELSE 'id:' || c.remoteId END AND r.active = 1))) ORDER BY c.sourceId, c.sortOrder, c.name")
    fun pagingLiveAddition(profileId: Long, contextKey: String, sourceIds: List<Long>, categoryIds: List<Long>, itemIds: List<Long>): PagingSource<Int, ChannelEntity>

    @Transaction
    suspend fun addLiveSelection(profileId: Long, contextKey: String, categoryIds: List<Long>, itemIds: List<Long>, excludedIds: List<Long>) {
        addProviderGroups(profileId, contextKey, categoryIds)
        addLiveChannels(profileId, contextKey, itemIds)
        excludedIds.distinct().forEach { setLiveChannelOverride(profileId, contextKey, it, included = false) }
    }

    @Query("SELECT c.id AS itemId, CAST(c.sourceId AS TEXT) || ':' || COALESCE(c.remoteId, c.name) AS stableKey FROM channels c LEFT JOIN custom_category_members m ON m.itemId = c.id AND m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = :contextKey LEFT JOIN content_order o ON o.itemId = c.id AND o.profileId = :profileId AND o.mediaType = 'LIVE' AND o.contextKey = :contextKey WHERE c.sourceId IN (:sourceIds) AND ((:categoryId IS NOT NULL AND c.categoryId = :categoryId) OR (:categoryId IS NULL AND ((m.itemId IS NOT NULL OR EXISTS (SELECT 1 FROM categories g JOIN custom_category_live_rules r ON r.sourceId = g.sourceId AND r.referenceKey = CASE WHEN g.remoteId IS NULL THEN 'name:' || g.name ELSE 'id:' || g.remoteId END WHERE g.id = c.categoryId AND g.mediaType = 'LIVE' AND r.profileId = :profileId AND r.contextKey = :contextKey AND r.ruleType = 'GROUP' AND r.active = 1) OR EXISTS (SELECT 1 FROM custom_category_live_rules r WHERE r.profileId = :profileId AND r.contextKey = :contextKey AND r.sourceId = c.sourceId AND r.ruleType = 'CHANNEL' AND r.referenceKey = CASE WHEN c.remoteId IS NULL THEN 'name:' || c.name ELSE 'id:' || c.remoteId END AND r.active = 1 AND r.included = 1)) AND NOT EXISTS (SELECT 1 FROM custom_category_live_rules r WHERE r.profileId = :profileId AND r.contextKey = :contextKey AND r.sourceId = c.sourceId AND r.ruleType = 'EXCLUDE' AND r.referenceKey = CASE WHEN c.remoteId IS NULL THEN 'name:' || c.name ELSE 'id:' || c.remoteId END AND r.active = 1)))) ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, m.position, c.sourceId, c.sortOrder, c.name")
    suspend fun liveSelectionKeys(profileId: Long, contextKey: String, sourceIds: List<Long>, categoryId: Long?): List<CustomCategorySelectionRow>

    @Query("SELECT categoryId, COUNT(*) AS itemCount FROM channels WHERE sourceId IN (:sourceIds) AND categoryId IS NOT NULL GROUP BY categoryId")
    fun observeLiveProviderCounts(sourceIds: List<Long>): Flow<List<CategoryItemCount>>

    @Query("SELECT * FROM custom_category_live_rules")
    suspend fun allLiveRules(): List<CustomCategoryLiveRuleEntity>

    @Query("SELECT r.*, CASE WHEN r.ruleType = 'GROUP' THEN EXISTS(SELECT 1 FROM categories g WHERE g.sourceId = r.sourceId AND g.mediaType = 'LIVE' AND r.referenceKey = CASE WHEN g.remoteId IS NULL THEN 'name:' || g.name ELSE 'id:' || g.remoteId END) ELSE EXISTS(SELECT 1 FROM channels c WHERE c.sourceId = r.sourceId AND r.referenceKey = CASE WHEN c.remoteId IS NULL THEN 'name:' || c.name ELSE 'id:' || c.remoteId END) END AS available FROM custom_category_live_rules r WHERE r.profileId = :profileId AND r.contextKey = :contextKey AND r.active = 1 ORDER BY r.ruleType, r.sourceId, r.displayName")
    fun observeLiveRuleAvailability(profileId: Long, contextKey: String): Flow<List<LiveCategoryRuleStatus>>

    @Query("SELECT * FROM custom_category_live_rules WHERE profileId = :profileId AND contextKey = :contextKey AND active = 1 ORDER BY ruleType, sourceId, displayName")
    fun observeLiveRules(profileId: Long, contextKey: String): Flow<List<CustomCategoryLiveRuleEntity>>

    @Query("SELECT * FROM custom_category_live_rules WHERE profileId = :profileId AND contextKey = :contextKey AND sourceId = :sourceId AND ruleType = :ruleType AND referenceKey = :referenceKey")
    suspend fun findLiveRule(profileId: Long, contextKey: String, sourceId: Long, ruleType: String, referenceKey: String): CustomCategoryLiveRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLiveRules(rows: List<CustomCategoryLiveRuleEntity>)

    @Query("SELECT * FROM channels WHERE id = :itemId")
    suspend fun liveChannelForRule(itemId: Long): ChannelEntity?

    @Query("SELECT * FROM categories WHERE id IN (:categoryIds) AND mediaType = 'LIVE'")
    suspend fun groupsForRules(categoryIds: List<Long>): List<tv.own.owntv.core.database.entity.CategoryEntity>

    @Query("SELECT COUNT(*) FROM channels WHERE categoryId IN (:categoryIds)")
    suspend fun previewGroupCount(categoryIds: List<Long>): Int

    @Transaction
    suspend fun saveLiveRule(row: CustomCategoryLiveRuleEntity) {
        val old = findLiveRule(row.profileId, row.contextKey, row.sourceId, row.ruleType, row.referenceKey)
        insertLiveRules(listOf(row.copy(updatedAt = maxOf(System.currentTimeMillis(), (old?.updatedAt ?: 0) + 1))))
    }

    @Transaction
    suspend fun addProviderGroups(profileId: Long, contextKey: String, categoryIds: List<Long>) {
        require(contextKey.startsWith("custom:"))
        val groups = groupsForRules(categoryIds.distinct())
        require(groups.size == categoryIds.distinct().size) { "Provider categories changed; select them again." }
        groups.forEach { g -> saveLiveRule(CustomCategoryLiveRuleEntity(profileId, contextKey, g.sourceId, "GROUP", providerReference(g.remoteId, g.name), g.name)) }
    }

    @Transaction
    suspend fun setLiveChannelOverride(profileId: Long, contextKey: String, itemId: Long, included: Boolean) {
        val c = requireNotNull(liveChannelForRule(itemId)) { "Channel changed; select it again." }
        val ref = providerReference(c.remoteId, c.name)
        if (included) {
            saveLiveRule(CustomCategoryLiveRuleEntity(profileId, contextKey, c.sourceId, "CHANNEL", ref, c.name))
            findLiveRule(profileId, contextKey, c.sourceId, "EXCLUDE", ref)?.let { deactivateLiveRule(it) }
        } else {
            saveLiveRule(CustomCategoryLiveRuleEntity(profileId, contextKey, c.sourceId, "EXCLUDE", ref, c.name, included = false))
        }
    }

    @Transaction
    suspend fun addLiveChannels(profileId: Long, contextKey: String, itemIds: List<Long>) {
        itemIds.distinct().forEach { appendItem(profileId, MediaType.LIVE, contextKey, it) }
    }

    @Transaction
    suspend fun deactivateLiveRule(row: CustomCategoryLiveRuleEntity) = saveLiveRule(row.copy(active = false))

    @Transaction
    suspend fun deactivateLiveContext(profileId: Long, contextKey: String) {
        allLiveRules().filter { it.profileId == profileId && it.contextKey == contextKey && it.active }
            .forEach { deactivateLiveRule(it) }
    }

    /** Newer intent wins; a stale backup must not reinstate a removed group or exclusion. */
    @Transaction
    suspend fun mergeLiveRules(rows: List<CustomCategoryLiveRuleEntity>) {
        rows.forEach { r ->
            val old = findLiveRule(r.profileId, r.contextKey, r.sourceId, r.ruleType, r.referenceKey)
            if (old == null || r.updatedAt > old.updatedAt) insertLiveRules(listOf(r))
        }
    }

    // --- browse side: paging / count / search (mirrors the ChannelDao manual-order joins) ---

    /**
     * Paged content of a custom category in rail order. Membership defines the SET (INNER JOIN);
     * the user's manual reorder within the category rides content_order with the same contextKey
     * (LEFT JOIN, matching [ContentOrderDao]'s position semantics); items without an explicit
     * order row fall back to membership position (insertion order).
     */
    @Query(
        "SELECT c.* FROM channels c " +
            "LEFT JOIN custom_category_members m ON m.itemId = c.id AND m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = c.id AND o.profileId = :profileId AND o.mediaType = 'LIVE' AND o.contextKey = :contextKey " +
            "WHERE c.sourceId IN (:sourceIds) AND " + LIVE_RULE_MEMBERSHIP + " " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, m.position, c.sourceId, c.sortOrder, c.name",
    )
    fun pagingChannels(profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    /** A–Z variant of [pagingChannels]: a manually placed channel keeps its saved position, the rest
     *  sort by name instead of falling back to membership/provider order — the same "manual order
     *  wins, the rest goes A–Z" convention the folder list itself uses (see `alphaRest`). */
    @Query(
        "SELECT c.* FROM channels c " +
            "LEFT JOIN custom_category_members m ON m.itemId = c.id AND m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = c.id AND o.profileId = :profileId AND o.mediaType = 'LIVE' AND o.contextKey = :contextKey " +
            "WHERE c.sourceId IN (:sourceIds) AND " + LIVE_RULE_MEMBERSHIP + " " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, c.name",
    )
    fun pagingChannelsAlpha(profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    @Query(
        "SELECT mv.* FROM movies mv " +
            "INNER JOIN custom_category_members m ON m.itemId = mv.id AND m.profileId = :profileId AND m.mediaType = 'MOVIE' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = mv.id AND o.profileId = :profileId AND o.mediaType = 'MOVIE' AND o.contextKey = :contextKey " +
            "WHERE mv.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, m.position, mv.sortOrder, mv.name",
    )
    fun pagingMovies(profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, MovieEntity>

    /** A–Z variant of [pagingMovies] — see [pagingChannelsAlpha]. */
    @Query(
        "SELECT mv.* FROM movies mv " +
            "INNER JOIN custom_category_members m ON m.itemId = mv.id AND m.profileId = :profileId AND m.mediaType = 'MOVIE' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = mv.id AND o.profileId = :profileId AND o.mediaType = 'MOVIE' AND o.contextKey = :contextKey " +
            "WHERE mv.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, mv.name",
    )
    fun pagingMoviesAlpha(profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, MovieEntity>

    @Query(
        "SELECT s.* FROM series s " +
            "INNER JOIN custom_category_members m ON m.itemId = s.id AND m.profileId = :profileId AND m.mediaType = 'SERIES' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = s.id AND o.profileId = :profileId AND o.mediaType = 'SERIES' AND o.contextKey = :contextKey " +
            "WHERE s.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, m.position, s.sortOrder, s.name",
    )
    fun pagingSeries(profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, SeriesEntity>

    /** A–Z variant of [pagingSeries] — see [pagingChannelsAlpha]. */
    @Query(
        "SELECT s.* FROM series s " +
            "INNER JOIN custom_category_members m ON m.itemId = s.id AND m.profileId = :profileId AND m.mediaType = 'SERIES' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = s.id AND o.profileId = :profileId AND o.mediaType = 'SERIES' AND o.contextKey = :contextKey " +
            "WHERE s.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, s.name",
    )
    fun pagingSeriesAlpha(profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, SeriesEntity>

    /** Live count of a custom category's in-scope items — the rail count badge. */
    @Query("SELECT CASE WHEN :type = 'LIVE' THEN (SELECT COUNT(*) FROM channels c LEFT JOIN custom_category_members m ON m.itemId = c.id AND m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = :contextKey WHERE c.sourceId IN (:sourceIds) AND " + LIVE_RULE_MEMBERSHIP + ") ELSE (SELECT COUNT(*) FROM custom_category_members m LEFT JOIN movies mv ON m.mediaType = 'MOVIE' AND m.itemId = mv.id LEFT JOIN series s ON m.mediaType = 'SERIES' AND m.itemId = s.id WHERE m.profileId = :profileId AND m.mediaType = :type AND m.contextKey = :contextKey AND COALESCE(mv.sourceId, s.sourceId) IN (:sourceIds)) END")
    fun countMembers(profileId: Long, type: MediaType, contextKey: String, sourceIds: List<Long>): Flow<Int>

    /** Content ids in one custom category. The Guide already holds its bounded channel window in
     *  memory, so this small projection lets it apply a custom-category filter without another
     *  content-table load. */
    @Query("SELECT c.id FROM channels c LEFT JOIN custom_category_members m ON m.itemId = c.id AND m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = :contextKey WHERE :type = 'LIVE' AND " + LIVE_RULE_MEMBERSHIP + " UNION SELECT itemId FROM custom_category_members WHERE :type != 'LIVE' AND profileId = :profileId AND mediaType = :type AND contextKey = :contextKey")
    suspend fun itemIds(profileId: Long, type: MediaType, contextKey: String): List<Long>

    /** Bounded snapshot of a custom category in rail order — the Move session's in-memory reorder. */
    @Query(
        "SELECT c.* FROM channels c " +
            "LEFT JOIN custom_category_members m ON m.itemId = c.id AND m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = c.id AND o.profileId = :profileId AND o.mediaType = 'LIVE' AND o.contextKey = :contextKey " +
            "WHERE c.sourceId IN (:sourceIds) AND " + LIVE_RULE_MEMBERSHIP + " " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, m.position, c.sourceId, c.sortOrder, c.name LIMIT :limit",
    )
    suspend fun snapshotChannels(profileId: Long, contextKey: String, sourceIds: List<Long>, limit: Int): List<ChannelEntity>

    @Query(
        "SELECT mv.* FROM movies mv " +
            "INNER JOIN custom_category_members m ON m.itemId = mv.id AND m.profileId = :profileId AND m.mediaType = 'MOVIE' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = mv.id AND o.profileId = :profileId AND o.mediaType = 'MOVIE' AND o.contextKey = :contextKey " +
            "WHERE mv.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, m.position, mv.sortOrder, mv.name LIMIT :limit",
    )
    suspend fun snapshotMovies(profileId: Long, contextKey: String, sourceIds: List<Long>, limit: Int): List<MovieEntity>

    @Query(
        "SELECT s.* FROM series s " +
            "INNER JOIN custom_category_members m ON m.itemId = s.id AND m.profileId = :profileId AND m.mediaType = 'SERIES' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = s.id AND o.profileId = :profileId AND o.mediaType = 'SERIES' AND o.contextKey = :contextKey " +
            "WHERE s.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, m.position, s.sortOrder, s.name LIMIT :limit",
    )
    suspend fun snapshotSeries(profileId: Long, contextKey: String, sourceIds: List<Long>, limit: Int): List<SeriesEntity>

    /** In-category search (mirrors ChannelDao.searchInCategory), for custom categories. */
    @Query(
        "SELECT c.* FROM channels c " +
            "LEFT JOIN custom_category_members m ON m.itemId = c.id AND m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = :contextKey " +
            "WHERE c.sourceId IN (:sourceIds) AND " + LIVE_RULE_MEMBERSHIP + " AND (c.name LIKE '%' || :query || '%' OR c.id IN (SELECT rowid FROM channels_fts WHERE channels_fts MATCH :ftsQuery)) " +
            "ORDER BY m.position, c.sortOrder, c.name",
    )
    fun searchChannelsFts(query: String, ftsQuery: String, profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    fun searchChannels(query: String, profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, ChannelEntity> =
        searchChannelsFts(query, customCategorySearchQuery(query), profileId, contextKey, sourceIds)

    @Query(
        "SELECT mv.* FROM movies mv " +
            "INNER JOIN custom_category_members m ON m.itemId = mv.id AND m.profileId = :profileId AND m.mediaType = 'MOVIE' AND m.contextKey = :contextKey " +
            "WHERE mv.sourceId IN (:sourceIds) AND mv.name LIKE '%' || :query || '%' " +
            "ORDER BY m.position, mv.sortOrder, mv.name",
    )
    fun searchMovies(query: String, profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, MovieEntity>

    @Query(
        "SELECT s.* FROM series s " +
            "INNER JOIN custom_category_members m ON m.itemId = s.id AND m.profileId = :profileId AND m.mediaType = 'SERIES' AND m.contextKey = :contextKey " +
            "WHERE s.sourceId IN (:sourceIds) AND s.name LIKE '%' || :query || '%' " +
            "ORDER BY m.position, s.sortOrder, s.name",
    )
    fun searchSeries(query: String, profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, SeriesEntity>
}

/**
 * One membership row joined to its content row's stable identity, for the per-source re-sync
 * snapshot ([CustomCategoryDao.exportRowsForSource]). Mirrors [ContentOrderExportRow]; the shape is
 * identical because the table is modeled on content_order.
 */
/** One custom category's member count, for the Move to… dialog badges. */
data class CustomCategoryCount(val contextKey: String, val count: Int)

data class CustomCategoryMemberExportRow(
    val profileId: Long,
    val mediaType: MediaType,
    val itemId: Long,
    val contextKey: String,
    val position: Int,
    val sourceId: Long,
    val remoteId: String?,
    val name: String?,
)

data class CustomCategorySelectionRow(val itemId: Long, val stableKey: String)

data class LiveCategoryRuleStatus(@androidx.room.Embedded val rule: CustomCategoryLiveRuleEntity, val available: Boolean)
