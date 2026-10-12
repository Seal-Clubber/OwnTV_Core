package tv.own.owntv.core.customize

import org.junit.Assert.*
import org.junit.Test

class CustomCategoryDeletionRestoreTest {
    @Test fun staleBackupCannotResurrectDeletedCategoryOrItsPins() {
        val local = SectionCustomizations(deletedCustomCategories = setOf("custom:deleted"))
        val backup = SectionCustomizations(
            customCategories = listOf(CustomCategory("custom:deleted", "Old"), CustomCategory("custom:kept", "Kept")),
            hiddenCategories = setOf("custom:deleted", "provider:1"),
            categoryNames = mapOf("custom:deleted" to "Renamed"), categoryOrder = listOf("custom:deleted", "custom:kept"),
        )
        val merged = mergeRestoredCustomCategories(local, backup)
        assertEquals(listOf("custom:kept"), merged.customCategories.map { it.id })
        assertEquals(setOf("provider:1"), merged.hiddenCategories)
        assertTrue(merged.categoryNames.isEmpty())
        assertEquals(listOf("custom:kept"), merged.categoryOrder)
        assertEquals(local.deletedCustomCategories, merged.deletedCustomCategories)
        assertFalse(local.isEmpty)
    }
    @Test fun incomingDeletionWinsAndOtherBackupChoicesStillRestore() {
        val local = SectionCustomizations(customCategories = listOf(CustomCategory("custom:deleted", "Old")))
        val incoming = SectionCustomizations(deletedCustomCategories = setOf("custom:deleted"), itemNames = mapOf("10:1" to "New name"))
        val merged = mergeRestoredCustomCategories(local, incoming)
        assertTrue(merged.customCategories.isEmpty())
        assertEquals(incoming.itemNames, merged.itemNames)
        assertEquals(merged, mergeRestoredCustomCategories(merged, incoming))
    }
}
