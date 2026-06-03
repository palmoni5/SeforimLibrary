package io.github.kdroidfilter.seforimlibrary.sefariasqlite

import io.github.kdroidfilter.seforimlibrary.core.models.ConnectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SefariaLinksImporterTest {
    @Test
    fun higherPriorityBookIsTreatedAsPrimary() {
        val sourceMeta = BookMeta(isBaseBook = true, categoryLevel = 2, priorityRank = 5)
        val targetMeta = BookMeta(isBaseBook = true, categoryLevel = 1, priorityRank = 20)

        val (forward, reverse) = resolveDirectionalConnectionTypesForMeta(
            baseType = ConnectionType.COMMENTARY,
            sourceBookId = 1L,
            targetBookId = 2L,
            sourceMeta = sourceMeta,
            targetMeta = targetMeta
        )

        assertEquals(ConnectionType.COMMENTARY, forward)
        assertEquals(ConnectionType.SOURCE, reverse)
    }

    @Test
    fun lowerPriorityBookBecomesSecondary() {
        val sourceMeta = BookMeta(isBaseBook = true, categoryLevel = 2, priorityRank = 50)
        val targetMeta = BookMeta(isBaseBook = true, categoryLevel = 1, priorityRank = 10)

        val (forward, reverse) = resolveDirectionalConnectionTypesForMeta(
            baseType = ConnectionType.COMMENTARY,
            sourceBookId = 1L,
            targetBookId = 2L,
            sourceMeta = sourceMeta,
            targetMeta = targetMeta
        )

        assertEquals(ConnectionType.SOURCE, forward)
        assertEquals(ConnectionType.COMMENTARY, reverse)
    }

    @Test
    fun twoCanonicalBaseBooksBecomeOther() {
        // למשל משנה תורה (הלכה) ↔ תלמוד ירושלמי — הקבלה/ציטוט, לא פירוש.
        val sourceMeta = BookMeta(
            isBaseBook = true, categoryLevel = 2, priorityRank = 5,
            isCanonicalBaseBook = true
        )
        val targetMeta = BookMeta(
            isBaseBook = true, categoryLevel = 1, priorityRank = 20,
            isCanonicalBaseBook = true
        )

        val (forward, reverse) = resolveDirectionalConnectionTypesForMeta(
            baseType = ConnectionType.COMMENTARY,
            sourceBookId = 1L,
            targetBookId = 2L,
            sourceMeta = sourceMeta,
            targetMeta = targetMeta
        )

        assertEquals(ConnectionType.OTHER, forward)
        assertEquals(ConnectionType.OTHER, reverse)
    }

    @Test
    fun realCommentatorOnCanonicalBaseStaysCommentary() {
        // ספר-יסוד קנוני (source) ומפרש אמיתי שאינו base (target) — נשאר פירוש.
        val sourceMeta = BookMeta(
            isBaseBook = true, categoryLevel = 1, priorityRank = 10,
            isCanonicalBaseBook = true
        )
        val targetMeta = BookMeta(
            isBaseBook = false, categoryLevel = 3, priorityRank = null,
            isCanonicalBaseBook = false
        )

        val (forward, reverse) = resolveDirectionalConnectionTypesForMeta(
            baseType = ConnectionType.COMMENTARY,
            sourceBookId = 1L,
            targetBookId = 2L,
            sourceMeta = sourceMeta,
            targetMeta = targetMeta
        )

        assertEquals(ConnectionType.COMMENTARY, forward)
        assertEquals(ConnectionType.SOURCE, reverse)
    }

    // --- isCanonicalBaseCategory ---

    @Test
    fun tanakhIsCanonicalRegardlessOfGershayim() {
        // P1: נרמול גרשיים — כל הווריאציות של "תנ״ך" צריכות להיחשב קנוניות.
        assertTrue(isCanonicalBaseCategory(listOf("אוצריא", "תנך", "תורה")))
        assertTrue(isCanonicalBaseCategory(listOf("אוצריא", "תנ\"ך", "תורה")))
        assertTrue(isCanonicalBaseCategory(listOf("אוצריא", "תנ״ך", "תורה")))
    }

    @Test
    fun rambamAndYerushalmiAreCanonical() {
        assertTrue(isCanonicalBaseCategory(listOf("אוצריא", "הלכה", "משנה תורה")))
        assertTrue(
            isCanonicalBaseCategory(listOf("אוצריא", "תלמוד ירושלמי", "סדר זרעים"))
        )
    }

    @Test
    fun midrashIsNotCanonicalEvenUnderHalacha() {
        // P2: מדרשי-הלכה (ספרא/מכילתא) ומפרשיהם יושבים תחת "מדרש/הלכה" —
        // אסור שייחשבו קנוניים, אחרת פירוש אמיתי יהפוך ל-OTHER.
        assertFalse(isCanonicalBaseCategory(listOf("אוצריא", "מדרש", "הלכה")))
        assertFalse(isCanonicalBaseCategory(listOf("אוצריא", "מדרש", "אגדה")))
    }
}
