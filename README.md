# Multiloader Conventions

Gradle convention plugins for the multiloader mod workspace.

## Scope

This repo currently scaffolds the first three convention layers:

- `com.iamkaf.multiloader.settings`
- `com.iamkaf.multiloader.root`
- `com.iamkaf.multiloader.core`

These are intentionally the non-publish, non-loader-specialized layers. `flight` stays separate until the publish boundary is mature enough to adopt.

## Intended Boundary

- `settings`
  - include enabled projects from version-local properties
  - set the root project name
  - configure shared dependency-resolution repositories
  - load the shared version catalog
- `root`
  - register aggregate tasks
  - validate required root properties
- `core`
  - configure repositories for project builds
  - configure Java toolchains
  - apply shared manifest metadata

## Required Properties

The first plugin slice expects these Gradle properties in the consumer version directory:

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

See [samples/minimal](samples/minimal) for the intended consumer shape during early development.

The sample uses `includeBuild("../../")` so the convention repo can be tested locally before any publication step exists.

## Next Steps

1. Wire these plugins into `multiloader-template`.
2. Prove them in `mochila2`.
3. Add audit automation around root files and version-directory policy.
4. Add loader-specific plugins only after the shared core contract stabilizes.

