pluginManagement {
    includeBuild("../../")
    repositories {
        maven {
            url = uri("https://maven.kaf.sh")
            name = "Kaf Maven"
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("com.iamkaf.multiloader.settings")
}
