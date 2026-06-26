package com.iamkaf.multiloader.support.adapters

import com.iamkaf.multiloader.support.GroovyGradleDsl
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer

object NeoGradleUserdevAdapter {
    fun configure(project: Project, neoforgeVersion: String?, modId: String) {
        project.configurations.getByName("runtimeClasspath")
            .extendsFrom(project.configurations.getByName("localRuntime"))

        project.dependencies.add("implementation", "net.neoforged:neoforge:$neoforgeVersion")

        val mainSourceSet = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
        val runs = project.extensions.getByName("runs")
        GroovyGradleDsl.invoke(
            runs,
            "configureEach",
            GroovyGradleDsl.closure { run ->
                GroovyGradleDsl.invoke(run, "systemProperty", "forge.logging.console.level", "debug")
                GroovyGradleDsl.invoke(run, "systemProperty", "neoforge.enabledGameTestNamespaces", modId)
                GroovyGradleDsl.invoke(GroovyGradleDsl.get(run, "environmentVariables")!!, "put", "MOD_CLASSES", "{source_roots}")
                GroovyGradleDsl.invoke(GroovyGradleDsl.get(run, "environmentVariables")!!, "put", "MCP_MAPPINGS", "{mcp_mappings}")
                GroovyGradleDsl.invoke(run, "modSource", mainSourceSet)
            },
        )
        NeoForgeModDevAdapter.configureNamedRun(runs, "client")
        NeoForgeModDevAdapter.configureNamedRun(runs, "data") { run ->
            val arguments = GroovyGradleDsl.get(run, "arguments")
            if (arguments != null) {
                GroovyGradleDsl.invoke(
                    arguments,
                    "addAll",
                    "--mod",
                    modId,
                    "--all",
                    "--output",
                    project.file("src/generated/resources").absolutePath,
                    "--existing",
                    project.file("src/main/resources").absolutePath,
                )
            }
        }
        NeoForgeModDevAdapter.configureNamedRun(runs, "server") { run ->
            GroovyGradleDsl.invoke(run, "argument", "--nogui")
        }
    }
}
