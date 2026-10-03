package com.iamkaf.multiloader.forge

import com.iamkaf.multiloader.support.adapters.MixinRefmapInjection
import net.minecraftforge.gradle.MavenizerInstance
import net.minecraftforge.gradle.MinecraftExtensionForProject
import net.minecraftforge.renamer.gradle.RenamerExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.bundling.Jar

/**
 * Forge 1.20.2 through 1.20.4 runs SRG member names in production, but ForgeGradle 7 builds those lines against
 * Mojang names and has no reobf step. Forge's Renamer Gradle plugin supplies the missing half:
 *
 * - `jar` stays the Mojang-named dev jar, moved to `build/devlibs`.
 * - `reobfJar` remaps it to SRG into `build/libs` under the same name. Maven and releases publish this jar.
 * - The Mixin annotation processor writes `<modid>.refmap.json` from the same mappings, and its shadow and
 *   accessor mappings feed `reobfJar`.
 * - Mod jars built by these conventions, such as Amber from Maven, reach dev classpaths remapped back to Mojang
 *   names, and dev runs rewrite the SRG names in their refmaps.
 */
internal object ForgeSrgRemap {
    // The Renamer that Renamer Gradle 1.1.0 runs for reobfJar.
    private const val RENAMER = "net.minecraftforge:renamer:2.2.0:all"
    private val MAPPINGS = Attribute.of("com.iamkaf.multiloader.forgeMappings", String::class.java)

    fun configure(
        project: Project,
        forgeDependency: MavenizerInstance,
        mixinConfigs: List<String>,
        modId: String,
        mixinVersion: String,
    ) {
        project.pluginManager.apply("net.minecraftforge.renamer")
        val renamer = project.extensions.getByType(RenamerExtension::class.java)
        val toSrg = project.files(forgeDependency.toSrgFile)
        renamer.setMappings(toSrg)

        val jar = project.tasks.named("jar", Jar::class.java)
        val reobfMappings: FileCollection =
            if (mixinConfigs.isEmpty()) toSrg else configureMixinRefmap(project, renamer, jar, mixinConfigs, modId, mixinVersion)
        val reobfJar = renamer.classes("reobfJar") {
            input.set(jar.flatMap { it.archiveFile })
            map.setFrom(reobfMappings)
            output.set(project.layout.buildDirectory.dir("libs").zip(jar.flatMap { it.archiveFileName }) { dir, name -> dir.file(name) })
        }

        // The dev jar keeps the release file name, so it moves aside for the SRG jar.
        jar.configure { destinationDirectory.set(project.layout.buildDirectory.dir("devlibs")) }

        // Publish the SRG jar in place of the dev jar.
        listOf("apiElements", "runtimeElements").forEach { name ->
            project.configurations.named(name) {
                outgoing.artifacts.clear()
                outgoing.artifact(reobfJar)
            }
        }

        configureDevDependencyRemapping(project, toSrg)
        configureDevRefmapRemapping(project, renamer, forgeDependency)
    }

    /**
     * Every jar carries `srg` as an artifact attribute, and the main and test classpaths ask for `mojang`, so
     * each jar on them passes through [SrgToMojangTransform]. Other resolutions, such as jar-in-jar, keep SRG jars.
     */
    private fun configureDevDependencyRemapping(project: Project, toSrg: FileCollection) {
        val renamerTool = project.configurations.create("multiloaderRenamer") {
            isCanBeConsumed = false
            isTransitive = false
        }
        project.dependencies.add(renamerTool.name, RENAMER)

        project.dependencies.attributesSchema.attribute(MAPPINGS)
        project.dependencies.artifactTypes.maybeCreate(ArtifactTypeDefinition.JAR_TYPE).attributes.attribute(MAPPINGS, "srg")
        project.dependencies.registerTransform(SrgToMojangTransform::class.java) {
            from.attribute(MAPPINGS, "srg").attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
            to.attribute(MAPPINGS, "mojang").attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
            parameters {
                renamerClasspath.from(renamerTool)
                mappings.from(toSrg)
            }
        }
        project.extensions.getByType(SourceSetContainer::class.java).configureEach {
            listOf(compileClasspathConfigurationName, runtimeClasspathConfigurationName).forEach { name ->
                project.configurations.named(name) { attributes.attribute(MAPPINGS, "mojang") }
            }
        }
    }

    private fun configureMixinRefmap(
        project: Project,
        renamer: RenamerExtension,
        jar: TaskProvider<Jar>,
        mixinConfigs: List<String>,
        modId: String,
        mixinVersion: String,
    ): FileCollection {
        val mainSourceSet = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
        project.dependencies.add("annotationProcessor", "org.spongepowered:mixin:$mixinVersion:processor")

        val mixin = renamer.mixin
        mixinConfigs.forEach(mixin::config)
        // Renamer names the refmap `<prefix>.refmap.json` and packs it into the jar.
        mixin.source(mainSourceSet, modId)
        mixin.jar(jar)
        project.tasks.named(mainSourceSet.compileJavaTaskName, JavaCompile::class.java) {
            // Loader-added targets, such as Forge's onSheared, have no SRG mapping and keep their name at runtime.
            options.compilerArgs.add("-AMSG_NO_OBFDATA_FOR_TARGET=warning")
        }
        MixinRefmapInjection.nameRefmapInConfigs(project, mixinConfigs, "$modId.refmap.json")

        // The official-to-SRG mappings plus the processor's mappings for mixin shadows and accessors.
        return project.files(mixin.generatedMappings.flatMap { it.output })
    }

    /**
     * Dev runs use Mojang names, but the refmaps of remapped libraries still point at SRG names. Mixin rewrites
     * refmap entries through an SRG-format file, so give it the reversed official-to-SRG mappings.
     */
    private fun configureDevRefmapRemapping(project: Project, renamer: RenamerExtension, forgeDependency: MavenizerInstance) {
        val srgToMojang = renamer.convert("srgToMojangMappings", forgeDependency.toSrgFile, "srg") {
            reverse.set(true)
        }
        val srgToMojangFile = srgToMojang.flatMap { it.output }.map { it.asFile }
        project.extensions.getByType(MinecraftExtensionForProject::class.java).runs.configureEach {
            systemProperty("mixin.env.remapRefMap", "true")
            systemProperty("mixin.env.refMapRemappingFile", srgToMojangFile)
        }
        project.tasks.withType(JavaExec::class.java).matching { it.name.startsWith("run") }.configureEach {
            dependsOn(srgToMojang)
        }
    }
}
