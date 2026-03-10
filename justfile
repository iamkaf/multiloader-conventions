default:
    @just --list

build:
    ./gradlew build

check:
    ./gradlew check

publish-local:
    ./gradlew publishToMavenLocal

