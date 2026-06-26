plugins {
    `java-gradle-plugin`
    id("org.gradle.kotlin.kotlin-dsl")
    groovy
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("project.java").get().toInt())
    }
}

dependencies {
    implementation(project(":conventions-support"))
    implementation(project(":common-plugin"))
}

gradlePlugin {
    plugins {
        create("multiloaderPlatform") {
            id = "com.iamkaf.multiloader.platform"
            implementationClass = "com.iamkaf.multiloader.platform.MultiloaderPlatformPlugin"
            displayName = "Multiloader Platform Plugin"
            description = "Applies shared loader-side bridge wiring to consume common sources and resources."
        }
    }
}
