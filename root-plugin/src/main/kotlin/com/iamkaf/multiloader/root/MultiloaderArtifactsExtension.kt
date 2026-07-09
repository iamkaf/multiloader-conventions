package com.iamkaf.multiloader.root

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

/** Root-owned artifact operations that combine outputs from multiple loader projects. */
open class MultiloaderArtifactsExtension @Inject constructor(objects: ObjectFactory) {
    private val horizontalMergeObject = objects.newInstance(HorizontalMerge::class.java, objects)

    fun getHorizontalMerge(): HorizontalMerge = horizontalMergeObject

    fun horizontalMerge(configure: Action<in HorizontalMerge>) {
        configure.execute(horizontalMergeObject)
    }

    open class HorizontalMerge @Inject constructor(objects: ObjectFactory) {
        /** Enables horizontal merging. No merge configurations or tasks are created while this is false. */
        val enabled: Property<Boolean> =
            objects.property(Boolean::class.javaObjectType).convention(false)

        /** Minecraft versions to merge. An empty set selects every version in the active target scope. */
        val versions: SetProperty<String> =
            objects.setProperty(String::class.java).convention(emptySet())

        /** Explicit acknowledgement for versions whose common classes may be loader-relocated. */
        val allowUnstableVersions: SetProperty<String> =
            objects.setProperty(String::class.java).convention(emptySet())

        /** Selects one Minecraft version without replacing versions selected elsewhere. */
        fun version(minecraftVersion: String) {
            versions.add(minecraftVersion)
        }

        /**
         * Accepts the known binary-name and mod-id relocation risk for one proven unsafe version.
         * This is intentionally a method call so unsafe support cannot be enabled accidentally by a broad flag.
         */
        fun acknowledgeUnsafeVersion(minecraftVersion: String) {
            allowUnstableVersions.add(minecraftVersion)
        }
    }
}
