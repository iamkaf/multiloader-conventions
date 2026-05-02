# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Unreleased

### Added

- Catalog coverage for every Konfig and TeaKit Minecraft line from `1.14.4` through `26.1.2`.

### Changed

- Updated loader and helper coordinates to support the full Stonecutter migration and TeaKit-backed validation flow.
- Aligned published `konfig` and `teakit` coordinates with the `0.3.0`, `0.6.0`, and `0.7.0` releases across all covered Minecraft lines.
- Updated the Minecraft 1.19.3 Forge coordinate to `44.1.0` so runtime conformance uses the current patched Forge line.
- Updated the Minecraft 1.19 Forge coordinate to `41.1.0` so Amber can compile against the patched Forge HUD event API used by conformance.
