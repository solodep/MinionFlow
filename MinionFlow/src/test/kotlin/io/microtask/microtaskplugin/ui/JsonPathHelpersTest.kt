package io.microtask.microtaskplugin.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonPathHelpersTest {

    private fun parse(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `readPathString returns value at deep path`() {
        val root = parse("""{"a":{"b":{"c":"hello"}}}""")
        assertEquals("hello", readPathString(root, listOf("a", "b", "c")))
    }

    @Test
    fun `readPathString returns empty when intermediate key missing`() {
        val root = parse("""{"a":{}}""")
        assertEquals("", readPathString(root, listOf("a", "b", "c")))
    }

    @Test
    fun `readPathString returns empty when intermediate node is not object`() {
        val root = parse("""{"a":"not-an-object"}""")
        assertEquals("", readPathString(root, listOf("a", "b")))
    }

    @Test
    fun `readPathString returns empty when leaf is array`() {
        val root = parse("""{"a":{"b":[1,2,3]}}""")
        assertEquals("", readPathString(root, listOf("a", "b")))
    }

    @Test
    fun `readPathInt returns stringified number`() {
        val root = parse("""{"x":42}""")
        assertEquals("42", readPathInt(root, listOf("x")))
    }

    @Test
    fun `readPathInt returns empty for non-numeric`() {
        val root = parse("""{"x":"hello"}""")
        assertEquals("", readPathInt(root, listOf("x")))
    }

    @Test
    fun `readPathInt returns empty when missing`() {
        val root = parse("""{}""")
        assertEquals("", readPathInt(root, listOf("x")))
    }

    @Test
    fun `readPathBoolean reads true`() {
        val root = parse("""{"flag":true}""")
        assertTrue(readPathBoolean(root, listOf("flag")))
    }

    @Test
    fun `readPathBoolean reads false`() {
        val root = parse("""{"flag":false}""")
        assertFalse(readPathBoolean(root, listOf("flag")))
    }

    @Test
    fun `readPathBoolean returns false when missing`() {
        val root = parse("""{}""")
        assertFalse(readPathBoolean(root, listOf("flag")))
    }

    @Test
    fun `readPathStringArray returns list of strings`() {
        val root = parse("""{"items":["a","b","c"]}""")
        assertEquals(listOf("a", "b", "c"), readPathStringArray(root, listOf("items")))
    }

    @Test
    fun `readPathStringArray filters out non-primitive entries`() {
        val root = parse("""{"items":["a",{"x":1},"b"]}""")
        assertEquals(listOf("a", "b"), readPathStringArray(root, listOf("items")))
    }

    @Test
    fun `readPathStringArray returns empty for missing key`() {
        val root = parse("""{}""")
        assertEquals(emptyList<String>(), readPathStringArray(root, listOf("items")))
    }

    @Test
    fun `setPathString creates nested objects`() {
        val root = JsonObject()
        setPathString(root, listOf("a", "b", "c"), "value")
        assertEquals("value", readPathString(root, listOf("a", "b", "c")))
    }

    @Test
    fun `setPathString removes key when value is blank`() {
        val root = parse("""{"a":{"b":"old"}}""")
        setPathString(root, listOf("a", "b"), "")
        assertFalse(root.getAsJsonObject("a").has("b"))
    }

    @Test
    fun `setPathInt sets numeric value`() {
        val root = JsonObject()
        setPathInt(root, listOf("count"), 7)
        assertEquals("7", readPathInt(root, listOf("count")))
    }

    @Test
    fun `setPathInt removes key when value is null`() {
        val root = parse("""{"count":99}""")
        setPathInt(root, listOf("count"), null)
        assertFalse(root.has("count"))
    }

    @Test
    fun `setPathBoolean sets value`() {
        val root = JsonObject()
        setPathBoolean(root, listOf("flag"), true)
        assertTrue(readPathBoolean(root, listOf("flag")))
    }

    @Test
    fun `setPathStringArray creates array`() {
        val root = JsonObject()
        setPathStringArray(root, listOf("items"), listOf("x", "y"))
        assertEquals(listOf("x", "y"), readPathStringArray(root, listOf("items")))
    }

    @Test
    fun `setPathStringArray removes key when list is empty`() {
        val root = parse("""{"items":["a"]}""")
        setPathStringArray(root, listOf("items"), emptyList())
        assertFalse(root.has("items"))
    }

    @Test
    fun `setPathString overwrites non-object intermediate node`() {
        val root = parse("""{"a":"primitive"}""")
        setPathString(root, listOf("a", "b"), "value")
        assertEquals("value", readPathString(root, listOf("a", "b")))
    }
}
