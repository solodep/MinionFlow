package io.microtask.microtaskplugin.wizard

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Files
import java.nio.file.Path

enum class MicroTaskExecutionMode(val id: String) {
    STATELESS("stateless"),
    SWARM_SYNC("swarm-sync");

    override fun toString(): String = id
}

data class MicroTaskStarterProjectModel(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val packageName: String,
    val projectName: String,
    val projectRoot: Path,
    val executionMode: MicroTaskExecutionMode,
    val javaVersion: Int = 21,
    val sdkCoordinates: String? = null,
    val repositoryUrl: String? = null,
    val extraDependencies: List<String> = emptyList()
)

object MicroTaskStarterProjectScaffolder {

    fun generate(model: MicroTaskStarterProjectModel) {
        val srcDir = model.projectRoot.resolve("src/main/java").resolve(model.packageName.replace('.', '/'))
        val testDir = model.projectRoot.resolve("src/test/java")

        Files.createDirectories(srcDir)
        Files.createDirectories(testDir)

        write(model.projectRoot.resolve("pom.xml"), renderPom(model))
        write(srcDir.resolve("Task.java"), renderTask(model))
        write(srcDir.resolve("Input.java"), renderInput(model))
        if (model.executionMode == MicroTaskExecutionMode.SWARM_SYNC) {
            write(srcDir.resolve("State.java"), renderState(model))
        }
        write(srcDir.resolve("Output.java"), renderOutput(model))
        write(model.projectRoot.resolve("microtask.json"), renderMicrotaskJson(model))
        write(model.projectRoot.resolve(".gitignore"), renderGitignore())
    }

    fun importPom(project: Project, pomPath: Path) {
        StartupManager.getInstance(project).runAfterOpened {
            ApplicationManager.getApplication().executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread

                val pomFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pomPath)
                if (pomFile == null) {
                    notify(project, "Maven import failed", "Generated pom.xml was not found: $pomPath", NotificationType.ERROR)
                    return@executeOnPooledThread
                }

                try {
                    val manager = MavenProjectsManager.getInstance(project)
                    manager.addManagedFilesOrUnignoreNoUpdate(listOf(pomFile))
                    manager.scheduleImportAndResolve()
                        .onError { error ->
                            notify(
                                project,
                                "Maven import failed",
                                error.message ?: "Unable to import generated pom.xml",
                                NotificationType.ERROR
                            )
                        }
                } catch (e: Exception) {
                    notify(
                        project,
                        "Maven import failed",
                        e.message ?: "Unable to import generated pom.xml",
                        NotificationType.ERROR
                    )
                }
            }
        }
    }

    private fun renderPom(model: MicroTaskStarterProjectModel): String {
        val repositoryBlock = model.repositoryUrl?.takeIf { it.isNotBlank() }?.let {
            """
            <repositories>
                <repository>
                    <id>microtask-sdk</id>
                    <url>${xmlEscape(it)}</url>
                </repository>
            </repositories>
            """.trimIndent()
        }.orEmpty()

        val dependencyLines = buildList {
            model.sdkCoordinates
                ?.takeIf { it.isNotBlank() }
                ?.let { add(dependencyBlock(it)) }

            addAll(model.extraDependencies.filter { it.isNotBlank() })
        }

        val dependenciesBlock = if (dependencyLines.isEmpty()) "" else """
            <dependencies>
                ${dependencyLines.joinToString("\n")}
            </dependencies>
        """.trimIndent()

        return """
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>

                <groupId>${xmlEscape(model.groupId)}</groupId>
                <artifactId>${xmlEscape(model.artifactId)}</artifactId>
                <version>${xmlEscape(model.version)}</version>
                <name>${xmlEscape(model.projectName)}</name>

                <properties>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    <maven.compiler.release>${model.javaVersion}</maven.compiler.release>
                </properties>

                $repositoryBlock

                $dependenciesBlock

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <version>3.14.0</version>
                            <configuration>
                                <release>${model.javaVersion}</release>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
        """.trimIndent() + "\n"
    }

    private fun renderTask(model: MicroTaskStarterProjectModel): String {
        return when (model.executionMode) {
            MicroTaskExecutionMode.STATELESS -> renderStatelessTask(model)
            MicroTaskExecutionMode.SWARM_SYNC -> renderSwarmTask(model)
        }
    }

    private fun renderStatelessTask(model: MicroTaskStarterProjectModel): String {
        return """
            package ${model.packageName};

            import org.verevka.TaskContext;
            import org.verevka.microtask.Microtask;

            public class Task implements Microtask<Input, Output> {
                @Override
                public Output execute(Input input, TaskContext ctx) {
                    return new Output();
                }
            }
        """.trimIndent() + "\n"
    }

    private fun renderSwarmTask(model: MicroTaskStarterProjectModel): String {
        return """
            package ${model.packageName};

            import org.verevka.swarm.SwarmFinishContext;
            import org.verevka.swarm.SwarmInitContext;
            import org.verevka.swarm.SwarmNeighborhood;
            import org.verevka.swarm.SwarmStepContext;
            import org.verevka.swarm.SwarmTask;

            public class Task implements SwarmTask<Input, State, Output> {
                @Override
                public State initialize(Input input, SwarmInitContext ctx) {
                    return new State();
                }

                @Override
                public State step(State self, SwarmNeighborhood<State> neighbors, SwarmStepContext ctx) {
                    return self;
                }

                @Override
                public Output finish(State finalState, SwarmFinishContext ctx) {
                    return new Output();
                }
            }
        """.trimIndent() + "\n"
    }

    private fun renderInput(model: MicroTaskStarterProjectModel): String {
        return """
            package ${model.packageName};

            public class Input {
                // your data here
            }
        """.trimIndent() + "\n"
    }

    private fun renderState(model: MicroTaskStarterProjectModel): String {
        return """
            package ${model.packageName};

            public class State {
                // your data here
            }
        """.trimIndent() + "\n"
    }

    private fun renderOutput(model: MicroTaskStarterProjectModel): String {
        return """
            package ${model.packageName};

            public class Output {
                // your data here
            }
        """.trimIndent() + "\n"
    }

    private fun renderMicrotaskJson(model: MicroTaskStarterProjectModel): String {
        return """
            {
              "execution": {
                "type": "${model.executionMode.id}",
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
                "type": "jsonl",
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
        """.trimIndent() + "\n"
    }

    private fun renderGitignore(): String {
        return """
            target/
            .idea/
            *.iml
            out/
        """.trimIndent() + "\n"
    }

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("MicroTask")
            .createNotification("$title\n$content", type)
            .notify(project)
    }

    private fun dependencyBlock(coords: String): String {
        val dependency = parseCoordinates(coords)
            ?: return "<!-- Invalid dependency coordinates: ${xmlEscape(coords)} -->"

        return """
            <dependency>
                <groupId>${xmlEscape(dependency.groupId)}</groupId>
                <artifactId>${xmlEscape(dependency.artifactId)}</artifactId>
                <version>${xmlEscape(dependency.version)}</version>
            </dependency>
        """.trimIndent()
    }

    private fun parseCoordinates(coords: String): MavenCoordinates? {
        val parts = coords.trim().split(':')
        if (parts.size < 3) return null
        return MavenCoordinates(parts[0], parts[1], parts.subList(2, parts.size).joinToString(":"))
    }

    private fun xmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private data class MavenCoordinates(
        val groupId: String,
        val artifactId: String,
        val version: String
    )
}
