package com.iamkaf.multiloader.root

import org.gradle.api.GradleException
import org.gradle.api.Project

object VersionMetadataTasks {
    fun register(project: Project) {
        project.tasks.register("checkMultiloaderVersionMetadata") {
            group = "verification"
            description = "Checks versions/<mc>/gradle.properties against Multiloader version policy."
            doLast {
                val differences = RootVersionMatrix.versionDirectories(project)
                    .flatMap { dir -> RootVersionMatrix.metadataDifferences(dir).map { dir.name to it } }

                if (differences.isNotEmpty()) {
                    val message = buildString {
                        appendLine("Multiloader version metadata is stale.")
                        differences.forEach { (version, difference) ->
                            appendLine(
                                "  $version ${difference.key}: expected '${difference.expected}', actual '${difference.actual}'",
                            )
                        }
                        append("Run :writeMultiloaderVersionMetadata to materialize policy-managed keys.")
                    }
                    throw GradleException(message)
                }
            }
        }

        project.tasks.register("writeMultiloaderVersionMetadata") {
            group = "build setup"
            description = "Writes policy-managed keys into versions/<mc>/gradle.properties while preserving project keys."
            doLast {
                RootVersionMatrix.versionDirectories(project).forEach(RootVersionMatrix::writeMaterializedMetadata)
            }
        }
    }
}
