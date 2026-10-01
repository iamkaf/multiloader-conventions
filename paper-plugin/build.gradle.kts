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

repositories {
    maven { url = uri("https://maven.firstdarkdev.xyz/releases") }
}

dependencies {
    implementation(localGroovy())
    implementation(project(":conventions-support"))
    implementation(project(":publishing-plugin"))

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
        create("multiloaderPaper") {
            id = "com.iamkaf.multiloader.paper"
            implementationClass = "com.iamkaf.multiloader.paper.MultiloaderPaperPlugin"
            displayName = "Multiloader Paper Plugin"
            description = "Builds one Paper server plugin jar against the oldest supported Paper API and runs it on pinned Paper servers."
        }
    }
}
