package io.microtask.microtaskplugin.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal fun readPathString(root: JsonObject, path: List<String>): String {
    var current: JsonObject = root
    for (i in 0 until path.lastIndex) {
        val next = current.get(path[i]) ?: return ""
        if (!next.isJsonObject) return ""
        current = next.asJsonObject
    }
    val leaf = current.get(path.last()) ?: return ""
    return if (leaf.isJsonPrimitive) leaf.asString else ""
}

internal fun readPathInt(root: JsonObject, path: List<String>): String {
    var current: JsonObject = root
    for (i in 0 until path.lastIndex) {
        val next = current.get(path[i]) ?: return ""
        if (!next.isJsonObject) return ""
        current = next.asJsonObject
    }
    val leaf = current.get(path.last()) ?: return ""
    return if (leaf.isJsonPrimitive) runCatching { leaf.asInt.toString() }.getOrElse { "" } else ""
}

internal fun readPathBoolean(root: JsonObject, path: List<String>): Boolean {
    var current: JsonObject = root
    for (i in 0 until path.lastIndex) {
        val next = current.get(path[i]) ?: return false
        if (!next.isJsonObject) return false
        current = next.asJsonObject
    }
    val leaf = current.get(path.last()) ?: return false
    return leaf.isJsonPrimitive && runCatching { leaf.asBoolean }.getOrDefault(false)
}

internal fun readPathStringArray(root: JsonObject, path: List<String>): List<String> {
    var current: JsonObject = root
    for (i in 0 until path.lastIndex) {
        val next = current.get(path[i]) ?: return emptyList()
        if (!next.isJsonObject) return emptyList()
        current = next.asJsonObject
    }
    val leaf = current.get(path.last()) ?: return emptyList()
    if (!leaf.isJsonArray) return emptyList()
    return leaf.asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
}

internal fun setPathString(root: JsonObject, path: List<String>, value: String) {
    val parent = ensureObjectPath(root, path.dropLast(1))
    if (value.isBlank()) parent.remove(path.last()) else parent.addProperty(path.last(), value)
}

internal fun setPathInt(root: JsonObject, path: List<String>, value: Int?) {
    val parent = ensureObjectPath(root, path.dropLast(1))
    if (value == null) parent.remove(path.last()) else parent.addProperty(path.last(), value)
}

internal fun setPathBoolean(root: JsonObject, path: List<String>, value: Boolean) {
    val parent = ensureObjectPath(root, path.dropLast(1))
    parent.addProperty(path.last(), value)
}

internal fun setPathStringArray(root: JsonObject, path: List<String>, values: List<String>) {
    val parent = ensureObjectPath(root, path.dropLast(1))
    if (values.isEmpty()) {
        parent.remove(path.last())
        return
    }
    val array = JsonArray()
    values.forEach { array.add(it) }
    parent.add(path.last(), array)
}

private fun ensureObjectPath(root: JsonObject, path: List<String>): JsonObject {
    var current = root
    for (part in path) {
        val next = current.get(part)
        current = if (next != null && next.isJsonObject) {
            next.asJsonObject
        } else {
            JsonObject().also { current.add(part, it) }
        }
    }
    return current
}
