# Multiloader Conventions

Gradle convention plugins for Kaf's multiloader Minecraft mod workspace.

This repository is the shared build layer for branch-based Stonecutter projects. The `3.0-SNAPSHOT` line is a porting line: the plugins are being reworked toward a Kotlin implementation with deeper, typed modules for version policy, source layout, loader toolchains, and publishing.

## Release Status

- Group: `com.iamkaf.multiloader`
- Version: `3.0-SNAPSHOT`
- Compatibility: porting effort for consumers
- Consumer DSL: Kotlin DSL only

The 3.0 line is allowed to break consumer build files. Mods adopting it must migrate Gradle scripts to Kotlin DSL (`settings.gradle.kts` and `build.gradle.kts`), update deliberately, and validate every supported loader and Minecraft version they ship.

## Versioning Policy

Major version bumps mean the convention plugins have been completely rewritten. Compatibility is not guaranteed, and consuming mods should treat adoption as a porting effort.

Minor version bumps cover feature additions, bug fixes, and minor behavior changes. They are intended to be safe-ish to adopt, but consumers should still validate their full loader and Minecraft-version matrix.

Patch version bumps, when used, are reserved for bug fixes and are considered non-breaking. They should be rare.

## Design Direction

The 3.0 rewrite has one architectural rule: the happy mainstream path should be obvious. Anything that is not the mainstream path should be separated, named, and selected intentionally.

Stable build behavior belongs in convention plugins. Repeated version or loader drift belongs behind explicit strategy modules. Project-specific quirks stay in the consumer until they repeat enough to justify a convention.

The main module seams for 3.0 are:

- `VersionPolicy`: Minecraft-line facts, loader eligibility, Java levels, catalog names, metadata ranges, mixin compatibility, and strategy selection.
- `MultiloaderProjectContext`: typed property, catalog, dependency, repository, and metadata expansion access.
- `StonecutterSourceLayout`: common and loader source/resource staging, generated roots, version overlays, and graph-only layouts.
- `FabricDependencyStrategy`: modern unobfuscated Fabric, normal obfuscated Loom, and legacy split Fabric API lanes.
- `ForgeRunStrategy`: mainstream ForgeGradle, LegacyForge/moddev, and 1.16.5 userdev run paths.
- `NeoForgeToolchainStrategy`: ModDev and NeoGradle userdev selection.
- `ClientRunEnvironmentPolicy`: Forge-like client run environment normalization, including Linux/Wayland to X11 launch stability.
- `PublicationPlanner`: release planning separate from Modrinth and CurseForge upload clients.

Some of these modules may be introduced before the full Groovy-to-Kotlin migration is complete. During the transition, README claims should follow the live branch state.

## Plugin Set

The plugin family is:

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

Included Gradle modules:

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

## Plugin Ownership

`settings`

- configures plugin repositories and shared plugin versions;
- creates the Stonecutter project tree from `versions/<mc>/gradle.properties`;
- creates version catalogs for included Minecraft lines;
- supports target scoping for version and loader subsets.

`root`

- owns root-only aggregate tasks and build graph reporting;
- wires translations and publishing defaults for Stonecutter consumers;
- forwards TeaKit runtime properties to supported run tasks;
- registers publication plans for enabled loader/version pairs.

`core`

- exposes shared convention support to plugin modules;
- is being reduced toward typed Kotlin helpers instead of dynamic Gradle extra-property closures.

`common`

- configures the `common` artifact for each Minecraft line;
- selects the common toolchain family by version policy;
- stages generated and version-specific common sources/resources;
- exposes common Java and resource outputs to loader projects.

`platform`

- bridges flat loader projects to a flat `common` project;
- remains available for older flat layouts while Stonecutter consumers use branch-aware loader plugins.

`fabric`

- configures Fabric Loom;
- selects Fabric dependency lanes by version policy;
- stages common, generated, and Fabric-specific sources/resources;
- exposes typed Fabric datagen helpers;
- routes common datagen output into the selected Stonecutter version lane;
- labels legacy checked-in generated-resource lanes when Fabric datagen is not a stable runtime path.

