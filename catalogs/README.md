# Version catalogs

Shared Gradle version catalogs for the mod workspace.

This nested build publishes one catalog module per Minecraft line. Consumer
repositories use these catalogs to keep loader versions, API versions,
publishing coordinates, and shared dependency pins aligned across projects.

The source of truth for what is published is [`settings.gradle`](settings.gradle).

## What this repo publishes

Each published module is a Gradle version catalog:

- Group: `com.iamkaf.platform`
- Artifact: `mc-<minecraftVersion>`
- Version: `<minecraftVersion>-SNAPSHOT`

Examples:

- `com.iamkaf.platform:mc-1.20.1:1.20.1-SNAPSHOT`
- `com.iamkaf.platform:mc-1.21.11:1.21.11-SNAPSHOT`
- `com.iamkaf.platform:mc-26.1.2:26.1.2-SNAPSHOT`

## Repository Layout

```text
catalogs/
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

From the repository root, use the nested build through the shared Gradle
wrapper:

- `./gradlew -p catalogs publishAll`
- `./gradlew -p catalogs publishAllToKafMaven`
- `./gradlew -p catalogs publishAllToMavenLocal`

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

- This build is intentionally simple. It is a publication wrapper around per-version `libs.versions.toml` files.
- If you change which catalogs should be published, update `settings.gradle` first.
