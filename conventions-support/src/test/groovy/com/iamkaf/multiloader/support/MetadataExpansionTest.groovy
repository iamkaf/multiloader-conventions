package com.iamkaf.multiloader.support

import org.gradle.api.artifacts.VersionCatalog
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

import java.lang.reflect.Proxy
import java.util.Optional

class MetadataExpansionTest extends Specification {

    @TempDir
    File testProjectDir

    def "loader resource expansion uses loader compatibility for common mixin configs"() {
        given:
        def root = ProjectBuilder.builder()
            .withName('root')
            .withProjectDir(testProjectDir)
            .build()
        def forge = ProjectBuilder.builder()
            .withName('forge')
            .withParent(root)
            .withProjectDir(new File(testProjectDir, 'forge'))
            .build()
        def project = ProjectBuilder.builder()
            .withName('1.20.6')
            .withParent(forge)
            .withProjectDir(new File(testProjectDir, 'forge/versions/1.20.6'))
            .build()
        new File(testProjectDir, 'versions/1.20.6').mkdirs()
        root.extensions.extraProperties.set('project.group', 'com.example')
        root.extensions.extraProperties.set('project.version', '1.0.0+1.20.6')
        root.extensions.extraProperties.set('mod.id', 'examplemod')
        root.extensions.extraProperties.set('mod.name', 'Example Mod')

        when:
        def expanded = MetadataExpansion.INSTANCE.stonecutter(
            MultiloaderProjectContext.of(project),
            '1.20.6',
            'forge',
            emptyCatalog(),
        )

        then:
        expanded.mixin_compat_common == 'JAVA_17'
        expanded.mixin_compat_forge == 'JAVA_17'
        expanded.mixin_compat_fabric == 'JAVA_21'
    }

    private static VersionCatalog emptyCatalog() {
        Proxy.newProxyInstance(
            VersionCatalog.classLoader,
            [VersionCatalog] as Class[],
            { proxy, method, args ->
                if (method.name == 'findVersion' || method.name == 'findLibrary' || method.name == 'findBundle') {
                    return Optional.empty()
                }
                if (method.name == 'getLibraryAliases' || method.name == 'getBundleAliases' || method.name == 'getVersionAliases') {
                    return []
                }
                if (method.name == 'toString') {
                    return 'emptyCatalog'
                }
                throw new UnsupportedOperationException(method.name)
            },
        ) as VersionCatalog
    }
}
