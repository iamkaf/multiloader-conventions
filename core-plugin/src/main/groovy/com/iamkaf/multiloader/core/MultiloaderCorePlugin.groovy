package com.iamkaf.multiloader.core

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

class MultiloaderCorePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.pluginManager.apply(JavaLibraryPlugin)
        project.pluginManager.apply('maven-publish')

        configureRepositories(project)
        configureCoordinates(project)
        configureArchiveNaming(project)
        configureJava(project)
        configureResourceMetadata(project)
        configureManifests(project)
        configureLicensePackaging(project)
        configureCapabilities(project)
        configurePublishing(project)
        configureCommonArtifacts(project)
        configureLoaderBridge(project)
    }

    private static void configureRepositories(Project project) {
        project.repositories.mavenLocal()
        project.repositories.mavenCentral()
        project.repositories.maven {
            name = 'Sponge'
            url = project.uri('https://repo.spongepowered.org/repository/maven-public')
        }
        project.repositories.maven {
            name = 'ParchmentMC'
            url = project.uri('https://maven.parchmentmc.org/')
        }
        project.repositories.maven {
            name = 'NeoForge'
            url = project.uri('https://maven.neoforged.net/releases')
        }
        project.repositories.maven {
            name = 'BlameJared'
            url = project.uri('https://maven.blamejared.com')
        }
        project.repositories.maven {
            name = 'TerraformersMC'
            url = project.uri('https://maven.terraformersmc.com/')
        }
        project.repositories.maven {
            name = 'Kaf Maven'
            url = project.uri('https://maven.kaf.sh')
        }
        project.repositories.maven {
            name = 'Fuzs Mod Resources'
            url = project.uri('https://raw.githubusercontent.com/Fuzss/modresources/main/maven/')
        }
    }

    private static void configureCoordinates(Project project) {
        if (project.rootProject.findProperty('group') != null) {
            project.group = project.rootProject.findProperty('group').toString()
        }

        if (project.rootProject.findProperty('version') != null) {
            project.version = project.rootProject.findProperty('version').toString()
        }
    }

    private static void configureArchiveNaming(Project project) {
        project.extensions.configure(BasePluginExtension) { base ->
            base.archivesName = "${requiredProperty(project, 'mod_id')}-${project.name}"
        }
    }

    private static void configureJava(Project project) {
        def javaVersion = project.findProperty('java_version')?.toString()?.toInteger()
        project.extensions.configure(JavaPluginExtension) { java ->
            java.withSourcesJar()
            java.withJavadocJar()

            if (javaVersion != null) {
                java.toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
            }
        }

        def sourceSets = project.extensions.findByType(SourceSetContainer)
        sourceSets?.named('main') { main ->
            main.resources.srcDir(project.file('src/main/generated'))
        }

        project.tasks.withType(JavaCompile).configureEach { task ->
            task.options.encoding = 'UTF-8'
        }
    }

    private static void configureResourceMetadata(Project project) {
        project.tasks.withType(ProcessResources).configureEach { task ->
            def expandProps = buildExpandProperties(project)
            def inputProps = expandProps.findAll { _, value -> value != null }
            def jsonExpandProps = expandProps.collectEntries { key, value ->
                [(key): value instanceof String ? value.replace('\n', '\\\\n') : value]
            }

            task.filesMatching(['META-INF/mods.toml', 'META-INF/neoforge.mods.toml']) {
                expand(expandProps)
            }

            task.filesMatching(['pack.mcmeta', 'fabric.mod.json', '*.mixins.json']) {
                expand(jsonExpandProps)
            }

            task.inputs.properties(inputProps)
            task.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }

    private static void configureManifests(Project project) {
        project.tasks.named('jar', Jar).configure { task ->
            task.manifest.attributes([
                'Specification-Title'   : requiredProperty(project, 'mod_name'),
                'Specification-Vendor'  : optionalProperty(project, 'mod_author'),
                'Specification-Version' : project.version.toString(),
                'Implementation-Title'  : project.name,
                'Implementation-Version': project.version.toString(),
                'Implementation-Vendor' : optionalProperty(project, 'mod_author'),
                'Built-On-Minecraft'    : versionAlias(project, 'minecraft'),
                'Built-By'              : 'multiloader-conventions',
            ])
        }
    }

    private static void configureLicensePackaging(Project project) {
        def licenseFile = resolveLicenseFile(project)
        if (!licenseFile.exists()) {
            return
        }

        ['jar', 'sourcesJar'].each { taskName ->
            project.tasks.named(taskName, Jar).configure { task ->
                task.from(licenseFile) {
                    rename { "${it}_${requiredProperty(project, 'mod_name')}" }
                }
                task.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            }
        }
    }

    private static void configureCapabilities(Project project) {
        ['apiElements', 'runtimeElements', 'sourcesElements', 'javadocElements'].each { variant ->
            project.configurations.named(variant).configure { configuration ->
                configuration.outgoing.capability("${project.group}:${project.name}:${project.version}")
                configuration.outgoing.capability("${project.group}:${project.extensions.getByType(BasePluginExtension).archivesName.get()}:${project.version}")
                configuration.outgoing.capability("${project.group}:${requiredProperty(project, 'mod_id')}-${project.name}-${versionAlias(project, 'minecraft')}:${project.version}")
                configuration.outgoing.capability("${project.group}:${requiredProperty(project, 'mod_id')}:${project.version}")
            }

            project.extensions.configure(PublishingExtension) { publishing ->
                publishing.publications.configureEach { publication ->
                    publication.suppressPomMetadataWarningsFor(variant)
                }
            }
        }
    }

    private static void configurePublishing(Project project) {
        project.extensions.configure(PublishingExtension) { publishing ->
            publishing.publications.register('mavenJava', MavenPublication) { publication ->
                publication.groupId = project.group.toString()
                publication.artifactId = project.extensions.getByType(BasePluginExtension).archivesName.get()
                publication.from(project.components.getByName('java'))
            }
            publishing.repositories.maven { repo ->
                repo.name = 'KafMaven'
                repo.url = project.uri(project.version.toString().endsWith('-SNAPSHOT')
                    ? 'https://z.kaf.sh/snapshots'
                    : 'https://z.kaf.sh/releases')
                repo.credentials { credentials ->
                    credentials.username = System.getenv('MAVEN_PUBLISH_USERNAME')
                    credentials.password = System.getenv('MAVEN_PUBLISH_PASSWORD')
                }
            }
        }
    }

    private static void configureCommonArtifacts(Project project) {
        if (project.name != 'common') {
            return
        }

        def commonJava = project.configurations.maybeCreate('commonJava')
        commonJava.canBeResolved = false
        commonJava.canBeConsumed = true

        def commonResources = project.configurations.maybeCreate('commonResources')
        commonResources.canBeResolved = false
        commonResources.canBeConsumed = true

        def sourceSets = project.extensions.getByType(SourceSetContainer)
        project.artifacts {
            add('commonJava', sourceSets.named('main').get().java.sourceDirectories.singleFile)
            sourceSets.named('main').get().resources.sourceDirectories.files.each { directory ->
                add('commonResources', directory)
            }
        }
    }

    private static void configureLoaderBridge(Project project) {
        if (!(project.name in ['fabric', 'forge', 'neoforge'])) {
            return
        }

        def commonProject = project.rootProject.findProject(':common')
        if (commonProject == null) {
            return
        }

        def commonJava = project.configurations.maybeCreate('commonJava')
        commonJava.canBeResolved = true

        def commonResources = project.configurations.maybeCreate('commonResources')
        commonResources.canBeResolved = true

        project.dependencies.add('compileOnly', project.dependencies.project(path: ':common')) {
            capabilities {
                requireCapability("${project.group}:${requiredProperty(project, 'mod_id')}")
            }
        }
        project.dependencies.add('commonJava', project.dependencies.project(path: ':common', configuration: 'commonJava'))
        project.dependencies.add('commonResources', project.dependencies.project(path: ':common', configuration: 'commonResources'))

        project.tasks.named('compileJava', JavaCompile).configure { task ->
            task.dependsOn(commonJava)
            task.source(commonJava)
        }

        project.tasks.named('processResources', ProcessResources).configure { task ->
            task.dependsOn(commonResources)
            task.from(commonResources)
        }

        project.tasks.named('javadoc', Javadoc).configure { task ->
            task.dependsOn(commonJava)
            task.source(commonJava)
            task.options.addBooleanOption('Xdoclint:none', true)
            task.options.addBooleanOption('quiet', true)
        }

        project.tasks.named('sourcesJar', Jar).configure { task ->
            task.dependsOn(commonJava)
            task.from(commonJava)
            task.dependsOn(commonResources)
            task.from(commonResources)
        }
    }

    private static File resolveLicenseFile(Project project) {
        def repoRootLicense = project.rootProject.file('../LICENSE')
        repoRootLicense.exists() ? repoRootLicense : project.rootProject.file('LICENSE')
    }

    private static Map<String, Object> buildExpandProperties(Project project) {
        [
            'version'                      : project.version.toString(),
            'group'                        : project.group.toString(),
            'minecraft_version'            : versionAlias(project, 'minecraft'),
            'minecraft_version_range'      : requiredProperty(project, 'minecraft_version_range'),
            'fabric_version_range'         : optionalProperty(project, 'fabric_version_range'),
            'fabric_version'               : optionalVersionAlias(project, 'fabric-api'),
            'fabric_loader_version'        : optionalVersionAlias(project, 'fabric-loader'),
            'mod_menu_version'             : optionalVersionAlias(project, 'modmenu'),
            'mod_name'                     : requiredProperty(project, 'mod_name'),
            'mod_author'                   : optionalProperty(project, 'mod_author'),
            'mod_id'                       : requiredProperty(project, 'mod_id'),
            'license'                      : optionalProperty(project, 'license'),
            'description'                  : optionalProperty(project, 'description'),
            'neoforge_version'             : optionalVersionAlias(project, 'neoforge'),
            'neoforge_loader_version_range': optionalProperty(project, 'neoforge_loader_version_range'),
            'forge_version'                : optionalVersionAlias(project, 'forge'),
            'forge_loader_version_range'   : optionalProperty(project, 'forge_loader_version_range'),
            'amber_version'                : optionalVersionAlias(project, 'amber'),
            'parchment_minecraft'          : optionalVersionAlias(project, 'parchment-minecraft'),
            'parchment_version'            : optionalVersionAlias(project, 'parchment'),
            'credits'                      : optionalProperty(project, 'credits'),
            'java_version'                 : requiredProperty(project, 'java_version'),
        ]
    }

    private static String versionAlias(Project project, String alias) {
        def value = optionalVersionAlias(project, alias)
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required version catalog alias '${alias}' for ${project.path}")
        }
        value
    }

    private static String optionalVersionAlias(Project project, String alias) {
        def catalogs = project.extensions.findByType(VersionCatalogsExtension)
        if (catalogs == null) {
            return null
        }

        VersionCatalog libs = catalogs.named('libs')
        def version = libs.findVersion(alias)
        version.present ? version.get().requiredVersion : null
    }

    private static String requiredProperty(Project project, String propertyName) {
        def value = optionalProperty(project, propertyName)
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required property '${propertyName}' for ${project.path}")
        }
        value
    }

    private static String optionalProperty(Project project, String propertyName) {
        project.findProperty(propertyName)?.toString()
    }
}
