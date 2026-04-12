package com.iamkaf.multiloader.translations

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

abstract class MultiloaderTranslationsExtension {

    abstract Property<String> getProjectSlug()

    abstract DirectoryProperty getOutputDir()

    abstract Property<String> getBaseUrl()

    abstract Property<String> getToken()
}
