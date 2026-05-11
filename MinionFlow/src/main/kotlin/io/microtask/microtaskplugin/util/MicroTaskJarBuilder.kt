package io.microtask.microtaskplugin.util

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.SystemInfo
import io.microtask.microtaskplugin.settings.MicroTaskSettingsService
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

object MicroTaskJarBuilder {

    private enum class BuildTool {
        GRADLE,
        MAVEN
    }

    data class BuildJarResult(
        val ok: Boolean,
        val jar: Path?,
        val diagnosticLines: List<String>
    )

    fun build(project: Project) {
        val basePath = project.basePath ?: return
        val settings = MicroTaskSettingsService.getInstance()
        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("MicroTask")

        notificationGroup
            .createNotification("Building artifact…", NotificationType.INFORMATION)
            .notify(project)

        object : Task.Backgroundable(project, "MicroTask: Build artifact", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = buildJarBlocking(project, basePath, indicator)

                val state = settings.state
                state.lastBuildOk = result.ok
                state.lastBuildAtEpochMs = System.currentTimeMillis()
                state.lastBuildJarPath = result.jar?.toString() ?: ""

                val title = if (result.ok) "Build succeeded" else "Build failed"
                val content = buildString {
                    if (result.jar != null) append("Artifact: ").append(result.jar).append('\n')
                    val diagnostics = result.diagnosticLines.take(12)
                    if (diagnostics.isNotEmpty()) {
                        append("\nErrors:\n")
                        diagnostics.forEach { append(it).append('\n') }
                    }
                }.trim()

                val type = if (result.ok) NotificationType.INFORMATION else NotificationType.ERROR
                val text = listOfNotNull(title, content.takeIf { it.isNotBlank() }).joinToString("\n")
                notificationGroup.createNotification(text, type).notify(project)
            }
        }.queue()
    }

    fun buildJarBlocking(project: Project, indicator: ProgressIndicator): BuildJarResult {
        val basePath = project.basePath ?: return BuildJarResult(false, null, listOf("Project basePath is null"))
        return buildJarBlocking(project, basePath, indicator)
    }

    private fun buildJarBlocking(project: Project, basePath: String, indicator: ProgressIndicator): BuildJarResult {
        val root = Path.of(basePath)
        return when {
            Files.exists(root.resolve("pom.xml")) -> buildWithMaven(project, root, indicator)
            Files.exists(root.resolve("build.gradle.kts")) || Files.exists(root.resolve("build.gradle")) -> buildWithGradle(project, root, indicator)
            else -> BuildJarResult(false, null, listOf("Neither pom.xml nor build.gradle(.kts) was found"))
        }
    }

    private fun buildWithMaven(project: Project, root: Path, indicator: ProgressIndicator): BuildJarResult {
        val result = runBuildTool(
            project = project,
            workDir = root,
            indicator = indicator,
            command = mavenCommand(root),
            args = listOf("-DskipTests", "package")
        )
        val artifact = findNewestJar(root.resolve("target"))
        return buildResult(result, artifact, "No JAR artifact was produced under target/")
    }

    private fun buildWithGradle(project: Project, root: Path, indicator: ProgressIndicator): BuildJarResult {
        val shadow = runBuildTool(
            project = project,
            workDir = root,
            indicator = indicator,
            command = gradleCommand(root),
            args = listOf("shadowJar")
        )
        val fallback = if (shadow.exitCode == 0) shadow else {
            runBuildTool(
                project = project,
                workDir = root,
                indicator = indicator,
                command = gradleCommand(root),
                args = listOf("jar")
            )
        }
        val artifact = findNewestJar(root.resolve("build/libs"))
        return buildResult(fallback, artifact, "No JAR artifact was produced under build/libs/")
    }

    private fun runBuildTool(
        project: Project,
        workDir: Path,
        indicator: ProgressIndicator,
        command: ToolCommand,
        args: List<String>
    ): BuildResult {
        val javaHome = resolveJavaHome(project)
        val fullArgs = if (command.tool == BuildTool.GRADLE) {
            listOf("-Dorg.gradle.java.home=$javaHome") + command.parameters + args
        } else {
            command.parameters + args
        }
        indicator.text = "Running: ${fullArgs.joinToString(" ", prefix = command.executable + " ")}"

        val cmdLine = GeneralCommandLine(command.executable)
            .withWorkDirectory(workDir.toString())
            .withParameters(fullArgs)
            .withEnvironment("JAVA_HOME", javaHome)

        val handler = CapturingProcessHandler(cmdLine)
        val output: ProcessOutput = handler.runProcess(10 * 60 * 1000)
        return BuildResult(output.exitCode, output.stdoutLines, output.stderrLines)
    }

    private fun gradleCommand(root: Path): ToolCommand {
        val isWindows = SystemInfo.isWindows
        val wrapper = if (isWindows) root.resolve("gradlew.bat").toFile() else root.resolve("gradlew").toFile()
        return when {
            wrapper.exists() && isWindows -> ToolCommand(wrapper.absolutePath, listOf("--no-daemon"), BuildTool.GRADLE)
            wrapper.exists() && wrapper.canExecute() -> ToolCommand(wrapper.absolutePath, listOf("--no-daemon"), BuildTool.GRADLE)
            wrapper.exists() -> ToolCommand("sh", listOf(wrapper.absolutePath, "--no-daemon"), BuildTool.GRADLE)
            isWindows -> ToolCommand("gradle.bat", listOf("--no-daemon"), BuildTool.GRADLE)
            else -> ToolCommand("gradle", listOf("--no-daemon"), BuildTool.GRADLE)
        }
    }

    private fun mavenCommand(root: Path): ToolCommand {
        val isWindows = SystemInfo.isWindows
        val wrapper = if (isWindows) root.resolve("mvnw.cmd").toFile() else root.resolve("mvnw").toFile()
        return when {
            wrapper.exists() && isWindows -> ToolCommand(wrapper.absolutePath, listOf("-q"), BuildTool.MAVEN)
            wrapper.exists() && wrapper.canExecute() -> ToolCommand(wrapper.absolutePath, listOf("-q"), BuildTool.MAVEN)
            wrapper.exists() -> ToolCommand("sh", listOf(wrapper.absolutePath, "-q"), BuildTool.MAVEN)
            isWindows -> ToolCommand("mvn.cmd", listOf("-q"), BuildTool.MAVEN)
            else -> ToolCommand("mvn", listOf("-q"), BuildTool.MAVEN)
        }
    }

    private fun resolveJavaHome(project: Project): String {
        val projectSdkHome = ProjectRootManager.getInstance(project).projectSdk?.homePath
        if (!projectSdkHome.isNullOrBlank()) {
            return normalizeJavaHome(projectSdkHome) ?: projectSdkHome
        }

        val ideHome = normalizeJavaHome(System.getProperty("java.home"))
        if (!ideHome.isNullOrBlank()) return ideHome

        return System.getProperty("java.home")
    }

    private fun normalizeJavaHome(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (file.name.equals("jre", ignoreCase = true)) {
            val parent = file.parentFile
            if (parent != null && File(parent, "bin").exists()) return parent.absolutePath
        }
        return file.absolutePath
    }

    private fun findNewestJar(dir: Path): Path? {
        if (!Files.exists(dir)) return null
        Files.list(dir).use { stream ->
            return stream
                .filter { it.isRegularFile() }
                .filter { it.fileName.toString().endsWith(".jar") }
                .filter { !it.fileName.toString().endsWith("-sources.jar") && !it.fileName.toString().endsWith("-javadoc.jar") }
                .max(Comparator.comparingLong { Files.getLastModifiedTime(it).toMillis() })
                .orElse(null)
        }
    }

    private data class BuildResult(
        val exitCode: Int,
        val stdoutLines: List<String>,
        val stderrLines: List<String>
    )

    private data class ToolCommand(
        val executable: String,
        val parameters: List<String>,
        val tool: BuildTool
    )

    private fun buildResult(result: BuildResult, artifact: Path?, missingArtifactMessage: String): BuildJarResult {
        val diagnostics = buildList {
            addAll(result.stderrLines)
            addAll(result.stdoutLines)
            if (result.exitCode == 0 && artifact == null) add(missingArtifactMessage)
        }.distinct()

        return BuildJarResult(result.exitCode == 0 && artifact != null, artifact, diagnostics)
    }
}
