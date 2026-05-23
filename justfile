default:
    @just --list

build:
    ./gradlew build

check:
    ./gradlew check

check-samples:
    ./gradlew checkSamples

publish-local:
    ./gradlew publishToMavenLocal

publish-kaf:
    @test -n "$MAVEN_PUBLISH_USERNAME" || (echo "MAVEN_PUBLISH_USERNAME is required" >&2; exit 1)
    @test -n "$MAVEN_PUBLISH_PASSWORD" || (echo "MAVEN_PUBLISH_PASSWORD is required" >&2; exit 1)
    ./gradlew publishAllPublicationsToKafMavenRepository --no-daemon --stacktrace
