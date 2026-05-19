package io.microtask.microtaskplugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent

class MicroTaskSettingsConfigurable : Configurable {

    private val settings = MicroTaskSettingsService.getInstance()

    private data class Model(
        var useMockBackend: Boolean,
        var mockDataDir: String,
        var serverUrl: String,
        var runSubmitPathTemplate: String,
        var runStatusPathTemplate: String,
        var executionConfigPathTemplate: String,
        var identityBaseUrl: String,
        var projectBaseUrl: String,
        var artifactBaseUrl: String,
        var sdkCoordinates: String,
        var sdkRepositoryUrl: String,
        var projectBinding: String,
        var accountId: String
    )

    private val model = Model(
        useMockBackend = settings.state.useMockBackend,
        mockDataDir = settings.state.mockDataDir,
        serverUrl = settings.state.serverUrl,
        runSubmitPathTemplate = settings.state.runSubmitPathTemplate,
        runStatusPathTemplate = settings.state.runStatusPathTemplate,
        executionConfigPathTemplate = settings.state.executionConfigPathTemplate,
        identityBaseUrl = settings.state.identityBaseUrl,
        projectBaseUrl = settings.state.projectBaseUrl,
        artifactBaseUrl = settings.state.artifactBaseUrl,
        sdkCoordinates = settings.state.sdkCoordinates,
        sdkRepositoryUrl = settings.state.sdkRepositoryUrl,
        projectBinding = settings.state.projectBinding,
        accountId = settings.state.accountId
    )

    private var dialog: DialogPanel? = null

    override fun getDisplayName(): String = "MicroTask"

    override fun createComponent(): JComponent {
        if (dialog != null) return dialog!!

        val created = panel {
            group("Local mode") {
                row {
                    checkBox("Use local mock backend (offline)")
                        .bindSelected(model::useMockBackend)
                        .comment("When enabled, plugin won't call HTTP services. Projects, inputs, configs and tasks are stored locally.")
                }
                row("Mock data directory") {
                    textField()
                        .bindText(model::mockDataDir)
                        .align(AlignX.FILL)
                        .comment("Optional. Default: <user.home>/.microtask-intellij/mock")
                }
            }

            group("Connection") {
                row("Task API base URL") {
                    textField()
                        .bindText(model::serverUrl)
                        .align(AlignX.FILL)
                        .comment("Default: artifact-service. Task create/status requests go here.")
                }
                row("Task submit path") {
                    textField()
                        .bindText(model::runSubmitPathTemplate)
                        .align(AlignX.FILL)
                        .comment("Template, e.g. /api/projects/{projectId}/tasks")
                }
                row("Task status path") {
                    textField()
                        .bindText(model::runStatusPathTemplate)
                        .align(AlignX.FILL)
                        .comment("Template, e.g. /api/projects/{projectId}/tasks/{taskId}")
                }
                row("Execution config path") {
                    textField()
                        .bindText(model::executionConfigPathTemplate)
                        .align(AlignX.FILL)
                        .comment("Template, e.g. /api/projects/{projectId}/executionConfigs")
                }
                row("Account ID") {
                    textField()
                        .bindText(model::accountId)
                        .align(AlignX.FILL)
                        .comment("Optional. Usually returned by identity-service on login")
                }
            }

            group("Service URLs") {
                row("Identity base URL") {
                    textField()
                        .bindText(model::identityBaseUrl)
                        .align(AlignX.FILL)
                        .comment("Base URL of identity-service. Swagger usually at &lt;host&gt;/q/swagger-ui/")
                }
                row("Project base URL") {
                    textField()
                        .bindText(model::projectBaseUrl)
                        .align(AlignX.FILL)
                        .comment("Base URL of project-service. Swagger usually at &lt;host&gt;/q/swagger-ui/")
                }
                row("Artifact base URL") {
                    textField()
                        .bindText(model::artifactBaseUrl)
                        .align(AlignX.FILL)
                        .comment("Base URL of artifact-service. Swagger usually at &lt;host&gt;/q/swagger-ui/")
                }
                row("Default project binding") {
                    textField()
                        .bindText(model::projectBinding)
                        .align(AlignX.FILL)
                        .comment("Used when no project is selected in the tool window")
                }
            }

            group("MinionFlow SDK") {
                row("Coordinates") {
                    textField()
                        .bindText(model::sdkCoordinates)
                        .align(AlignX.FILL)
                        .comment("Maven coordinates of the SDK to add to generated projects. Default: io.github.verevka8:sdk:1.0.0 (Maven Central).")
                }
                row("Repository URL") {
                    textField()
                        .bindText(model::sdkRepositoryUrl)
                        .align(AlignX.FILL)
                        .comment("Optional custom Maven repo. Leave empty for Maven Central.")
                }
            }
        }

        dialog = created
        reset()
        return created
    }

    override fun isModified(): Boolean {
        val d = dialog ?: return false
        return d.isModified()
    }

    override fun apply() {
        val d = dialog ?: return
        d.apply()

        val s = settings.state
        s.useMockBackend = model.useMockBackend
        s.mockDataDir = model.mockDataDir.trim()
        s.serverUrl = model.serverUrl.trim().trimEnd('/')
        s.runSubmitPathTemplate = model.runSubmitPathTemplate.trim()
        s.runStatusPathTemplate = model.runStatusPathTemplate.trim()
        s.executionConfigPathTemplate = model.executionConfigPathTemplate.trim()
        s.identityBaseUrl = model.identityBaseUrl.trim().trimEnd('/')
        s.projectBaseUrl = model.projectBaseUrl.trim().trimEnd('/')
        s.artifactBaseUrl = model.artifactBaseUrl.trim().trimEnd('/')
        s.projectBinding = model.projectBinding.trim()
        s.accountId = model.accountId.trim()
        s.sdkCoordinates = model.sdkCoordinates.trim()
        s.sdkRepositoryUrl = model.sdkRepositoryUrl.trim()
    }

    override fun reset() {
        model.useMockBackend = settings.state.useMockBackend
        model.mockDataDir = settings.state.mockDataDir
        model.serverUrl = settings.state.serverUrl
        model.runSubmitPathTemplate = settings.state.runSubmitPathTemplate
        model.runStatusPathTemplate = settings.state.runStatusPathTemplate
        model.executionConfigPathTemplate = settings.state.executionConfigPathTemplate
        model.identityBaseUrl = settings.state.identityBaseUrl
        model.projectBaseUrl = settings.state.projectBaseUrl
        model.artifactBaseUrl = settings.state.artifactBaseUrl
        model.sdkCoordinates = settings.state.sdkCoordinates
        model.sdkRepositoryUrl = settings.state.sdkRepositoryUrl
        model.projectBinding = settings.state.projectBinding
        model.accountId = settings.state.accountId

        dialog?.reset()
    }

    override fun disposeUIResources() {
        dialog = null
    }
}
