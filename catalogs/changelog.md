# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Unreleased

### Added

- Catalog coverage for every Konfig and TeaKit Minecraft line from `1.14.4` through `26.2`.
- Added the `mc-26.2` catalog for the final Minecraft 26.2 release train.
- Added Fabric and NeoForge Patchouli runtime aliases for Minecraft 26.1 through 26.1.2.

### Changed

- Updated the Minecraft 26.2 catalog to Fabric API `0.152.1+26.2`.
- Updated loader and helper coordinates to support the full Stonecutter migration and TeaKit-backed validation flow.
- Aligned Konfig coordinates with `0.5.0` and TeaKit coordinates with published releases across all covered Minecraft lines.
- Updated the Minecraft 1.19.3 Forge coordinate to `44.1.0` so runtime conformance uses the current patched Forge line.
- Updated the Minecraft 1.19 Forge coordinate to `41.1.0` so Amber can compile against the patched Forge HUD event API used by conformance.
