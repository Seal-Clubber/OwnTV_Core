package tv.own.owntv.core.customize

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import tv.own.owntv.core.database.entity.CustomCategoryLiveRuleEntity

class LiveCategoryRulesTest {
    @Test fun backupRoundTripRemapsOwnersAndRetainsDeletionIntent() {
        val rows = listOf(
            CustomCategoryLiveRuleEntity(1, "custom:tr", 7, "GROUP", "id:42", "Türkçe", updatedAt = 100),
            CustomCategoryLiveRuleEntity(1, "custom:tr", 7, "EXCLUDE", "id:9", "İstanbul", included = false, updatedAt = 101),
            CustomCategoryLiveRuleEntity(1, "custom:tr", 8, "CHANNEL", "name:العربية", "العربية", active = false, updatedAt = 102),
        )
        assertEquals(rows.map { it.copy(profileId = 12, sourceId = if (it.sourceId == 7L) 70 else 80) },
            rows.toBackupRules().restoreLiveRules(mapOf(1L to 12L), mapOf(7L to 70L, 8L to 80L)))
    }
    @Test fun unmappedOwnersAndMalformedRulesNeverAttachToLocalRows() {
        val row = CustomCategoryLiveRuleEntity(1, "custom:tr", 7, "GROUP", "id:42", "News", updatedAt = 100)
        assertTrue(listOf(row).toBackupRules().restoreLiveRules(emptyMap(), mapOf(7L to 7L)).isEmpty())
        assertTrue(listOf(row).toBackupRules().restoreLiveRules(mapOf(1L to 1L), emptyMap()).isEmpty())
        val invalid = JSONArray().put("not an object")
            .put(listOf(row.copy(contextKey = "7:42")).toBackupRules().get(0))
            .put(listOf(row.copy(ruleType = "UNKNOWN")).toBackupRules().get(0))
            .put(listOf(row.copy(referenceKey = "42")).toBackupRules().get(0))
        assertTrue(invalid.restoreLiveRules(mapOf(1L to 1L), mapOf(7L to 7L)).isEmpty())
    }
    @Test fun providerIdsDoNotCollideWithFallbackNames() {
        assertEquals("id:42", providerReference("42", "News"))
        assertEquals("name:42", providerReference(null, "42"))
        assertEquals("id:42", providerReference("42", "Renamed News"))
    }
    @Test fun unicodeWordsArePreservedAndOperatorsAreQuoted() {
        assertEquals("\"İstanbul\"* AND \"nieuws\"* AND \"één\"* AND \"العربية\"*", customCategorySearchQuery("İstanbul nieuws één العربية"))
        assertEquals("\"OR\"* AND \"DROP\"* AND \"TABLE\"*", customCategorySearchQuery("\" OR * - DROP TABLE;"))
        assertEquals("\"__no_search_token__\"", customCategorySearchQuery("\"*-"))
    }
}
