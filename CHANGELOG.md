# Changelog

All notable changes to Ferrite are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/) with the
`-alpha` suffix indicating pre-release research builds.

## [0.1.0-alpha] — 2026-04-18

First public alpha. Research mod framing — instrumentation only, no
gameplay changes. Windows 64-bit only.

### Added

- **Client-side performance monitors.** All log under the `[ferrite]` prefix on a 5-second window:
  - `[chunkgen]` — chunk generation phase costs (noise-dispatch, noise-sync, surface), per-chunk averages and maxes
  - `[client-lag]` — FPS avg/min/max, entity count, loaded chunk count, with qualitative tag (OK/WARN/LAG)
  - `[entity-render]` — sampled (1-in-100) per-entity render time, plus extrapolated per-frame cost on current hardware and estimated cost on low-end GPU (Intel HD 620 reference)
  - `[mspt]` — real server tick duration via START/END tick events
  - `[rust-engine]` — Rust worker pool initialization (Rayon, hardware-aware sizing)
- **Rust native library** bundled in the jar at `assets/ferrite/natives/rust_mod.dll`.
  - Extracted to a temp file at runtime on Windows, loaded via `System.load`
  - Graceful fallback on non-Windows — mod loads cleanly but native features stay disabled with a clear log message
- **Automatic build integration.** `./gradlew build` invokes `cargo build --release` for the Rust side and bundles the resulting DLL into the jar automatically. Requires `cargo` on PATH.
- **Mixin-based instrumentation.** `@Inject` hooks on:
  - `NoiseChunkGenerator.populateNoise` (async dispatch + private sync overload)
  - `NoiseChunkGenerator.buildSurface`
  - `ChunkNoiseSampler.sampleStartDensity` / `sampleEndDensity`
  - `AquiferSampler$Impl.apply` (sampled 1-in-100)
  - `EntityRenderManager.render` (sampled 1-in-100)
- Accessor mixins for `ChunkNoiseSampler.interpolators` and `DensityInterpolator` buffer fields — used by the interpolator diagnostic.
- One-shot diagnostic Mixin that dumps interpolator structure on first chunk gen after launch.
- Full documentation:
  - [README.md](README.md) — what the mod is, how to install, how to build
  - [docs/CURSEFORGE_DESCRIPTION.md](docs/CURSEFORGE_DESCRIPTION.md) — CurseForge project page text
  - [docs/PROFILING.md](docs/PROFILING.md) — full profiling investigation, architectural findings, why the current approach is instrumentation-only
  - [SETUP_MINGW.md](SETUP_MINGW.md) — Rust toolchain setup notes

### Build infrastructure

- Toolchain pinned via [rust-toolchain.toml](rust-toolchain.toml) to `nightly-2025-08-29` + `x86_64-pc-windows-gnu` target.
- GNU linker path pinned in [.cargo/config.toml](.cargo/config.toml) to `C:/msys64/mingw64/bin/gcc.exe` so `cargo build` works without PATH juggling.
- `build.gradle` simulates low-end hardware for dev runs via `-Xmx3G -Xms512M` JVM args, making baseline measurements comparable across sessions.
- `.gitignore` excludes the DLL staging directory (`src/main/resources/assets/ferrite/natives/`) since it's a build artifact.

### Research findings documented

- Rust bulk compute is ~7× faster than vanilla noise-sync for equivalent work (2.5 ms vs 17 ms per chunk).
- Per-call JNI overhead (200–500 ns) exceeds the cost of hot functions called 98K times per chunk, making per-call porting non-viable.
- Vanilla's density-function interpolators hold rotating `[2][49]` corner buffers, not a consolidated corner grid — reading them requires reconstructing a state machine.
- A full Rust replacement for vanilla worldgen would require porting the density-function composition tree (C2ME-scale work, 2–4 weeks, high version fragility).

### Known limitations

- **Windows 64-bit only.** Mac/Linux builds require cross-compilation to `.so` / `.dylib` and are deferred to a future release.
- **No gameplay changes.** This release is instrumentation only by design.
- **No config toggle for log verbosity.** The mod writes ~12 log lines per minute during active play. A toggle is on the roadmap.
- **Cargo required to build from source.** `./gradlew build -x copyRustDll` can produce a jar without the DLL for contributors without a Rust toolchain, but the resulting jar runs without native features.

### License

- MIT — see [LICENSE](LICENSE). Previously CC0-1.0 in the pre-release research branch.

---

## Pre-release history

The mod grew out of the `rust-mod-probe` research project. Key pre-release
commits documenting the investigation path are preserved on the `ferrite`
and `main` branches. Notable milestones:

- JNI framework + first end-to-end Rust call from a Fabric mod
- Custom Rust-powered chunk generator in a separate dimension (Phase B)
- Hydraulic erosion post-pass on vanilla chunks (Phase A) — abandoned due to
  unavoidable chunk-boundary seams on integer-grid terrain
- Feature injector pattern (monoliths) — proved but not included in v0.1.0
- Phase-by-phase profiling of vanilla chunk generation
- Bulk-handoff architecture validation
- Density function interpolator structure investigation

See [docs/PROFILING.md](docs/PROFILING.md) for the full investigation
write-up.
