package com.iamkaf.multiloader.publishing

import spock.lang.Specification
import spock.lang.TempDir

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MultiloaderPublishRulesTest extends Specification {

    @TempDir
    File tempDir

    def "rejects a loader jar that has metadata but no classes"() {
        given:
        def jar = writeJar('resources-only.jar', ['META-INF/mods.toml', 'assets/testmod/icon.png'])

        when:
        MultiloaderPublishRules.checkEmptyJar(jar, ['forge'])

        then:
        def error = thrown(IllegalStateException)
        error.message.contains('resources-only.jar contains no classes')
    }

    def "accepts a loader jar with metadata and classes"() {
        given:
        def jar = writeJar('mod.jar', ['META-INF/mods.toml', 'com/example/TestMod.class'])

        when:
        MultiloaderPublishRules.checkEmptyJar(jar, ['forge'])

        then:
        noExceptionThrown()
    }

    private File writeJar(String name, List<String> entries) {
        def jar = new File(tempDir, name)
        jar.withOutputStream { output ->
            new ZipOutputStream(output).withCloseable { zip ->
                entries.each { entry ->
                    zip.putNextEntry(new ZipEntry(entry))
                    zip.write(1)
                    zip.closeEntry()
                }
            }
        }
        jar
    }
}
