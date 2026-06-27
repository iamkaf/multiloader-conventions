plugins {
    `java-gradle-plugin`
    id("org.gradle.kotlin.kotlin-dsl")
    groovy
}

repositories {
    maven {
        url = uri("https://maven.fabricmc.net")
    }
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
    implementation(buildTools.fabric.loom)
    implementation(buildTools.neoforge.moddev.plugin)
    implementation(buildTools.neoforge.legacyforge.plugin)
    implementation(project(":core-plugin"))
    implementation(project(":conventions-support"))

    testImplementation(localGroovy())
    testImplementation("org.spockframework:spock-core:2.4-M1-groovy-4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("multiloaderCommon") {
            id = "com.iamkaf.multiloader.common"
            implementationClass = "com.iamkaf.multiloader.common.MultiloaderCommonPlugin"
            displayName = "Multiloader Common Plugin"
            description = "Applies shared Java, publishing, metadata, and artifact conventions for multiloader projects."
        }
    }
}
