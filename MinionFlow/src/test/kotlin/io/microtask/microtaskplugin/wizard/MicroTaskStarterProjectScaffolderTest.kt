package io.microtask.microtaskplugin.wizard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class MicroTaskStarterProjectScaffolderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun buildModel(
        executionMode: MicroTaskExecutionMode = MicroTaskExecutionMode.STATELESS,
        sdkCoordinates: String? = null,
        repositoryUrl: String? = null
    ): MicroTaskStarterProjectModel {
        return MicroTaskStarterProjectModel(
            groupId = "io.test",
            artifactId = "demo-app",
            version = "1.0.0",
            packageName = "io.test.demo",
            projectName = "Demo App",
            projectRoot = tempFolder.root.toPath(),
            executionMode = executionMode,
            sdkCoordinates = sdkCoordinates,
            repositoryUrl = repositoryUrl
        )
    }

    private fun root(): Path = tempFolder.root.toPath()

    private fun srcFile(name: String): Path =
        root().resolve("src/main/java/io/test/demo").resolve(name)

    @Test
    fun `generate creates pom xml with project coordinates`() {
        MicroTaskStarterProjectScaffolder.generate(buildModel())

        val pom = root().resolve("pom.xml")
        assertTrue(Files.exists(pom))
        val content = Files.readString(pom)
        assertTrue(content.contains("<groupId>io.test</groupId>"))
        assertTrue(content.contains("<artifactId>demo-app</artifactId>"))
        assertTrue(content.contains("<version>1.0.0</version>"))
    }

    @Test
    fun `generate omits dependencies when sdkCoordinates is null`() {
        MicroTaskStarterProjectScaffolder.generate(buildModel(sdkCoordinates = null))

        val pom = Files.readString(root().resolve("pom.xml"))
        assertFalse(pom.contains("<dependencies>"))
    }

    @Test
    fun `generate includes dependency when sdkCoordinates is set`() {
        MicroTaskStarterProjectScaffolder.generate(
            buildModel(sdkCoordinates = "com.example:my-sdk:2.0.0")
        )

        val pom = Files.readString(root().resolve("pom.xml"))
        assertTrue(pom.contains("<dependencies>"))
        assertTrue(pom.contains("<groupId>com.example</groupId>"))
        assertTrue(pom.contains("<artifactId>my-sdk</artifactId>"))
        assertTrue(pom.contains("<version>2.0.0</version>"))
    }

    @Test
    fun `generate includes repositories block when repositoryUrl is set`() {
        MicroTaskStarterProjectScaffolder.generate(
            buildModel(repositoryUrl = "https://repo.example.com/maven")
        )

        val pom = Files.readString(root().resolve("pom.xml"))
        assertTrue(pom.contains("<repositories>"))
        assertTrue(pom.contains("https://repo.example.com/maven"))
    }

    @Test
    fun `generate skips repositories block when repositoryUrl is null`() {
        MicroTaskStarterProjectScaffolder.generate(buildModel(repositoryUrl = null))

        val pom = Files.readString(root().resolve("pom.xml"))
        assertFalse(pom.contains("<repositories>"))
    }

    @Test
    fun `stateless mode generates Task with Microtask interface`() {
        MicroTaskStarterProjectScaffolder.generate(
            buildModel(executionMode = MicroTaskExecutionMode.STATELESS)
        )

        val taskJava = Files.readString(srcFile("Task.java"))
        assertTrue(taskJava.contains("implements Microtask<Input, Output>"))
        assertTrue(taskJava.contains("package io.test.demo;"))
    }

    @Test
    fun `swarm mode generates Task with SwarmTask interface and State class`() {
        MicroTaskStarterProjectScaffolder.generate(
            buildModel(executionMode = MicroTaskExecutionMode.SWARM_SYNC)
        )

        val taskJava = Files.readString(srcFile("Task.java"))
        assertTrue(taskJava.contains("implements SwarmTask<Input, State, Output>"))
        assertTrue(Files.exists(srcFile("State.java")))
    }

    @Test
    fun `stateless mode does not generate State`() {
        MicroTaskStarterProjectScaffolder.generate(
            buildModel(executionMode = MicroTaskExecutionMode.STATELESS)
        )
        assertFalse(Files.exists(srcFile("State.java")))
    }

    @Test
    fun `generate writes microtask json with correct execution type`() {
        MicroTaskStarterProjectScaffolder.generate(
            buildModel(executionMode = MicroTaskExecutionMode.SWARM_SYNC)
        )

        val mtJson = Files.readString(root().resolve("microtask.json"))
        assertTrue(mtJson.contains("\"type\": \"swarm-sync\""))
    }

    @Test
    fun `generate writes Input and Output stubs alongside Task`() {
        MicroTaskStarterProjectScaffolder.generate(buildModel())

        assertTrue(Files.exists(srcFile("Input.java")))
        assertTrue(Files.exists(srcFile("Output.java")))
        assertTrue(Files.readString(srcFile("Input.java")).contains("package io.test.demo;"))
    }

    @Test
    fun `generate writes gitignore`() {
        MicroTaskStarterProjectScaffolder.generate(buildModel())

        val gitignore = Files.readString(root().resolve(".gitignore"))
        assertTrue(gitignore.contains("target/"))
        assertTrue(gitignore.contains(".idea/"))
    }

    @Test
    fun `MicroTaskExecutionMode toString returns id`() {
        assertEquals("stateless", MicroTaskExecutionMode.STATELESS.toString())
        assertEquals("swarm-sync", MicroTaskExecutionMode.SWARM_SYNC.toString())
    }
}
