# Version Capability Matrix

This document defines how `multiloader-conventions` should deal with
Minecraft-version and loader-version quirks.

The goal is not to hide every difference behind one plugin. The goal is to
separate:

- stable shared behavior that belongs in the plugin family
- repeated versioned behavior that belongs in explicit strategy points
- one-off quirks that should stay consumer-local until they repeat

## Rules

1. Do not add scattered `if (mcVersion == ...)` conditionals across plugins.
2. Extract a version-specific rule only after it appears in at least two real
   version lines or consumers.
3. Keep major tool-family selection consumer-local unless the workspace has
   fully standardized it.
4. Every extracted strategy needs fixture coverage in
   [samples/minimal](/home/kaf/code/mods/multiloader-conventions/samples/minimal)
   or another dedicated sample.
5. If a quirk is not yet standardized, document it here and keep it local.

## Capability States

- `stable`
  - one implementation is expected to work across all supported lines
- `versioned`
  - behavior belongs in the plugin family, but via an explicit strategy point
- `local`
  - keep it in consumer build files for now

## Matrix

| Capability | State | Current Owner | Inputs | Notes |
| --- | --- | --- | --- | --- |
| Settings repo/catalog wiring | stable | `settings` | `project.enabled-loaders`, `project.catalog-coordinate` | Already centralized. |
| Root aggregate tasks | stable | `root` | enabled subprojects | Already centralized. |
| Common source/resource bridge | stable | `platform` | project layout | Already centralized. |
| Resource expansion for metadata files | stable | `common` | root/version properties | Already centralized. |
| Archive naming, manifests, capabilities, Maven publication | stable | `common` | `mod.id`, `mod.name`, `project.version` | Already centralized. |
| Fabric base dependency wiring | stable | `fabric` | version catalog | Already centralized. |
| Fabric run config shape | stable | `fabric` | Loom DSL | Current `client` / `server` run wiring is shared. |
| Forge run config and mixin arg shape | versioned | `forge` | MC line, ForgeGradle/LegacyForge behavior | Centralized now, including legacy TeaKit runtime handling on `1.16.5`, `1.17.1`, `1.18`, `1.18.1`, and `1.18.2`. |
| NeoForge run/datagen shape | versioned | `neoforge` | MC line, ModDev behavior | Shared for current active lines, but should stay explicit as a strategy point. |
| Fabric datagen DSL | versioned | `fabric` typed extension + consumer opt-in | Loom/Fabric API DSL by line | Centralized as `multiloaderFabric.commonDatagen`; consumers still opt in per line. |
| Fabric legacy dependency pins | versioned | `fabric` | MC line, catalog gaps | Centralized for known exceptions such as the strict `1.18.2` Fabric Loader pin and old Fabric API module set. |
| Common-project tool plugin family | versioned | `common` | MC line, catalog | Centralized now: Fabric Loom on the old Fabric-only era, `legacyforge` on the LegacyForge window, `moddev` when NeoForm is available. |
| Forge tool plugin family/version | versioned | `forge` | MC line, catalog | Centralized for `1.16.5` and `1.17+`. Other pre-`1.17` Forge lines stay consumer-local unless explicitly promoted. |
| NeoForge tool plugin family/version | stable | `neoforge` | MC line, catalog | Centralized in the plugin. |
| Publishing payload assembly | stable | `publishing` | loader outputs, changelog, IDs | Already centralized. |
| Publishing tag normalization | versioned | `publishing` | MC version, Java version, loader set | Current implementation is shared, but CurseForge/Modrinth quirks should be treated as versioned strategy points. |
| Placeholder or prototype publish metadata policy | local | consumer properties | repo intent | Not a plugin concern. |
| Rare one-off loader dependency hacks | local | consumer loader build | mod-specific needs | Keep local until repeated. |

## Current Known Versioned Strategy Points

These are the first places where explicit strategy helpers are likely needed if
more drift appears.

### 1. Fabric Datagen Strategy

Current state:

- centralized helper in
  [ConventionSupport.groovy](/home/kaf/code/mods/multiloader-conventions/conventions-support/src/main/groovy/com/iamkaf/multiloader/support/ConventionSupport.groovy)
