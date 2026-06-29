package com.iamkaf.multiloader.translations

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

abstract class MultiloaderTranslationsExtension {
    abstract val projectSlug: Property<String>

    abstract val outputDir: DirectoryProperty

    abstract val baseUrl: Property<String>

    abstract val token: Property<String>
}
