# Changelog

All notable changes to Ferrite are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/) with the
`-alpha` suffix indicating pre-release research builds.

## [Unreleased]

### Pre-chunk loading (investigated, shipped disabled)

Implemented movement-predictive chunk ticket submission — samples player
velocity each tick, extrapolates vd+8 chunks ahead, submits vanilla
ChunkTicketType tickets on Rayon background path.

Measured on dedicated server (vd=10, elytra speed):
  submitted: 10-36/5s
  avg-lead:  3-6t (150-300ms)
  max-lead:  57-60t (cold cache / frontier terrain only)

Verdict: vanilla's chunk scheduler reaches FULL status in ~3-6 ticks
regardless of how early we ask. Pushing margin from +8 to +16 chunks
produced identical results. Modern hardware generates chunks fast enough
that predictive pre-submission has no meaningful headroom.

Contrast: cramming port achieved 65% reduction because the cost was
algorithmic (O(N²) → spatial hash). Pre-chunk cost is I/O-bound and
already parallelized by vanilla. No Rust angle either — the bottleneck
is vanilla's noise pipeline, which hits the same density-function
blocker as the earlier worldgen port attempt.

Code ships enabled (ENABLED=true) for ongoing measurement across user
configurations — the dedicated-server result above may not hold on
high-view-distance servers (vd≥16), overloaded hosts, or sustained
frontier exploration where vanilla's scheduler falls behind. Disable
by setting `PreChunkDispatcher.ENABLED = false` if the [prechunk] log
shows avg-lead persistently ≤ 6t with non-trivial CPU cost.

---

## [0.1.2-alpha] — 2026-04-19

Cross-platform native support. No gameplay changes beyond what 0.1.1-alpha
already shipped — Linux and macOS users now get the same cramming
speedup as Windows users.

### Added

- **Linux support** — `librust_mod.so` built for `x86_64-unknown-linux-gnu`,
  bundled at `/assets/ferrite/natives/linux/` in the jar.
- **macOS support** — universal `librust_mod.dylib` combining
  `aarch64-apple-darwin` (Apple Silicon) and `x86_64-apple-darwin` (Intel)
  via `lipo -create`. Bundled at `/assets/ferrite/natives/macos/`.
- **Host-aware gradle build** — `buildRustLib` detects the current OS
  via `OperatingSystem.current()` and picks the right (target, lib-name,
  subdir) triple. Passes `--target` explicitly so output paths are
  deterministic.
- **Four-job CI pipeline** — three parallel native builds
  (windows/linux/macos) + an assembly job that downloads all three
  artifacts and assembles one jar. See `.github/workflows/build.yml`.

### Changed

- **`RustBridge.java`** — per-OS resource path selection at runtime:
  `/assets/ferrite/natives/{windows,linux,macos}/rust_mod.{dll,so,dylib}`.
  Unsupported OS still falls back cleanly (no crash, clear log).
- **`.cargo/config.toml`** — dropped the `[build] target` default (was
  forcing `x86_64-pc-windows-gnu` on every host); kept the gnu-specific
  linker spec which only applies when actually targeting gnu.
- **`SETUP_MINGW.md`** — renamed header, covers all three platforms;
  MinGW-specific instructions preserved below.

### Verified

- WSL Ubuntu 24.04: `.so` extracted, `System.load` succeeded, `initEngine`
  returned a Rayon pool size, server reached "Done" in 2.7 s with Fabric
  API + Ferrite loaded.
- CI: four jobs green, artifacts for all three platforms produced.

### Known limitations

- **Linux x86_64 only** — no ARM (aarch64) Linux build yet.
- **Cramming damage still deferred** (carried over from 0.1.1).

---

## [0.2.0-alpha] — 2026-04-19

First release with a real gameplay-affecting optimization. Previous
`0.1.0-alpha` was instrumentation only.

### Added

- **Cramming Rust port (the win).** `LivingEntity.tickCramming` on mobs
  is now handled by a Rust spatial-hash push accumulator.
  - `cramming.rs` — 2-block 2D spatial hash, pair iteration with
    array-index guard, exact vanilla push formula (Chebyshev distance,
    `f >= 0.01`, `1/sqrt(f)` magnitude clamped to 1, × 0.05 scale)
  - `cramming_jni.rs` — zero-copy direct-ByteBuffer handoff, bounds-
    checked, fallback-safe on parse failure
  - `CrammingHandoff` — pre-allocated request/result buffers (80 + 64 KB)
  - `CrammingDispatcher` — per-tick batch via `world.getTime()` guard:
    first `tickCramming` call of a tick triggers the batch, all
    subsequent cancels are no-ops
  - `CrammingCancelMixin` — `@Inject(HEAD, cancellable=true)` on
    `tickCramming()V`; only mobs go through the Rust path
  - **Measured result at 1000+ mobs:** cramming cost 14 ms → 0.03 ms
    (450× reduction). Total entity tick: ~60 ms → ~21 ms (~3×). TPS held
    at 20, zero crashes, zero fallbacks observed.
- **Movement-phase instrumentation.** Seven-bucket breakdown of
  `LivingEntity.tickMovement` internals:
  - `cramming`, `blockCollision`, `navigator`, `move`, `adjustColl`,
    `travel`, `gravity` + computed `other`
  - Cross-monitor live read pattern (reads `movement_self` from
    `MonsterPhaseMonitor` at report time to compute `other` bucket
    without reset-race)
  - New `[movement-internals]` log line, 5-second window
- **`[cramming-dispatch]` diagnostic log** — per-5s window: batches
  executed, total mobs processed, pushed count.
- **Physics (Entity.adjustMovementForCollisions) port — shelved but
  documented.** Full end-to-end JNI pipeline, AABB sweep engine, and
  chunk-section bucketing snapshot model were built and verified
  correct (18K successful dispatches, 0 fallback, end-to-end math
  matches vanilla). Benchmark showed snapshot materialization cost
  dominates sweep savings at realistic mob counts: ~0.8 ms/bucket ×
  100 buckets = 80 ms/tick overhead vs ~8 ms/tick of sweep wins.
  Framework kept disabled (`PhysicsDispatcher.ENABLED=false`) for
  future invalidation-cache redesign. Full architectural findings in
  the commit log under `c991ac8`, `5dbf40c`, `18ce009`.
- **Full end-to-end `@Redirect` + `@Invoker` Mixin pattern.** New
  `EntityAdjustInvoker` accessor interface provides a bypass-the-redirect
  path for vanilla fallback — reusable for future per-method Rust ports.

### Changed

- `CrammingMixin` (timing instrumentation) now gates itself off when
  `CrammingDispatcher.ENABLED` is true, preventing ThreadLocal timer
  imbalance when the cancel-mixin skips the method body.
- `ferrite.mixins.json` — registered 8 new mixins across the physics +
  cramming ports.

### Known limitations

- **Cramming damage deferred.** 1.21.11's `GameRules.getInt` was
  refactored out; the `maxEntityCramming` game-rule damage is not
  applied while the Rust path is active. Push is applied normally.
- **Physics port shelved**, as noted above. The instrumentation buckets
  it produced are still shipped.

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