- consumer opt-in in loader build files
- covered by the `samples/datagen` fixture on `1.21.11`

Reason:

- the Fabric/Loom datagen DSL is not guaranteed to stay identical across all
  supported lines

If more divergence appears, replace the current helper closure with a named
strategy keyed by version capability, not by ad hoc checks in consumer builds.

### 2. Forge Dev Run Strategy

Current state:

- centralized in the `forge` plugin
- `1.16.5` is explicitly supported as the legacy Forge floor
- `1.14.x`, `1.15.x`, and `1.16.0` through `1.16.4` are rejected by the convention plugin and stay consumer-local
- `1.17.x` through `1.20.1` use `net.neoforged.moddev.legacyforge`
- newer supported Forge lines use `net.minecraftforge.gradle`
- TeaKit runtime runs need legacy classpath/service handling on `1.16.5`, `1.17.1`, `1.18`, `1.18.1`, and `1.18.2`
- validated on active template and consumer lines

Reason:

- ForgeGradle behavior has already changed across generations
- dev-run setup and mixin argument wiring are historically fragile
- legacy TeaKit launch wiring is fragile enough to keep as an explicit strategy

If a new MC line needs different Forge run or mixin wiring, add a dedicated
strategy boundary inside the plugin instead of branching inline.

### 3. Fabric Legacy Dependency Strategy

Current state:

- Fabric dependency wiring is centralized in the `fabric` plugin
- most lines use catalog-provided Fabric Loader and Fabric API coordinates
- `1.18.2` pins Fabric Loader to `0.14.9`
- old unobfuscated/Fabric-only lines use a fixed set of Fabric API modules

Reason:

- older Fabric lines do not always tolerate the current catalog default
- old Fabric API module coordinates are not interchangeable with the modern
  aggregate Fabric API dependency

Keep these exceptions in one helper/strategy area in the plugin. If another
line needs a pinned loader or split Fabric API module set, add it beside the
existing strategy instead of placing the pin in consumer builds.

### 4. Common Tool-Family Strategy

Current state:

- common-project tool plugin selection is centralized
- the strategy is keyed by the available tool family for that MC line
- `1.14.x`, `1.15.x`, and `1.16.x` common projects use Fabric Loom
- later lines without NeoForm use `net.neoforged.moddev.legacyforge`
- lines with NeoForm use `net.neoforged.moddev`

Reason:

- the workspace currently spans Fabric-Loom-backed old common builds,
  LegacyForge common builds, and NeoForm-backed common builds

Keep this as an explicit strategy point. If another common build family appears,
add it here instead of scattering version checks across consumers.

### 5. Publishing Normalization Strategy

Current state:

- shared dry-run/live publish flow lives in `publishing`
- game-version, loader, dependency, and Java-tag normalization are centralized

Reason:

- Modrinth and CurseForge evolve independently
- loader tags and Java tags are not guaranteed to remain uniform forever

If publish metadata starts to vary by MC line or loader family, isolate that in
strategy helpers under `publishing`, not in consumers.

## Fixture Expectations

Every strategy point should map to at least one sample or real-consumer check.

Current sample fixture matrix:

- `1.21.11`
  - minimal full-stack build
  - Fabric datagen
  - dry-run publish

Older and legacy runtime strategy points are currently proven through real
consumers rather than dedicated samples. When a legacy quirk graduates into
shared convention behavior, add either a focused sample or a named consumer
verification command to this section.

Current proving-ground consumers:

- [multiloader-template](/home/kaf/code/mods/multiloader-template)
- [mochila2](/home/kaf/code/mods/mochila2)

## Extraction Policy

Use this order when a new quirk appears:

1. Verify it in a real consumer.
2. Decide whether it is `stable`, `versioned`, or `local`.
3. If `local`, keep it in the consumer and record it here.
4. If `versioned`, add a named strategy point in the relevant plugin.
5. Add fixture coverage before broad rollout.

The important constraint is that the plugins should become the home of
intentional version policy, not a bag of hidden exceptions.
