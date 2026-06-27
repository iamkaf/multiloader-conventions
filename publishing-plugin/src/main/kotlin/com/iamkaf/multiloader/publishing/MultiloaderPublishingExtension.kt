package com.iamkaf.multiloader.publishing

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

open class MultiloaderPublishingExtension @Inject constructor(objects: ObjectFactory) {
    private val configObject: PublishingConfig = objects.newInstance(PublishingConfig::class.java, objects)
    private val metadataObject: PublishingMetadata = objects.newInstance(PublishingMetadata::class.java, objects)
    private val publishObject: Publish = objects.newInstance(Publish::class.java, objects)
    private val loadersObject: Loaders = objects.newInstance(Loaders::class.java, objects)
    private val publicationsObject: NamedDomainObjectContainer<Publication> =
        objects.domainObjectContainer(Publication::class.java) { name ->
            objects.newInstance(Publication::class.java, name, objects)
        }

    fun getConfig(): PublishingConfig = configObject

    fun getMetadata(): PublishingMetadata = metadataObject

    fun getPublish(): Publish = publishObject

    fun getLoaders(): Loaders = loadersObject

    fun getPublications(): NamedDomainObjectContainer<Publication> = publicationsObject

    fun config(configure: Closure<*>) = configure(configObject, configure)

    fun config(configure: Action<in PublishingConfig>) = configure.execute(configObject)

    fun metadata(configure: Closure<*>) = configure(metadataObject, configure)

    fun metadata(configure: Action<in PublishingMetadata>) = configure.execute(metadataObject)

    fun publish(configure: Closure<*>) = configure(publishObject, configure)

    fun publish(configure: Action<in Publish>) = configure.execute(publishObject)

    fun loaders(configure: Closure<*>) = configure(loadersObject, configure)

    fun loaders(configure: Action<in Loaders>) = configure.execute(loadersObject)

    fun publications(configure: Closure<*>) = configure(publicationsObject, configure)

    fun publications(configure: Action<in NamedDomainObjectContainer<Publication>>) =
        configure.execute(publicationsObject)

    fun publication(name: String, configure: Closure<*>) {
        configure(publicationsObject.maybeCreate(name), configure)
    }

    fun publication(name: String, configure: Action<in Publication>) {
        configure.execute(publicationsObject.maybeCreate(name))
    }

    open class PublishingConfig @Inject constructor(objects: ObjectFactory) {
        val dryRun: Property<Boolean> =
            objects.property(Boolean::class.javaObjectType).convention(false)
        val releaseType: Property<String> =
            objects.property(String::class.java).convention("release")
    }

    open class PublishingMetadata @Inject constructor(objects: ObjectFactory) {
        val displayName: Property<String> = objects.property(String::class.java)
        val changelogText: Property<String> = objects.property(String::class.java)
        val changelogFile: Property<String> = objects.property(String::class.java)

        fun changelog(configure: Closure<*>) = configure(this, configure)

        fun changelog(configure: Action<in PublishingMetadata>) = configure.execute(this)

        fun fromText(text: String) {
            changelogText.set(text)
        }

        fun fromFile(path: String) {
            changelogFile.set(path)
        }
    }

    open class Publish @Inject constructor(objects: ObjectFactory) {
        val disableEmptyJarCheck: Property<Boolean> =
            objects.property(Boolean::class.javaObjectType).convention(false)
        val isManualRelease: Property<Boolean> =
            objects.property(Boolean::class.javaObjectType).convention(false)
        private val modrinthObject: Modrinth = objects.newInstance(Modrinth::class.java, objects)
        private val curseforgeObject: CurseForge = objects.newInstance(CurseForge::class.java, objects)
        val additionalFiles: ListProperty<AdditionalFile> =
            objects.listProperty(AdditionalFile::class.java).convention(emptyList())

        fun getModrinth(): Modrinth = modrinthObject

        fun getCurseforge(): CurseForge = curseforgeObject

        fun modrinth(configure: Closure<*>) = configure(modrinthObject, configure)

        fun modrinth(configure: Action<in Modrinth>) = configure.execute(modrinthObject)

        fun curseforge(configure: Closure<*>) = configure(curseforgeObject, configure)

        fun curseforge(configure: Action<in CurseForge>) = configure.execute(curseforgeObject)

        @JvmOverloads
        fun additionalFile(path: String, configure: Closure<*>? = null) {
            val file = AdditionalFile(path)
            if (configure != null) {
                configure(file, configure)
            }
            additionalFiles.add(file)
        }

        fun additionalFile(path: String, configure: Action<in AdditionalFile>) {
            val file = AdditionalFile(path)
            configure.execute(file)
            additionalFiles.add(file)
        }

        open class Modrinth @Inject constructor(objects: ObjectFactory) {
            val id: Property<String> = objects.property(String::class.java)
            val token: Property<String> = objects.property(String::class.java)
            private val dependenciesObject: Dependencies = objects.newInstance(Dependencies::class.java, objects)

            fun getDependencies(): Dependencies = dependenciesObject

            fun dependencies(configure: Closure<*>) = configure(dependenciesObject, configure)

            fun dependencies(configure: Action<in Dependencies>) = configure.execute(dependenciesObject)
        }

        open class CurseForge @Inject constructor(objects: ObjectFactory) {
            val id: Property<String> = objects.property(String::class.java)
            val token: Property<String> = objects.property(String::class.java)
            val environment: Property<String> = objects.property(String::class.java).convention("both")
            val javaVersions: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())
            private val dependenciesObject: Dependencies = objects.newInstance(Dependencies::class.java, objects)

