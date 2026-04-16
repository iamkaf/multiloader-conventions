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
| Forge run config and mixin arg shape | versioned | `forge` | MC line, FG behavior | Works today, but is a likely future strategy point if FG behavior diverges again. |
| NeoForge run/datagen shape | versioned | `neoforge` | MC line, ModDev behavior | Shared for current active lines, but should stay explicit as a strategy point. |
| Fabric datagen DSL | versioned | `fabric` helper + consumer opt-in | Loom/Fabric API DSL by line | Currently centralized only as `enableCommonFabricDatagen`, but consumers opt in per line. |
| Common-project tool plugin family | versioned | `common` | MC line, catalog | Centralized now: Fabric Loom on the old Fabric-only era, `legacyforge` on the LegacyForge window, `moddev` when NeoForm is available. |
| Forge tool plugin family/version | versioned | `forge` | MC line, catalog | Centralized now for the supported floor. The convention plugin only supports Forge on `1.17+`; older Forge stays consumer-local. |
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
- intentionally disabled on template `1.20.1`

Reason:

- the Fabric/Loom datagen DSL is not guaranteed to stay identical across all
  supported lines

If more divergence appears, replace the current helper closure with a named
strategy keyed by version capability, not by ad hoc checks in consumer builds.

### 2. Forge Dev Run Strategy

Current state:

- centralized in the `forge` plugin
- validated on active template and `mochila2` lines

Reason:

- ForgeGradle behavior has already changed across generations
- dev-run setup and mixin argument wiring are historically fragile

If a new MC line needs different Forge run or mixin wiring, add a dedicated
strategy boundary inside the plugin instead of branching inline.

### 3. Common Tool-Family Strategy

Current state:

- common-project tool plugin selection is centralized
- the strategy is keyed by the available tool family for that MC line

Reason:

- the workspace currently spans Fabric-Loom-backed old common builds,
  LegacyForge common builds, and NeoForm-backed common builds

Keep this as an explicit strategy point. If another common build family appears,
add it here instead of scattering version checks across consumers.

### 4. Publishing Normalization Strategy

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

Current required fixture matrix:

- `1.20.1`
  - Fabric build
  - Forge build
  - dry-run publish
- `1.21.1`
  - Fabric build
  - Forge build
  - NeoForge build
  - dry-run publish
- `1.21.10`
  - full stack build
  - Fabric datagen
  - dry-run publish
- `1.21.11`
  - full stack build
  - Fabric datagen
  - dry-run publish

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
