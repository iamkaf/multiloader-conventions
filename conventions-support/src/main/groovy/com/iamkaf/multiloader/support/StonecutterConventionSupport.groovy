package com.iamkaf.multiloader.support

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.PublishingExtension

import java.util.Properties

class StonecutterConventionSupport {

    static String requiredProp(Project project, String name) {
        def value = optionalProp(project, name)
        if (value == null || value.isBlank()) {
            throw new GradleException("Missing required property '${name}' for ${project.path}")
        }
        value
    }

    static String optionalProp(Project project, String name) {
        if (name == 'loader') {
            return project.parent?.name
        }

        def versionKey = resolveVersionKey(project)
        if (versionKey != null && !versionKey.isBlank()) {
            def cacheKey = "versionProps:${versionKey}"
            Properties versionProps
            if (project.rootProject.extensions.extraProperties.has(cacheKey)) {
                versionProps = project.rootProject.extensions.extraProperties.get(cacheKey) as Properties
            } else {
                versionProps = new Properties()
                def versionFile = project.rootProject.file("versions/${versionKey}/gradle.properties")
                if (versionFile.isFile()) {
                    versionFile.withInputStream(versionProps.&load)
                }
                project.rootProject.extensions.extraProperties.set(cacheKey, versionProps)
            }

            def versionValue = versionProps.getProperty(name)
            if (versionValue != null && !versionValue.isBlank()) {
                return versionValue
            }
        }

        def directValue = project.findProperty(name)?.toString()
        if (directValue != null && !directValue.isBlank()) {
            if (name == 'publish.game-versions' && directValue == 'auto') {
                return optionalProp(project, 'project.minecraft')
            }
            return directValue
        }

        null
    }

    private static String resolveVersionKey(Project project) {
        def directVersionDir = project.rootProject.file("versions/${project.name}/gradle.properties")
        if (directVersionDir.isFile()) {
            return project.name
        }

        def stonecutterBuild = project.extensions.findByName('stonecutterBuild') ?: project.extensions.findByName('stonecutter')
        def currentVersion = stonecutterBuild?.current?.version?.toString()
        if (currentVersion != null && !currentVersion.isBlank()) {
            return currentVersion
        }

        null
    }

    static String catalogName(String minecraftVersion) {
        "libsMc${minecraftVersion.replace('.', '').replace('-', '')}"
    }

    static VersionCatalog catalogFor(Project project, String minecraftVersion) {
        def catalogs = project.extensions.getByType(VersionCatalogsExtension)
        def versionCatalog = catalogs.find(catalogName(minecraftVersion))
        if (versionCatalog.present) {
            return versionCatalog.get()
        }
        return catalogs.named('libs')
    }

    static String versionOrNull(VersionCatalog catalog, String alias) {
        def version = catalog.findVersion(alias)
        if (!version.present) {
            return null
        }

        def resolved = version.get().requiredVersion
        if (resolved == null || resolved.isBlank() || resolved == 'null') {
            return null
        }

        resolved
    }

    static Object library(VersionCatalog catalog, String alias) {
        def dependency = catalog.findLibrary(alias)
        if (!dependency.present) {
            throw new GradleException("Missing library alias '${alias}'")
        }
        dependency.get()
    }

    static boolean useUnobfuscatedMinecraft(String minecraftVersion) {
        minecraftVersion.startsWith('26.')
    }

    static void sharedRepositories(Project project) {
        project.repositories {
            mavenLocal()
            mavenCentral()
            maven {
                name = 'TerraformersMC'
                url = project.uri('https://maven.terraformersmc.com/')
                metadataSources {
                    mavenPom()
                    artifact()
                }
            }
            maven { name = 'Nucleoid'; url = project.uri('https://maven.nucleoid.xyz/') }
            maven { name = 'Sponge'; url = project.uri('https://repo.spongepowered.org/repository/maven-public') }
            maven { name = 'ParchmentMC'; url = project.uri('https://maven.parchmentmc.org/') }
            maven { name = 'NeoForge'; url = project.uri('https://maven.neoforged.net/releases') }
            maven { name = 'BlameJared'; url = project.uri('https://maven.blamejared.com') }
            maven { name = 'Modrinth'; url = project.uri('https://api.modrinth.com/maven') }
            maven { name = 'Kaf Maven'; url = project.uri('https://maven.kaf.sh') }
            maven { name = 'Fuzs Mod Resources'; url = project.uri('https://raw.githubusercontent.com/Fuzss/modresources/main/maven/') }
        }
    }

    static void publishingRepositories(PublishingExtension publishing, String version) {
        publishing.repositories {
            maven {
                name = 'KafMaven'
                url = URI.create(version.endsWith('-SNAPSHOT')
                    ? 'https://z.kaf.sh/snapshots'
                    : 'https://z.kaf.sh/releases')
                credentials {
                    username = System.getenv('MAVEN_PUBLISH_USERNAME')
                    password = System.getenv('MAVEN_PUBLISH_PASSWORD')
                }
            }
        }
    }

    static List<String> mixinConfigs(Project project, String loader) {
        def modId = requiredProp(project, 'mod.id')
        [
            project.rootProject.file("common/src/main/resources/${modId}.mixins.json").exists() ? "${modId}.mixins.json" : null,
            project.rootProject.file("${loader}/src/main/resources/${modId}.${loader}.mixins.json").exists() ? "${modId}.${loader}.mixins.json" : null,
        ].findAll { it != null }
    }

    static Map<String, Object> expandProps(Project project, String minecraftVersion, String loader, VersionCatalog catalog) {
        [
            'version'                           : requiredProp(project, 'project.version'),
            'group'                             : requiredProp(project, 'project.group'),
            'minecraft_version'                 : minecraftVersion,
            'minecraft_version_range'           : optionalProp(project, 'mod.minecraft-range'),
            'fabric_version_range'              : optionalProp(project, 'mod.fabric-range'),
            'fabric_version'                    : versionOrNull(catalog, 'fabric-api'),
            'fabric_loader_version'             : versionOrNull(catalog, 'fabric-loader'),
            'mod_menu_version'                  : versionOrNull(catalog, 'modmenu'),
            'mod_name'                          : requiredProp(project, 'mod.name'),
            'mod_author'                        : optionalProp(project, 'mod.authors'),
            'mod_id'                            : requiredProp(project, 'mod.id'),
            'license'                           : optionalProp(project, 'mod.license'),
            'description'                       : optionalProp(project, 'mod.description'),
            'neoforge_version'                  : versionOrNull(catalog, 'neoforge'),
            'neoforge_loader_version_range'     : optionalProp(project, 'mod.neoforge-loader-range'),
            'forge_version'                     : versionOrNull(catalog, 'forge'),
            'forge_loader_version_range'        : optionalProp(project, 'mod.forge-loader-range'),
            'amber_version'                     : versionOrNull(catalog, 'amber'),
            'parchment_minecraft'               : versionOrNull(catalog, 'parchment-minecraft'),
            'parchment_version'                 : versionOrNull(catalog, 'parchment'),
            'credits'                           : optionalProp(project, 'mod.credits'),
            'java_version'                      : requiredProp(project, 'project.java'),
            'mixin_compat_common'               : requiredProp(project, 'mixin.compat.common'),
            'mixin_compat_fabric'               : requiredProp(project, 'mixin.compat.fabric'),
            'mixin_compat_forge'                : requiredProp(project, 'mixin.compat.forge'),
            'mixin_compat_neoforge'             : requiredProp(project, 'mixin.compat.neoforge'),
        ]
    }
}
