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
