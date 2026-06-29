plugins {
    `java-gradle-plugin`
    id("org.gradle.kotlin.kotlin-dsl")
    groovy
}

repositories {
    maven {
        url = uri("https://maven.minecraftforge.net")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("project.java").get().toInt())
    }
}

dependencies {
    implementation(localGroovy())
    implementation(buildTools.forgegradle)
    implementation(buildTools.neoforge.legacyforge.plugin)
    implementation(project(":core-plugin"))
    implementation(project(":conventions-support"))
    implementation(project(":platform-plugin"))
}

gradlePlugin {
    plugins {
        create("multiloaderForge") {
            id = "com.iamkaf.multiloader.forge"
            implementationClass = "com.iamkaf.multiloader.forge.MultiloaderForgePlugin"
            displayName = "Multiloader Forge Plugin"
            description = "Applies invariant Forge loader wiring for multiloader projects."
        }
    }
}
