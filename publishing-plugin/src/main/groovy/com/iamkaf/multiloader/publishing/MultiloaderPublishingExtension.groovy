package com.iamkaf.multiloader.publishing

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

import javax.inject.Inject

class MultiloaderPublishingExtension {
    final PublishingConfig config
    final PublishingMetadata metadata
    final Publish publish
    final Loaders loaders

    @Inject
    MultiloaderPublishingExtension(ObjectFactory objects) {
        this.config = objects.newInstance(PublishingConfig, objects)
        this.metadata = objects.newInstance(PublishingMetadata, objects)
        this.publish = objects.newInstance(Publish, objects)
        this.loaders = objects.newInstance(Loaders, objects)
    }

    void config(Closure<?> configure) {
        configure.delegate = config
        configure.resolveStrategy = Closure.DELEGATE_FIRST
        configure.call()
    }

    void metadata(Closure<?> configure) {
        configure.delegate = metadata
        configure.resolveStrategy = Closure.DELEGATE_FIRST
        configure.call()
    }

    void publish(Closure<?> configure) {
        configure.delegate = publish
        configure.resolveStrategy = Closure.DELEGATE_FIRST
        configure.call()
    }

    void loaders(Closure<?> configure) {
        configure.delegate = loaders
        configure.resolveStrategy = Closure.DELEGATE_FIRST
        configure.call()
    }

    static class PublishingConfig {
        final Property<Boolean> dryRun
        final Property<String> releaseType

        @Inject
        PublishingConfig(ObjectFactory objects) {
            this.dryRun = objects.property(Boolean).convention(false)
            this.releaseType = objects.property(String).convention('release')
        }
    }

    static class PublishingMetadata {
        final Property<String> displayName
        final Property<String> changelogText
        final Property<String> changelogFile

        @Inject
        PublishingMetadata(ObjectFactory objects) {
            this.displayName = objects.property(String)
            this.changelogText = objects.property(String)
            this.changelogFile = objects.property(String)
        }

        void changelog(Closure<?> configure) {
            configure.delegate = this
            configure.resolveStrategy = Closure.DELEGATE_FIRST
            configure.call()
        }

        void fromText(String text) {
            changelogText.set(text)
        }

        void fromFile(String path) {
            changelogFile.set(path)
        }
    }

    static class Publish {
        final Property<Boolean> disableEmptyJarCheck
        final Property<Boolean> isManualRelease
        final Modrinth modrinth
        final CurseForge curseforge
        final ListProperty<AdditionalFile> additionalFiles

        @Inject
        Publish(ObjectFactory objects) {
            this.disableEmptyJarCheck = objects.property(Boolean).convention(false)
            this.isManualRelease = objects.property(Boolean).convention(false)
            this.modrinth = objects.newInstance(Modrinth, objects)
            this.curseforge = objects.newInstance(CurseForge, objects)
            this.additionalFiles = objects.listProperty(AdditionalFile).convention([])
        }

        void modrinth(Closure<?> configure) {
            configure.delegate = modrinth
            configure.resolveStrategy = Closure.DELEGATE_FIRST
            configure.call()
        }

        void curseforge(Closure<?> configure) {
            configure.delegate = curseforge
            configure.resolveStrategy = Closure.DELEGATE_FIRST
            configure.call()
        }

        void additionalFile(String path, Closure<?> configure = null) {
            def file = new AdditionalFile(path)
            if (configure != null) {
                configure.delegate = file
                configure.resolveStrategy = Closure.DELEGATE_FIRST
                configure.call()
            }
            additionalFiles.add(file)
        }

        static class Modrinth {
            final Property<String> id
            final Property<String> token
            final Dependencies dependencies

            @Inject
            Modrinth(ObjectFactory objects) {
                this.id = objects.property(String)
                this.token = objects.property(String)
                this.dependencies = objects.newInstance(Dependencies, objects)
            }

            void dependencies(Closure<?> configure) {
                configure.delegate = dependencies
                configure.resolveStrategy = Closure.DELEGATE_FIRST
                configure.call()
            }
        }

        static class CurseForge {
            final Property<String> id
            final Property<String> token
            final Property<String> environment
            final ListProperty<String> javaVersions
            final Dependencies dependencies

            @Inject
            CurseForge(ObjectFactory objects) {
                this.id = objects.property(String)
                this.token = objects.property(String)
                this.environment = objects.property(String).convention('both')
                this.javaVersions = objects.listProperty(String).convention([])
                this.dependencies = objects.newInstance(Dependencies, objects)
            }

            void dependencies(Closure<?> configure) {
                configure.delegate = dependencies
                configure.resolveStrategy = Closure.DELEGATE_FIRST
                configure.call()
            }
        }

        static class Dependencies {
            final ListProperty<String> required
            final ListProperty<String> optional
            final ListProperty<String> incompatible
            final ListProperty<String> embedded

            @Inject
            Dependencies(ObjectFactory objects) {
                this.required = objects.listProperty(String).convention([])
                this.optional = objects.listProperty(String).convention([])
                this.incompatible = objects.listProperty(String).convention([])
                this.embedded = objects.listProperty(String).convention([])
            }

            void required(String slug) { required.add(slug) }
            void optional(String slug) { optional.add(slug) }
            void incompatible(String slug) { incompatible.add(slug) }
            void embedded(String slug) { embedded.add(slug) }
        }

        static class AdditionalFile {
            final String path
            String displayName
            String changelog

            AdditionalFile(String path) {
                this.path = path
            }

            void displayName(String value) {
                displayName = value
            }

            void changelog(String value) {
                changelog = value
            }
        }
    }

    static class Loaders {
        final LoaderConfig fabric
        final LoaderConfig forge
        final LoaderConfig neoforge

        @Inject
        Loaders(ObjectFactory objects) {
            this.fabric = objects.newInstance(LoaderConfig, objects, true)
            this.forge = objects.newInstance(LoaderConfig, objects, true)
            this.neoforge = objects.newInstance(LoaderConfig, objects, true)
        }

        void fabric(Closure<?> configure) {
            configure.delegate = fabric
            configure.resolveStrategy = Closure.DELEGATE_FIRST
            configure.call()
        }

        void forge(Closure<?> configure) {
            configure.delegate = forge
            configure.resolveStrategy = Closure.DELEGATE_FIRST
            configure.call()
        }

        void neoforge(Closure<?> configure) {
            configure.delegate = neoforge
            configure.resolveStrategy = Closure.DELEGATE_FIRST
            configure.call()
        }
    }

    static class LoaderConfig {
        final Property<Boolean> enabled

        @Inject
        LoaderConfig(ObjectFactory objects, Boolean defaultEnabled) {
            this.enabled = objects.property(Boolean).convention(defaultEnabled)
        }
    }
}
