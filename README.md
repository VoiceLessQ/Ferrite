# rust-mod-probe

An experimental Fabric mod for Minecraft 1.21.11 that lets mod logic run in **Rust** via JNI. The goal is to evaluate whether native code can meaningfully improve performance for compute-heavy tasks (worldgen, pathfinding, meshing, AI) inside a Minecraft mod.

## Project layout

```
rust/
  api/      rosttasse          — JNI abstraction layer (traits, conversion, class defs)
  macros/   rosttasse_macros   — proc macros (#[bind], #[export], #[main])
  mc/       rosttasse-mc       — Rust bindings to Minecraft types
  mod/      rust-mod           — the example mod's native library (cdylib)
src/main/java/me/apika/apikaprobe/
  ExampleMod.java      — Fabric entrypoint, calls RustBridge.main()
  Bridge.java          — loads rust_mod.dll, declares native methods
  ModItems.java        — (stub)
  mixin/               — mixin hooks
```

The Rust workspace is a multi-crate layout driven by the root [Cargo.toml](Cargo.toml). The Java side is a standard Fabric / Loom project driven by [build.gradle](build.gradle).

## Building

**Prerequisites**
- Nightly Rust toolchain (uses `#![feature(associated_type_defaults)]`)
- MSYS2 with `mingw-w64-x86_64-gcc` (the `x86_64-pc-windows-gnu` target's linker)
- JDK 21

**One-time setup**
```bash
rustup override set nightly-2025-08-29-x86_64-pc-windows-gnu
```

**Build**
```bash
cargo build --release --manifest-path=rust/mod/Cargo.toml
```

The linker path is pinned in [.cargo/config.toml](.cargo/config.toml) to `C:/msys64/mingw64/bin/gcc.exe`. If your MSYS2 is installed elsewhere, edit that file.

Output: `target/x86_64-pc-windows-gnu/release/rust_mod.dll`.

See [SETUP_MINGW.md](SETUP_MINGW.md) for alternatives (portable MinGW, MSVC).

## Running

The Gradle `runClient` task is wired to pass `-Djava.library.path` pointing at the Rust build output, so once the DLL is built:

```bash
./gradlew runClient
```

The mod loads `rust_mod.dll` on init and calls `RustBridge.main()` (a `native` method), which executes Rust code that registers a `serjio` item in the Redstone creative tab via JNI.

## Status

- ✅ Rust workspace builds clean on nightly + MSYS2 gcc
- ✅ Native library loads in Fabric 1.21.11
- ✅ Item registration from Rust through JNI works end-to-end
- ⚠️ `rosttasse-mc` covers a small subset of MC (entity / item / registry / text / util / world)
- ❌ No bindings yet for worldgen, pathfinding, chunk generation, or rendering

## License

CC0-1.0. See [LICENSE](LICENSE).
