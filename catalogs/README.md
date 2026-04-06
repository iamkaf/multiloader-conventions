# version-catalog

Gradle multi-project repo containing Kaf’s shared **Version Catalogs** — one module per Minecraft version.

## Maven server (how to consume)

The public Maven repository URL to add to builds is:

- `https://maven.kaf.sh/`

### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven {
        url = uri("https://maven.kaf.sh/")
    }
    mavenCentral()
}
```

### Gradle (Groovy)

```groovy
repositories {
    maven { url 'https://maven.kaf.sh/' }
    mavenCentral()
}
```

### Maven (pom.xml)

```xml
<repository>
  <id>kaf-maven</id>
  <url>https://maven.kaf.sh/</url>
</repository>
```

## Coordinates

- **Group:** `com.iamkaf.platform`
- **Artifact:** `mc-<minecraftVersion>` (example: `mc-26.1`)
- **Version:** `<minecraftVersion>-SNAPSHOT` (example: `26.1-SNAPSHOT`)

Examples:
- `com.iamkaf.platform:mc-1.20.1:1.20.1-SNAPSHOT`
- `com.iamkaf.platform:mc-26.1:26.1-SNAPSHOT`
- `com.iamkaf.platform:mc-26.1.1:26.1.1-SNAPSHOT`

## Using a catalog (Gradle)

In `settings.gradle(.kts)`:

```kotlin
dependencyResolutionManagement {
  repositories {
    maven("https://maven.kaf.sh/")
    mavenCentral()
  }

  versionCatalogs {
    create("libs") {
      from("com.iamkaf.platform:mc-26.1:26.1-SNAPSHOT")
    }
  }
}
```

## Available catalogs

This repo currently contains:
- `mc-1.16.5` → `mc-26.1.1`, including `mc-26.1`

## Publishing (maintainers)

This repo is configured to publish catalogs to Kaf’s Maven infrastructure.

Convenience tasks:
- Publish all catalogs: `./gradlew publishAll`
- Publish all catalogs to KafMaven: `./gradlew publishAllToKafMaven`
- Publish all catalogs to mavenLocal: `./gradlew publishAllToMavenLocal`

Note: publishing credentials are read from `MAVEN_PUBLISH_USERNAME` / `MAVEN_PUBLISH_PASSWORD` (or Gradle properties `maven.kaf.username` / `maven.kaf.password`).
