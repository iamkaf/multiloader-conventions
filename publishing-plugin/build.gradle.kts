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
    implementation(localGroovy())
    implementation(project(":conventions-support"))
    implementation("org.apache.maven:maven-artifact:3.9.9")

    testImplementation(localGroovy())
    testImplementation(gradleTestKit())
    testImplementation("org.spockframework:spock-core:2.4-M1-groovy-4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("multiloaderPublishing") {
            id = "com.iamkaf.multiloader.publishing"
            implementationClass = "com.iamkaf.multiloader.publishing.MultiloaderPublishingPlugin"
            displayName = "Multiloader Publishing Plugin"
            description = "Stages multiloader release artifacts and writes their release manifest."
        }
    }
}
