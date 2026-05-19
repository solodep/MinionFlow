package io.microtask.microtaskplugin.api

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.microtask.microtaskplugin.settings.MicroTaskSettingsService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.file.StandardCopyOption

internal class LocalMockBackend(
    private val settings: MicroTaskSettingsService,
    private val gson: Gson
) {

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "microtask-mock").apply { isDaemon = true }
    }

    fun dispose() {
        scheduler.shutdownNow()
    }

    private data class StoredProject(val id: String, val name: String)
    private data class StoredArtifact(
        val id: String,
        val alias: String,
        val size: Long,
        val createdAt: String,
        val updatedAt: String
    )
    private data class StoredInput(
        val id: String,
        val alias: String,
        val inputType: String,
        val size: Long,
        val createdAt: String,
        val updatedAt: String
    )
    private data class StoredExecutionConfig(
        val id: String,
        val alias: String,
        val createdAt: String,
        val configJson: String
    )
    private data class StoredTask(
        val taskId: String,
        val projectId: String,
        val jarId: String,
        val jarAlias: String,
        val inputId: String,
        val inputAlias: String,
        val configId: String,
        val configAlias: String,
        val status: String,
        val launchedBy: String,
        val createdAt: String,
        val startedAt: String?,
        val finishedAt: String?,
        val doneAt: String?
    )

    private val tasks = ConcurrentHashMap<String, StoredTask>()

    private fun rootDir(): Path {
        val configured = settings.state.mockDataDir.trim()
        val root = if (configured.isNotBlank()) Path.of(configured) else Path.of(System.getProperty("user.home"), ".microtask-intellij", "mock")
        Files.createDirectories(root)
        Files.createDirectories(root.resolve("projects"))
        Files.createDirectories(root.resolve("tasks"))
        return root
    }

    fun login(login: String, pass: String): MicroTaskApiClient.SessionInfo {
        val accountId = settings.state.accountId.ifBlank { "mock-account" }
        settings.state.accountId = accountId
        val refresh = "mock-refresh"
        settings.setRefreshJwt(refresh)
        return MicroTaskApiClient.SessionInfo(
            accessJwt = "mock-access",
            accessExpiresAtEpochMs = System.currentTimeMillis() + 3_600_000,
            accountId = accountId,
            refreshJwt = refresh
        )
    }

    fun logoutCurrent() {
        settings.setRefreshJwt("")
    }

    fun listProjects(): List<MicroTaskApiClient.ProjectInfo> {
        val file = rootDir().resolve("projects.json")
        val stored = if (Files.exists(file)) {
            runCatching { gson.fromJson(Files.readString(file), Array<StoredProject>::class.java).toList() }.getOrElse { emptyList() }
        } else {
            emptyList()
        }

        val projects = stored.toMutableList()
        if (projects.isEmpty()) {
            val id = settings.state.projectBinding.ifBlank { UUID.randomUUID().toString() }
            projects += StoredProject(id = id, name = "Local Project")
            Files.writeString(file, gson.toJson(projects))
        }
        return projects.map { MicroTaskApiClient.ProjectInfo(it.id, it.name, "") }
    }

    fun listArtifacts(projectId: String): List<MicroTaskApiClient.ArtifactInfo> {
        val metaFile = ensureProjectDir(projectId).resolve("artifacts.json")
        return readList(metaFile, Array<StoredArtifact>::class.java).map { MicroTaskApiClient.ArtifactInfo(it.id, it.alias) }
    }

    fun listInputs(projectId: String): List<MicroTaskApiClient.InputInfo> {
        val metaFile = ensureProjectDir(projectId).resolve("inputs.json")
        return readList(metaFile, Array<StoredInput>::class.java).map { MicroTaskApiClient.InputInfo(it.id, it.alias, it.inputType) }
    }

    fun createArtifact(projectId: String, alias: String, jarPath: Path): MicroTaskApiClient.ArtifactInfo {
        val projectDir = ensureProjectDir(projectId)
        val id = UUID.randomUUID().toString()
        val itemDir = projectDir.resolve("artifacts").resolve(id)
        Files.createDirectories(itemDir)
        val target = itemDir.resolve(jarPath.fileName.toString())
        Files.copy(jarPath, target, StandardCopyOption.REPLACE_EXISTING)
        val now = Instant.now().toString()
        val stored = StoredArtifact(id, alias, Files.size(target), now, now)
        append(projectDir.resolve("artifacts.json"), stored, Array<StoredArtifact>::class.java)
        return MicroTaskApiClient.ArtifactInfo(id, alias)
    }

    fun updateArtifactContent(projectId: String, artifactId: String, jarPath: Path) {
        val projectDir = ensureProjectDir(projectId)
        val itemDir = projectDir.resolve("artifacts").resolve(artifactId)
        Files.createDirectories(itemDir)
        val target = itemDir.resolve(jarPath.fileName.toString())
        Files.copy(jarPath, target, StandardCopyOption.REPLACE_EXISTING)

        val now = Instant.now().toString()
        val list = readList(projectDir.resolve("artifacts.json"), Array<StoredArtifact>::class.java).toMutableList()
        for (i in list.indices) {
            if (list[i].id == artifactId) {
                list[i] = list[i].copy(size = Files.size(target), updatedAt = now)
                break
            }
        }
        Files.writeString(projectDir.resolve("artifacts.json"), gson.toJson(list))
    }

    fun createInput(projectId: String, alias: String, inputType: String, file: Path): MicroTaskApiClient.InputInfo {
        val projectDir = ensureProjectDir(projectId)
        val id = UUID.randomUUID().toString()
        val itemDir = projectDir.resolve("inputs").resolve(id)
        Files.createDirectories(itemDir)
        val target = itemDir.resolve(file.fileName.toString())
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
        val now = Instant.now().toString()
        val stored = StoredInput(id, alias, inputType, Files.size(target), now, now)
        append(projectDir.resolve("inputs.json"), stored, Array<StoredInput>::class.java)
        return MicroTaskApiClient.InputInfo(id, alias, inputType)
    }

    fun createExecutionConfig(projectId: String, alias: String, configJson: String): MicroTaskApiClient.ExecutionConfigInfo {
        JsonParser.parseString(configJson)
        val projectDir = ensureProjectDir(projectId)
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val stored = StoredExecutionConfig(id, alias, now, configJson)
        append(projectDir.resolve("execution-configs.json"), stored, Array<StoredExecutionConfig>::class.java)
        projectDir.resolve("execution-configs").resolve("$id.json").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, configJson)
        }
        return MicroTaskApiClient.ExecutionConfigInfo(id, alias)
    }

    fun submitTask(projectId: String, jarId: String, inputId: String, configId: String): MicroTaskApiClient.RunSubmitResult {
        val projectDir = ensureProjectDir(projectId)
        val artifact = readList(projectDir.resolve("artifacts.json"), Array<StoredArtifact>::class.java).firstOrNull { it.id == jarId }
            ?: throw IllegalArgumentException("Mock artifact not found: $jarId")
        val input = readList(projectDir.resolve("inputs.json"), Array<StoredInput>::class.java).firstOrNull { it.id == inputId }
            ?: throw IllegalArgumentException("Mock input not found: $inputId")
        val config = readList(projectDir.resolve("execution-configs.json"), Array<StoredExecutionConfig>::class.java).firstOrNull { it.id == configId }
            ?: throw IllegalArgumentException("Mock config not found: $configId")

        val now = Instant.now().toString()
        val taskId = UUID.randomUUID().toString()
        val task = StoredTask(
            taskId = taskId,
            projectId = projectId,
            jarId = jarId,
            jarAlias = artifact.alias,
            inputId = inputId,
            inputAlias = input.alias,
            configId = configId,
            configAlias = config.alias,
            status = "CREATED",
            launchedBy = settings.state.accountId.ifBlank { "mock-account" },
            createdAt = now,
            startedAt = now,
            finishedAt = null,
            doneAt = null
        )
        tasks[taskId] = task

        scheduler.schedule({ updateTaskStatus(taskId, "RUNNING", finish = false) }, 2, TimeUnit.SECONDS)
        scheduler.schedule({ updateTaskStatus(taskId, "SUCCEEDED", finish = true) }, 5, TimeUnit.SECONDS)

        val raw = gson.toJson(taskToPayload(task))
        Files.writeString(rootDir().resolve("tasks").resolve("$taskId.json"), raw)
        return MicroTaskApiClient.RunSubmitResult(runId = taskId, rawResponse = raw)
    }

    fun getRunStatus(runId: String): String {
        val task = tasks[runId] ?: return gson.toJson(mapOf("taskId" to runId, "status" to "UNKNOWN", "mock" to true))
        return gson.toJson(taskToPayload(task) + ("mock" to true))
    }

    fun deleteArtifact(projectId: String, artifactId: String) {
        val projectDir = ensureProjectDir(projectId)
        val metaFile = projectDir.resolve("artifacts.json")
        val list = readList(metaFile, Array<StoredArtifact>::class.java).filterNot { it.id == artifactId }
        Files.writeString(metaFile, gson.toJson(list))
        val artifactDir = projectDir.resolve("artifacts").resolve(artifactId)
        if (Files.exists(artifactDir)) artifactDir.toFile().deleteRecursively()
    }

    private fun taskToPayload(task: StoredTask): Map<String, Any?> {
        return mapOf(
            "taskId" to task.taskId,
            "projectId" to task.projectId,
            "launchedBy" to task.launchedBy,
            "status" to task.status,
            "jarId" to task.jarId,
            "jarAlias" to task.jarAlias,
            "inputId" to task.inputId,
            "inputAlias" to task.inputAlias,
            "configId" to task.configId,
            "configAlias" to task.configAlias,
            "createdAt" to task.createdAt,
            "startedAt" to task.startedAt,
            "finishedAt" to task.finishedAt,
            "doneAt" to task.doneAt
        )
    }

    private fun updateTaskStatus(taskId: String, status: String, finish: Boolean) {
        val previous = tasks[taskId] ?: return
        val now = Instant.now().toString()
        tasks[taskId] = previous.copy(
            status = status,
            finishedAt = if (finish) now else previous.finishedAt,
            doneAt = if (finish) now else previous.doneAt
        )
    }

    private fun ensureProjectDir(projectId: String): Path {
        val dir = rootDir().resolve("projects").resolve(projectId)
        Files.createDirectories(dir)
        return dir
    }

    private fun <T> readList(file: Path, clazz: Class<Array<T>>): List<T> {
        if (!Files.exists(file)) return emptyList()
        return runCatching { gson.fromJson(Files.readString(file), clazz).toList() }.getOrElse { emptyList() }
    }

    private fun <T> append(file: Path, value: T, clazz: Class<Array<T>>) {
        val list = readList(file, clazz).toMutableList()
        list += value
        Files.writeString(file, gson.toJson(list))
    }
}
