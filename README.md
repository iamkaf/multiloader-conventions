# Multiloader Conventions

Gradle convention plugins for the multiloader mod workspace.

This repo is the shared build layer used by the branch-based Stonecutter projects in this workspace. It centralizes the repeatable parts of the build while leaving genuinely versioned or one-off behavior explicit.

Current release in this repo:

- Group: `com.iamkaf.multiloader`
- Version: `2.0.0`

## Plugin Set

The live plugin family included by `settings.gradle` is:

- `com.iamkaf.multiloader.settings`
- `com.iamkaf.multiloader.root`
- `com.iamkaf.multiloader.core`
- `com.iamkaf.multiloader.common`
- `com.iamkaf.multiloader.platform`
- `com.iamkaf.multiloader.fabric`
- `com.iamkaf.multiloader.forge`
- `com.iamkaf.multiloader.neoforge`
- `com.iamkaf.multiloader.translations`
- `com.iamkaf.multiloader.publishing`

Included modules in this repo:

- `conventions-support`
- `core-plugin`
- `settings-plugin`
- `root-plugin`
- `common-plugin`
- `platform-plugin`
- `fabric-plugin`
- `forge-plugin`
- `neoforge-plugin`
- `translations-plugin`
- `publishing-plugin`

## What Each Plugin Owns

`settings`

- builds the Stonecutter project graph from `versions/<mc>/gradle.properties`
- sets the root project name
- wires repositories for dependency resolution
- loads the shared version catalog

`root`

- owns root-only aggregate tasks
- owns root-level validations and top-level workflow wiring

`core`

- exposes shared helpers used by the other plugins:
  - property access
  - catalog access
  - repository wiring
  - metadata expansion
  - publishing helpers
  - mixin compatibility expansion

`common`

- configures the standalone `common` artifact
- publishes the shared common component
- expands metadata and manifest values
- exposes the shared common source and resource outputs to loader projects
- selects the common tool family by Minecraft line

`platform`

- bridges loader projects to the `common` project outputs
- keeps the common-to-loader source/resource flow consistent

`fabric`

- configures Fabric loader wiring
- configures Fabric runs
- owns current shared Fabric dependency policy
- exposes the current helper path for Fabric datagen

`forge`

- configures Forge loader wiring
- configures Forge run behavior
- owns the supported Forge floor in the convention layer

`neoforge`

- configures NeoForge loader wiring
- configures NeoForge runs and current datagen shape

`translations`

- downloads approved non-`en_us` locale JSON files from `i18n.kaf.sh`
- writes them to the configured consumer lang directory
- intentionally stays separate from source-locale ownership

`publishing`

- assembles loader outputs into release tasks
- exposes dry-run and live Modrinth and CurseForge tasks
- builds version-specific aggregate publish tasks

## Current Version Policy

The detailed policy lives in [docs/version-capability-matrix.md](docs/version-capability-matrix.md). The short version is:

- stable behavior belongs in plugins
- repeated drift belongs behind explicit strategy points
- one-off quirks stay consumer-local until they repeat

Important current boundaries:

- the convention Forge plugin supports Forge on `1.17+`
- pre-`1.17` Forge stays consumer-local
- the common plugin selects different tool families by Minecraft line
- Fabric datagen is centralized as a helper, but still consumer opt-in

## Expected Consumer Shape

These plugins are built for the branch-based Stonecutter layout:

```text
consumer/
├── common/
├── fabric/
├── forge/
├── neoforge/
├── versions/<mc>/
├── settings.gradle.kts
├── stonecutter.gradle.kts
└── gradle.properties
```

The active graph is derived from:

- shared root properties
- `versions/<mc>/gradle.properties`
- the selected catalog coordinate
- `project.enabled-loaders`

See:

- [samples/minimal](samples/minimal)
- [samples/datagen](samples/datagen)

## Required Consumer Properties

The plugins expect these version-local properties:

