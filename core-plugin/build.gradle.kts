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
        create("multiloaderCore") {
            id = "com.iamkaf.multiloader.core"
            implementationClass = "com.iamkaf.multiloader.core.MultiloaderCorePlugin"
            displayName = "Multiloader Core Plugin"
            description = "Marker plugin for stable multiloader convention plugin resolution."
        }
    }
}
