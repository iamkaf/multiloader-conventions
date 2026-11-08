package com.iamkaf.multiloader.publishing

import org.gradle.api.Project
import java.util.Locale

internal object PublishingDefaults {
    fun configure(project: Project, extension: MultiloaderPublishingExtension) {
        extension.getConfig().releaseType.convention(PublicationPlanner.optionalProperty(project, "publish.release-type") ?: "release")
        extension.getPublish().getCurseforge().environment.convention(curseEnvironment(project))

        PublicationPlanner.optionalProperty(project, "publish.modrinth.id")?.let { modrinthId ->
            extension.getPublish().getModrinth().id.convention(modrinthId)
        }

        PublicationPlanner.optionalProperty(project, "publish.curseforge.id")?.let { curseId ->
            extension.getPublish().getCurseforge().id.convention(curseId)
        }

        PublicationPlanner.requiredCsv(project, "dependencies.modrinth.required").forEach { dependency ->
            extension.getPublish().getModrinth().getDependencies().required(dependency)
        }
        PublicationPlanner.requiredCsv(project, "dependencies.curseforge.required").forEach { dependency ->
            extension.getPublish().getCurseforge().getDependencies().required(dependency)
        }
    }

    private fun curseEnvironment(project: Project): String {
        val client = (PublicationPlanner.optionalProperty(project, "environments.client") ?: "required").lowercase(Locale.ROOT)
        val server = (PublicationPlanner.optionalProperty(project, "environments.server") ?: "required").lowercase(Locale.ROOT)

        if (client == "required" && server == "required") return "both"
        if (client == "required" && server != "required") return "client"
        if (server == "required" && client != "required") return "server"
        return "both"
    }
}
