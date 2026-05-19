package io.microtask.microtaskplugin.api

import com.auth0.jwt.JWT
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import io.microtask.microtaskplugin.settings.MicroTaskSettingsService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service(Service.Level.APP)
class MicroTaskApiClient : Disposable {

    override fun dispose() {
        mockBackend.dispose()
    }

    private val log = Logger.getInstance(MicroTaskApiClient::class.java)
    private val settings = MicroTaskSettingsService.getInstance()
    private val gson = Gson()
    private val mockBackend = LocalMockBackend(settings, gson)

    private fun isMock(): Boolean = settings.state.useMockBackend

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Volatile private var accessJwt: String = ""
    @Volatile private var accessExpiresAtEpochMs: Long = 0
    @Volatile private var accountId: String = ""

    data class SessionInfo(
        val accessJwt: String,
        val accessExpiresAtEpochMs: Long,
        val accountId: String,
        val refreshJwt: String
    )

    data class ProjectInfo(val id: String, val name: String, val description: String) {
        override fun toString(): String = name
    }

    data class ArtifactInfo(val id: String, val alias: String) {
        override fun toString(): String = alias
    }

    data class InputInfo(val id: String, val alias: String, val inputType: String) {
        override fun toString(): String = if (inputType.isBlank()) "$alias ($id)" else "$alias [$inputType] ($id)"
    }

    data class ExecutionConfigInfo(val id: String, val alias: String) {
        override fun toString(): String = if (alias.isBlank()) id else "$alias ($id)"
    }

    data class RunSubmitResult(val runId: String, val rawResponse: String)

    data class ApiError(
        val httpsStatus: Int,
        val title: String,
        val code: String,
        val description: String,
        val instance: String?
    )

    class ApiException(val status: Int, val code: String?, override val message: String) : RuntimeException(message)

    fun hasSavedSession(): Boolean {
        if (isMock()) return true
        val accessAlive = accessJwt.isNotBlank() && System.currentTimeMillis() < accessExpiresAtEpochMs
        return accessAlive || settings.state.hasRefreshJwt
    }

    fun isLoggedIn(): Boolean = hasSavedSession()

    fun currentAccountId(): String = accountId.ifBlank { settings.state.accountId }

    fun refreshSavedSessionHint(): Boolean {
        if (isMock()) return true
        val accessAlive = accessJwt.isNotBlank() && System.currentTimeMillis() < accessExpiresAtEpochMs
        val refreshPresent = settings.getRefreshJwt().isNotBlank()
        settings.state.hasRefreshJwt = refreshPresent
        return accessAlive || refreshPresent
    }

