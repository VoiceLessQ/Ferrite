# Changelog

All notable changes to Ferrite are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions
follow [Semantic Versioning](https://semver.org/); the `-alpha` suffix
marks pre-release research builds.

## [Unreleased]

### Added

- **Alternate Current wire algorithm** — adapted from
  [Space Walker's Alternate Current](https://github.com/SpaceWalkerRS/alternate-current)
  (MIT, attributed in [LICENSES.md](LICENSES.md)). Installed
  transparently as a `DefaultRedstoneController` subclass via a
  `@Redirect(NEW)` mixin on `RedstoneWireBlock`'s controller field;
  existing worlds with vanilla redstone dust pick up the new algorithm
  with no migration or world-creation toggle.
- `/ferrite redstone ac on | off | status` — runtime toggle for the AC
  path. Default OFF. Op-level 2.
- `[redstone-oracle]` shadow-compute correctness checker — validates
  every sampled wire against vanilla's `calculateWirePowerAt` and logs
  per-window node mismatches. Active whenever AC or the Rust BFS is on.
- `[redstone]` phase monitor — wire cascade counts (split by
  gate-driven vs direct, default vs experimental controller), gate
  scheduled-tick durations, server-ticks per 5s window.
- `[redstone-rust]` dispatcher liveness counter — confirms whether the
  Rust BFS path is actually firing when enabled.

### Performance

Measured on the reference redstone lag machine, default controller
(no experimental toggle), 5s windows:

| Configuration                 | Cascades / tick | Effective TPS |
| ----------------------------- | --------------: | ------------: |
| Vanilla default               |        ~255,000 |          ~0.4 |
| Mojang experimental redstone  |         ~35,000 |         ~2–5  |
| **Ferrite AC port**           |      **~7,760** |       **6+**  |

33× cascade reduction vs vanilla default. Beats experimental
redstone (~4.5× fewer cascades) without requiring the experimental
world-creation toggle.

Correctness: 327,000+ sampled oracle node checks during the AC run,
zero mismatches. Phase monitor confirms `default=0` — vanilla's
controller is fully bypassed when AC is enabled.

### Investigated

- **Per-cascade Rust BFS for redstone** — correct output
  (327K checks, 0 mismatches) but ~10× slower than vanilla at per-call
  granularity; JNI round-trip overhead exceeds the per-cascade compute
  saving. Shipped disabled. Infrastructure retained
  ([rust/mod/src/redstone.rs](rust/mod/src/redstone.rs),
  `RedstoneRustDispatcher`) in case a within-cascade batched approach
  becomes worth attempting. See
  [docs/REDSTONE_PORT_PLAN.md](docs/REDSTONE_PORT_PLAN.md) for the
  full analysis.
- **Predictive chunk pre-loading** — movement-vector ticket
  submission. 150–300 ms of headroom on dedicated servers, but
  vanilla's own scheduler already reaches `FULL` status in 3–6 ticks
  regardless of how early tickets arrive, so no meaningful TPS impact
  was measurable. Ships enabled for ongoing measurement across user
  configurations; disable via `PreChunkDispatcher.ENABLED = false`.

---

## [0.1.2-alpha] — 2026-04-19

Cross-platform native support. No gameplay changes; Linux and macOS
users get the cramming speedup Windows users already had.

### Added

- Linux native: `librust_mod.so` at `/assets/ferrite/natives/linux/`.
- macOS universal native (aarch64 + x86_64 via `lipo -create`) at
  `/assets/ferrite/natives/macos/`.
- Host-aware Gradle `buildRustLib` — picks the correct target triple
  per host OS.
- Four-job CI pipeline: three parallel per-platform native builds and
  one assembly job.

### Changed

- `RustBridge.loadNativeLibrary` selects the per-OS resource path at
  runtime. Unsupported platforms log clearly and fall back to pure Java.
- `.cargo/config.toml` — removed the Windows-only `[build] target`
  default; retained the GNU linker spec for the GNU target.
- `SETUP_MINGW.md` now covers all three supported platforms.

### Known limitations

- Linux x86_64 only; no aarch64 Linux build yet.
- Cramming damage (max-entity-cramming rule) still deferred from 0.1.1.

---

## [0.2.0-alpha] — 2026-04-19

First gameplay-affecting release.

### Added

- **Cramming Rust port.** `LivingEntity.tickCramming` is batched once
  per server tick and evaluated in a Rust spatial-hash push
  accumulator. Vanilla's push formula is preserved exactly (Chebyshev
  distance, 0.05 scale).
  - Measured at 1000+ mobs: `tickCramming` cost 14 ms → 0.03 ms;
    total entity tick 60 ms → 21 ms; TPS holds at 20.
- `[movement-internals]` log — seven-bucket breakdown of
  `LivingEntity.tickMovement` (`cramming`, `blockCollision`,
  `navigator`, `move`, `adjustColl`, `travel`, `gravity`, computed
  `other`).
- `[cramming-dispatch]` log — per-window batch count, total mobs
  processed, pushed count.
- `EntityAdjustInvoker` — `@Invoker` accessor interface providing a
  bypass-the-redirect path for vanilla fallback. Reusable pattern for
  future per-method Rust ports.

### Changed

- `CrammingMixin`'s timing hooks disable themselves when
  `CrammingDispatcher.ENABLED` is true, preventing ThreadLocal timer
  imbalance with the cancel-mixin.

### Investigated

- **Entity collision-adjust Rust port** — full JNI pipeline, AABB
  sweep engine, and chunk-section snapshot model implemented and
  correctness-verified (18K dispatches, zero fallback). Benchmark
  showed snapshot cost dominates sweep savings at realistic mob
  counts (~80 ms/tick overhead vs ~8 ms/tick win). Shipped disabled
  (`PhysicsDispatcher.ENABLED = false`); retained for a future
  invalidation-cache redesign.

### Known limitations

- `maxEntityCramming` game-rule damage not applied while the Rust
  cramming path is active (1.21.11 API churn).

---

## [0.1.0-alpha] — 2026-04-18

First public alpha. Instrumentation-only research mod. Windows 64-bit.

### Added

- Performance monitors, each logged under the `[ferrite]` prefix on a
  5-second window: `[chunkgen]`, `[client-lag]`, `[entity-render]`,
  `[mspt]`, `[rust-engine]`.
- Rust native library bundled at
  `assets/ferrite/natives/rust_mod.dll`; extracted and loaded at
  runtime. Non-Windows platforms load cleanly with native features
  disabled.
- Automatic Rust build integration — `./gradlew build` invokes
  `cargo build --release` and bundles the resulting DLL.
- Mixin instrumentation on `NoiseChunkGenerator`, `ChunkNoiseSampler`,
  `AquiferSampler$Impl`, and `EntityRenderManager`.

### Build

- Toolchain pinned via `rust-toolchain.toml`
  (`nightly-2025-08-29`, `x86_64-pc-windows-gnu`).
- GNU linker pinned in `.cargo/config.toml`.
- Dev-run heap capped at 3 GB to simulate low-end hardware for
  comparable baselines across sessions.

### Known limitations

- Windows 64-bit only.
- No gameplay changes; instrumentation only.
- No log-verbosity toggle.

### License

- MIT. Changed from CC0-1.0 used in the pre-release research branch.

---

## Pre-release history

Ferrite grew out of the `rust-mod-probe` research project. Notable
pre-release milestones are preserved in the `ferrite` / `main` branch
history. The full architectural investigation is documented in
[docs/PROFILING.md](docs/PROFILING.md).