- `project.version`
- `project.minecraft`
- `project.java`
- `mod.name`
- `mod.id`

Common optional properties:

- `project.enabled-loaders`
- `project.catalog-coordinate`
- `project.build-java`
- `project.plugins`
- `translations.token`
- `publish.*`
- `dependencies.*`
- `environments.*`

Workspace defaults are usually supplied from the consumer root `gradle.properties`, with version overrides in `versions/<mc>/gradle.properties`.

## Consumer Setup

Typical `settings.gradle.kts` plugin management:

```kotlin
pluginManagement {
    val multiloaderConventionsVersion = providers.gradleProperty("project.plugins").get()

    repositories {
        mavenLocal()
        maven("https://maven.kaf.sh")
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        id("com.iamkaf.multiloader.settings") version multiloaderConventionsVersion
        id("com.iamkaf.multiloader.root") version multiloaderConventionsVersion
        id("com.iamkaf.multiloader.common") version multiloaderConventionsVersion
        id("com.iamkaf.multiloader.fabric") version multiloaderConventionsVersion
        id("com.iamkaf.multiloader.forge") version multiloaderConventionsVersion
        id("com.iamkaf.multiloader.neoforge") version multiloaderConventionsVersion
        id("com.iamkaf.multiloader.translations") version multiloaderConventionsVersion
        id("com.iamkaf.multiloader.publishing") version multiloaderConventionsVersion
    }
}
```

Typical root plugin application:

```kotlin
plugins {
    id("dev.kikugie.stonecutter")
    id("com.iamkaf.multiloader.root")
}
```

The concrete current consumer examples are the real source of truth:

- `samples/minimal`
- `samples/datagen`
- active consumer repos such as `multiloader-template`, `konfig`, and `teakit`

## Translations Plugin

Apply the translations plugin in the consumer root and configure it explicitly:

```groovy
plugins {
    id 'com.iamkaf.multiloader.root'
    id 'com.iamkaf.multiloader.translations'
}

multiloaderTranslations {
    projectSlug = 'liteminer'
    outputDir = layout.projectDirectory.dir('common/src/main/resources/assets/liteminer/lang')
}
```

Private export example:

```groovy
multiloaderTranslations {
    projectSlug = 'liteminer'
    outputDir = layout.projectDirectory.dir('common/src/main/resources/assets/liteminer/lang')
    token = providers.gradleProperty('translations.token')
}
```

Run it manually:

```bash
./gradlew downloadTranslations
```

The translations plugin:

- is root-only
- never treats `en_us` as remote-owned
- never deletes unrelated local lang files automatically

## Publishing Plugin

The publishing plugin exposes aggregate and per-loader tasks.

Important tasks:

- `publishMod`
- `publishCurseforge`
- `publishModrinth`
- `publishCurseforgeFabric`
- `publishCurseforgeForge`
- `publishCurseforgeNeoforge`
- `publishModrinthFabric`
- `publishModrinthForge`
- `publishModrinthNeoforge`
- `publishingRelease`

Use the aggregate tasks for normal release flows. Use the per-loader tasks when one destination or one loader upload fails and you need a targeted retry.

## Validation

Root checks:

```bash
./gradlew build
./gradlew checkAll
./gradlew checkSamples
```

Important sample validations:

```bash
./gradlew -p samples/minimal validateConventionProperties validateSampleWiring build publishingRelease
./gradlew -p samples/datagen validateConventionProperties validateSampleWiring :fabric:runDatagen verifyDatagenOutput
```

These sample consumers are the main regression harness for the plugin family.

## Notes

- `platform-plugin` still exists in the repo and in the live build graph. It remains part of the current bridge between `common` and loader projects.
- This repo is not trying to erase every Minecraft-version difference. It is trying to make version policy explicit and repeatable.
- If a quirk is not standardized yet, the intended policy is to keep it in the consumer and document it in `docs/version-capability-matrix.md`.
