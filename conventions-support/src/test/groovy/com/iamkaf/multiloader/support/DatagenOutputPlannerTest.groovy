package com.iamkaf.multiloader.support

import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

class DatagenOutputPlannerTest extends Specification {

    @TempDir
    File testProjectDir

    def "flat common datagen writes to shared common generated resources"() {
        given:
        def root = ProjectBuilder.builder()
            .withName('root')
            .withProjectDir(testProjectDir)
            .build()
        def fabric = ProjectBuilder.builder()
            .withName('fabric')
            .withParent(root)
            .withProjectDir(new File(testProjectDir, 'fabric'))
            .build()

        expect:
        DatagenOutputPlanner.commonDatagenOutputDirectory(fabric, '26.2', false) ==
            new File(testProjectDir, 'common/src/main/generated')
    }

    def "Stonecutter common datagen writes to the selected version lane"() {
        given:
        def root = ProjectBuilder.builder()
            .withName('root')
            .withProjectDir(testProjectDir)
            .build()
        def fabric = ProjectBuilder.builder()
            .withName('fabric')
            .withParent(root)
            .withProjectDir(new File(testProjectDir, 'fabric'))
            .build()

        when:
        def lane = DatagenOutputPlanner.commonStonecutterLane(fabric, '26.2')

        then:
        lane.minecraftVersion == '26.2'
        lane.rootName == 'common'
        lane.directory == new File(testProjectDir, 'versions/26.2/common/src/main/generated')
        DatagenOutputPlanner.commonDatagenOutputDirectory(fabric, '26.2', true) == lane.directory
    }

    def "loader generated resources use loader-specific version lanes"() {
        given:
        def root = ProjectBuilder.builder()
            .withName('root')
            .withProjectDir(testProjectDir)
            .build()
        def fabric = ProjectBuilder.builder()
            .withName('fabric')
            .withParent(root)
            .withProjectDir(new File(testProjectDir, 'fabric'))
            .build()

        expect:
        DatagenOutputPlanner.loaderGeneratedResourcesRoot(fabric, 'fabric', '1.20.1') ==
            new File(testProjectDir, 'versions/1.20.1/fabric/src/main/generated')
    }
}
