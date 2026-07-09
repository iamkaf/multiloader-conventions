package com.iamkaf.multiloader.root

import org.gradle.api.GradleException
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HorizontalJarValidatorTest extends Specification {
    private static final String MOD_ID = 'examplemod'

    @TempDir
    File testDir

    def "stable validation covers metadata mixins entrypoints access assets and common paths"() {
        given:
        def fixture = stableFixture()

        expect:
        validate(fixture.merged, fixture.sources, HorizontalMergeTier.STABLE)
    }

    def "stable validation accepts classfile reserialization at the same binary path"() {
        given:
        def fixture = stableFixture()
        def entries = new LinkedHashMap<String, byte[]>(fixture.mergedEntries)
        entries['com/example/Common.class'] = bytes('forgix-reserialized-common-class')

        expect:
        validate(
            jar('stable-reserialized.jar', entries),
            fixture.sources,
            HorizontalMergeTier.STABLE,
        )
    }

    def "stable validation rejects broken merged jar surfaces"() {
        given:
        def fixture = stableFixture()
        def entries = new LinkedHashMap<String, byte[]>(fixture.mergedEntries)
        mutation(entries)

        when:
        HorizontalJarValidator.INSTANCE.validate(
            jar("broken-${expectedMessage.hashCode()}.jar", entries),
            fixture.sources,
            MOD_ID,
            HorizontalMergeTier.STABLE,
        )

        then:
        def error = thrown(GradleException)
        error.message.contains(expectedMessage)

        where:
        expectedMessage                                | mutation
        'fabric.mod.json changed mod id'                | { it['fabric.mod.json'] = fabricMetadata('wrong', 'common.mixins.json', 'example.accesswidener') }
        'Mixin from common.mixins.json'                 | { it.remove('com/example/mixin/CommonMixin.class') }
        'Fabric entrypoint references a missing class'  | { it.remove('com/example/FabricMain.class') }
        'Fabric access widener references a missing'    | { it.remove('example.accesswidener') }
        'asset/data entry changed bytes'                | { it['assets/examplemod/lang/en_us.json'] = bytes('changed') }
        'stable common entry was renamed or removed'    | { it.remove('com/example/Common.class') }
    }

    def "unreadable output fails ZIP validation"() {
        given:
        def fixture = stableFixture()
        def unreadable = new File(testDir, 'not-a-jar.jar')
        unreadable.text = 'not zip data'

        when:
        HorizontalJarValidator.INSTANCE.validate(unreadable, fixture.sources, MOD_ID, HorizontalMergeTier.STABLE)

        then:
        def error = thrown(GradleException)
        error.message.contains('not a readable ZIP')
    }

    def "unsafe acknowledged tier accepts Forgix loader suffixes but validates references"() {
        given:
        def fixture = unsafeFixture()

        expect:
        validate(fixture.merged, fixture.sources, HorizontalMergeTier.UNSTABLE_RELOCATED)
    }

    def "policy requires an acknowledgement for each unsafe version"() {
        expect:
        HorizontalMergePolicy.INSTANCE.requireSupported('26.1.2', [] as Set) == HorizontalMergeTier.STABLE
        HorizontalMergePolicy.INSTANCE.requireSupported('1.21.1', ['1.21.1'] as Set) == HorizontalMergeTier.UNSTABLE_RELOCATED
        HorizontalMergePolicy.INSTANCE.requireSupported('1.21.11', ['1.21.11'] as Set) == HorizontalMergeTier.UNSTABLE_RELOCATED

        when:
        HorizontalMergePolicy.INSTANCE.requireSupported('1.21.11', [] as Set)

        then:
        def error = thrown(GradleException)
        error.message.contains('acknowledgeUnsafeVersion("1.21.11")')
    }

    private boolean validate(File merged, Map<String, File> sources, HorizontalMergeTier tier) {
        HorizontalJarValidator.INSTANCE.validate(merged, sources, MOD_ID, tier)
        true
    }

    private Map stableFixture() {
        def common = [
            'common.mixins.json': mixinConfig('com.example.mixin', 'CommonMixin'),
            'com/example/mixin/CommonMixin.class': bytes('common-mixin'),
            'com/example/Common.class': bytes('common-class'),
            'assets/examplemod/lang/en_us.json': bytes('{"key":"value"}'),
        ]
        def fabric = new LinkedHashMap<String, byte[]>(common)
        fabric.putAll([
            'fabric.mod.json': fabricMetadata(MOD_ID, 'common.mixins.json', 'example.accesswidener'),
            'example.accesswidener': bytes('accessWidener v2 named\n'),
            'com/example/FabricMain.class': bytes('fabric-main'),
        ])
        def forge = new LinkedHashMap<String, byte[]>(common)
        forge.putAll([
            'META-INF/mods.toml': tomlMetadata(MOD_ID, 'common.mixins.json'),
            'META-INF/accesstransformer.cfg': bytes('public net.minecraft.Example field\n'),
        ])
        def neoForge = new LinkedHashMap<String, byte[]>(common)
        neoForge.putAll([
            'META-INF/neoforge.mods.toml': tomlMetadata(MOD_ID, 'common.mixins.json'),
            'META-INF/accesstransformer.cfg': bytes('public net.minecraft.Example field\n'),
        ])
        def merged = new LinkedHashMap<String, byte[]>()
        [fabric, forge, neoForge].each { merged.putAll(it) }
        [
            merged: jar('stable-merged.jar', merged),
            mergedEntries: merged,
            sources: [
                fabric: jar('stable-fabric.jar', fabric),
                forge: jar('stable-forge.jar', forge),
                neoforge: jar('stable-neoforge.jar', neoForge),
            ],
        ]
    }

    private Map unsafeFixture() {
        def stable = stableFixture()
        def commonAsset = bytes('{"key":"value"}')
        def merged = [
            'fabric.mod.json': fabricMetadata("${MOD_ID}_fabric", 'common.mixins_fabric.json', 'example_fabric.accesswidener', 'com.example.FabricMain_fabric'),
            'META-INF/mods.toml': tomlMetadata("${MOD_ID}_forge", 'common.mixins_forge.json'),
            'META-INF/neoforge.mods.toml': tomlMetadata(MOD_ID, 'common.mixins_neoforge.json'),
            'common.mixins_fabric.json': mixinConfig('com.example.mixin', 'CommonMixin_fabric'),
            'common.mixins_forge.json': mixinConfig('com.example.mixin', 'CommonMixin_forge'),
            'common.mixins_neoforge.json': mixinConfig('com.example.mixin', 'CommonMixin_neoforge'),
            'com/example/mixin/CommonMixin_fabric.class': bytes('fabric-common-mixin'),
            'com/example/mixin/CommonMixin_forge.class': bytes('forge-common-mixin'),
            'com/example/mixin/CommonMixin_neoforge.class': bytes('neoforge-common-mixin'),
            'com/example/FabricMain_fabric.class': bytes('fabric-main'),
            'example_fabric.accesswidener': bytes('accessWidener v2 named relocated\n'),
            'META-INF/accesstransformer.cfg': bytes('public net.minecraft.Example field\n'),
            'assets/examplemod/lang/en_us_fabric.json': commonAsset,
            'assets/examplemod/lang/en_us_forge.json': commonAsset,
            'assets/examplemod/lang/en_us_neoforge.json': commonAsset,
        ] as LinkedHashMap<String, byte[]>
        [merged: jar('unsafe-merged.jar', merged), sources: stable.sources]
    }

    private File jar(String name, Map<String, byte[]> entries) {
        def file = new File(testDir, name)
        file.withOutputStream { output ->
            new ZipOutputStream(output).withCloseable { zip ->
                entries.each { path, content ->
                    zip.putNextEntry(new ZipEntry(path))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }
        file
    }

    private static byte[] fabricMetadata(String id, String mixin, String accessWidener, String entrypoint = 'com.example.FabricMain') {
        bytes("""{
  "schemaVersion": 1,
  "id": "${id}",
  "entrypoints": {"main": ["${entrypoint}"]},
  "mixins": ["${mixin}"],
  "accessWidener": "${accessWidener}"
}""")
    }

    private static byte[] tomlMetadata(String id, String mixin) {
        bytes("""modLoader = "javafml"
loaderVersion = "[1,)"
[[mods]]
modId = "${id}"
[[mixins]]
config = "${mixin}"
""")
    }

    private static byte[] mixinConfig(String packageName, String mixin) {
        bytes("""{"package":"${packageName}","mixins":["${mixin}"]}""")
    }

    private static byte[] bytes(String value) {
        value.getBytes(StandardCharsets.UTF_8)
    }
}
