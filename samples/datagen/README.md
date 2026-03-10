# Datagen Sample

This sample exercises the local convention plugins through `includeBuild("../../")`.

It is intentionally focused on the Fabric datagen path:

- root applies `settings` and `root`
- `common` applies `common`
- `fabric` applies `fabric`
- the sample uses `enableCommonFabricDatagen()` to target `common/src/main/generated`

Use `:fabric:runDatagen` to verify that the extracted helper still routes generated resources into `common`.
