package com.iamkaf.multiloader.support

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.PublishingExtension

import java.util.Properties

class StonecutterConventionSupport {

    private static final List<String> LEGACY_FABRIC_ONLY = [
        '1.14.4', '1.15', '1.15.1', '1.15.2',
        '1.16', '1.16.1', '1.16.2', '1.16.3', '1.16.4', '1.16.5',
        '1.17'
    ]

    private static final Map<String, String> FORGE_LOADER_RANGES = [
        '1.16.4':'[36,)', '1.16.5':'[36,)', '1.17.1':'[37,)',
        '1.18':'[38,)', '1.18.1':'[39,)', '1.18.2':'[40,)',
        '1.19':'[41,)', '1.19.1':'[42,)', '1.19.2':'[43,)', '1.19.3':'[44,)', '1.19.4':'[45,)',
        '1.20':'[46,)', '1.20.1':'[47,)', '1.20.2':'[48,)', '1.20.3':'[49,)', '1.20.4':'[49,)', '1.20.6':'[50,)',
        '1.21':'[51,)', '1.21.1':'[52,)', '1.21.3':'[53,)', '1.21.4':'[54,)', '1.21.5':'[55,)', '1.21.6':'[56,)',
        '1.21.7':'[57,)', '1.21.8':'[58,)', '1.21.9':'[59,)', '1.21.10':'[60,)', '1.21.11':'[61,)',
        '26.1':'[62,)', '26.1.1':'[62,)', '26.1.2':'[62,)'
    ]

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
                } else {
                    versionProps.putAll(versionMetadata(versionKey))
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
        def directVersionDir = project.rootProject.file("versions/${project.name}")
        if (directVersionDir.isDirectory()) {
            return project.name
        }

        def stonecutterBuild = project.extensions.findByName('stonecutterBuild') ?: project.extensions.findByName('stonecutter')
        def currentVersion = stonecutterBuild?.current?.version?.toString()
        if (currentVersion != null && !currentVersion.isBlank()) {
            return currentVersion
        }

        null
    }

    private static Properties versionMetadata(String versionKey) {
        def props = new Properties()
        props.setProperty('project.minecraft', versionKey)
        props.setProperty('project.version', "11.0.0+${versionKey}")
        props.setProperty('project.java', javaVersion(versionKey))
        props.setProperty('project.build-java', buildJavaVersion(versionKey))
        props.setProperty('project.enabled-loaders', enabledLoaders(versionKey))
        props.setProperty('mod.minecraft-range', "[${versionKey}, ${nextMinecraftUpperBound(versionKey)})")
        props.setProperty('mod.fabric-range', ">=${versionKey}")
        applyMixinCompat(props, versionKey)

        def forgeRange = FORGE_LOADER_RANGES[versionKey]
        if (forgeRange != null) {
            props.setProperty('mod.forge-loader-range', forgeRange)
        }

        if (enabledLoaders(versionKey).contains('neoforge')) {
            props.setProperty('mod.neoforge-loader-range', '[4,)')
        }

        props
    }

    private static String enabledLoaders(String versionKey) {
        if (LEGACY_FABRIC_ONLY.contains(versionKey) || versionKey == '1.20.5') {
            return 'fabric'
        }
        if (versionKey == '1.21.2') {
            return 'fabric,neoforge'
        }
        if (versionKey == '1.21.1' || versionKey.startsWith('26.') || (versionKey.startsWith('1.21.') && !['1.21.2'].contains(versionKey))) {
            return 'fabric,forge,neoforge'
        }
        return 'fabric,forge'
    }

    private static String javaVersion(String versionKey) {
        if (versionKey.startsWith('26.')) {
            return '25'
        }
        if (versionKey.startsWith('1.14') || versionKey.startsWith('1.15') || versionKey.startsWith('1.16')) {
            return '8'
        }
        if (versionKey == '1.17' || versionKey == '1.17.1') {
            return '16'
        }
        if (versionKey == '1.20.5' || versionKey == '1.20.6' || versionKey.startsWith('1.21')) {
            return '21'
        }
        return '17'
    }

    private static String buildJavaVersion(String versionKey) {
        if (versionKey.startsWith('26.')) {
            return '25'
        }
        if (versionKey.startsWith('1.14') || versionKey.startsWith('1.15') || versionKey.startsWith('1.16')) {
            return '17'
        }
        return '21'
    }

    private static String nextMinecraftUpperBound(String versionKey) {
        if (versionKey.startsWith('26.')) {
            return '27'
        }
        def parts = versionKey.tokenize('.')
        if (parts.isEmpty() || parts[0] != '1' || parts.size() < 2) {
            return versionKey
        }
        return "1.${(parts[1] as int) + 1}"
    }

    private static void applyMixinCompat(Properties props, String versionKey) {
        if (versionKey.startsWith('1.14') || versionKey.startsWith('1.15') || versionKey.startsWith('1.16')) {
            props.setProperty('mixin.compat.common', 'JAVA_8')
            props.setProperty('mixin.compat.fabric', 'JAVA_8')
            if (enabledLoaders(versionKey).contains('forge')) {
                props.setProperty('mixin.compat.forge', 'JAVA_8')
            }
            return
        }

        if (versionKey == '1.17' || versionKey == '1.17.1') {
            props.setProperty('mixin.compat.common', 'JAVA_16')
            props.setProperty('mixin.compat.fabric', 'JAVA_16')
            if (enabledLoaders(versionKey).contains('forge')) {
                props.setProperty('mixin.compat.forge', 'JAVA_16')
            }
            return
        }

        if (versionKey.startsWith('1.18') || versionKey.startsWith('1.19') || versionKey == '1.20' || versionKey == '1.20.1' || versionKey == '1.20.2' || versionKey == '1.20.3' || versionKey == '1.20.4') {
            props.setProperty('mixin.compat.common', 'JAVA_17')
            props.setProperty('mixin.compat.fabric', 'JAVA_17')
            if (enabledLoaders(versionKey).contains('forge')) {
                props.setProperty('mixin.compat.forge', 'JAVA_17')
            }
        }
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
