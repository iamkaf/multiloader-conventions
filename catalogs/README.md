# version-catalog

Gradle multi-project repo containing Kaf's shared **Version Catalogs**, one module per Minecraft version.

## Maven repository

- Releases: https://z.kaf.sh/releases
- Snapshots: https://z.kaf.sh/snapshots

(Group: `com.iamkaf.platform`)

## Available catalogs

Currently generated in this repo:
- `mc-1.20.1` → `mc-1.21.11`

Example coordinates:
- `com.iamkaf.platform:mc-1.20.1:1.20.1-SNAPSHOT`
- `com.iamkaf.platform:mc-1.21.11:1.21.11-SNAPSHOT`

## Using a catalog (Gradle)

In `settings.gradle(.kts)`:

```kotlin
dependencyResolutionManagement {
  repositories {
    maven("https://z.kaf.sh/releases")
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

## Generating / Updating catalogs

This repo includes a generator script:

```bash
./gen-mc-catalogs.sh
```

(Uses Amber branches when available; falls back to Echo when Amber isn’t available.)

## Publishing

Convenience tasks:

- Publish all catalogs to KafMaven: `./gradlew publishAllToKafMaven`
- Publish all catalogs to mavenLocal: `./gradlew publishAllToMavenLocal`
