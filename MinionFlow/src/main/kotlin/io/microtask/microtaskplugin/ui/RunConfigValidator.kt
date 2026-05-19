package io.microtask.microtaskplugin.ui

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal sealed class RunConfigParseResult {
    data class Ok(val root: JsonObject) : RunConfigParseResult()
    data class Invalid(val message: String) : RunConfigParseResult()
}

internal fun parseRunConfig(text: String): RunConfigParseResult {
    val element = try {
        JsonParser.parseString(text)
    } catch (e: Exception) {
        return RunConfigParseResult.Invalid("Invalid JSON: ${e.message}")
    }
    if (!element.isJsonObject) return RunConfigParseResult.Invalid("Invalid JSON: expected a JSON object")
    return RunConfigParseResult.Ok(element.asJsonObject)
}

internal fun validateRunConfigStructure(root: JsonObject): String? {
    if (!root.has("execution") || !root.get("execution").isJsonObject) return "Missing object: execution"
    if (!root.has("input") || !root.get("input").isJsonObject) return "Missing object: input"
    if (!root.has("output") || !root.get("output").isJsonObject) return "Missing object: output"

    val execution = root.getAsJsonObject("execution") ?: return "Missing object: execution"
    if (!execution.has("worker") || !execution.get("worker").isJsonObject) return "Missing object: execution.worker"
    if (!execution.has("timeouts") || !execution.get("timeouts").isJsonObject) return "Missing object: execution.timeouts"

    return null
}

internal fun prettifyJson(text: String, gson: Gson): Result<String> {
    return runCatching {
        gson.toJson(JsonParser.parseString(text))
    }.recoverCatching { throw IllegalArgumentException("Invalid JSON: ${it.message}") }
}
