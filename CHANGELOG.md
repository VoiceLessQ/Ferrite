# Changelog

## [Unreleased]

### Added
- End-to-end verification that Rust code executes from a running Fabric mod: `RustBridge.main()` registers an item (`apikaprobe:serjio`) in the Redstone creative tab via JNI, with no `UnsatisfiedLinkError` on launch.
- `loom.runs.configureEach` block in [build.gradle](build.gradle) that passes `-Djava.library.path=<rust target dir>` to all Gradle run tasks, so `./gradlew runClient` can discover `rust_mod.dll` without manual copying.
- Documentation: [README.md](README.md) rewritten to describe the Rust ↔ Java architecture, build steps, and current binding coverage.

### Changed
- Pinned the Rust toolchain for this directory to `nightly-2025-08-29-x86_64-pc-windows-gnu` via `rustup override`. Required because [rust/api/src/lib.rs](rust/api/src/lib.rs#L1) uses `#![feature(associated_type_defaults)]`, which is nightly-only.

### Build notes
- Pinned the linker for the `x86_64-pc-windows-gnu` target in [.cargo/config.toml](.cargo/config.toml) to `C:/msys64/mingw64/bin/gcc.exe`, so `cargo build` works out of the box without needing `mingw-w64-x86_64-gcc` on `PATH`.

## Previous commits (from `git log`)

- `04e9cb8` wip class file bytecode
- `bdcab51` fix. Welcome back serjio :kiss:
- `71e1bd8` allow renaming on bind
- `f49d8d7` add `export` macro
- `25c8494` Add automatic resolver for enums
