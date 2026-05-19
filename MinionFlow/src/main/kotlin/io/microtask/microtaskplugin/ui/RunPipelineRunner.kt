package io.microtask.microtaskplugin.ui

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import io.microtask.microtaskplugin.api.MicroTaskApiClient
import io.microtask.microtaskplugin.settings.MicroTaskSettingsService
import io.microtask.microtaskplugin.util.MicroTaskJarBuilder
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JFileChooser

internal data class InputUploadSpec(
    val file: Path,
    val alias: String,
    val inputType: String
)

internal fun chooseInputUploadSpec(
    parent: JComponent,
    project: Project,
    suggestedType: String
): InputUploadSpec? {
    val chooser = JFileChooser(project.basePath ?: System.getProperty("user.home")).apply {
        dialogTitle = "Choose input file"
        fileSelectionMode = JFileChooser.FILES_ONLY
    }
    if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null
    val file = chooser.selectedFile?.toPath() ?: return null
    val alias = Messages.showInputDialog(project, "Input alias", "Upload Input", null, file.fileName.toString(), null)
        ?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val inputType = Messages.showInputDialog(project, "Input type", "Upload Input", null, suggestedType.ifBlank { "jsonl" }, null)
        ?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return InputUploadSpec(file, alias, inputType)
}

internal fun formatTaskStatus(raw: String, gson: Gson): String {
    val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return raw
    val statusValue = readPathString(root, listOf("taskStatus"))
        .ifBlank { readPathString(root, listOf("status")) }
    val fields = listOf(
        "taskId" to readPathString(root, listOf("taskId")),
        "status" to statusValue,
        "executionType" to readPathString(root, listOf("executionType")),
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

internal class RunPipelineRunner(
    private val project: Project,
    private val api: MicroTaskApiClient,
    private val settings: MicroTaskSettingsService
) {

    data class Inputs(
        val projectId: String,
        val selectedArtifact: MicroTaskApiClient.ArtifactInfo?,
        val aliasForNewArtifact: String,
        val selectedInput: MicroTaskApiClient.InputInfo?,
        val inputUploadSpec: InputUploadSpec?,
        val configSnapshot: String,
        val configAlias: String
    )

    data class Result(val runId: String, val rawResponse: String)

    fun run(
        inputs: Inputs,
        onSuccess: (Result) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        object : Task.Backgroundable(project, "MicroTask: Run", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Build artifact"
                    val build = MicroTaskJarBuilder.buildJarBlocking(project, indicator)
                    if (!build.ok || build.jar == null) throw RuntimeException("Build failed")

                    settings.state.lastBuildOk = true
                    settings.state.lastBuildJarPath = build.jar.toString()
                    settings.state.lastBuildAtEpochMs = System.currentTimeMillis()

                    indicator.text = "Upload or update JAR"
                    val jarId = if (inputs.selectedArtifact != null) {
                        api.updateArtifactContent(inputs.projectId, inputs.selectedArtifact.id, build.jar)
                        inputs.selectedArtifact.id
                    } else {
                        api.createArtifact(inputs.projectId, inputs.aliasForNewArtifact, build.jar).id
                    }
                    settings.state.selectedArtifactId = jarId

                    indicator.text = "Prepare input"
                    val inputId = if (inputs.selectedInput != null) {
                        inputs.selectedInput.id
                    } else {
                        val spec = inputs.inputUploadSpec ?: throw IllegalStateException("Input was not chosen")
                        api.createInput(inputs.projectId, spec.alias, spec.inputType, spec.file).id
                    }
                    settings.state.selectedInputId = inputId

                    indicator.text = "Create config and start task"
                    val submit = api.submitTask(
                        inputs.projectId,
                        jarId,
                        inputId,
                        inputs.configAlias,
                        inputs.configSnapshot
                    )
                    if (submit.runId.isNotBlank()) settings.state.lastRunId = submit.runId

                    ApplicationManager.getApplication().invokeLater {
                        onSuccess(Result(submit.runId, submit.rawResponse))
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        onFailure(e)
                    }
                }
            }
        }.queue()
    }
}
