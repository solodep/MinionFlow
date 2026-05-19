package io.microtask.microtaskplugin.settings

import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "MicroTaskSettings",
    storages = [Storage("microtask.xml")]
)
class MicroTaskSettingsService : PersistentStateComponent<MicroTaskSettingsService.State> {

    class State {
        var useMockBackend: Boolean = false
        var mockDataDir: String = ""
        var hasRefreshJwt: Boolean = false

        var serverUrl: String = ""
        var runSubmitPathTemplate: String = "/api/projects/{projectId}/tasks"
        var runStatusPathTemplate: String = "/api/projects/{projectId}/tasks/{taskId}"
        var executionConfigPathTemplate: String = "/api/projects/{projectId}/executionConfigs"

        var identityBaseUrl: String = ""
        var projectBaseUrl: String = ""
        var artifactBaseUrl: String = ""

        var accountId: String = ""
        var sdkCoordinates: String = "io.github.verevka8:sdk:1.0.0"
        var sdkRepositoryUrl: String = ""
        var projectBinding: String = ""
        var selectedArtifactId: String = ""
        var selectedInputId: String = ""
        var selectedExecutionConfigId: String = ""

        var runConfigJson: String = """
            {
              "execution": {
                "type": "stateless",
                "scheduling": {
                  "mode": "fixed",
                  "parallelism": 5
                },
                "worker": {
                  "bound": "cpu",
                  "concurrency": 1,
                  "resources": {
                    "cpu": "500m",
                    "memory": "512Mi"
                  }
                },
                "timeouts": {
                  "microtaskSeconds": 60,
                  "taskSeconds": 3600
                },
                "retry": {
                  "maxAttempts": 3,
                  "backoff": {
                    "strategy": "exponential",
                    "baseMs": 500,
                    "maxMs": 10000,
                    "jitter": true
                  }
                },
                "limits": {}
              },
              "input": {
                "type": "JSONL",
                "source": {
                  "bucket": "micro-tasks",
                  "key": "100.jsonl"
                }
              },
              "output": {
                "destination": {
                  "type": "s3",
                  "bucket": "results",
                  "prefix": "prj_123/run123/"
                },
                "perTask": {
                  "dirTemplate": "tasks/{task_id}/",
                  "result": {
                    "filename": "result.json",
                    "format": "json"
                  }
                },
                "artifacts": {
                  "uploadFromWorkDir": "/out/",
                  "pathTemplate": "tasks/{task_id}/artifacts/"
                }
              },
              "security": {
                "network": {
                  "allowDomains": [
                    "yandex.ru",
                    "google.com"
                  ]
                }
              }
            }
        """.trimIndent()

        var lastBuildJarPath: String = ""
        var lastBuildOk: Boolean = false
        var lastBuildAtEpochMs: Long = 0

        var lastRunId: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun getRefreshJwt(): String {
        return PasswordSafe.instance.getPassword(refreshKey()) ?: ""
    }

    fun setRefreshJwt(refreshJwt: String) {
        val attrs = refreshKey()
        if (refreshJwt.isBlank()) {
            state.hasRefreshJwt = false
            PasswordSafe.instance.set(attrs, null)
            return
        }
        state.hasRefreshJwt = true
        PasswordSafe.instance.set(attrs, Credentials("microtask", refreshJwt))
    }

    private fun refreshKey(): CredentialAttributes {
        return CredentialAttributes(generateServiceName("MicroTask", "refreshJWT"))
    }

    companion object {
        fun getInstance(): MicroTaskSettingsService =
            ApplicationManager.getApplication().getService(MicroTaskSettingsService::class.java)
    }
}
