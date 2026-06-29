package com.iamkaf.multiloader.support.flat

import com.iamkaf.multiloader.support.GroovyGradleDsl
import org.gradle.api.Project

object FlatFabricDependencies {
    fun configureBaseDependencies(project: Project) {
        project.dependencies.add("minecraft", FlatProjectAccess.requiredLibrary(project, "minecraft"))
        if (!FlatProjectAccess.isUnobfuscatedMinecraft(project)) {
            val loom = project.extensions.getByName("loom")
            val mappings = GroovyGradleDsl.invoke(
                loom,
                "layered",
                GroovyGradleDsl.closure { layered ->
                    GroovyGradleDsl.invoke(layered, "officialMojangMappings")
                    GroovyGradleDsl.invoke(layered, "parchment", FlatProjectAccess.requiredLibrary(project, "parchment"))
                },
            )
            project.dependencies.add("mappings", requireNotNull(mappings) { "Fabric Loom did not provide mappings for ${project.path}" })
            project.dependencies.add("modImplementation", FlatProjectAccess.requiredLibrary(project, "fabric-loader"))
            return
        }

        project.dependencies.add("implementation", FlatProjectAccess.requiredLibrary(project, "fabric-loader"))
    }
}
