# Multiloader Conventions

Gradle convention plugins for the multiloader mod workspace.

## Scope

This repo now provides the first real plugin family:

- `com.iamkaf.multiloader.settings`
- `com.iamkaf.multiloader.root`
- `com.iamkaf.multiloader.common`
- `com.iamkaf.multiloader.platform`
- `com.iamkaf.multiloader.fabric`
- `com.iamkaf.multiloader.forge`
- `com.iamkaf.multiloader.neoforge`

Compatibility plugin:

- `com.iamkaf.multiloader.core`

`core` is deprecated. It exists only as a temporary alias that applies `common`, and `platform` on loader projects.

## Intended Boundary

- `settings`
  - include enabled projects from version-local properties
  - set the root project name
  - configure shared dependency-resolution repositories
  - load the shared version catalog
- `root`
  - register aggregate tasks
  - validate required root properties
  - expose shared changelog and publisher helpers
- `common`
  - configure Java, publishing, resource expansion, manifest metadata, capabilities, and shared repositories
  - expose `commonJava` and `commonResources` from the `common` project
- `platform`
  - bridge loader projects to `:common`
- `fabric`
  - apply invariant Fabric loader wiring
- `forge`
  - apply invariant Forge loader wiring
- `neoforge`
  - apply invariant NeoForge loader wiring

`flight` stays separate until the publish boundary is mature enough to adopt.

## Required Properties

The convention plugins expect these Gradle properties in the consumer version directory:

- `mod_name`
- `mod_id`
- `platform_minecraft_version`
- `java_version`

Optional properties:

- `enabled_loaders`
  - comma-separated list such as `fabric,forge,neoforge`
- `version_catalog_coordinate`
  - overrides the default catalog coordinate

If `enabled_loaders` is omitted, the settings plugin defaults to `fabric,forge,neoforge`.

## Sample Consumer

See [samples/minimal](samples/minimal) for the current consumer shape and [samples/datagen](samples/datagen) for the current Fabric datagen shape.

The samples use `includeBuild("../../")` so the convention repo can be tested locally before any publication step exists.

## Validation

Use:

- `./gradlew build`
- `./gradlew -p samples/minimal validateConventionProperties validateSampleWiring build`
- `./gradlew -p samples/datagen validateConventionProperties validateSampleWiring :fabric:runDatagen verifyDatagenOutput`
- `./gradlew checkSamples`
