# Multiloader Conventions

Gradle convention plugins for building one Minecraft mod across Fabric, Forge,
and NeoForge.

The plugins centralize the build behavior that multiloader projects otherwise
repeat: repositories and version catalogs, source sharing, loader toolchains,
run configurations, datagen, translations, and publishing. They support both
flat projects and branch-based [Stonecutter](https://stonecutter.kikugie.dev/)
projects.

> [!IMPORTANT]
> Version `3.0-SNAPSHOT` is an in-development, breaking release. Consumers must
> use Kotlin DSL (`.gradle.kts`) and should treat adoption as a migration rather
> than a drop-in upgrade.

## At a glance

- **Coordinates:** `com.iamkaf.multiloader:*:3.0-SNAPSHOT`
- **Gradle DSL:** Kotlin only
- **Loaders:** Fabric, Forge, and NeoForge
- **Project layouts:** flat and Stonecutter
- **Executable examples:** [minimal](samples/minimal) and
  [datagen](samples/datagen)

## Add the plugins

Set the conventions version in the consumer's `gradle.properties`:

```properties
project.plugins=3.0-SNAPSHOT
```

Then configure plugin resolution and apply the settings plugin in
`settings.gradle.kts`:

```kotlin
pluginManagement {
    val conventionsVersion = providers.gradleProperty("project.plugins").get()

    repositories {
        mavenLocal()
        maven("https://maven.kaf.sh") {
            content {
                includeGroupByRegex("com\\.iamkaf(\\..*)?")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        id("com.iamkaf.multiloader.settings") version conventionsVersion
        id("com.iamkaf.multiloader.root") version conventionsVersion
        id("com.iamkaf.multiloader.common") version conventionsVersion
        id("com.iamkaf.multiloader.fabric") version conventionsVersion
        id("com.iamkaf.multiloader.forge") version conventionsVersion
        id("com.iamkaf.multiloader.neoforge") version conventionsVersion
        id("com.iamkaf.multiloader.translations") version conventionsVersion
        id("com.iamkaf.multiloader.publishing") version conventionsVersion
    }
}

plugins {
    id("com.iamkaf.multiloader.settings")
}
```

Apply the root and optional publishing plugins in the root `build.gradle.kts`:

```kotlin
plugins {
    id("com.iamkaf.multiloader.root")
    id("com.iamkaf.multiloader.publishing")

    id("com.iamkaf.multiloader.common") apply false
    id("com.iamkaf.multiloader.fabric") apply false
    id("com.iamkaf.multiloader.forge") apply false
    id("com.iamkaf.multiloader.neoforge") apply false
}
```

Each child project only needs its matching plugin:

```kotlin
// fabric/build.gradle.kts
plugins {
    id("com.iamkaf.multiloader.fabric")
}
```

The [minimal sample](samples/minimal) is the best starting point for a complete
flat consumer. Stonecutter consumers add their version tree and apply the same
project plugins within each selected branch.

## Consumer layout

A flat project has one common project and one project per enabled loader:

```text
my-mod/
├── common/
├── fabric/
├── forge/
├── neoforge/
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

A Stonecutter project adds version-specific properties and overlays:

```text
my-mod/
├── common/
├── fabric/
├── forge/
├── neoforge/
├── versions/
│   └── <minecraft>/
│       ├── gradle.properties
│       └── <source-root>/...
├── build.gradle.kts
├── settings.gradle.kts
├── stonecutter.gradle.kts
└── gradle.properties
```

Keep shared sources in the normal `common`, `fabric`, `forge`, and `neoforge`
roots. Add files under `versions/<minecraft>/<source-root>/...` only when a
Minecraft line genuinely differs.

The settings plugin builds the active graph from the root and version-local
properties. `project.enabled-loaders` chooses the available loaders;
`multiloader.target.versions` and `multiloader.target.loaders` can narrow the
graph for a focused invocation.

## Consumer properties

The core project identity is defined in `gradle.properties` (or in each
Stonecutter version's `gradle.properties`):

```properties
project.group=com.example
project.version=1.0.0+1.21.11
project.minecraft=1.21.11
project.java=21
project.enabled-loaders=fabric,forge,neoforge

mod.id=examplemod
mod.name=Example Mod
```

Real projects also provide loader ranges, metadata, mixin compatibility, and
dependency aliases required by the loaders they enable. See the
[minimal sample properties](samples/minimal/gradle.properties) for a working
baseline.

Frequently used optional property groups include:

- `project.catalog-coordinate` and `project.build-java`
- `dependencies.*` and `environments.*`
- `translations.token`
- `publish.*`

## Plugin reference

| Plugin ID | Apply to | Responsibility |
| --- | --- | --- |
| `com.iamkaf.multiloader.settings` | settings | Repositories, catalogs, Stonecutter project discovery, and target scoping |
| `com.iamkaf.multiloader.root` | root project | Aggregate tasks, graph reporting, shared defaults, and horizontal artifacts |
| `com.iamkaf.multiloader.common` | common project | Shared sources, resources, metadata expansion, and the common toolchain |
| `com.iamkaf.multiloader.platform` | flat loader project | Compatibility bridge from a loader project to a flat `common` project |
| `com.iamkaf.multiloader.fabric` | Fabric project | Loom, dependencies, runs, source sharing, and datagen |
| `com.iamkaf.multiloader.forge` | Forge project | Forge toolchain selection, dependencies, runs, and source sharing |
| `com.iamkaf.multiloader.neoforge` | NeoForge project | NeoForge toolchain selection, dependencies, runs, and datagen |
| `com.iamkaf.multiloader.translations` | root project | Remote translation downloads |
| `com.iamkaf.multiloader.publishing` | root project | Modrinth and CurseForge release planning and uploads |

`com.iamkaf.multiloader.core` supplies shared plugin infrastructure and is not
normally applied by consumers.

Version-dependent loader and toolchain behavior is documented in the
[version capability matrix](docs/version-capability-matrix.md).

## Fabric datagen

Enable common generated resources from a Fabric project:

```kotlin
multiloaderFabric {
    commonDatagen.set(true)
}
```

Flat projects write to `common/src/main/generated`. Stonecutter projects write
to `versions/<minecraft>/common/src/main/generated`.

Fabric datagen is supported as a runtime path on Minecraft 1.17 and newer. On
1.14.4 through 1.16.5, generated resources are expected to be checked in;
`runDatagen` reports that compatibility boundary without launching the legacy
runtime. See the [datagen sample](samples/datagen) for a working configuration.

## Translations

Apply the translations plugin at the root and configure the destination:

```kotlin
plugins {
    id("com.iamkaf.multiloader.translations")
}

multiloaderTranslations {
    projectSlug.set("example-mod")
    outputDir.set(
        layout.projectDirectory.dir(
            "common/src/main/resources/assets/examplemod/lang",
        ),
    )
    token.set(providers.gradleProperty("translations.token")) // optional
}
```

Download translations with:

```bash
./gradlew downloadTranslations
```

The plugin downloads approved non-`en_us` locale files. It leaves `en_us` and
unrelated local language files under the consumer's control.

## Publishing

The publishing plugin plans releases from the active loader/version graph and
provides these aggregate tasks:

| Task | Purpose |
| --- | --- |
| `publishMod` | Publish to every configured destination |
| `publishCurseforge` | Publish only to CurseForge |
| `publishModrinth` | Publish only to Modrinth |
| `publishingRelease` | Plan or execute the configured release flow |

Targeted loader, version, and destination tasks are generated for partial
retries. Set `publish.dry-run=true` while validating release configuration.

## Horizontal multiloader jars

The root plugin can merge the loader artifacts for one Minecraft version into
one jar. The feature is disabled by default and does not alter the normal
loader-specific artifacts or publications.

```kotlin
multiloaderArtifacts {
    horizontalMerge {
        enabled.set(true)
        versions.addAll("1.21.1", "26.1.2", "26.2")

        acknowledgeUnsafeVersion("1.21.1")
    }
}
```

Leaving `versions` empty selects every complete multiloader version in the
active target scope. `version("26.2")` is an additive shorthand.

Minecraft 26.1 and newer are the stable tier. Minecraft 1.21.1 and 1.21.11 are
an unsafe relocated tier and require an explicit acknowledgement for each
version; other older versions are rejected. Unsafe merges may rename common
classes, mixin configurations, assets, and loader mod IDs, which can break
addons and mixins.

The root plugin registers:

- `mergeHorizontalJar<Version>` and `validateHorizontalJar<Version>` for one
  version
- `mergeHorizontalJars` and `validateHorizontalJars` for all selected versions

Artifacts are written to:

```text
build/libs/horizontal/<minecraft>/<mod-id>-multiloader-<project-version>.jar
```

Validation checks the merged ZIP, loader metadata, referenced mixins and
classes, entrypoints, access wideners or transformers, assets, data, and shared
resources. Horizontal platform upload tasks are not yet registered; normal
per-loader platform and Maven publications remain unchanged.

`printMultiloaderGraph` and `writeMultiloaderGraph` report whether each version
is eligible, planned, validated, and publishable.

## Development

Run the repository checks from the root:

```bash
./gradlew build
./gradlew checkAll
./gradlew checkSamples
```

The individual sample checks are:

```bash
./gradlew -p samples/minimal validateConventionProperties validateSampleWiring build publishingRelease
./gradlew -p samples/datagen validateConventionProperties validateSampleWiring :fabric:runDatagen verifyDatagenOutput
```

Publish local changes for consumer testing with Gradle's
`publishToMavenLocal` tasks.

## Versioning

- **Major releases** may rewrite the conventions and require consumer
  migrations.
- **Minor releases** add features and fixes, and may include small behavior
  changes.
- **Patch releases** are reserved for non-breaking fixes.

Consumers should validate every Minecraft version and loader they ship when
upgrading, especially while using the `3.0-SNAPSHOT` line.
