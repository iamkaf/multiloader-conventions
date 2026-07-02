pluginManagement {
    includeBuild("../../")
    repositories {
        maven {
            url = uri("https://maven.kaf.sh")
            name = "Kaf Maven"
            content {
                includeGroupByRegex("com\\.iamkaf(\\..*)?")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("com.iamkaf.multiloader.settings")
}
