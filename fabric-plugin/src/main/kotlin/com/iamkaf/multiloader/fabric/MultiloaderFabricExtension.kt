package com.iamkaf.multiloader.fabric

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

open class MultiloaderFabricExtension @Inject constructor(objects: ObjectFactory) {
    val commonDatagen: Property<Boolean> =
        objects.property(Boolean::class.javaObjectType).convention(false)

    fun setCommonDatagen(enabled: Boolean) {
        commonDatagen.set(enabled)
    }
}
