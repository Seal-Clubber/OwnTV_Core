package tv.own.owntv.core.database

import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.own.owntv.core.database.entity.*
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType

@RunWith(AndroidJUnit4::class)
class LiveCategoryRulesDaoTest {
    private lateinit var db: OwnTVDatabase
    private val ctx = "custom:turkish"
    @Before fun setup() = runBlocking {
        db = ownTVTestDatabase()
        db.profileDao().insert(ProfileEntity(id = 1, name = "Primary", avatarColor = 0))
        db.profileDao().insert(ProfileEntity(id = 2, name = "Other", avatarColor = 0))
        for (sid in listOf(10L, 11L)) db.sourceDao().insert(SourceEntity(id = sid, name = "Provider $sid", type = SourceType.XTREAM, url = "https://example.test/$sid"))
        db.categoryDao().upsertAll(listOf(group(20, 10, "news"), group(21, 10, "sports"), group(22, 11, "news")))
        db.channelDao().upsertAll(listOf(channel(30, 10, 20, "one", "İstanbul"), channel(31, 10, 21, "two", "één"),
            channel(32, 11, 22, "one", "İstanbul"), channel(33, 10, 21, "three", "العربية")))
    }
    @After fun teardown() { db.close() }
    private fun group(id: Long, sid: Long, ref: String) = CategoryEntity(id, sid, MediaType.LIVE, "Same name", ref)
    private fun channel(id: Long, sid: Long, group: Long, ref: String, name: String) =
        ChannelEntity(id = id, sourceId = sid, categoryId = group, remoteId = ref, name = name, streamUrl = "https://example.test/$ref")
    private suspend fun ids(pid: Long = 1) = db.customCategoryDao().snapshotChannels(pid, ctx, listOf(10, 11), 100).map { it.id }.toSet()

    @Test fun unionDeduplicatesAndExclusionWinsWithProfileIsolation() = runBlocking {
        val dao = db.customCategoryDao()
        dao.addProviderGroups(1, ctx, listOf(20, 21, 22, 20))
        dao.addLiveChannels(1, ctx, listOf(30, 30))
        assertEquals(setOf(30L, 31L, 32L, 33L), ids())
        assertTrue(ids(2).isEmpty())
        dao.setLiveChannelOverride(1, ctx, 30, false)
        assertEquals(setOf(31L, 32L, 33L), ids())
        assertEquals(3, dao.countMembers(1, MediaType.LIVE, ctx, listOf(10, 11)).first())
        dao.deactivateLiveRule(dao.findLiveRule(1, ctx, 10, "EXCLUDE", "id:one")!!)
        assertTrue(30L in ids())
        dao.deactivateLiveRule(dao.findLiveRule(1, ctx, 10, "GROUP", "id:news")!!)
        assertTrue(30L in ids()) // explicit inclusion survives removal of its group
    }
    @Test fun refreshChangesRowIdsAndNamesButRetainsRulesAndExclusions() = runBlocking {
        val dao = db.customCategoryDao()
        dao.addProviderGroups(1, ctx, listOf(20))
        dao.setLiveChannelOverride(1, ctx, 30, false)
        db.channelDao().clearSource(10)
        db.categoryDao().clear(10, MediaType.LIVE)
        assertTrue(ids().isEmpty())
        db.categoryDao().upsertAll(listOf(group(200, 10, "news").copy(name = "Renamed")))
        db.channelDao().upsertAll(listOf(channel(300, 10, 200, "one", "Renamed channel"), channel(301, 10, 200, "new", "New channel")))
        assertEquals(setOf(301L), ids())
        assertEquals(2, dao.observeLiveRules(1, ctx).first().size)
    }
    @Test fun staleRestoreCannotReinstateRemovedRulesAndFailedSelectionIsAtomic() = runBlocking {
        val dao = db.customCategoryDao()
        dao.addProviderGroups(1, ctx, listOf(20))
        val before = dao.findLiveRule(1, ctx, 10, "GROUP", "id:news")!!
        dao.deactivateLiveRule(before)
        dao.mergeLiveRules(listOf(before))
        assertTrue(ids().isEmpty())
        val result = runCatching { dao.addLiveSelection(1, ctx, listOf(21), listOf(9999), emptyList()) }
        assertTrue(result.isFailure)
        assertNull(dao.findLiveRule(1, ctx, 10, "GROUP", "id:sports"))
    }
    @Test fun unicodeSearchStaysWithinCategory() = runBlocking {
        val dao = db.customCategoryDao()
        dao.addProviderGroups(1, ctx, listOf(20))
        val result = dao.searchChannels("istanbul", 1, ctx, listOf(10, 11)).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = false))
        val page = result as PagingSource.LoadResult.Page
        assertEquals(listOf(30L), page.data.map { it.id })
    }
}