`forge`

- configures ForgeGradle or LegacyForge based on version policy;
- stages common, generated, and Forge-specific sources/resources;
- owns Forge run setup for supported lines;
- normalizes client run tasks on Linux/Wayland hosts for GLFW/X11 stability;
- keeps unsupported or exceptional legacy behavior out of the mainstream path.

`neoforge`

- configures NeoForge ModDev or NeoGradle userdev based on version policy;
- stages common, generated, and NeoForge-specific sources/resources;
- owns NeoForge run and datagen setup;
- normalizes client run tasks on Linux/Wayland hosts for GLFW/X11 stability.

`translations`

- downloads approved non-`en_us` locale JSON files from `i18n.kaf.sh`;
- writes them to the configured consumer language directory;
- keeps source-locale ownership local to the consumer.

`publishing`

- plans loader and platform publication tasks;
- stages artifacts for Modrinth and CurseForge uploads;
- supports dry-run and live publishing modes;
- keeps platform-specific upload clients behind destination adapters.

## Expected Consumer Shape

The main supported shape is a branch-based Stonecutter repository:

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

Every Gradle script in a v3 consumer must use Kotlin DSL. The settings plugin and public project plugins reject checked-in Groovy `*.gradle` scripts so legacy build glue cannot silently stay on the mainstream path.

Version-local metadata lives in `versions/<mc>/gradle.properties`. Source and resource overlays live under `versions/<mc>/<root>/...` only when a version genuinely diverges.

The active graph is derived from:

- root `gradle.properties`;
- `versions/<mc>/gradle.properties`;
- the selected version catalog coordinate;
- `project.enabled-loaders`;
- optional `multiloader.target.versions` and `multiloader.target.loaders` scope properties.

## Required Consumer Properties

Required version-local properties:

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

Consumers should treat `project.plugins=3.0-SNAPSHOT` as an intentional migration choice, not a drop-in patch update.

## Consumer Setup

Typical `settings.gradle.kts` plugin management:

```kotlin
pluginManagement {
    val multiloaderConventionsVersion = providers.gradleProperty("project.plugins").get()

    repositories {
        mavenLocal()
        maven("https://maven.kaf.sh") {
            name = "Kaf Maven"
            content {
                includeGroupByRegex("com\\.iamkaf(\\..*)?")
            }
        }
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

Minimal branch build files should only apply the matching plugin:

```kotlin
plugins {
    id("com.iamkaf.multiloader.fabric")
}
```

Use the samples as executable references:

- [samples/minimal](samples/minimal)
- [samples/datagen](samples/datagen)

### Fabric Datagen

Consumers opt into common generated resources with the typed Kotlin DSL:

```kotlin
multiloaderFabric {
    commonDatagen.set(true)
}
```

Flat projects write to `common/src/main/generated`. Stonecutter projects write to `versions/<minecraft>/common/src/main/generated`, so generated resources can follow the same version lanes as hand-written overlays.

Minecraft `1.17` and newer use Fabric datagen as the mainstream path. Minecraft `1.14.4` through `1.16.5` use checked-in compatibility lanes; their `runDatagen` task reports that boundary and exits successfully instead of launching an unstable legacy Fabric datagen runtime.

## Translations

Apply translations in the consumer root when remote translations are part of the project:

```kotlin
plugins {
    id("com.iamkaf.multiloader.root")
    id("com.iamkaf.multiloader.translations")
}

