package io.microtask.microtaskplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import io.microtask.microtaskplugin.util.MicroTaskJarBuilder

class BuildJarAction : AnAction(), DumbAware {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        if (project == null || project.basePath.isNullOrBlank()) {
            Messages.showWarningDialog("Open a project first", "MicroTask")
            return
        }
        MicroTaskJarBuilder.build(project)
    }
}
