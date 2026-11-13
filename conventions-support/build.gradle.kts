import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.gradle.kotlin.kotlin-dsl")
    groovy
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("project.java").get().toInt())
    }
}

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())

    testImplementation(localGroovy())
    testImplementation(gradleTestKit())
    testImplementation("org.spockframework:spock-core:2.4-M1-groovy-4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

tasks.named<GroovyCompile>("compileGroovy") {
    val compileKotlin = tasks.named<KotlinCompile>("compileKotlin")
    dependsOn(compileKotlin)
    classpath += files(compileKotlin.flatMap { it.destinationDirectory })
}

val buildToolsCatalog = extensions.getByType<VersionCatalogsExtension>().named("buildTools")
val generatedBuildToolsResources = layout.buildDirectory.dir("generated/build-tools-resources")

val generateBuildToolsVersionResource = tasks.register("generateBuildToolsVersionResource") {
    val outputFile = generatedBuildToolsResources.map {
        it.file("com/iamkaf/multiloader/support/build-tools.properties")
    }
    fun requiredVersion(alias: String): String =
        buildToolsCatalog.findVersion(alias)
            .orElseThrow { GradleException("Missing build-tools catalog version '$alias'") }
            .requiredVersion

    val versions = linkedMapOf(
        "foojayResolverConventionPlugin" to requiredVersion("foojay-resolver-convention-plugin"),
        "stonecutterPlugin" to requiredVersion("stonecutter"),
        "neoforgeModDevPlugin" to requiredVersion("neoforge-moddev"),
        "neoforgeLegacyForgePlugin" to requiredVersion("neoforge-moddev"),
        "forgeGradlePlugin" to requiredVersion("forgegradle"),
        "amberLoom" to requiredVersion("amber-loom"),
        "amberMavenizer" to requiredVersion("amber-mavenizer"),
        "amberNeoformRuntime" to requiredVersion("amber-neoform-runtime"),
    )
    inputs.property("versions", versions)
    outputs.file(outputFile)

    doLast {
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            (versions.map { (name, version) -> "$name=$version" } + "").joinToString(System.lineSeparator()),
        )
    }
}

sourceSets.named("main") {
    resources.srcDir(generatedBuildToolsResources)
}
tasks.named<ProcessResources>("processResources") {
    dependsOn(generateBuildToolsVersionResource)
}
