import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Exec

plugins {
    base
}

group = providers.gradleProperty("project.group").get()
version = providers.gradleProperty("project.version").get()

subprojects {
    apply(plugin = "maven-publish")

    group = rootProject.group
    version = rootProject.version

    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://maven.fabricmc.net")
        }
    }

    afterEvaluate {
        extensions.configure<PublishingExtension> {
            if (plugins.hasPlugin("java-gradle-plugin")) {
                publications.findByName("mavenJava")?.let { duplicatePublication ->
                    publications.remove(duplicatePublication)
                }
            } else if (plugins.hasPlugin("java")) {
                if (publications.findByName("mavenJava") == null) {
                    publications.create("mavenJava", MavenPublication::class.java) {
                        from(components.getByName("java"))
                    }
                }
            }
        }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "KafMaven"
                url = uri(
                    if (version.toString().endsWith("-SNAPSHOT")) {
                        "https://z.kaf.sh/snapshots"
                    } else {
                        "https://z.kaf.sh/releases"
                    },
                )
                credentials {
                    username = System.getenv("MAVEN_PUBLISH_USERNAME")
                    password = System.getenv("MAVEN_PUBLISH_PASSWORD")
                }
            }
        }
    }
}

tasks.register("checkAll") {
    group = "verification"
    description = "Runs check in every plugin project."
    dependsOn(subprojects.map { "${it.path}:check" })
}

tasks.register<Exec>("checkMinimalSample") {
    group = "verification"
    description = "Builds the minimal sample, validates the extracted loader wiring, and dry-runs publishing."
    workingDir = rootDir
    commandLine(
        "./gradlew",
        "-p",
        "samples/minimal",
        "--no-daemon",
        "--stacktrace",
        "validateConventionProperties",
        "validateSampleWiring",
        "build",
        "publishingRelease",
    )
}

tasks.register<Exec>("checkDatagenSample") {
    group = "verification"
    description = "Runs the datagen sample and verifies generated resources land in common."
    workingDir = rootDir
    commandLine(
        "./gradlew",
        "-p",
        "samples/datagen",
        "--no-daemon",
        "--stacktrace",
        "validateConventionProperties",
        "validateSampleWiring",
        ":fabric:runDatagen",
        "verifyDatagenOutput",
    )
}

tasks.register("checkSamples") {
    group = "verification"
    description = "Runs all sample consumer validations."
    dependsOn("checkMinimalSample", "checkDatagenSample")
}
