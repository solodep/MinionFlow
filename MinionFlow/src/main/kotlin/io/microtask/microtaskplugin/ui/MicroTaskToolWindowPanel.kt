package io.microtask.microtaskplugin.ui

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
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
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

internal fun button(text: String, action: () -> Unit): JButton =
    JButton(text).apply { addActionListener { action() } }

internal fun buttonGrid(columns: Int, vararg buttons: JButton): JPanel =
    JPanel(GridLayout(0, columns, 8, 8)).apply { buttons.forEach { add(it) } }

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

    private val status = JBLabel("Ready")
    private val lastBuild = JBLabel("")
    private val lastRun = JBLabel("")

    private val configForm = ExecutionConfigForm(
        project = project,
        initialJson = loadProjectJsonOrDefault(),
        fallbackJson = { settings.state.runConfigJson },
        onStatus = { status.text = it },
        gson = gson
    )

    private val pipelineRunner = RunPipelineRunner(project, api, settings)

    init {
        minimumSize = Dimension(MIN_TOOL_WINDOW_WIDTH, 320)

        updateLastBuildLabel()
        updateLastRunLabel()
        refreshAccountLabel()

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
            button("Validate JSON") { configForm.validate(project, showOk = true) },
            button("Format JSON") { configForm.format(project) },
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
                add(configForm.component, BorderLayout.CENTER)
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

    private fun save() {
        val state = settings.state
        state.serverUrl = serverUrlField.text.trim().trimEnd('/')
        state.projectBinding = projectBindingField.text.trim()
        state.runConfigJson = configForm.text
        writeProjectJson(configForm.text)
        status.text = "Saved"
        updateLastBuildLabel()
    }

    private fun reloadFromSettings() {
        serverUrlField.text = settings.state.serverUrl
        projectBindingField.text = settings.state.projectBinding
        configForm.reloadFromExternalText(loadProjectJsonOrDefault())
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
            ?.trim()?.takeIf { it.isNotBlank() } ?: return

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

        val spec = chooseInputUploadSpec(this, project, configForm.suggestedInputType) ?: return
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
        if (!configForm.validate(project, showOk = false)) return

        val selectedArtifact = artifactCombo.selectedItem as? MicroTaskApiClient.ArtifactInfo
        val aliasForNewArtifact = if (selectedArtifact == null) {
            Messages.showInputDialog(project, "Alias for new JAR artifact", "Create Artifact", null, defaultJarAlias(), null)
                ?.trim()?.takeIf { it.isNotBlank() } ?: return
        } else {
            ""
        }

        val selectedInput = inputCombo.selectedItem as? MicroTaskApiClient.InputInfo
        val inputUploadSpec = if (selectedInput == null) chooseInputUploadSpec(this, project, configForm.suggestedInputType) else null
        if (selectedInput == null && inputUploadSpec == null) return

        val inputs = RunPipelineRunner.Inputs(
            projectId = projectId,
            selectedArtifact = selectedArtifact,
            aliasForNewArtifact = aliasForNewArtifact,
            selectedInput = selectedInput,
            inputUploadSpec = inputUploadSpec,
            configSnapshot = configForm.text,
            configAlias = defaultConfigAlias()
        )

        pipelineRunner.run(
            inputs = inputs,
            onSuccess = { result ->
                updateLastBuildLabel()
                updateLastRunLabel()
                status.text = if (result.runId.isBlank()) "Task started (no taskId in response)" else "Task started: ${result.runId}"
                loadArtifactsInBackground(silent = true)
                loadInputsInBackground(silent = true)
                if (result.runId.isBlank()) {
                    Messages.showInfoMessage(project, result.rawResponse, "Task response")
                }
            },
            onFailure = { e ->
                status.text = "Run: FAIL"
                if (e is MicroTaskApiClient.ApiException) {
                    Messages.showErrorDialog(project, "${e.message}\n\n(code: ${e.code ?: ""}, http: ${e.status})", "MicroTask")
                } else {
                    Messages.showErrorDialog(project, e.message ?: e.toString(), "MicroTask")
                }
            }
        )
    }

    private fun checkStatus() {
        val initial = settings.state.lastRunId
        val runId = Messages.showInputDialog(project, "Task ID", "Check task status", null, initial, null)
            ?.trim()?.takeIf { it.isNotBlank() } ?: return

        val projectId = currentProjectId()
        if (projectId.isBlank()) {
            Messages.showWarningDialog(project, "Select a project first", "MicroTask")
            return
        }

        runApiTask("Get task status", { api.getRunStatus(projectId, runId) }) { raw ->
            settings.state.lastRunId = runId
            updateLastRunLabel()
            Messages.showInfoMessage(project, formatTaskStatus(raw, gson), "Task status")
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

    private fun commitTextFieldsToSettings() {
        settings.state.serverUrl = serverUrlField.text.trim().trimEnd('/')
        settings.state.projectBinding = projectBindingField.text.trim()
    }

    private fun <T> runApiTask(title: String, action: () -> T, onOk: ((T) -> Unit)? = null) {
        commitTextFieldsToSettings()
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

    private fun defaultJarAlias(): String {
        val projectName = project.name.ifBlank { "microtask" }
        return "$projectName.jar"
    }

    private fun defaultConfigAlias(): String {
        val suffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return "config-$suffix"
    }
}
