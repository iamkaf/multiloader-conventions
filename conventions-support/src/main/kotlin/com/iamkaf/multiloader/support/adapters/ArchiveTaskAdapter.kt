package com.iamkaf.multiloader.support.adapters

import com.iamkaf.multiloader.support.GroovyGradleDsl
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.AbstractArchiveTask

object ArchiveTaskAdapter {
    fun archiveFile(project: Project, task: Task): Provider<RegularFile> {
        if (task is AbstractArchiveTask) {
            return task.archiveFile
        }

        val hasArchiveFile = (GroovyGradleDsl.invoke(task, "hasProperty", "archiveFile") as? Boolean) == true
        if (!hasArchiveFile) {
            throw IllegalStateException("[Publishing] Task ${project.path}:${task.name} does not expose archiveFile")
        }

        @Suppress("UNCHECKED_CAST")
        return GroovyGradleDsl.invoke(task, "property", "archiveFile") as Provider<RegularFile>
    }
}
