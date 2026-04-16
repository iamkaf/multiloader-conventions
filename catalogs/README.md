# version-catalog

Shared Gradle version catalogs for the mod workspace.

This repo publishes one catalog module per Minecraft line. Consumer repos use these catalogs to keep loader versions, API versions, publishing coordinates, and shared dependency pins aligned across projects.

The source of truth for what is actually published is `settings.gradle`.

## What this repo publishes

Each published module is a Gradle version catalog:

- Group: `com.iamkaf.platform`
- Artifact: `mc-<minecraftVersion>`
- Version: `<minecraftVersion>-SNAPSHOT`

Examples:

- `com.iamkaf.platform:mc-1.20.1:1.20.1-SNAPSHOT`
- `com.iamkaf.platform:mc-1.21.11:1.21.11-SNAPSHOT`
- `com.iamkaf.platform:mc-26.1.2:26.1.2-SNAPSHOT`

## Published Build Graph

The root `settings.gradle` currently includes these catalog modules:

- `mc-1.16.5`
- `mc-1.18.2`
- `mc-1.19.2`
- `mc-1.20.1`
- `mc-1.20.2`
- `mc-1.20.3`
- `mc-1.20.4`
- `mc-1.20.5`
- `mc-1.20.6`
- `mc-1.21`
- `mc-1.21.1`
- `mc-1.21.2`
- `mc-1.21.3`
- `mc-1.21.4`
- `mc-1.21.5`
- `mc-1.21.6`
- `mc-1.21.7`
- `mc-1.21.8`
- `mc-1.21.9`
- `mc-1.21.10`
- `mc-1.21.11`
- `mc-26.1-snapshot-5`
- `mc-26.1`
- `mc-26.1.1`
- `mc-26.1.2`

There are additional `mc-*` directories in the repo that are not currently part of the live root build. If you care about what is actually published, trust `settings.gradle`, not the directory list.

## Repository Layout

```text
version-catalog/
├── mc-<version>/
│   ├── build.gradle
│   └── gradle/libs.versions.toml
├── build.gradle
├── settings.gradle
└── gradle.properties
```

Each included subproject:

- applies `version-catalog`
- publishes `components.versionCatalog`
- reads its catalog data from `gradle/libs.versions.toml`

## Consuming a Catalog

Add the Kaf Maven repository:

### Gradle Kotlin DSL

```kotlin
repositories {
    maven("https://maven.kaf.sh/")
    mavenCentral()
}
```

### Gradle Groovy DSL

```groovy
repositories {
    maven { url 'https://maven.kaf.sh/' }
    mavenCentral()
}
```

Then load the catalog in `settings.gradle(.kts)`:

### Kotlin DSL

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://maven.kaf.sh/")
        mavenCentral()
    }

    versionCatalogs {
        create("libs") {
            from("com.iamkaf.platform:mc-1.21.11:1.21.11-SNAPSHOT")
        }
    }
}
```

### Groovy DSL

```groovy
dependencyResolutionManagement {
    repositories {
        maven { url = uri('https://maven.kaf.sh/') }
        mavenCentral()
    }

    versionCatalogs {
        libs {
            from('com.iamkaf.platform:mc-1.21.11:1.21.11-SNAPSHOT')
        }
    }
}
```

## Publishing

Root convenience tasks:

- `./gradlew publishAll`
- `./gradlew publishAllToKafMaven`
- `./gradlew publishAllToMavenLocal`

What they do:

- `publishAll`
  - runs `publish` in every included catalog project
- `publishAllToKafMaven`
  - publishes every included catalog to the configured Kaf Maven repository
- `publishAllToMavenLocal`
  - publishes every included catalog to `mavenLocal`

Credentials:

- `MAVEN_PUBLISH_USERNAME`
- `MAVEN_PUBLISH_PASSWORD`

or Gradle properties:

- `maven.kaf.username`
- `maven.kaf.password`

## Notes

- This repo is intentionally simple. It is a publication wrapper around per-version `libs.versions.toml` files.
- The published graph is deliberately narrower than the full set of historical version directories currently present in the repo.
- If you change which catalogs should be published, update `settings.gradle` first.