            fun getDependencies(): Dependencies = dependenciesObject

            fun dependencies(configure: Closure<*>) = configure(dependenciesObject, configure)

            fun dependencies(configure: Action<in Dependencies>) = configure.execute(dependenciesObject)
        }

        open class Dependencies @Inject constructor(objects: ObjectFactory) {
            private val requiredProperty: ListProperty<String> =
                objects.listProperty(String::class.java).convention(emptyList())
            private val optionalProperty: ListProperty<String> =
                objects.listProperty(String::class.java).convention(emptyList())
            private val incompatibleProperty: ListProperty<String> =
                objects.listProperty(String::class.java).convention(emptyList())
            private val embeddedProperty: ListProperty<String> =
                objects.listProperty(String::class.java).convention(emptyList())

            fun getRequired(): ListProperty<String> = requiredProperty

            fun getOptional(): ListProperty<String> = optionalProperty

            fun getIncompatible(): ListProperty<String> = incompatibleProperty

            fun getEmbedded(): ListProperty<String> = embeddedProperty

            fun required(slug: String) = requiredProperty.add(slug)

            fun optional(slug: String) = optionalProperty.add(slug)

            fun incompatible(slug: String) = incompatibleProperty.add(slug)

            fun embedded(slug: String) = embeddedProperty.add(slug)
        }

        open class AdditionalFile(val path: String) {
            private var displayNameValue: String? = null
            private var changelogValue: String? = null

            fun getDisplayName(): String? = displayNameValue

            fun setDisplayName(value: String?) {
                displayNameValue = value
            }

            fun displayName(value: String) {
                displayNameValue = value
            }

            fun getChangelog(): String? = changelogValue

            fun setChangelog(value: String?) {
                changelogValue = value
            }

            fun changelog(value: String) {
                changelogValue = value
            }
        }
    }

    open class Loaders @Inject constructor(objects: ObjectFactory) {
        private val fabricObject: LoaderConfig = objects.newInstance(LoaderConfig::class.java, objects, true)
        private val forgeObject: LoaderConfig = objects.newInstance(LoaderConfig::class.java, objects, true)
        private val neoforgeObject: LoaderConfig = objects.newInstance(LoaderConfig::class.java, objects, true)

        fun getFabric(): LoaderConfig = fabricObject

        fun getForge(): LoaderConfig = forgeObject

        fun getNeoforge(): LoaderConfig = neoforgeObject

        fun fabric(configure: Closure<*>) = configure(fabricObject, configure)

        fun fabric(configure: Action<in LoaderConfig>) = configure.execute(fabricObject)

        fun forge(configure: Closure<*>) = configure(forgeObject, configure)

        fun forge(configure: Action<in LoaderConfig>) = configure.execute(forgeObject)

        fun neoforge(configure: Closure<*>) = configure(neoforgeObject, configure)

        fun neoforge(configure: Action<in LoaderConfig>) = configure.execute(neoforgeObject)
    }

    open class LoaderConfig @Inject constructor(objects: ObjectFactory, defaultEnabled: Boolean) {
        val enabled: Property<Boolean> =
            objects.property(Boolean::class.javaObjectType).convention(defaultEnabled)
    }

    open class Publication @Inject constructor(private val publicationName: String, objects: ObjectFactory) : Named {
        private val enabledProperty: Property<Boolean> =
            objects.property(Boolean::class.javaObjectType).convention(true)
        private val projectPathProperty: Property<String> = objects.property(String::class.java)
        private val artifactTaskProperty: Property<String> =
            objects.property(String::class.java).convention("jar")
        private val fallbackArtifactTaskProperty: Property<String> = objects.property(String::class.java)
        private val buildTasksProperty: ListProperty<String> =
            objects.listProperty(String::class.java).convention(emptyList())
        private val loadersProperty: ListProperty<String> =
            objects.listProperty(String::class.java).convention(emptyList())
        private val gameVersionsProperty: ListProperty<String> =
            objects.listProperty(String::class.java).convention(emptyList())
        private val javaVersionsProperty: ListProperty<String> =
            objects.listProperty(String::class.java).convention(emptyList())
        private val displayNameProperty: Property<String> = objects.property(String::class.java)

        override fun getName(): String = publicationName

        fun getEnabled(): Property<Boolean> = enabledProperty

        fun getProjectPath(): Property<String> = projectPathProperty

        fun getArtifactTask(): Property<String> = artifactTaskProperty

        fun getFallbackArtifactTask(): Property<String> = fallbackArtifactTaskProperty

        fun getBuildTasks(): ListProperty<String> = buildTasksProperty

        fun getLoaders(): ListProperty<String> = loadersProperty

        fun getGameVersions(): ListProperty<String> = gameVersionsProperty

        fun getJavaVersions(): ListProperty<String> = javaVersionsProperty

        fun getDisplayName(): Property<String> = displayNameProperty

        fun project(path: String) = projectPathProperty.set(path)

        fun artifactTask(name: String) = artifactTaskProperty.set(name)

        fun fallbackArtifactTask(name: String) = fallbackArtifactTaskProperty.set(name)

        fun buildTask(name: String) = buildTasksProperty.add(name)

        fun loader(loader: String) = loadersProperty.add(loader)

        fun gameVersion(version: String) = gameVersionsProperty.add(version)

        fun javaVersion(version: String) = javaVersionsProperty.add(version)

        fun displayName(value: String) = displayNameProperty.set(value)
    }

    private companion object {
        fun configure(target: Any, configure: Closure<*>) {
            configure.delegate = target
            configure.resolveStrategy = Closure.DELEGATE_FIRST
            configure.call()
        }
    }
}
