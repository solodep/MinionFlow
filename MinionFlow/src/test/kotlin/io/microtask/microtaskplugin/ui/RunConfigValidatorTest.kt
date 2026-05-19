package io.microtask.microtaskplugin.ui

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunConfigValidatorTest {

    @Test
    fun `parseRunConfig returns Ok for valid object`() {
        val result = parseRunConfig("""{"a":1}""")
        assertTrue(result is RunConfigParseResult.Ok)
    }

    @Test
    fun `parseRunConfig returns Invalid for malformed JSON`() {
        val result = parseRunConfig("not json")
        assertTrue(result is RunConfigParseResult.Invalid)
    }

    @Test
    fun `parseRunConfig returns Invalid for non-object JSON`() {
        val result = parseRunConfig("""[1,2,3]""")
        assertTrue(result is RunConfigParseResult.Invalid)
    }

    @Test
    fun `parseRunConfig returns Invalid for primitive`() {
        val result = parseRunConfig(""""just-a-string"""")
        assertTrue(result is RunConfigParseResult.Invalid)
    }

    @Test
    fun `validateRunConfigStructure returns null for valid structure`() {
        val root = JsonParser.parseString(
            """
            {
                "execution": { "worker": {}, "timeouts": {} },
                "input": {},
                "output": {}
            }
            """.trimIndent()
        ).asJsonObject
        assertNull(validateRunConfigStructure(root))
    }

    @Test
    fun `validateRunConfigStructure reports missing execution`() {
        val root = JsonParser.parseString("""{"input":{},"output":{}}""").asJsonObject
        assertEquals("Missing object: execution", validateRunConfigStructure(root))
    }

    @Test
    fun `validateRunConfigStructure reports missing input`() {
        val root = JsonParser.parseString(
            """{"execution":{"worker":{},"timeouts":{}},"output":{}}"""
        ).asJsonObject
        assertEquals("Missing object: input", validateRunConfigStructure(root))
    }

    @Test
    fun `validateRunConfigStructure reports missing output`() {
        val root = JsonParser.parseString(
            """{"execution":{"worker":{},"timeouts":{}},"input":{}}"""
        ).asJsonObject
        assertEquals("Missing object: output", validateRunConfigStructure(root))
    }

    @Test
    fun `validateRunConfigStructure reports missing worker`() {
        val root = JsonParser.parseString(
            """{"execution":{"timeouts":{}},"input":{},"output":{}}"""
        ).asJsonObject
        assertEquals("Missing object: execution.worker", validateRunConfigStructure(root))
    }

    @Test
    fun `validateRunConfigStructure reports missing timeouts`() {
        val root = JsonParser.parseString(
            """{"execution":{"worker":{}},"input":{},"output":{}}"""
        ).asJsonObject
        assertEquals("Missing object: execution.timeouts", validateRunConfigStructure(root))
    }

    @Test
    fun `prettifyJson succeeds on valid input`() {
        val result = prettifyJson("""{"a":1}""", Gson())
        assertTrue(result.isSuccess)
    }

    @Test
    fun `prettifyJson fails on invalid input`() {
        val result = prettifyJson("not json", Gson())
        assertTrue(result.isFailure)
    }
}
