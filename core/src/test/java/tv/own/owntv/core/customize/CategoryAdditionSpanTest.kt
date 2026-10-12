package tv.own.owntv.core.customize

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class CategoryAdditionSpanTest {
    @Test fun existingMoveSelectionCanBeReadWithoutReorderingOrClearingIt() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            val rows = listOf("tr:general", "tr:sports", "tr:news", "tr:cinema", "nl:general")
            val span = SpanSelector({ rows }, { it }, scope)
            span.beginMoveRange(rows[0])
            span.setEndKey(rows[3])
            assertEquals(rows.take(4), span.keysInRange(rows[3]))
            assertEquals(SpanSelector.Mode.MOVE, span.mode.value)
            assertEquals(rows[0], span.anchorKey.value)
            assertEquals(rows[3], span.endKey.value)
            assertEquals(rows.take(4), span.keysInRange(rows[3])) // opening/cancelling a preview changes no selection
        } finally { scope.cancel() }
    }
    @Test fun reverseEndpointsIncludeEveryRowAcrossPages() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            val rows = (0..8000).map { "channel:$it" }
            val span = SpanSelector({ rows }, { it }, scope)
            span.beginMoveRange(rows[7000])
            assertEquals(rows.subList(10, 7001), span.keysInRange(rows[10]))
        } finally { scope.cancel() }
    }
}
