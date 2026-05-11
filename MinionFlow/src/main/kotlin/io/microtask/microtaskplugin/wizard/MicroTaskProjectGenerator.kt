package io.microtask.microtaskplugin.wizard

import com.intellij.icons.AllIcons
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.openapi.externalSystem.service.project.wizard.MavenizedNewProjectWizardStep
import com.intellij.openapi.externalSystem.util.ui.DataView
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindText
import javax.swing.Icon
import java.nio.file.Path

class MicroTaskProjectGenerator : GeneratorNewProjectWizard {

    override val id: String = "microtask-project"

    override val name: String = "MicroTask"

    override val icon: Icon = AllIcons.Nodes.Module

    override val description: String = "Create a Maven-based MicroTask starter project"

    override val ordinal: Int = 200

    override fun createStep(context: WizardContext): NewProjectWizardStep {
        return NewProjectWizardChainStep(RootNewProjectWizardStep(context))
            .nextStep {
                NewProjectWizardBaseStep(it).apply {
                    defaultName = "microtask-app"
                }
            }
            .nextStep { MicroTaskMavenStarterStep(it) }
    }
}

private class MicroTaskMavenStarterStep(
    parent: NewProjectWizardBaseStep
) : MavenizedNewProjectWizardStep<String, NewProjectWizardBaseStep>(parent) {

    private var executionMode: MicroTaskExecutionMode = MicroTaskExecutionMode.STATELESS
    private val packageNameProperty = propertyGraph.lazyProperty { suggestPackageName() }

    init {
        if (groupId.isBlank()) groupId = "io.microtask"
        if (version.isBlank()) version = "0.1.0-SNAPSHOT"
    }

    override fun createView(data: String): DataView<String> {
        return object : DataView<String>() {
            override val data: String = data

            override val location: String = ""

            override val icon: Icon = AllIcons.Nodes.Module

            override val presentationName: String = data

            override val groupId: String = this@MicroTaskMavenStarterStep.groupId

            override val version: String = this@MicroTaskMavenStarterStep.version
        }
    }

    override fun findAllParents(): List<String> = emptyList()

    override fun setupSettingsUI(builder: Panel) {
        packageNameProperty.set(suggestPackageName())

        builder.row("Execution mode") {
            val comboBox = comboBox(MicroTaskExecutionMode.entries.toList()).component
            comboBox.selectedItem = executionMode
            comboBox.addActionListener {
                executionMode = comboBox.selectedItem as? MicroTaskExecutionMode ?: MicroTaskExecutionMode.STATELESS
            }
        }

        builder.row("Base package") {
            textField()
                .bindText(packageNameProperty)
        }
    }

    override fun setupAdvancedSettingsUI(builder: Panel) = Unit

    override fun setupProject(project: Project) {
        val normalizedGroupId = normalizeIdentifier(groupId, "io.microtask")
        val normalizedArtifactId = normalizeArtifactId(artifactId).ifBlank { "microtask-app" }
        val normalizedPackageName = normalizePackageName(
            packageNameProperty.get().trim(),
            normalizedGroupId,
            normalizedArtifactId
        )

        val model = MicroTaskStarterProjectModel(
            groupId = normalizedGroupId,
            artifactId = normalizedArtifactId,
            version = version.trim().ifBlank { "0.1.0-SNAPSHOT" },
            packageName = normalizedPackageName,
            projectName = parentStep.name.ifBlank { normalizedArtifactId },
            projectRoot = Path.of(parentStep.contentEntryPath),
            executionMode = executionMode,
            sdkCoordinates = "io.github.verevka8:sdk:1.0.0"
        )

        MicroTaskStarterProjectScaffolder.generate(model)
        MicroTaskStarterProjectScaffolder.importPom(project, model.projectRoot.resolve("pom.xml"))
    }

    private fun suggestPackageName(): String {
        val normalizedGroupId = normalizeIdentifier(groupId, "io.microtask")
        val normalizedArtifactId = normalizeArtifactId(artifactId)
        return normalizePackageName("", normalizedGroupId, normalizedArtifactId.ifBlank { "microtask-app" })
    }

    private fun normalizeIdentifier(raw: String, fallback: String): String {
        val sanitized = raw.trim()
            .split('.')
            .map { part -> part.lowercase().replace(Regex("[^a-z0-9_]"), "") }
            .filter { it.isNotBlank() }
            .joinToString(".")
        return sanitized.ifBlank { fallback }
    }

    private fun normalizeArtifactId(raw: String): String {
        return raw.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    private fun normalizePackageName(raw: String, groupId: String, artifactId: String): String {
        if (raw.isNotBlank()) {
            return normalizeIdentifier(raw, "$groupId.app")
        }

        return "$groupId.${artifactId.replace('-', '.').replace('_', '.')}"
            .split('.')
            .map { it.lowercase().replace(Regex("[^a-z0-9_]"), "") }
            .filter { it.isNotBlank() }
            .joinToString(".")
            .ifBlank { "$groupId.app" }
    }
}