    @Synchronized
    fun login(loginOrEmail: String, password: String): SessionInfo {
        if (isMock()) {
            val session = mockBackend.login(loginOrEmail, password)
            accessJwt = session.accessJwt
            accessExpiresAtEpochMs = session.accessExpiresAtEpochMs
            accountId = session.accountId
            settings.state.accountId = session.accountId
            return session
        }

        val url = "${settings.state.identityBaseUrl}/api/sessions"
        val bodyObj = JsonObject().apply {
            if (loginOrEmail.contains('@')) addProperty("email", loginOrEmail) else addProperty("username", loginOrEmail)
            addProperty("password", password)
        }

        val req = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(bodyObj)))
            .build()

        val resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (resp.statusCode() >= 400) throw toApiException(resp)

        val refresh = extractRefreshJwt(resp)
            ?: throw ApiException(resp.statusCode(), "missingRefreshJwt", "No refreshJWT cookie in response")
        settings.setRefreshJwt(refresh)

        val obj = safeJson(resp.body())
        val access = readString(obj, listOf("accessJWT", "accessJwt", "token"))
        val expiresAt = readExpiryEpochMs(obj, access) ?: (System.currentTimeMillis() + Duration.ofMinutes(5).toMillis())
        val accId = readString(obj, listOf("accountId", "accountID", "id")).ifBlank { settings.state.accountId }

        accessJwt = access
        accessExpiresAtEpochMs = expiresAt
        accountId = accId
        settings.state.accountId = accId

        return SessionInfo(access, expiresAt, accId, refresh)
    }

    @Synchronized
    fun logoutCurrent() {
        if (isMock()) {
            mockBackend.logoutCurrent()
            accessJwt = ""
            accessExpiresAtEpochMs = 0
            accountId = ""
            return
        }

        val refresh = settings.getRefreshJwt()
        if (refresh.isBlank()) {
            accessJwt = ""
            accessExpiresAtEpochMs = 0
            accountId = ""
            return
        }

        val url = "${settings.state.identityBaseUrl}/api/sessions/me"
        val req = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(20))
            .header("Cookie", "refreshJWT=$refresh")
            .DELETE()
            .build()

        val resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (resp.statusCode() >= 400) throw toApiException(resp)

        settings.setRefreshJwt("")
        accessJwt = ""
        accessExpiresAtEpochMs = 0
        accountId = ""
    }

    @Synchronized
    fun ensureAccessToken(): String {
        if (isMock()) {
            if (accessJwt.isBlank()) {
                accessJwt = "mock-access"
                accessExpiresAtEpochMs = System.currentTimeMillis() + 3_600_000
            }
            return accessJwt
        }

        val leftMs = accessExpiresAtEpochMs - System.currentTimeMillis()
        if (accessJwt.isBlank() || leftMs < 30_000) {
            refreshAccessToken()
        }
        return accessJwt
    }

    @Synchronized
    fun refreshAccessToken() {
        val refresh = settings.getRefreshJwt()
        if (refresh.isBlank()) throw ApiException(401, "unauthorized", "No refreshJWT stored. Please login.")

        val url = "${settings.state.identityBaseUrl}/api/sessions/refresh"
        val req = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(20))
            .header("Cookie", "refreshJWT=$refresh")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (resp.statusCode() >= 400) throw toApiException(resp)

        extractRefreshJwt(resp)?.let { settings.setRefreshJwt(it) }

        val obj = safeJson(resp.body())
        val access = readString(obj, listOf("accessJWT", "accessJwt", "token"))
        val expiresAt = readExpiryEpochMs(obj, access) ?: (System.currentTimeMillis() + Duration.ofMinutes(5).toMillis())
        val accId = readString(obj, listOf("accountId", "accountID", "id"))

        accessJwt = access
        accessExpiresAtEpochMs = expiresAt
        if (accId.isNotBlank()) {
            accountId = accId
            settings.state.accountId = accId
        }
    }

    fun listProjects(page: Int? = null, size: Int? = null): List<ProjectInfo> {
        if (isMock()) return mockBackend.listProjects()

        val base = "${settings.state.projectBaseUrl}/projects"
        val url = buildString {
            append(base)
            val params = mutableListOf<String>()
            if (page != null) params += "page=$page"
            if (size != null) params += "size=$size"
            if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
        }

        val body = requestJson("GET", url, null)
        val items = findArray(body, listOf("records", "items", "projects", "data"))
            ?: body.takeIf { it.isJsonArray }?.asJsonArray
            ?: JsonArray()

        return items.mapNotNull { el ->
            val obj = el.asJsonObjectOrNull() ?: return@mapNotNull null
            val id = readString(obj, listOf("projectId", "id"))
            val name = readString(obj, listOf("projectName", "name", "alias", "title")).ifBlank { id }
            val desc = readString(obj, listOf("description", "desc"))
            if (id.isBlank()) null else ProjectInfo(id, name, desc)
        }
    }

    fun listArtifacts(projectId: String): List<ArtifactInfo> {
        if (isMock()) return mockBackend.listArtifacts(projectId)

        val url = "${settings.state.artifactBaseUrl}/api/projects/$projectId/artifacts"
        val body = requestJson("GET", url, null)
        val items = findArray(body, listOf("records", "items", "artifacts", "data"))
            ?: body.takeIf { it.isJsonArray }?.asJsonArray
            ?: JsonArray()

        return items.mapNotNull { parseArtifactRecord(it) }
    }

    fun listInputs(projectId: String): List<InputInfo> {
        if (isMock()) return mockBackend.listInputs(projectId)

        val url = "${settings.state.artifactBaseUrl}/api/projects/$projectId/inputs"
        val body = requestJson("GET", url, null)
        val items = findArray(body, listOf("records", "items", "inputs", "data"))
            ?: body.takeIf { it.isJsonArray }?.asJsonArray
            ?: JsonArray()

        return items.mapNotNull { parseInputRecord(it) }
    }

    fun createArtifact(projectId: String, alias: String, file: Path): ArtifactInfo {
        if (isMock()) return mockBackend.createArtifact(projectId, alias, file)

        val url = "${settings.state.artifactBaseUrl}/api/projects/$projectId/artifacts"
        val bytes = Files.readAllBytes(file)
        val (bodyPublisher, contentType) = multipart(
            listOf(
                Part.text("alias", alias),
                Part.file("file", file.fileName.toString(), "application/java-archive", bytes)
            )
        )

        val obj = safeJson(requestRaw("POST", url, bodyPublisher, contentType))
        return parseArtifactRecord(obj)
            ?: ArtifactInfo(UUID.randomUUID().toString(), alias)
    }

    fun updateArtifactContent(projectId: String, artifactId: String, file: Path) {
        if (isMock()) {
            mockBackend.updateArtifactContent(projectId, artifactId, file)
            return
        }

        val url = "${settings.state.artifactBaseUrl}/api/projects/$projectId/artifacts/$artifactId/content"
        val bytes = Files.readAllBytes(file)
        val (bodyPublisher, contentType) = multipart(
            listOf(Part.file("file", file.fileName.toString(), "application/java-archive", bytes))
        )
        requestRaw("PUT", url, bodyPublisher, contentType)
    }

    fun createInput(projectId: String, alias: String, inputType: String, file: Path): InputInfo {
        if (isMock()) return mockBackend.createInput(projectId, alias, inputType, file)

        val url = "${settings.state.artifactBaseUrl}/api/projects/$projectId/inputs"
        val bytes = Files.readAllBytes(file)
        val (bodyPublisher, contentType) = multipart(
            listOf(
                Part.text("alias", alias),
                Part.text("inputType", inputType),
                Part.file("file", file.fileName.toString(), contentTypeForFile(file), bytes)
            )
        )

        val obj = safeJson(requestRaw("POST", url, bodyPublisher, contentType))
        return parseInputRecord(obj)
            ?: InputInfo(UUID.randomUUID().toString(), alias, inputType)
    }

    fun createExecutionConfig(projectId: String, alias: String, configJson: String): ExecutionConfigInfo {
        if (isMock()) return mockBackend.createExecutionConfig(projectId, alias, configJson)

        val url = buildRunUrl(
            settings.state.artifactBaseUrl,
            settings.state.executionConfigPathTemplate,
            mapOf("projectId" to projectId)
        )

        val configBody = normalizeExecutionConfig(configJson)
        val reqBody = JsonObject().apply {
            addProperty("alias", alias)
            add("config", configBody)
        }

        val respText = requestRaw(
            method = "POST",
            url = url,
            body = HttpRequest.BodyPublishers.ofString(gson.toJson(reqBody), StandardCharsets.UTF_8),
            contentType = "application/json"
        )
        val id = extractExecutionConfigId(respText)
        settings.state.selectedExecutionConfigId = id
        return ExecutionConfigInfo(id, alias)
    }

    fun submitTask(projectId: String, jarId: String, inputId: String, configAlias: String, configJson: String): RunSubmitResult {
        if (isMock()) {
            val createdConfig = mockBackend.createExecutionConfig(projectId, configAlias, configJson)
            settings.state.selectedExecutionConfigId = createdConfig.id
            return mockBackend.submitTask(projectId, jarId, inputId, createdConfig.id)
        }

        val createdConfig = createExecutionConfig(projectId, configAlias, configJson)
        val submitBaseUrl = settings.state.serverUrl.ifBlank { settings.state.artifactBaseUrl }
        val url = buildRunUrl(
            submitBaseUrl,
            settings.state.runSubmitPathTemplate,
            mapOf("projectId" to projectId, "artifactId" to jarId, "configId" to createdConfig.id, "inputId" to inputId)
        )

        val bodyJson = JsonObject().apply {
            addProperty("jarId", jarId)
            addProperty("inputId", inputId)
            addProperty("configId", createdConfig.id)
        }

        val respText = requestRaw(
            method = "POST",
            url = url,
            body = HttpRequest.BodyPublishers.ofString(gson.toJson(bodyJson), StandardCharsets.UTF_8),
            contentType = "application/json"
        )
        return RunSubmitResult(extractRunId(respText), respText)
    }

    fun getRunStatus(projectId: String, runId: String): String {
        if (isMock()) return mockBackend.getRunStatus(runId)

        val baseUrl = settings.state.serverUrl.ifBlank { settings.state.artifactBaseUrl }
        val url = buildRunUrl(
            baseUrl,
            settings.state.runStatusPathTemplate,
            mapOf("projectId" to projectId, "taskId" to runId, "runId" to runId)
        )
        return requestRaw("GET", url, HttpRequest.BodyPublishers.noBody(), null)
    }

    fun deleteArtifact(projectId: String, artifactId: String) {
        if (isMock()) {
            mockBackend.deleteArtifact(projectId, artifactId)
            return
        }

        val url = "${settings.state.artifactBaseUrl}/api/projects/$projectId/artifacts/$artifactId"
        requestRaw("DELETE", url, HttpRequest.BodyPublishers.noBody(), null)
    }

    private fun requestJson(method: String, url: String, jsonBody: JsonObject?): JsonElement {
        val bodyString = jsonBody?.let { gson.toJson(it) }
        val respText = requestRaw(
            method = method,
            url = url,
            body = if (bodyString == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8),
            contentType = bodyString?.let { "application/json" }
        )
        return safeJsonElement(respText)
    }

    private fun requestRaw(method: String, url: String, body: HttpRequest.BodyPublisher, contentType: String?): String {
        val token = ensureAccessToken()
        val builder = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofMinutes(2))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")

        if (contentType != null) builder.header("Content-Type", contentType)

        val req = buildRequest(builder, method, body)
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (resp.statusCode() == 401) {
            try {
                refreshAccessToken()
            } catch (_: Exception) {
                throw toApiException(resp)
            }
            val retryBuilder = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofMinutes(2))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $accessJwt")
            if (contentType != null) retryBuilder.header("Content-Type", contentType)
            val retryReq = buildRequest(retryBuilder, method, body)
            val retryResp = http.send(retryReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (retryResp.statusCode() >= 400) throw toApiException(retryResp)
            return retryResp.body()
        }

        if (resp.statusCode() >= 400) throw toApiException(resp)
        return resp.body()
    }

    private fun buildRequest(builder: HttpRequest.Builder, method: String, body: HttpRequest.BodyPublisher): HttpRequest {
        return when (method.uppercase()) {
            "GET" -> builder.GET().build()
            "POST" -> builder.POST(body).build()
            "PUT" -> builder.PUT(body).build()
            "PATCH" -> builder.method("PATCH", body).build()
            "DELETE" -> builder.method("DELETE", body).build()
            else -> builder.method(method.uppercase(), body).build()
        }
    }

    private fun toApiException(resp: HttpResponse<String>): ApiException {
        val status = resp.statusCode()
        val body = resp.body().orEmpty()
        val err = runCatching { gson.fromJson(body, ApiError::class.java) }.getOrNull()
        val code = err?.code
        val msg = when {
            err != null && err.description.isNotBlank() -> err.description
            body.isNotBlank() -> body.take(400)
            else -> "HTTP $status"
        }
        log.warn("API error $status code=${code ?: ""} body=${body.take(500)}")
        return ApiException(status, code, msg)
    }

    private fun extractRefreshJwt(resp: HttpResponse<String>): String? {
        val setCookies = resp.headers().allValues("set-cookie")
        val cookie = setCookies.firstOrNull { it.startsWith("refreshJWT=") } ?: return null
        val value = cookie.removePrefix("refreshJWT=").substringBefore(';')
        return value.ifBlank { null }
    }

    private fun safeJson(text: String): JsonObject {
        return safeJsonElement(text).asJsonObjectOrNull() ?: JsonObject()
    }

    private fun safeJsonElement(text: String): JsonElement {
        return try {
            gson.fromJson(text, JsonElement::class.java) ?: JsonObject()
        } catch (_: Exception) {
            JsonObject()
        }
    }

    private fun readString(obj: JsonObject, keys: List<String>): String {
        for (key in keys) {
            val el = obj.get(key) ?: continue
            if (el.isJsonPrimitive) return el.asString
        }
        return ""
    }

    private fun readExpiryEpochMs(obj: JsonObject, accessToken: String? = null): Long? {
        val directMsKeys = listOf("accessExpiresAtEpochMs", "expiresAtEpochMs", "accessJwtExpiresAtEpochMs")
        for (key in directMsKeys) {
            val el = obj.get(key)
            if (el != null && el.isJsonPrimitive) {
                val value = el.asLong
                if (value > 10_000_000_000L) return value
            }
        }

        val secKeys = listOf("expiresAtEpochSeconds", "expiresAtUnixSeconds", "accessJwtExpiresAtEpochSeconds")
        for (key in secKeys) {
            val el = obj.get(key)
            if (el != null && el.isJsonPrimitive) {
                val value = el.asLong
                if (value > 1_000_000_000L) return value * 1000
            }
        }

        val isoKeys = listOf("expiresAt", "accessJwtExpiresAt", "accessJWTExpiresAt")
        for (key in isoKeys) {
            val el = obj.get(key)
            if (el != null && el.isJsonPrimitive) {
                val value = el.asString
                runCatching { return Instant.parse(value).toEpochMilli() }
            }
        }

        val inSecondsKeys = listOf("expiresInSeconds", "accessExpiresInSeconds", "accessJwtExpiresInSeconds")
        for (key in inSecondsKeys) {
            val el = obj.get(key)
            if (el != null && el.isJsonPrimitive) {
                val seconds = el.asLong
                return System.currentTimeMillis() + seconds * 1000
            }
        }

        if (!accessToken.isNullOrBlank()) {
            runCatching {
                val decoded = JWT.decode(accessToken)
                decoded.expiresAt?.time
            }.getOrNull()?.let { return it }
        }

        return null
    }

    private fun findArray(root: JsonElement, keys: List<String>): JsonArray? {
        val obj = root.asJsonObjectOrNull() ?: return null
        for (key in keys) {
            val el = obj.get(key) ?: continue
            if (el.isJsonArray) return el.asJsonArray
        }
        return null
    }

    private fun parseArtifactRecord(element: JsonElement): ArtifactInfo? {
        val obj = element.asJsonObjectOrNull() ?: return null
        val artifactObj = obj.objectFieldOrNull("artifact") ?: obj
        val id = readString(artifactObj, listOf("artifactId", "id"))
        val alias = readString(obj, listOf("alias", "name")).ifBlank {
            readString(artifactObj, listOf("originalName", "fileName", "name")).ifBlank { id }
        }
        return if (id.isBlank()) null else ArtifactInfo(id, alias)
    }

    private fun parseInputRecord(element: JsonElement): InputInfo? {
        val obj = element.asJsonObjectOrNull() ?: return null
        val nested = obj.objectFieldOrNull("input")
            ?: obj.objectFieldOrNull("artifact")
            ?: obj
        val id = readString(nested, listOf("inputId", "artifactId", "id"))
        val alias = readString(obj, listOf("alias", "name")).ifBlank {
            readString(nested, listOf("originalName", "fileName", "name")).ifBlank { id }
        }
        val inputType = readString(obj, listOf("inputType", "type"))
            .ifBlank { readString(nested, listOf("inputType", "type")) }
        return if (id.isBlank()) null else InputInfo(id, alias, inputType)
    }

    private fun JsonObject.objectFieldOrNull(name: String): JsonObject? {
        val el = get(name) ?: return null
        return if (el.isJsonObject) el.asJsonObject else null
    }

    private fun buildRunUrl(baseUrl: String, pathTemplate: String, params: Map<String, String>): String {
        val base = baseUrl.trim().trimEnd('/').ifBlank { settings.state.artifactBaseUrl.trim().trimEnd('/') }
        var path = pathTemplate.trim()
        if (base.isBlank()) throw IllegalStateException("Execution service base URL is empty. Configure it in Settings → MicroTask")
        if (path.isBlank()) throw IllegalStateException("Run endpoint path is empty. Configure it in Settings → MicroTask")
        if (!path.startsWith('/')) path = "/$path"

        for ((key, value) in params) {
            path = path.replace("{$key}", value)
        }

        return base + path
    }

    private fun normalizeExecutionConfig(configJson: String): JsonObject {
        val root = runCatching { JsonParser.parseString(configJson).asJsonObject }.getOrElse { JsonObject() }

        val execution = root.objectFieldOrNull("execution")
        if (execution != null) {
            val securityNetwork = root.objectFieldOrNull("security")?.objectFieldOrNull("network")
            if (securityNetwork != null && !execution.has("network")) {
                execution.add("network", securityNetwork)
            }
            return execution
        }

        return root.objectFieldOrNull("config")
            ?: root.objectFieldOrNull("configuration")
            ?: root
    }

    private fun extractRunId(respText: String): String {
        val el = runCatching { gson.fromJson(respText, JsonElement::class.java) }.getOrNull() ?: return ""
        return findScopedString(
            el,
            primaryKeys = listOf("taskId", "runId", "executionId"),
            wrapperKeys = listOf("task", "run", "execution", "data", "result")
        ) ?: ""
    }

    private fun extractExecutionConfigId(respText: String): String {
        val el = runCatching { gson.fromJson(respText, JsonElement::class.java) }.getOrNull() ?: return ""
        val obj = el.asJsonObjectOrNull() ?: return ""

        readString(obj, listOf("configId", "executionConfigId"))
            .ifBlank { null }?.let { return it }

        for (wrapper in listOf("executionConfig", "config", "data", "result")) {
            val nested = obj.objectFieldOrNull(wrapper) ?: continue
            readString(nested, listOf("configId", "executionConfigId", "id"))
                .ifBlank { null }?.let { return it }
        }

        return readString(obj, listOf("id"))
    }

    private fun findScopedString(
        root: JsonElement,
        primaryKeys: List<String>,
        wrapperKeys: List<String>
    ): String? {
        val obj = root.asJsonObjectOrNull()
            ?: return if (root.isJsonArray) root.asJsonArray.asSequence()
                .mapNotNull { findScopedString(it, primaryKeys, wrapperKeys) }
                .firstOrNull() else null

        readString(obj, primaryKeys).ifBlank { null }?.let { return it }

        for (wrapper in wrapperKeys) {
            val nested = obj.get(wrapper) ?: continue
            findScopedString(nested, primaryKeys, wrapperKeys)?.let { return it }
        }
        return null
    }

    private fun contentTypeForFile(file: Path): String {
        return Files.probeContentType(file) ?: "application/octet-stream"
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private sealed class Part {
        data class Text(val name: String, val value: String) : Part()
        data class File(val name: String, val filename: String, val contentType: String, val bytes: ByteArray) : Part()

        companion object {
            fun text(name: String, value: String) = Text(name, value)
            fun file(name: String, filename: String, contentType: String, bytes: ByteArray) = File(name, filename, contentType, bytes)
        }
    }

    private fun multipart(parts: List<Part>): Pair<HttpRequest.BodyPublisher, String> {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        for (part in parts) {
            when (part) {
                is Part.Text -> builder.addFormDataPart(part.name, part.value)
                is Part.File -> builder.addFormDataPart(
                    part.name,
                    part.filename,
                    part.bytes.toRequestBody(part.contentType.toMediaType())
                )
            }
        }

        val body = builder.build()
        val buffer = Buffer()
        body.writeTo(buffer)
        val bytes = buffer.readByteArray()
        return HttpRequest.BodyPublishers.ofByteArray(bytes) to (body.contentType()?.toString() ?: "multipart/form-data")
    }
}
