package com.iamkaf.multiloader.forge

import com.iamkaf.multiloader.platform.MultiloaderPlatformPlugin
import com.iamkaf.multiloader.support.ConventionSupport
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.language.jvm.tasks.ProcessResources

class MultiloaderForgePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        if (!isStonecutterForgeProject(project)) {
            applyFlatForgePlugin(project)
            return
        }

        applyStonecutterForgePlugin(project)
    }

    private static void applyFlatForgePlugin(Project project) {
        project.pluginManager.apply(MultiloaderPlatformPlugin)
        project.pluginManager.apply('net.minecraftforge.gradle')
        def minecraftVersion = ConventionSupport.versionAlias(project, 'minecraft')
        requireSupportedForgeVersion(project, minecraftVersion)
        def usesUnobfuscatedMinecraft = ConventionSupport.isUnobfuscatedMinecraft(project)

        def mixinConfigs = ConventionSupport.collectMixinConfigs(project, 'forge')
        project.tasks.named('jar', Jar).configure { task ->
            if (!mixinConfigs.isEmpty()) {
                task.manifest.attributes(['MixinConfigs': mixinConfigs.join(',')])
            }
        }

        def accessTransformerFile = project.file('src/main/resources/META-INF/accesstransformer.cfg')
        project.extensions.configure('minecraft') { minecraft ->
            if (!usesUnobfuscatedMinecraft) {
                minecraft.mappings(channel: 'official', version: minecraftVersion)
            }

            if (accessTransformerFile.exists()) {
                minecraft.accessTransformer = accessTransformerFile
            }

            minecraft.runs {
                configureEach {
                    workingDir.convention(project.layout.projectDirectory.dir('run'))
                    mixinConfigs.each { mixinConfig ->
                        args '--mixin.config', mixinConfig
                    }
                    environment 'MOD_CLASSES', '{source_roots}'
                }

                register('client') {
                }

                register('server') {
                    args '--nogui'
                }
            }

            ['client', 'server'].each { runName ->
                minecraft.runs.named(runName).configure { run ->
                    run.mods {
                        "${ConventionSupport.requiredProp(project, 'mod.id')}" {
                            source project.sourceSets.main
                        }
                    }
                }
            }
        }

        project.repositories {
            project.minecraft.mavenizer(it)
            if (project.fg.hasProperty('forgeMaven')) {
                maven project.fg.forgeMaven
            }
            if (project.fg.hasProperty('minecraftLibsMaven')) {
                maven project.fg.minecraftLibsMaven
            }
        }

        if (!usesUnobfuscatedMinecraft) {
            project.dependencies {
                def targetMinecraftVersion = ConventionSupport.versionAlias(project, 'minecraft')
                def forgeVersion = ConventionSupport.versionAlias(project, 'forge')
                def forgeArtifactVersion = forgeVersion.startsWith("${targetMinecraftVersion}-") ? forgeVersion : "${targetMinecraftVersion}-${forgeVersion}"
                def forgeCoordinate = "net.minecraftforge:forge:${forgeArtifactVersion}"
                implementation project.minecraft.dependency(
                    forgeCoordinate
                )
                implementation('net.sf.jopt-simple:jopt-simple:5.0.4') {
                    version {
                        strictly '5.0.4'
                    }
                }
            }
        } else {
            project.dependencies {
                implementation project.minecraft.dependency("net.minecraftforge:forge:${ConventionSupport.versionAlias(project, 'forge')}")
                implementation('net.sf.jopt-simple:jopt-simple:5.0.4') {
                    version {
                        strictly '5.0.4'
                    }
                }
            }
        }

        project.extensions.getByType(SourceSetContainer).configureEach { sourceSet ->
            def dir = project.layout.buildDirectory.dir("sourcesSets/$sourceSet.name")
            sourceSet.output.resourcesDir = dir
            sourceSet.java.destinationDirectory = dir
        }
    }

    private static void applyStonecutterForgePlugin(Project project) {
        project.pluginManager.apply('com.iamkaf.multiloader.core')
        project.pluginManager.apply('java-library')
        project.pluginManager.apply('maven-publish')
        project.pluginManager.apply('net.minecraftforge.gradle')

        def requiredProp = { String name -> project.ext.requiredProp.call(project, name) }
        def optionalProp = { String name -> project.ext.optionalProp.call(project, name) }
        def minecraftVersion = requiredProp('project.minecraft')
        requireSupportedForgeVersion(project, minecraftVersion)
        def catalog = project.ext.catalogFor.call(project, minecraftVersion) as VersionCatalog
        def library = { String alias -> project.ext.library.call(catalog, alias) }
        def versionOrNull = { String alias -> project.ext.versionOrNull.call(catalog, alias) }
        def useUnobfuscatedMinecraft = project.ext.useUnobfuscatedMinecraft.call(minecraftVersion)
        def modId = requiredProp('mod.id')
        def modName = requiredProp('mod.name')
        def loader = requiredProp('loader')
        def teaKitVersion = versionOrNull('teakit')
        def teaKitLibrary = catalog.findLibrary('teakit-forge')
        def hasTeaKit = teaKitLibrary.present && teaKitVersion != null && teaKitVersion != 'null'
        def useTeaKit = project.providers.systemProperty("${modId}.withTeaKit")
            .orElse(project.providers.gradleProperty("${modId}.withTeaKit"))
            .map { it.toBoolean() }
            .orElse(false)
            .get()
        def useLegacyTeaKitRunHack = ['1.16.5', '1.17.1', '1.18', '1.18.1', '1.18.2'].contains(minecraftVersion)
        def accessTransformerFile = project.rootProject.file('common/src/main/resources/META-INF/accesstransformer.cfg')
        def mixinConfigs = project.ext.mixinConfigs.call(project, loader)
        def commonProject = project.project(":common:${minecraftVersion}")
        def commonGeneratedJavaDir = commonProject.layout.buildDirectory.dir('generated/stonecutter/main/java')
        def commonGeneratedResourcesDir = commonProject.layout.buildDirectory.dir('generated/stonecutter/main/resources')
        def generatedJavaDir = project.layout.buildDirectory.dir('generated/stonecutter/main/java')
        def generatedResourcesDir = project.layout.buildDirectory.dir('generated/stonecutter/main/resources')
        def mergedJavaDir = project.layout.buildDirectory.dir('generated/merged/main/java')
        def versionDir = project.rootProject.file("versions/${minecraftVersion}")

        project.group = requiredProp('project.group')
        project.version = requiredProp('project.version')

        project.extensions.configure(BasePluginExtension) { base ->
            base.archivesName = "${modId}-${loader}"
        }

        project.ext.sharedRepositories.call(project)

        project.tasks.register('stageMergedJavaSources', Sync) { task ->
            task.duplicatesStrategy = DuplicatesStrategy.INCLUDE
            task.dependsOn(commonProject.tasks.named('stonecutterGenerate'))
            task.dependsOn(project.tasks.named('stonecutterGenerate'))
            task.from(commonGeneratedJavaDir)
            task.from(generatedJavaDir)
            def versionCommonJavaDir = versionDir.toPath().resolve('common/src/main/java').toFile()
            if (versionCommonJavaDir.isDirectory()) {
                task.from(versionCommonJavaDir)
            }
            def versionLoaderJavaDir = versionDir.toPath().resolve('forge/src/main/java').toFile()
            if (versionLoaderJavaDir.isDirectory()) {
                task.from(versionLoaderJavaDir)
            }
            task.into(mergedJavaDir)
        }

        project.sourceSets {
            main {
                java.srcDirs = [mergedJavaDir.get().asFile]
                resources.srcDirs = [
                    versionDir.toPath().resolve('common/src/main/resources').toFile(),
                    versionDir.toPath().resolve('forge/src/main/resources').toFile(),
                    commonGeneratedResourcesDir.get().asFile,
                    generatedResourcesDir.get().asFile,
                    project.rootProject.file('src/main/generated'),
                ]
            }
        }

        project.extensions.configure(JavaPluginExtension) { java ->
            java.withSourcesJar()
            java.withJavadocJar()
            java.toolchain.languageVersion = JavaLanguageVersion.of(requiredProp('project.java').toInteger())
        }

        project.tasks.withType(JavaCompile).configureEach {
            options.encoding = 'UTF-8'
        }

        project.tasks.withType(ProcessResources).configureEach {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            exclude('.cache/**')
        }

        project.tasks.named('sourcesJar', Jar).configure {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            exclude('.cache/**')
        }

        ['compileJava', 'processResources', 'sourcesJar', 'javadoc'].each { taskName ->
            project.tasks.named(taskName).configure {
                dependsOn commonProject.tasks.named('stonecutterGenerate')
                dependsOn project.tasks.named('stonecutterGenerate')
                dependsOn project.tasks.named('stageMergedJavaSources')
            }
        }

        project.repositories {
            project.minecraft.mavenizer(delegate)
            if (project.fg.hasProperty('forgeMaven')) {
                maven project.fg.forgeMaven
            }
            if (project.fg.hasProperty('minecraftLibsMaven')) {
                maven project.fg.minecraftLibsMaven
            }
        }

        project.extensions.configure('minecraft') { minecraft ->
            if (!useUnobfuscatedMinecraft) {
                minecraft.mappings(channel: 'official', version: minecraftVersion)
            }

            if (accessTransformerFile.exists()) {
                minecraft.accessTransformer = accessTransformerFile
            }

            minecraft.runs {
                configureEach {
                    workingDir.convention(project.layout.projectDirectory.dir('run'))
                    mixinConfigs.each { mixinConfig ->
                        args '--mixin.config', mixinConfig
                    }
                    environment 'MOD_CLASSES', '{source_roots}'
                }

                register('client') {
                }

                register('server') {
                    args '--nogui'
                }
            }

            ['client', 'server'].each { runName ->
                minecraft.runs.named(runName).configure { run ->
                    run.mods {
                        "${modId}" {
                            source project.sourceSets.main
                        }
                    }
                }
            }
        }

        project.dependencies {
            compileOnly library('mixin')
            compileOnly library('mixin-extras')
            annotationProcessor library('mixin-extras')
            implementation library('gson')

            def forgeCoordinate
            if (useUnobfuscatedMinecraft) {
                forgeCoordinate = "net.minecraftforge:forge:${versionOrNull('forge')}"
            } else {
                def forgeVersion = versionOrNull('forge')
                def forgeArtifactVersion = forgeVersion.startsWith("${minecraftVersion}-") ? forgeVersion : "${minecraftVersion}-${forgeVersion}"
                forgeCoordinate = "net.minecraftforge:forge:${forgeArtifactVersion}"
            }
            implementation project.minecraft.dependency(forgeCoordinate)

            implementation('net.sf.jopt-simple:jopt-simple:5.0.4') {
                version {
                    strictly '5.0.4'
                }
            }

            if (useTeaKit && hasTeaKit && !useLegacyTeaKitRunHack) {
                runtimeOnly teaKitLibrary.get()
            }
        }

        project.extensions.getByType(SourceSetContainer).configureEach { sourceSet ->
            def dir = project.layout.buildDirectory.dir("sourcesSets/$sourceSet.name")
            sourceSet.output.resourcesDir = dir.get().asFile
            sourceSet.java.destinationDirectory = dir
        }

        def expandProps = project.ext.expandProps.call(project, minecraftVersion, loader, catalog)
        def jsonExpandProps = expandProps.collectEntries { key, value ->
            [(key): value instanceof String ? value.replace('\n', '\\\\n') : value]
        }

        project.tasks.named('processResources', ProcessResources).configure {
            inputs.properties(expandProps.findAll { _, value -> value != null })

            filesMatching(['META-INF/mods.toml']) {
                expand(expandProps)
            }

            filesMatching(['pack.mcmeta', '*.mixins.json']) {
                expand(jsonExpandProps)
            }
        }

        project.tasks.named('jar', Jar).configure {
            manifest.attributes([
                'Specification-Title'   : modName,
                'Specification-Vendor'  : optionalProp('mod.authors'),
                'Specification-Version' : project.version.toString(),
                'Implementation-Title'  : loader,
                'Implementation-Version': project.version.toString(),
                'Implementation-Vendor' : optionalProp('mod.authors'),
                'Built-On-Minecraft'    : minecraftVersion,
                'Built-By'              : 'multiloader-conventions',
            ])

            if (!mixinConfigs.isEmpty()) {
                manifest.attributes(['MixinConfigs': mixinConfigs.join(',')])
            }
        }

        project.extensions.configure(PublishingExtension) { publishing ->
            publishing.publications {
                mavenJava(MavenPublication) {
                    from project.components.java
                    artifactId = project.base.archivesName.get()
                }
            }
        }

        project.ext.publishingRepositories.call(project.extensions.getByType(PublishingExtension), project.version.toString())
    }

    private static void requireSupportedForgeVersion(Project project, String minecraftVersion) {
        if (minecraftVersion == '1.16.5') {
            return
        }

        if (minecraftVersion == '1.14.4' ||
            minecraftVersion == '1.15' ||
            minecraftVersion?.startsWith('1.15.') ||
            minecraftVersion == '1.16' ||
            minecraftVersion?.startsWith('1.16.')) {
            throw new GradleException("Forge convention support starts at Minecraft 1.17. Keep ${project.path} on a repo-local legacy setup for ${minecraftVersion}.")
        }
    }

    private static boolean isStonecutterForgeProject(Project project) {
        project.parent?.name == 'forge'
    }
}
