# version-catalog

Gradle multi-project repo containing Kaf's shared Version Catalogs.

## Maven repository

**Browsing (web UI):**
- Base: https://z.kaf.sh/
- Group index: https://z.kaf.sh/group/com.iamkaf.platform

**Actual Maven repository (for Gradle/Maven consumption):**
- Releases: https://maven.kaf.sh/releases
- Snapshots: https://maven.kaf.sh/snapshots

*(Note: `z.kaf.sh/{releases|snapshots}` is used for publishing; `z.kaf.sh` is for browsing.)*

## Available catalogs

- `com.iamkaf.platform:mc-1.21.10:1.21.10-SNAPSHOT`
- `com.iamkaf.platform:mc-1.21.11:1.21.11-SNAPSHOT`

## Using a catalog (Gradle)

In `settings.gradle(.kts)`:

```kotlin
dependencyResolutionManagement {
  repositories {
    maven("https://maven.kaf.sh/snapshots")
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
