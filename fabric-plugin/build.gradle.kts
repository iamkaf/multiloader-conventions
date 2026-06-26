plugins {
    `java-gradle-plugin`
    id("org.gradle.kotlin.kotlin-dsl")
    groovy
}

repositories {
    maven {
        url = uri("https://maven.fabricmc.net")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("project.java").get().toInt())
    }
}

dependencies {
    implementation(localGroovy())
    implementation(buildTools.fabric.loom)
    implementation(project(":core-plugin"))
    implementation(project(":conventions-support"))
    implementation(project(":platform-plugin"))
}

gradlePlugin {
    plugins {
        create("multiloaderFabric") {
            id = "com.iamkaf.multiloader.fabric"
            implementationClass = "com.iamkaf.multiloader.fabric.MultiloaderFabricPlugin"
            displayName = "Multiloader Fabric Plugin"
            description = "Applies invariant Fabric loader wiring for multiloader projects."
        }
    }
}
