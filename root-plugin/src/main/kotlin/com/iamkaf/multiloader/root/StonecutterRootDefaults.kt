package com.iamkaf.multiloader.root

import com.iamkaf.multiloader.support.adapters.StonecutterDslAdapter
import org.gradle.api.Project

object StonecutterRootDefaults {
    fun configure(project: Project) {
        StonecutterDslAdapter.configureDefaultHandlers(project)
    }
}
