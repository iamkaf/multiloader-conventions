package com.iamkaf.platform

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

class PlatformVersionCatalogPlugin implements Plugin<Settings> {

    @Override
    void apply(Settings settings) {
        settings.dependencyResolutionManagement {
            versionCatalogs {
                libs {
                    // Core Versions (for reference in plugins)
                    plugin('loom', 'fabric-loom').version(property(settings, 'fabric_loom_plugin_version'))
                    plugin('neoforge-moddev', 'net.neoforged.moddev').version(property(settings, 'neoforge_moddev_plugin_version'))
                    plugin('modpublisher', 'com.hypherionmc.modutils.modpublisher').version(property(settings, 'modpublisher_plugin_version'))

                    // Core Dependencies
                    library('minecraft', 'com.mojang', 'minecraft').version(property(settings, 'minecraft_version'))
                    library('gson', 'com.google.code.gson', 'gson').version(property(settings, 'gson_version'))

                    // Mixin
                    library('mixin', 'org.spongepowered', 'mixin').version(property(settings, 'mixin_version'))
                    library('mixin-extras', 'io.github.llamalad7', 'mixinextras-common').version(property(settings, 'mixinextras_version'))

                    // Fabric
                    library('fabric-loader', 'net.fabricmc', 'fabric-loader').version(property(settings, 'fabric_loader_version'))
                    library('fabric-api', 'net.fabricmc.fabric-api', 'fabric-api').version(property(settings, 'fabric_version'))

                    // NeoForge
                    library('neoforge', 'net.neoforged', 'neoforge').version(property(settings, 'neoforge_version'))

                    // Forge
                    library('forge', 'net.minecraftforge', 'forge').version(property(settings, 'forge_version'))

                    // Parchment
                    library('parchment', "org.parchmentmc.data", "parchment-${property(settings, 'parchment_minecraft')}").version(property(settings, 'parchment_version'))

                    // Amber (all platforms)
                    library('amber', 'com.iamkaf.amber', 'amber-common').version(property(settings, 'amber_version'))
                    library('amber-fabric', 'com.iamkaf.amber', 'amber-fabric').version(property(settings, 'amber_version'))
                    library('amber-neoforge', 'com.iamkaf.amber', 'amber-neoforge').version(property(settings, 'amber_version'))
                    library('amber-forge', 'com.iamkaf.amber', 'amber-forge').version(property(settings, 'amber_version'))
                }
            }

            repositories {
                mavenCentral()
                maven { url = 'https://maven.fabricmc.net/' }
                maven { url = 'https://maven.neoforged.net/releases' }
                maven { url = 'https://maven.minecraftforge.net/' }
                maven { url = 'https://repo.spongepowered.org/repository/maven-public/' }
                maven { url = 'https://maven.parchmentmc.org' }
                maven { url = 'https://maven.kaf.sh' }
            }
        }
    }

    private static String property(Settings settings, String name) {
        settings.hasProperty(name) ? settings.property(name) : System.getProperty(name)
    }
}
