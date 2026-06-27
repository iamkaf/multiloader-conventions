package com.iamkaf.multiloader.support.adapters

import com.iamkaf.multiloader.support.GroovyGradleDsl
import com.iamkaf.multiloader.support.MultiloaderProjectContext
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import java.io.File

object FabricLoomAdapter {
    fun addMappings(
        project: Project,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        hasParchment: Boolean,
    ) {
        val loom = project.extensions.getByName("loom")
        val mappings = if (hasParchment) {
            GroovyGradleDsl.invoke(
                loom,
                "layered",
                GroovyGradleDsl.closure { layered ->
                    GroovyGradleDsl.invoke(layered, "officialMojangMappings")
                    GroovyGradleDsl.invoke(layered, "parchment", context.library(catalog, "parchment"))
                },
            )
        } else {
            GroovyGradleDsl.invoke(loom, "officialMojangMappings")
        }
        project.dependencies.add("mappings", requireNotNull(mappings) { "Fabric Loom did not provide mappings for ${project.path}" })
    }

    fun addFabricLoader(
        project: Project,
        configuration: String,
        context: MultiloaderProjectContext,
        catalog: VersionCatalog,
        minecraftVersion: String,
    ) {
        if (minecraftVersion == "1.18.2") {
            val dependency = project.dependencies.create("net.fabricmc:fabric-loader:0.14.9") as ExternalModuleDependency
            dependency.version {
                strictly("0.14.9")
            }
            project.dependencies.add(configuration, dependency)
            return
        }

        project.dependencies.add(configuration, context.library(catalog, "fabric-loader"))
    }

    fun addLegacyFabricApiModules(project: Project, configuration: String) {
        listOf(
            "net.fabricmc.fabric-api:fabric-api-base:0.4.0+3cc0f0907d",
            "net.fabricmc.fabric-api:fabric-command-api-v1:1.0.9+6a2618f53a",
            "net.fabricmc.fabric-api:fabric-networking-api-v1:1.0.5+3cc0f0907d",
            "net.fabricmc.fabric-api:fabric-lifecycle-events-v1:1.2.2+3cc0f0907d",
            "net.fabricmc.fabric-api:fabric-resource-loader-v0:0.4.2+ca58154a7d",
        ).forEach { dependency ->
            project.dependencies.add(configuration, dependency)
        }
    }

    fun configureCommonDatagen(project: Project, outputDirectory: File) {
        val fabricApi = project.extensions.findByName("fabricApi")
            ?: throw IllegalStateException("Cannot enable common Fabric datagen because the fabricApi extension is not available.")
        GroovyGradleDsl.invoke(
            fabricApi,
            "configureDataGeneration",
            GroovyGradleDsl.closure { datagen ->
                GroovyGradleDsl.set(datagen, "client", true)
                GroovyGradleDsl.set(datagen, "outputDirectory", outputDirectory)
            },
        )
    }

    fun configureLoom(project: Project, modId: String, accessWidener: File) {
        val loom = project.extensions.getByName("loom")
        if (accessWidener.exists()) {
            val accessWidenerPath = GroovyGradleDsl.get(loom, "accessWidenerPath")
            if (accessWidenerPath != null) {
                GroovyGradleDsl.invoke(accessWidenerPath, "set", accessWidener)
            }
        }

        GroovyGradleDsl.invoke(
            loom,
            "mixin",
            GroovyGradleDsl.closure { mixin ->
                val defaultRefmapName = GroovyGradleDsl.get(mixin, "defaultRefmapName")
                if (defaultRefmapName != null) {
                    GroovyGradleDsl.invoke(defaultRefmapName, "set", "$modId.refmap.json")
                }
            },
        )

        GroovyGradleDsl.invoke(
            loom,
            "runs",
            GroovyGradleDsl.closure { runs ->
                GroovyGradleDsl.invoke(
                    runs,
                    "client",
                    GroovyGradleDsl.closure { run ->
                        GroovyGradleDsl.invoke(run, "client")
                        GroovyGradleDsl.invoke(run, "setConfigName", "Fabric Client")
                        GroovyGradleDsl.invoke(run, "ideConfigGenerated", true)
                        GroovyGradleDsl.invoke(run, "runDir", "runs/client")
                    },
                )
                GroovyGradleDsl.invoke(
                    runs,
                    "server",
                    GroovyGradleDsl.closure { run ->
                        GroovyGradleDsl.invoke(run, "server")
                        GroovyGradleDsl.invoke(run, "setConfigName", "Fabric Server")
                        GroovyGradleDsl.invoke(run, "ideConfigGenerated", true)
                        GroovyGradleDsl.invoke(run, "runDir", "runs/server")
                    },
                )
            },
        )
    }
}
