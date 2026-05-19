package io.microtask.microtaskplugin.ui

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTaskStatusTest {

    private val gson = Gson()

    @Test
    fun `prefers taskStatus over status`() {
        val raw = """{"taskId":"abc","taskStatus":"RUNNING","status":"OLD"}"""
        val result = formatTaskStatus(raw, gson)
        assertTrue(result.contains("status: RUNNING"))
        assertFalse(result.contains("status: OLD"))
    }

    @Test
    fun `falls back to status when taskStatus missing`() {
        val raw = """{"taskId":"abc","status":"PENDING"}"""
        val result = formatTaskStatus(raw, gson)
        assertTrue(result.contains("status: PENDING"))
    }

    @Test
    fun `includes executionType when present`() {
        val raw = """{"taskId":"abc","executionType":"stateless"}"""
        val result = formatTaskStatus(raw, gson)
        assertTrue(result.contains("executionType: stateless"))
    }

    @Test
    fun `returns raw when JSON cannot be parsed`() {
        val raw = "not json"
        assertEquals(raw, formatTaskStatus(raw, gson))
    }

    @Test
    fun `omits blank fields from output`() {
        val raw = """{"taskId":"abc"}"""
        val result = formatTaskStatus(raw, gson)
        assertTrue(result.contains("taskId: abc"))
        assertFalse(result.contains("jarId"))
        assertFalse(result.contains("status:"))
    }

    @Test
    fun `includes multiple recognized fields`() {
        val raw = """
            {
                "taskId":"abc",
                "taskStatus":"SUCCEEDED",
                "jarAlias":"my-jar",
                "createdAt":"2026-05-19T12:00:00Z"
            }
        """.trimIndent()
        val result = formatTaskStatus(raw, gson)
        assertTrue(result.contains("taskId: abc"))
        assertTrue(result.contains("status: SUCCEEDED"))
        assertTrue(result.contains("jarAlias: my-jar"))
        assertTrue(result.contains("createdAt: 2026-05-19T12:00:00Z"))
    }
}
