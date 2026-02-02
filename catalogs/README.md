# version-catalog

Gradle multi-project repo containing Kaf's shared Version Catalogs.

## Maven repository

https://maven.kaf.sh

## Available catalogs

- `com.iamkaf.platform:mc-1.21.10:1.21.10-SNAPSHOT`
- `com.iamkaf.platform:mc-1.21.11:1.21.11-SNAPSHOT`

## Using a catalog (Gradle)

In `settings.gradle(.kts)`:

```kotlin
dependencyResolutionManagement {
  repositories {
    maven("https://maven.kaf.sh")
    mavenCentral()
  }

  versionCatalogs {
    create("libs") {
      from("com.iamkaf.platform:mc-1.21.10:1.21.10-SNAPSHOT")
    }
  }
}
```
