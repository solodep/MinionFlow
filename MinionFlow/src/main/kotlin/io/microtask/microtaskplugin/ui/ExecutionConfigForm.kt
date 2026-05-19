package io.microtask.microtaskplugin.ui

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.json.JsonFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.ScrollPaneConstants

internal class ExecutionConfigForm(
    project: Project,
    initialJson: String,
    private val fallbackJson: () -> String,
    private val onStatus: (String) -> Unit,
    private val gson: Gson
) {

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
    private val inputTypeCombo = editableCombo("", "JSONL")
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

    private val jsonEditor = EditorTextField(initialJson, project, JsonFileType.INSTANCE).apply {
        setOneLineMode(false)
        preferredSize = Dimension(420, 320)
    }

    val component: JComponent

    init {
        loadFormFromJson(showStatus = false)
        component = buildComponent()
    }

    var text: String
        get() = jsonEditor.text
        set(value) {
            jsonEditor.text = value
        }

    val suggestedInputType: String
        get() = comboValue(inputTypeCombo).ifBlank { "JSONL" }

    fun reloadFromExternalText(value: String) {
        jsonEditor.text = value
        loadFormFromJson(showStatus = false)
    }

    fun validate(parentProject: Project, showOk: Boolean): Boolean {
        val parsed = parseRunConfig(jsonEditor.text)
        val root = when (parsed) {
            is RunConfigParseResult.Ok -> parsed.root
            is RunConfigParseResult.Invalid -> {
                Messages.showErrorDialog(parentProject, parsed.message, "MicroTask")
                onStatus("JSON invalid")
                return false
            }
        }

        validateRunConfigStructure(root)?.let { error ->
            Messages.showErrorDialog(parentProject, error, "MicroTask")
            onStatus("JSON invalid")
            return false
        }

        if (showOk) {
            Messages.showInfoMessage(parentProject, "Execution config structure looks valid", "MicroTask")
        }
        onStatus("JSON valid")
        return true
    }

    fun format(parentProject: Project) {
        prettifyJson(jsonEditor.text, gson)
            .onSuccess {
                jsonEditor.text = it
                onStatus("JSON formatted")
            }
            .onFailure {
                Messages.showErrorDialog(parentProject, it.message ?: "Invalid JSON", "MicroTask")
            }
    }

    private fun buildComponent(): JComponent {
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

        val optionsPanel = JPanel(BorderLayout(0, 8)).apply {
            add(JBScrollPane(optionsForm).apply {
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                border = null
            }, BorderLayout.CENTER)
            add(syncButtons, BorderLayout.SOUTH)
        }

        val jsonPanel = JPanel(BorderLayout(0, 6)).apply {
            add(JBLabel("Raw execution config (advanced mode):"), BorderLayout.NORTH)
            add(JBScrollPane(jsonEditor), BorderLayout.CENTER)
        }

        return JTabbedPane().apply {
            addTab("Options", optionsPanel)
            addTab("Raw JSON", jsonPanel)
        }
    }

    private fun applyFormToJson() {
        val root = parseOrFallback()

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
        onStatus("Options applied to JSON")
    }

    private fun loadFormFromJson(showStatus: Boolean) {
        val root = try {
            JsonParser.parseString(jsonEditor.text).asJsonObject
        } catch (_: Exception) {
            parseOrFallback()
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

        if (showStatus) onStatus("Options loaded from JSON")
    }

    private fun parseOrFallback(): JsonObject {
        return try {
            JsonParser.parseString(jsonEditor.text).asJsonObject
        } catch (_: Exception) {
            JsonParser.parseString(fallbackJson()).asJsonObject
        }
    }

    private fun intValue(field: JBTextField): Int? = field.text.trim().toIntOrNull()

    private fun comboValue(combo: JComboBox<String>): String =
        combo.editor.item?.toString()?.trim().orEmpty()

    private fun selectCombo(combo: JComboBox<String>, value: String) {
        combo.selectedItem = value
        combo.editor.item = value
    }

    private fun commaSeparatedValues(text: String): List<String> =
        text.split(',').map { it.trim() }.filter { it.isNotBlank() }

    private fun editableCombo(vararg values: String): JComboBox<String> =
        JComboBox(values).apply { isEditable = true }
}
