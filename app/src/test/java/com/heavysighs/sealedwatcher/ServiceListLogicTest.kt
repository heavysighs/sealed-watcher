package com.heavysighs.sealedwatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceListLogicTest {

    private val curboxFull = "neth.iecal.curbox/neth.iecal.curbox.CurboxAccessibilityService"
    private val curboxShort = "neth.iecal.curbox/.CurboxAccessibilityService"
    private val other = "com.example.other/com.example.other.OtherService"

    @Test
    fun normalize_shortForm_expands() {
        assertEquals(curboxFull, normalizeComponent(curboxShort))
    }

    @Test
    fun normalize_fullForm_unchanged() {
        assertEquals(curboxFull, normalizeComponent(curboxFull))
    }

    @Test
    fun normalize_classInAnotherPackage_unchanged() {
        val id = "com.example.app/org.lib.Service"
        assertEquals(id, normalizeComponent(id))
    }

    @Test
    fun normalize_noClass_unchanged() {
        assertEquals("com.example.app", normalizeComponent("com.example.app"))
    }

    @Test
    fun parse_nullAndEmpty() {
        assertTrue(parseTokens(null).isEmpty())
        assertTrue(parseTokens("").isEmpty())
        assertTrue(parseTokens(":").isEmpty())
    }

    @Test
    fun parse_splitsOnColon() {
        assertEquals(listOf(curboxFull, other), parseTokens("$curboxFull:$other"))
    }

    @Test
    fun contains_matchesAcrossForms() {
        // Setting written by the system (full form); id from the
        // installed-services list (short form) — must still match.
        assertTrue(containsService(listOf(curboxFull, other), curboxShort))
        assertTrue(containsService(listOf(curboxShort, other), curboxFull))
    }

    @Test
    fun contains_rejectsDifferentServiceSamePackage() {
        val different = "neth.iecal.curbox/neth.iecal.curbox.SomeOtherService"
        assertFalse(containsService(listOf(different), curboxShort))
    }

    @Test
    fun withService_appendsWhenMissing() {
        assertEquals(
            listOf(other, curboxShort),
            withService(listOf(other), curboxShort)
        )
    }

    @Test
    fun withService_noDuplicateAcrossForms() {
        assertEquals(
            listOf(curboxFull),
            withService(listOf(curboxFull), curboxShort)
        )
    }

    @Test
    fun withoutService_removesOnlyTarget() {
        assertEquals(
            listOf(other),
            withoutService(listOf(curboxFull, other), curboxShort)
        )
    }

    @Test
    fun roundTrip_joinParse() {
        val tokens = listOf(curboxFull, other)
        assertEquals(tokens, parseTokens(joinTokens(tokens)))
    }
}
