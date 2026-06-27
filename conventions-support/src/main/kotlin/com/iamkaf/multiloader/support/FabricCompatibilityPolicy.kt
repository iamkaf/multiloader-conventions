package com.iamkaf.multiloader.support

import org.gradle.api.Project
import org.gradle.api.tasks.Sync

object FabricCompatibilityPolicy {
    fun configureDatagenRuntimeAvailability(project: Project, minecraftVersion: String) {
        if (supportsFabricApiDatagenRuntime(minecraftVersion)) return

        project.tasks.register("runDatagen") {
            group = "fabric"
            description = "Reports that this Minecraft line uses checked-in generated resources instead of Fabric datagen."
            doLast {
                project.logger.lifecycle(
                    "Fabric datagen is not available for Minecraft $minecraftVersion; using the checked-in generated resource lane.",
                )
            }
        }
    }

    fun excludeDatagenSourcesFromGameplayRuns(project: Project, minecraftVersion: String) {
        if (minecraftVersion !in datagenUnsafeGameplayVersions) return
        val needsDatagenRuntime = project.gradle.startParameter.taskNames.any {
            it.contains("datagen", ignoreCase = true)
        } && supportsFabricApiDatagenRuntime(minecraftVersion)
        if (needsDatagenRuntime) return

        project.tasks.matching { it.name == StonecutterSourceLayout.STAGE_JAVA_TASK }
            .withType(Sync::class.java)
            .configureEach {
                exclude("**/*Datagen.java")
                exclude("**/datagen/**")
            }
    }

    fun supportsFabricApiDatagenRuntime(minecraftVersion: String): Boolean =
        VersionPolicy.fabricDatagenRuntimeStrategy(minecraftVersion) == FabricDatagenRuntimeStrategy.FABRIC_API_DATAGEN

    private val datagenUnsafeGameplayVersions = setOf(
        "1.14.4",
        "1.15", "1.15.1", "1.15.2",
        "1.16", "1.16.1", "1.16.2", "1.16.3", "1.16.4", "1.16.5",
        "1.17", "1.17.1",
        "1.18", "1.18.1", "1.18.2",
        "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4",
        "1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4",
    )
}
