package com.iamkaf.multiloader.support.adapters

import com.iamkaf.multiloader.support.GroovyGradleDsl
import org.gradle.api.Project

object StonecutterDslAdapter {
    fun configureDefaultHandlers(project: Project) {
        project.plugins.withId("dev.kikugie.stonecutter") {
            val stonecutter = project.extensions.getByName("stonecutter")
            GroovyGradleDsl.invoke(
                stonecutter,
                "handlers",
                GroovyGradleDsl.closure { handlers ->
                    GroovyGradleDsl.invoke(handlers, "inherit", "json5", "json")
                },
            )
        }
    }
}
