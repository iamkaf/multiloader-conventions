plugins {
    `java-gradle-plugin`
    id("org.gradle.kotlin.kotlin-dsl")
    groovy
}

repositories {
    maven {
        url = uri("https://maven.neoforged.net/releases")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("project.java").get().toInt())
    }
}

dependencies {
    implementation(localGroovy())
    implementation(buildTools.neoforge.moddev.plugin)
    implementation(buildTools.neogradle.userdev)
    implementation(project(":core-plugin"))
    implementation(project(":conventions-support"))
    implementation(project(":platform-plugin"))
}

gradlePlugin {
    plugins {
        create("multiloaderNeoForge") {
            id = "com.iamkaf.multiloader.neoforge"
            implementationClass = "com.iamkaf.multiloader.neoforge.MultiloaderNeoForgePlugin"
            displayName = "Multiloader NeoForge Plugin"
            description = "Applies invariant NeoForge loader wiring for multiloader projects."
        }
    }
}
