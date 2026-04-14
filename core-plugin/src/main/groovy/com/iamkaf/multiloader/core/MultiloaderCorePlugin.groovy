package com.iamkaf.multiloader.core

import com.iamkaf.multiloader.support.StonecutterConventionSupport
import org.gradle.api.Plugin
import org.gradle.api.Project

class MultiloaderCorePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        def ext = project.extensions.extraProperties

        ext.set('requiredProp', { Project target, String name ->
            StonecutterConventionSupport.requiredProp(target, name)
        })
        ext.set('optionalProp', { Project target, String name ->
            StonecutterConventionSupport.optionalProp(target, name)
        })
        ext.set('catalogName', { String minecraftVersion ->
            StonecutterConventionSupport.catalogName(minecraftVersion)
        })
        ext.set('catalogFor', { Project target, String minecraftVersion ->
            StonecutterConventionSupport.catalogFor(target, minecraftVersion)
        })
        ext.set('versionOrNull', { catalog, String alias ->
            StonecutterConventionSupport.versionOrNull(catalog, alias)
        })
        ext.set('library', { catalog, String alias ->
            StonecutterConventionSupport.library(catalog, alias)
        })
        ext.set('useUnobfuscatedMinecraft', { String minecraftVersion ->
            StonecutterConventionSupport.useUnobfuscatedMinecraft(minecraftVersion)
        })
        ext.set('sharedRepositories', { Project target ->
            StonecutterConventionSupport.sharedRepositories(target)
        })
        ext.set('publishingRepositories', { publishing, String version ->
            StonecutterConventionSupport.publishingRepositories(publishing, version)
        })
        ext.set('mixinConfigs', { Project target, String loader ->
            StonecutterConventionSupport.mixinConfigs(target, loader)
        })
        ext.set('expandProps', { Project target, String minecraftVersion, String loader, catalog ->
            StonecutterConventionSupport.expandProps(target, minecraftVersion, loader, catalog)
        })
    }
}
