package com.iamkaf.multiloader.support

import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import java.net.URI

object RepositoryPolicy {
    fun configureProjectRepositories(project: Project) {
        project.repositories.mavenLocal()
        project.repositories.mavenCentral()
        project.repositories.maven {
            name = "TerraformersMC"
            url = project.uri("https://maven.terraformersmc.com/")
            metadataSources {
                mavenPom()
                artifact()
            }
        }
        project.repositories.maven {
            name = "Nucleoid"
            url = project.uri("https://maven.nucleoid.xyz/")
        }
        project.repositories.maven {
            name = "Sponge"
            url = project.uri("https://repo.spongepowered.org/repository/maven-public")
        }
        project.repositories.maven {
            name = "ParchmentMC"
            url = project.uri("https://maven.parchmentmc.org/")
        }
        project.repositories.maven {
            name = "NeoForge"
            url = project.uri("https://maven.neoforged.net/releases")
        }
        project.repositories.maven {
            name = "BlameJared"
            url = project.uri("https://maven.blamejared.com")
        }
        project.repositories.maven {
            name = "Modrinth"
            url = project.uri("https://api.modrinth.com/maven")
        }
        project.repositories.maven {
            name = "Kaf Maven"
            url = project.uri("https://maven.kaf.sh")
        }
        project.repositories.maven {
            name = "Fuzs Mod Resources"
            url = project.uri("https://raw.githubusercontent.com/Fuzss/modresources/main/maven/")
        }
    }

    fun configurePublishingRepositories(publishing: PublishingExtension, version: String) {
        publishing.repositories.maven {
            name = "KafMaven"
            url = URI.create(
                if (version.endsWith("-SNAPSHOT")) "https://z.kaf.sh/snapshots"
                else "https://z.kaf.sh/releases",
            )
            credentials {
                username = System.getenv("MAVEN_PUBLISH_USERNAME")
                password = System.getenv("MAVEN_PUBLISH_PASSWORD")
            }
        }
    }
}
