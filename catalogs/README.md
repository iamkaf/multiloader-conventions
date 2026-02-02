# version-catalog

Gradle multi-project repo containing Kaf's shared Version Catalogs.

## Maven repository

- Base: https://z.kaf.sh/
- Group index: https://z.kaf.sh/group/com.iamkaf.platform
- Repos:
  - Releases: https://z.kaf.sh/releases
  - Snapshots: https://z.kaf.sh/snapshots

## Available catalogs

- `com.iamkaf.platform:mc-1.21.10:1.21.10-SNAPSHOT`
- `com.iamkaf.platform:mc-1.21.11:1.21.11-SNAPSHOT`

## Using a catalog (Gradle)

In `settings.gradle(.kts)`:

```kotlin
dependencyResolutionManagement {
  repositories {
    maven("https://z.kaf.sh/snapshots")
    mavenCentral()
  }

  versionCatalogs {
    create("libs") {
      from("com.iamkaf.platform:mc-1.21.10:1.21.10-SNAPSHOT")
    }
  }
}
```

Then in `build.gradle(.kts)`:

```kotlin
dependencies {
  implementation(libs.gson)
}
```
