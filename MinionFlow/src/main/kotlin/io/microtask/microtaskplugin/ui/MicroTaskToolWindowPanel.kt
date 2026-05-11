package io.microtask.microtaskplugin.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.json.JsonFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import io.microtask.microtaskplugin.api.MicroTaskApiClient
import io.microtask.microtaskplugin.settings.MicroTaskSettingsConfigurable
import io.microtask.microtaskplugin.settings.MicroTaskSettingsService
import io.microtask.microtaskplugin.util.MicroTaskJarBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.ScrollPaneConstants

class MicroTaskToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    companion object {
        const val MIN_TOOL_WINDOW_WIDTH = 520
    }

    private val settings = MicroTaskSettingsService.getInstance()
    private val api = ApplicationManager.getApplication().getService(MicroTaskApiClient::class.java)
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val serverUrlField = JBTextField(settings.state.serverUrl.ifBlank { settings.state.artifactBaseUrl })
    private val projectBindingField = JBTextField(settings.state.projectBinding)

    private val loginField = JBTextField()
    private val passwordField = JBPasswordField()
    private val accountLabel = JBLabel("Not logged in")

    private val projectCombo = JComboBox<MicroTaskApiClient.ProjectInfo>()
    private val artifactCombo = JComboBox<MicroTaskApiClient.ArtifactInfo>()
    private val inputCombo = JComboBox<MicroTaskApiClient.InputInfo>()

    private val executionTypeCombo = editableCombo("", "stateless")
    private val schedulingModeCombo = editableCombo("", "fixed", "adaptive", "auto")
    private val parallelismField = JBTextField()
    private val minParallelismField = JBTextField()
    private val maxParallelismField = JBTextField()
    private val workerBoundCombo = editableCombo("", "cpu", "io")
    private val concurrencyField = JBTextField()
    private val cpuField = JBTextField()
    private val memoryField = JBTextField()
    private val microtaskSecondsField = JBTextField()
    private val taskSecondsField = JBTextField()
    private val maxAttemptsField = JBTextField()
    private val backoffStrategyCombo = editableCombo("", "fixed", "exponential", "linear")
    private val baseMsField = JBTextField()
    private val maxMsField = JBTextField()
    private val jitterCheckBox = JCheckBox("Enable jitter")
    private val inputTypeCombo = editableCombo("", "jsonl", "json", "csv")
    private val inputBucketField = JBTextField()
    private val inputKeyField = JBTextField()
    private val outputTypeCombo = editableCombo("", "s3", "local")
    private val outputBucketField = JBTextField()
    private val outputPrefixField = JBTextField()
    private val resultFilenameField = JBTextField()
    private val resultFormatField = JBTextField()
    private val uploadFromWorkDirField = JBTextField()
    private val artifactsPathTemplateField = JBTextField()
    private val allowDomainsField = JBTextField()

    private val jsonEditor = EditorTextField(loadProjectJsonOrDefault(), project, JsonFileType.INSTANCE).apply {
        setOneLineMode(false)
        preferredSize = Dimension(420, 320)
    }

    private val status = JBLabel("Ready")
    private val lastBuild = JBLabel("")
    private val lastRun = JBLabel("")

    init {
        minimumSize = Dimension(MIN_TOOL_WINDOW_WIDTH, 320)

        updateLastBuildLabel()
        updateLastRunLabel()
        refreshAccountLabel()
        loadFormFromJson(showStatus = false)

        ApplicationManager.getApplication().executeOnPooledThread {
            val hasSavedSession = api.refreshSavedSessionHint()
            if (!hasSavedSession) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                refreshAccountLabel()
                loadProjectsInBackground()
            }
        }

        projectCombo.addActionListener {
            val selected = projectCombo.selectedItem as? MicroTaskApiClient.ProjectInfo ?: return@addActionListener
            projectBindingField.text = selected.id
            settings.state.projectBinding = selected.id
            loadArtifactsInBackground(silent = true)
            loadInputsInBackground(silent = true)
        }

        artifactCombo.addActionListener {
            val selected = artifactCombo.selectedItem as? MicroTaskApiClient.ArtifactInfo
            settings.state.selectedArtifactId = selected?.id.orEmpty()
        }

        inputCombo.addActionListener {
            val selected = inputCombo.selectedItem as? MicroTaskApiClient.InputInfo
            settings.state.selectedInputId = selected?.id.orEmpty()
        }

        val topForm = FormBuilder.createFormBuilder()
            .addLabeledComponent("Task API base URL:", serverUrlField, 1, false)
            .addLabeledComponent("Login (username/email):", loginField, 1, false)
            .addLabeledComponent("Password:", passwordField, 1, false)
            .addLabeledComponent("Account:", accountLabel, 1, false)
            .addLabeledComponent("Project:", projectCombo, 1, false)
            .addLabeledComponent("JAR artifact:", artifactCombo, 1, false)
            .addLabeledComponent("Input:", inputCombo, 1, false)
            .addLabeledComponent("Project binding:", projectBindingField, 1, false)
            .panel

        val mainButtons = buttonGrid(
            2,
            button("Save") { save() },
            button("Refresh") { reloadFromSettings() },
            button("Open microtask.json") { openProjectJsonInEditor() },
            button("Validate JSON") { validateJson(showOk = true) },
            button("Format JSON") { formatJson() },
            button("Open Settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, MicroTaskSettingsConfigurable::class.java)
                reloadFromSettings()
            },
            button("Build artifact") {
                if (project.basePath.isNullOrBlank()) {
                    Messages.showWarningDialog(project, "Open a project first", "MicroTask")
                    return@button
                }
                save()
                MicroTaskJarBuilder.build(project)
            },
            button("Login") {
                val login = loginField.text.trim()
                val pass = String(passwordField.password)
                if (login.isBlank() || pass.isBlank()) {
                    Messages.showWarningDialog(project, "Enter username/email and password", "MicroTask")
                    return@button
                }
                doLogin(login, pass)
            },
            button("Logout") {
                runApiTask("Logout", { api.logoutCurrent() }) { _ ->
                    accountLabel.text = "Not logged in"
                    projectCombo.removeAllItems()
                    artifactCombo.removeAllItems()
                    inputCombo.removeAllItems()
                }
            },
            button("Load projects") { loadProjectsInBackground() },
            button("Load artifacts") { loadArtifactsInBackground() },
            button("Load inputs") { loadInputsInBackground() }
        )

        val topSection = JPanel(BorderLayout(0, 8)).apply {
            add(topForm, BorderLayout.NORTH)
            add(mainButtons, BorderLayout.CENTER)
        }

        val optionsPanel = buildOptionsPanel()
        val jsonPanel = JPanel(BorderLayout(0, 6)).apply {
            add(JBLabel("Raw execution config (advanced mode):"), BorderLayout.NORTH)
            add(JBScrollPane(jsonEditor), BorderLayout.CENTER)
        }

        val tabs = JTabbedPane().apply {
            addTab("Options", optionsPanel)
            addTab("Raw JSON", jsonPanel)
        }

        val artifactButtons = buttonGrid(
            2,
            button("Upload JAR as new artifact") { uploadNewArtifact() },
            button("Update selected artifact") { updateArtifact() },
            button("Delete selected artifact") { deleteArtifact() },
            button("Upload input") { uploadNewInput() },
            button("Run task (build + upload + start)") { runPipeline() },
            button("Check task status") { checkStatus() }
        )

        val bottom = JPanel(BorderLayout(0, 6)).apply {
            add(status, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                add(lastBuild, BorderLayout.NORTH)
                add(lastRun, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }

        val content = JPanel(BorderLayout(0, 10)).apply {
            minimumSize = Dimension(MIN_TOOL_WINDOW_WIDTH, 320)
            add(topSection, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, 8)).apply {
                add(tabs, BorderLayout.CENTER)
                add(artifactButtons, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }

        add(JBScrollPane(content).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
            border = null
        }, BorderLayout.CENTER)
    }

    private fun buildOptionsPanel(): JPanel {
        val optionsForm = FormBuilder.createFormBuilder()
            .addLabeledComponent("Execution type:", executionTypeCombo, 1, false)
            .addLabeledComponent("Scheduling mode:", schedulingModeCombo, 1, false)
            .addLabeledComponent("Parallelism:", parallelismField, 1, false)
            .addLabeledComponent("Min parallelism:", minParallelismField, 1, false)
            .addLabeledComponent("Max parallelism:", maxParallelismField, 1, false)
            .addLabeledComponent("Worker bound:", workerBoundCombo, 1, false)
            .addLabeledComponent("Worker concurrency:", concurrencyField, 1, false)
            .addLabeledComponent("CPU:", cpuField, 1, false)
            .addLabeledComponent("Memory:", memoryField, 1, false)
            .addLabeledComponent("Microtask timeout, sec:", microtaskSecondsField, 1, false)
            .addLabeledComponent("Task timeout, sec:", taskSecondsField, 1, false)
            .addLabeledComponent("Retry max attempts:", maxAttemptsField, 1, false)
            .addLabeledComponent("Backoff strategy:", backoffStrategyCombo, 1, false)
            .addLabeledComponent("Backoff base ms:", baseMsField, 1, false)
            .addLabeledComponent("Backoff max ms:", maxMsField, 1, false)
            .addLabeledComponent("Backoff jitter:", jitterCheckBox)
            .addLabeledComponent("Input type:", inputTypeCombo, 1, false)
            .addLabeledComponent("Input bucket:", inputBucketField, 1, false)
            .addLabeledComponent("Input key:", inputKeyField, 1, false)
            .addLabeledComponent("Output type:", outputTypeCombo, 1, false)
            .addLabeledComponent("Output bucket:", outputBucketField, 1, false)
            .addLabeledComponent("Output prefix:", outputPrefixField, 1, false)
            .addLabeledComponent("Result filename:", resultFilenameField, 1, false)
            .addLabeledComponent("Result format:", resultFormatField, 1, false)
            .addLabeledComponent("Upload from work dir:", uploadFromWorkDirField, 1, false)
            .addLabeledComponent("Artifacts path template:", artifactsPathTemplateField, 1, false)
            .addLabeledComponent("Allow domains (comma-separated):", allowDomainsField, 1, false)
            .panel

        val syncButtons = buttonGrid(
            2,
            button("Apply form → JSON") { applyFormToJson() },
            button("Load form ← JSON") { loadFormFromJson(showStatus = true) }
        )

        return JPanel(BorderLayout(0, 8)).apply {
            add(JBScrollPane(optionsForm).apply {
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                border = null
            }, BorderLayout.CENTER)
            add(syncButtons, BorderLayout.SOUTH)
        }
    }

    private fun save() {
        val state = settings.state
        state.serverUrl = serverUrlField.text.trim().trimEnd('/')
        state.projectBinding = projectBindingField.text.trim()
        state.runConfigJson = jsonEditor.text
        writeProjectJson(jsonEditor.text)
        status.text = "Saved"
        updateLastBuildLabel()
    }

    private fun reloadFromSettings() {
        serverUrlField.text = settings.state.serverUrl
        projectBindingField.text = settings.state.projectBinding
        jsonEditor.text = loadProjectJsonOrDefault()
        loadFormFromJson(showStatus = false)
        status.text = "Reloaded"
        updateLastBuildLabel()
        refreshAccountLabel()
    }

    private fun refreshAccountLabel() {
        accountLabel.text = when {
            api.currentAccountId().isNotBlank() && api.isLoggedIn() -> "Logged in (${api.currentAccountId()})"
            api.hasSavedSession() -> "Saved session"
            else -> "Not logged in"
        }
    }

    private fun doLogin(login: String, pass: String) {
        runApiTask("Login", { api.login(login, pass) }) { _ ->
            passwordField.text = ""
            refreshAccountLabel()
            loadProjectsInBackground()
        }
    }

    private fun loadProjectsInBackground() {
        runApiTask("Load projects", { api.listProjects() }) { projects ->
            projectCombo.removeAllItems()
            projects.forEach { projectCombo.addItem(it) }

            val want = settings.state.projectBinding
            val selected = projects.firstOrNull { it.id == want }
            if (selected != null) {
                projectCombo.selectedItem = selected
            } else if (projects.isNotEmpty()) {
                projectCombo.selectedIndex = 0
                (projectCombo.selectedItem as? MicroTaskApiClient.ProjectInfo)?.let {
                    projectBindingField.text = it.id
                    settings.state.projectBinding = it.id
                }
            }
        }
    }

    private fun loadArtifactsInBackground(silent: Boolean = false) {
        val projectId = currentProjectId()
        if (projectId.isBlank()) {
            if (!silent) Messages.showWarningDialog(project, "Select a project first", "MicroTask")
            return
        }

        runApiTask("Load artifacts", { api.listArtifacts(projectId) }) { artifacts ->
            artifactCombo.removeAllItems()
            artifacts.forEach { artifactCombo.addItem(it) }
            restoreComboSelection(artifactCombo, settings.state.selectedArtifactId)
        }
    }

    private fun loadInputsInBackground(silent: Boolean = false) {
        val projectId = currentProjectId()
        if (projectId.isBlank()) {
            if (!silent) Messages.showWarningDialog(project, "Select a project first", "MicroTask")
            return
        }

        runApiTask("Load inputs", { api.listInputs(projectId) }) { inputs ->
            inputCombo.removeAllItems()
            inputs.forEach { inputCombo.addItem(it) }
            restoreComboSelection(inputCombo, settings.state.selectedInputId)
        }
    }

    private fun uploadNewArtifact() {
        val projectId = currentProjectId()
        if (projectId.isBlank()) {
            Messages.showWarningDialog(project, "Select a project first", "MicroTask")
            return
        }

        val jar = currentJarPath() ?: return
        val alias = Messages.showInputDialog(project, "Alias (name shown in UI)", "Create Artifact", null, jar.fileName.toString(), null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return

        runApiTask("Upload artifact", { api.createArtifact(projectId, alias, jar) }) { created ->
            settings.state.selectedArtifactId = created.id
            loadArtifactsInBackground()
        }
    }

    private fun updateArtifact() {
        val projectId = currentProjectId()
        val artifact = artifactCombo.selectedItem as? MicroTaskApiClient.ArtifactInfo
        if (projectId.isBlank() || artifact == null) {
            Messages.showWarningDialog(project, "Select a project and an artifact", "MicroTask")
            return
        }

        val jar = currentJarPath() ?: return
        runApiTask("Update artifact", { api.updateArtifactContent(projectId, artifact.id, jar) }) { _ ->
            settings.state.selectedArtifactId = artifact.id
            loadArtifactsInBackground()
        }
    }

    private fun deleteArtifact() {
        val projectId = currentProjectId()
        val artifact = artifactCombo.selectedItem as? MicroTaskApiClient.ArtifactInfo
        if (projectId.isBlank() || artifact == null) {
            Messages.showWarningDialog(project, "Select a project and an artifact", "MicroTask")
            return
        }

        val ok = Messages.showYesNoDialog(project, "Delete artifact '${artifact.alias}'?", "MicroTask", null)
        if (ok != Messages.YES) return

        runApiTask("Delete artifact", { api.deleteArtifact(projectId, artifact.id) }) { _ ->
            if (settings.state.selectedArtifactId == artifact.id) settings.state.selectedArtifactId = ""
            loadArtifactsInBackground()
        }
    }

    private fun uploadNewInput() {
        val projectId = currentProjectId()
        if (projectId.isBlank()) {
            Messages.showWarningDialog(project, "Select a project first", "MicroTask")
            return
        }

        val spec = chooseInputUploadSpec() ?: return
        runApiTask("Upload input", { api.createInput(projectId, spec.alias, spec.inputType, spec.file) }) { created ->
            settings.state.selectedInputId = created.id
            loadInputsInBackground()
        }
    }

    private fun runPipeline() {
        val projectId = currentProjectId()
        if (projectId.isBlank()) {
            Messages.showWarningDialog(project, "Select a project first", "MicroTask")
            return
        }

        save()
        if (!validateJson(showOk = false)) return

        val selectedArtifact = artifactCombo.selectedItem as? MicroTaskApiClient.ArtifactInfo
        val aliasForNewArtifact = if (selectedArtifact == null) {
            Messages.showInputDialog(project, "Alias for new JAR artifact", "Create Artifact", null, defaultJarAlias(), null)
                ?.trim()?.takeIf { it.isNotBlank() } ?: return
        } else {
            ""
        }

        val selectedInput = inputCombo.selectedItem as? MicroTaskApiClient.InputInfo
        val inputUploadSpec = if (selectedInput == null) chooseInputUploadSpec() else null
        if (selectedInput == null && inputUploadSpec == null) return

        val configSnapshot = jsonEditor.text
        val configAlias = defaultConfigAlias()

        object : Task.Backgroundable(project, "MicroTask: Run", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Build artifact"
                    val build = MicroTaskJarBuilder.buildJarBlocking(project, indicator)
                    if (!build.ok || build.jar == null) {
                        throw RuntimeException("Build failed")
                    }

                    settings.state.lastBuildOk = true
                    settings.state.lastBuildJarPath = build.jar.toString()
                    settings.state.lastBuildAtEpochMs = System.currentTimeMillis()

                    indicator.text = "Upload or update JAR"
                    val jarId = if (selectedArtifact != null) {
                        api.updateArtifactContent(projectId, selectedArtifact.id, build.jar)
                        selectedArtifact.id
                    } else {
                        api.createArtifact(projectId, aliasForNewArtifact, build.jar).id
                    }
                    settings.state.selectedArtifactId = jarId

                    indicator.text = "Prepare input"
                    val inputId = if (selectedInput != null) {
                        selectedInput.id
                    } else {
                        val spec = inputUploadSpec ?: throw IllegalStateException("Input was not chosen")
                        api.createInput(projectId, spec.alias, spec.inputType, spec.file).id
                    }
                    settings.state.selectedInputId = inputId

                    indicator.text = "Create config and start task"
                    val result = api.submitTask(projectId, jarId, inputId, configAlias, configSnapshot)
                    if (result.runId.isNotBlank()) settings.state.lastRunId = result.runId

                    ApplicationManager.getApplication().invokeLater {
                        updateLastBuildLabel()
                        updateLastRunLabel()
                        status.text = if (result.runId.isBlank()) "Task started (no taskId in response)" else "Task started: ${result.runId}"
                        loadArtifactsInBackground(silent = true)
                        loadInputsInBackground(silent = true)
                        if (result.runId.isBlank()) {
                            Messages.showInfoMessage(project, result.rawResponse, "Task response")
                        }
                    }
                } catch (e: MicroTaskApiClient.ApiException) {
                    ApplicationManager.getApplication().invokeLater {
                        status.text = "Run: FAIL"
                        Messages.showErrorDialog(project, "${e.message}\n\n(code: ${e.code ?: ""}, http: ${e.status})", "MicroTask")
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        status.text = "Run: FAIL"
                        Messages.showErrorDialog(project, e.message ?: e.toString(), "MicroTask")
                    }
                }
            }
        }.queue()
    }

    private fun checkStatus() {
        val initial = settings.state.lastRunId
        val runId = Messages.showInputDialog(project, "Task ID", "Check task status", null, initial, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return

        val projectId = currentProjectId()
        if (projectId.isBlank()) {
            Messages.showWarningDialog(project, "Select a project first", "MicroTask")
            return
        }

        runApiTask("Get task status", { api.getRunStatus(projectId, runId) }) { raw ->
            settings.state.lastRunId = runId
            updateLastRunLabel()
            Messages.showInfoMessage(project, formatTaskStatus(raw), "Task status")
        }
    }

    private fun currentProjectId(): String {
        val selected = projectCombo.selectedItem as? MicroTaskApiClient.ProjectInfo
        return selected?.id ?: settings.state.projectBinding
    }

    private fun currentJarPath(): Path? {
        val state = settings.state
        if (!state.lastBuildOk || state.lastBuildJarPath.isBlank()) {
            Messages.showWarningDialog(project, "Build the artifact first", "MicroTask")
            return null
        }
        val path = Path.of(state.lastBuildJarPath)
        if (!Files.exists(path)) {
            Messages.showWarningDialog(project, "Artifact not found: ${state.lastBuildJarPath}", "MicroTask")
            return null
        }
        return path
    }

    private fun chooseInputUploadSpec(): InputUploadSpec? {
        val chooser = JFileChooser(project.basePath ?: System.getProperty("user.home")).apply {
            dialogTitle = "Choose input file"
            fileSelectionMode = JFileChooser.FILES_ONLY
        }
        val result = chooser.showOpenDialog(this)
        if (result != JFileChooser.APPROVE_OPTION) return null
        val file = chooser.selectedFile?.toPath() ?: return null
        val alias = Messages.showInputDialog(project, "Input alias", "Upload Input", null, file.fileName.toString(), null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val suggestedType = comboValue(inputTypeCombo).ifBlank { "jsonl" }
        val inputType = Messages.showInputDialog(project, "Input type", "Upload Input", null, suggestedType, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return InputUploadSpec(file, alias, inputType)
    }

    private fun formatTaskStatus(raw: String): String {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return raw
        val fields = listOf(
            "taskId" to readPathString(root, listOf("taskId")),
            "status" to readPathString(root, listOf("status")),
            "jarId" to readPathString(root, listOf("jarId")),
            "jarAlias" to readPathString(root, listOf("jarAlias")),
            "inputId" to readPathString(root, listOf("inputId")),
            "inputAlias" to readPathString(root, listOf("inputAlias")),
            "configId" to readPathString(root, listOf("configId")),
            "configAlias" to readPathString(root, listOf("configAlias")),
            "createdAt" to readPathString(root, listOf("createdAt")),
            "startedAt" to readPathString(root, listOf("startedAt")),
            "finishedAt" to readPathString(root, listOf("finishedAt")),
            "doneAt" to readPathString(root, listOf("doneAt"))
        )
        val pretty = fields.filter { it.second.isNotBlank() }.joinToString("\n") { "${it.first}: ${it.second}" }
        return if (pretty.isBlank()) gson.toJson(root) else pretty
    }

    private fun <T> restoreComboSelection(combo: JComboBox<T>, wantedId: String) {
        if (wantedId.isBlank()) return
        for (i in 0 until combo.itemCount) {
            val item = combo.getItemAt(i)
            val id = when (item) {
                is MicroTaskApiClient.ArtifactInfo -> item.id
                is MicroTaskApiClient.InputInfo -> item.id
                else -> null
            }
            if (id == wantedId) {
                combo.selectedIndex = i
                return
            }
        }
    }

    private fun <T> runApiTask(title: String, action: () -> T, onOk: ((T) -> Unit)? = null) {
        object : Task.Backgroundable(project, "MicroTask: $title", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = title
                try {
                    val result = action()
                    ApplicationManager.getApplication().invokeLater {
                        status.text = "$title: OK"
                        refreshAccountLabel()
                        onOk?.invoke(result)
                    }
                } catch (e: MicroTaskApiClient.ApiException) {
                    ApplicationManager.getApplication().invokeLater {
                        status.text = "$title: FAIL"
                        Messages.showErrorDialog(project, "${e.message}\n\n(code: ${e.code ?: ""}, http: ${e.status})", "MicroTask")
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        status.text = "$title: FAIL"
                        Messages.showErrorDialog(project, e.message ?: e.toString(), "MicroTask")
                    }
                }
            }
        }.queue()
    }

    private fun loadProjectJsonOrDefault(): String {
        val base = project.basePath ?: return settings.state.runConfigJson
        val path = Path.of(base, "microtask.json")
        return try {
            if (Files.exists(path)) Files.readString(path) else settings.state.runConfigJson
        } catch (_: Exception) {
            settings.state.runConfigJson
        }
    }

    private fun writeProjectJson(text: String) {
        val base = project.basePath ?: return
        val path = Path.of(base, "microtask.json")
        try {
            Files.writeString(path, text)
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            status.text = "Saved (microtask.json)"
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Failed to write microtask.json: ${e.message}", "MicroTask")
        }
    }

    private fun openProjectJsonInEditor() {
        val base = project.basePath ?: return
        val path = Path.of(base, "microtask.json")
        if (!Files.exists(path)) {
            writeProjectJson(settings.state.runConfigJson)
        }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    private fun updateLastBuildLabel() {
        val state = settings.state
        if (state.lastBuildAtEpochMs == 0L) {
            lastBuild.text = "Last build: —"
            return
        }
        val ok = if (state.lastBuildOk) "OK" else "FAIL"
        val path = state.lastBuildJarPath.ifBlank { "—" }
        lastBuild.text = "Last build: $ok | $path"
    }

    private fun updateLastRunLabel() {
        val id = settings.state.lastRunId
        lastRun.text = if (id.isBlank()) "Last run: —" else "Last run: $id"
    }

    private fun validateJson(showOk: Boolean): Boolean {
        val text = jsonEditor.text
        val root = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Invalid JSON: ${e.message}", "MicroTask")
            status.text = "JSON invalid"
            return false
        }

        val structureError = validateRunConfigStructure(root)
        if (structureError != null) {
            Messages.showErrorDialog(project, structureError, "MicroTask")
            status.text = "JSON invalid"
            return false
        }

        if (showOk) {
            Messages.showInfoMessage(project, "Execution config structure looks valid", "MicroTask")
        }
        status.text = "JSON valid"
        return true
    }

    private fun validateRunConfigStructure(root: JsonObject): String? {
        if (!root.has("execution") || !root.get("execution").isJsonObject) return "Missing object: execution"
        if (!root.has("input") || !root.get("input").isJsonObject) return "Missing object: input"
        if (!root.has("output") || !root.get("output").isJsonObject) return "Missing object: output"

        val execution = root.getAsJsonObject("execution")
        if (execution == null) return "Missing object: execution"
        if (!execution.has("worker") || !execution.get("worker").isJsonObject) return "Missing object: execution.worker"
        if (!execution.has("timeouts") || !execution.get("timeouts").isJsonObject) return "Missing object: execution.timeouts"

        return null
    }

    private fun formatJson() {
        val root = try {
            JsonParser.parseString(jsonEditor.text)
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Invalid JSON: ${e.message}", "MicroTask")
            return
        }
        jsonEditor.text = gson.toJson(root)
        status.text = "JSON formatted"
    }

    private fun applyFormToJson() {
        val root = parseOrDefaultConfig()

        setPathString(root, listOf("execution", "type"), comboValue(executionTypeCombo))
        setPathString(root, listOf("execution", "scheduling", "mode"), comboValue(schedulingModeCombo))
        setPathInt(root, listOf("execution", "scheduling", "parallelism"), intValue(parallelismField))
        setPathInt(root, listOf("execution", "scheduling", "minParallelism"), intValue(minParallelismField))
        setPathInt(root, listOf("execution", "scheduling", "maxParallelism"), intValue(maxParallelismField))
        setPathString(root, listOf("execution", "worker", "bound"), comboValue(workerBoundCombo))
        setPathInt(root, listOf("execution", "worker", "concurrency"), intValue(concurrencyField))
        setPathString(root, listOf("execution", "worker", "resources", "cpu"), cpuField.text.trim())
        setPathString(root, listOf("execution", "worker", "resources", "memory"), memoryField.text.trim())
        setPathInt(root, listOf("execution", "timeouts", "microtaskSeconds"), intValue(microtaskSecondsField))
        setPathInt(root, listOf("execution", "timeouts", "taskSeconds"), intValue(taskSecondsField))
        setPathInt(root, listOf("execution", "retry", "maxAttempts"), intValue(maxAttemptsField))
        setPathString(root, listOf("execution", "retry", "backoff", "strategy"), comboValue(backoffStrategyCombo))
        setPathInt(root, listOf("execution", "retry", "backoff", "baseMs"), intValue(baseMsField))
        setPathInt(root, listOf("execution", "retry", "backoff", "maxMs"), intValue(maxMsField))
        setPathBoolean(root, listOf("execution", "retry", "backoff", "jitter"), jitterCheckBox.isSelected)
        setPathString(root, listOf("input", "type"), comboValue(inputTypeCombo))
        setPathString(root, listOf("input", "source", "bucket"), inputBucketField.text.trim())
        setPathString(root, listOf("input", "source", "key"), inputKeyField.text.trim())
        setPathString(root, listOf("output", "destination", "type"), comboValue(outputTypeCombo))
        setPathString(root, listOf("output", "destination", "bucket"), outputBucketField.text.trim())
        setPathString(root, listOf("output", "destination", "prefix"), outputPrefixField.text.trim())
        setPathString(root, listOf("output", "perTask", "result", "filename"), resultFilenameField.text.trim())
        setPathString(root, listOf("output", "perTask", "result", "format"), resultFormatField.text.trim())
        setPathString(root, listOf("output", "artifacts", "uploadFromWorkDir"), uploadFromWorkDirField.text.trim())
        setPathString(root, listOf("output", "artifacts", "pathTemplate"), artifactsPathTemplateField.text.trim())
        setPathStringArray(root, listOf("security", "network", "allowDomains"), commaSeparatedValues(allowDomainsField.text))

        jsonEditor.text = gson.toJson(root)
        status.text = "Options applied to JSON"
    }

    private fun loadFormFromJson(showStatus: Boolean) {
        val root = try {
            JsonParser.parseString(jsonEditor.text).asJsonObject
        } catch (_: Exception) {
            parseOrDefaultConfig()
        }

        selectCombo(executionTypeCombo, readPathString(root, listOf("execution", "type")))
        selectCombo(schedulingModeCombo, readPathString(root, listOf("execution", "scheduling", "mode")))
        parallelismField.text = readPathInt(root, listOf("execution", "scheduling", "parallelism"))
        minParallelismField.text = readPathInt(root, listOf("execution", "scheduling", "minParallelism"))
        maxParallelismField.text = readPathInt(root, listOf("execution", "scheduling", "maxParallelism"))
        selectCombo(workerBoundCombo, readPathString(root, listOf("execution", "worker", "bound")))
        concurrencyField.text = readPathInt(root, listOf("execution", "worker", "concurrency"))
        cpuField.text = readPathString(root, listOf("execution", "worker", "resources", "cpu"))
        memoryField.text = readPathString(root, listOf("execution", "worker", "resources", "memory"))
        microtaskSecondsField.text = readPathInt(root, listOf("execution", "timeouts", "microtaskSeconds"))
        taskSecondsField.text = readPathInt(root, listOf("execution", "timeouts", "taskSeconds"))
        maxAttemptsField.text = readPathInt(root, listOf("execution", "retry", "maxAttempts"))
        selectCombo(backoffStrategyCombo, readPathString(root, listOf("execution", "retry", "backoff", "strategy")))
        baseMsField.text = readPathInt(root, listOf("execution", "retry", "backoff", "baseMs"))
        maxMsField.text = readPathInt(root, listOf("execution", "retry", "backoff", "maxMs"))
        jitterCheckBox.isSelected = readPathBoolean(root, listOf("execution", "retry", "backoff", "jitter"))
        selectCombo(inputTypeCombo, readPathString(root, listOf("input", "type")))
        inputBucketField.text = readPathString(root, listOf("input", "source", "bucket"))
        inputKeyField.text = readPathString(root, listOf("input", "source", "key"))
        selectCombo(outputTypeCombo, readPathString(root, listOf("output", "destination", "type")))
        outputBucketField.text = readPathString(root, listOf("output", "destination", "bucket"))
        outputPrefixField.text = readPathString(root, listOf("output", "destination", "prefix"))
        resultFilenameField.text = readPathString(root, listOf("output", "perTask", "result", "filename"))
        resultFormatField.text = readPathString(root, listOf("output", "perTask", "result", "format"))
        uploadFromWorkDirField.text = readPathString(root, listOf("output", "artifacts", "uploadFromWorkDir"))
        artifactsPathTemplateField.text = readPathString(root, listOf("output", "artifacts", "pathTemplate"))
        allowDomainsField.text = readPathStringArray(root, listOf("security", "network", "allowDomains")).joinToString(", ")

        if (showStatus) status.text = "Options loaded from JSON"
    }

    private fun parseOrDefaultConfig(): JsonObject {
        return try {
            JsonParser.parseString(jsonEditor.text).asJsonObject
        } catch (_: Exception) {
            JsonParser.parseString(settings.state.runConfigJson).asJsonObject
        }
    }

    private fun readPathString(root: JsonObject, path: List<String>): String {
        var current: JsonObject = root
        for (i in 0 until path.lastIndex) {
            val next = current.get(path[i]) ?: return ""
            if (!next.isJsonObject) return ""
            current = next.asJsonObject
        }
        val leaf = current.get(path.last()) ?: return ""
        return if (leaf.isJsonPrimitive) leaf.asString else ""
    }

    private fun readPathInt(root: JsonObject, path: List<String>): String {
        var current: JsonObject = root
        for (i in 0 until path.lastIndex) {
            val next = current.get(path[i]) ?: return ""
            if (!next.isJsonObject) return ""
            current = next.asJsonObject
        }
        val leaf = current.get(path.last()) ?: return ""
        return if (leaf.isJsonPrimitive) runCatching { leaf.asInt.toString() }.getOrElse { "" } else ""
    }

    private fun readPathBoolean(root: JsonObject, path: List<String>): Boolean {
        var current: JsonObject = root
        for (i in 0 until path.lastIndex) {
            val next = current.get(path[i]) ?: return false
            if (!next.isJsonObject) return false
            current = next.asJsonObject
        }
        val leaf = current.get(path.last()) ?: return false
        return leaf.isJsonPrimitive && runCatching { leaf.asBoolean }.getOrDefault(false)
    }

    private fun readPathStringArray(root: JsonObject, path: List<String>): List<String> {
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

    private fun setPathString(root: JsonObject, path: List<String>, value: String) {
        val parent = ensureObjectPath(root, path.dropLast(1))
        if (value.isBlank()) parent.remove(path.last()) else parent.addProperty(path.last(), value)
    }

    private fun setPathInt(root: JsonObject, path: List<String>, value: Int?) {
        val parent = ensureObjectPath(root, path.dropLast(1))
        if (value == null) parent.remove(path.last()) else parent.addProperty(path.last(), value)
    }

    private fun setPathBoolean(root: JsonObject, path: List<String>, value: Boolean) {
        val parent = ensureObjectPath(root, path.dropLast(1))
        parent.addProperty(path.last(), value)
    }

    private fun setPathStringArray(root: JsonObject, path: List<String>, values: List<String>) {
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

    private fun intValue(field: JBTextField): Int? = field.text.trim().toIntOrNull()

    private fun comboValue(combo: JComboBox<String>): String {
        return combo.editor.item?.toString()?.trim().orEmpty()
    }

    private fun selectCombo(combo: JComboBox<String>, value: String) {
        combo.selectedItem = value
        combo.editor.item = value
    }

    private fun commaSeparatedValues(text: String): List<String> {
        return text.split(',').map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun defaultJarAlias(): String {
        val projectName = project.name.ifBlank { "microtask" }
        return "$projectName.jar"
    }

    private fun defaultConfigAlias(): String {
        val suffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return "config-$suffix"
    }

    private fun editableCombo(vararg values: String): JComboBox<String> {
        return JComboBox(values).apply { isEditable = true }
    }

    private fun button(text: String, action: () -> Unit): JButton {
        return JButton(text).apply { addActionListener { action() } }
    }

    private fun buttonGrid(columns: Int, vararg buttons: JButton): JPanel {
        return JPanel(GridLayout(0, columns, 8, 8)).apply {
            buttons.forEach { add(it) }
        }
    }

    private data class InputUploadSpec(
        val file: Path,
        val alias: String,
        val inputType: String
    )
}