multiloaderTranslations {
    projectSlug.set("liteminer")
    outputDir.set(layout.projectDirectory.dir("common/src/main/resources/assets/liteminer/lang"))
}
```

Private exports can use a Gradle property token:

```kotlin
multiloaderTranslations {
    projectSlug.set("liteminer")
    outputDir.set(layout.projectDirectory.dir("common/src/main/resources/assets/liteminer/lang"))
    token.set(providers.gradleProperty("translations.token"))
}
```

Run it manually:

```bash
./gradlew downloadTranslations
```

The translations plugin is root-only, never treats `en_us` as remote-owned, and never deletes unrelated local language files automatically.

## Publishing

The publishing plugin exposes aggregate and per-destination tasks.

Common tasks:

- `publishMod`
- `publishCurseforge`
- `publishModrinth`
- `publishingRelease`

Per-loader and per-version tasks are generated from the active build graph. Use aggregate tasks for normal release flows. Use targeted tasks when one destination or one loader upload needs a retry.

Agent workflows may publish to Maven local for validation. Kaf Maven publishing is reserved for manual release operations.

## Horizontal Multi-loader Jars

The root plugin can opt into one merged jar per Minecraft version. This feature is off by default: disabled consumers do not get Forgix configurations, merge tasks, validation tasks, or changes to their existing raw loader builds and publications.

```kotlin
multiloaderArtifacts {
    horizontalMerge {
        enabled.set(true)
        versions.addAll("1.21.1", "1.21.11", "26.1.2", "26.2")

        acknowledgeUnsafeVersion("1.21.1")
        acknowledgeUnsafeVersion("1.21.11")
    }
}
```

An empty `versions` set selects every complete multi-loader version in the active target scope. `version("26.2")` is available as an additive shorthand for selecting one version.

Minecraft `26.1` and newer form the stable tier. Common class binary names and paths must remain identical across the input and merged jars; non-class resources must also preserve their bytes. Forgix may reserialize otherwise equivalent classfiles while merging. Minecraft `1.21.1` and `1.21.11` are an unsafe relocated tier: Forgix may suffix common classes, mixin configs, assets, and Fabric/Forge mod IDs, which can break addon dependencies and mixins. Each unsafe version requires its own `acknowledgeUnsafeVersion(...)` call. Other older versions are rejected.

The root-owned tasks are:

- `mergeHorizontalJar<Version>` and `validateHorizontalJar<Version>` for one version, such as `mergeHorizontalJar2612` and `validateHorizontalJar2612`;
- `mergeHorizontalJars` and `validateHorizontalJars` for every selected version.

Merged artifacts are written to `build/libs/horizontal/<minecraft>/<mod-id>-multiloader-<project-version>.jar`. The explicit `multiloader` loader segment distinguishes horizontal artifacts from loader-specific jars when both coexist, including repositories where only newer Minecraft versions can be merged. The merge consumes the real archive providers selected by version policy (`remapJar` where required and `jar` otherwise, plus a Forge reobfuscation lifecycle task when the loader exposes one), copies every input to disposable task-local storage, and never gives Forgix a raw build output it can mutate.

Validation checks the output as a readable ZIP and verifies loader metadata, tier-appropriate mod IDs, referenced mixin configs and classes, Fabric entrypoints and access wideners, Forge-like access transformers, assets/data, stable common class paths, and exact non-class common resources.

`printMultiloaderGraph` and `writeMultiloaderGraph` add a default-safe `horizontal` object to each version. It reports `enabled`, `planned`, `stabilityTier`, `unsafeAcknowledged`, `selectedLoaders`, `mergeTask`, `validateTask`, `artifactPath`, `publishable`, `nonPublishableReason`, and `platformPublishTasks`. Horizontal platform upload tasks are intentionally not registered yet because the publishing plugin cannot safely represent cross-loader dependency semantics. Raw per-loader platform and Maven publications remain unchanged.

## Validation

Root checks:

```bash
./gradlew build
./gradlew checkAll
./gradlew checkSamples
```

Sample checks:

```bash
./gradlew -p samples/minimal validateConventionProperties validateSampleWiring build publishingRelease
./gradlew -p samples/datagen validateConventionProperties validateSampleWiring :fabric:runDatagen verifyDatagenOutput
```

Consumer migration checks should start with the newest supported Minecraft line and then work backward through older lines once the mainstream path is stable.

## Policy References

Detailed version capability notes live in [docs/version-capability-matrix.md](docs/version-capability-matrix.md).

This repository does not try to erase every Minecraft-version difference. It makes the shared policy explicit, keeps ordinary builds boring, and labels exceptional compatibility behavior so consumers do not have to rediscover it.
