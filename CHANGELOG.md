# Changelog

All notable changes to Ferrite are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions
follow [Semantic Versioning](https://semver.org/); the `-alpha` suffix
marks pre-release research builds.

## [Unreleased]

### Added

- `/ferrite cramming on | off | status` — runtime toggle for the
  batched cramming dispatcher. Lets users A/B Ferrite vs vanilla in
  their own world without restart. Default ON (matches prior
  behavior).

### Changed

- **Cramming is now full vanilla 1:1 parity.** Fixed the two
  outstanding gaps that previously made Ferrite cramming a
  "with-caveat" feature:
  - **Cramming damage is now applied.** Rust returns a per-entity
    `crowdedCount` (overlapping pushable non-passenger pairs);
    Java applies 6.0 cramming damage when
    `crowdedCount > maxEntityCramming - 1` and the per-entity
    `Random.nextInt(4) == 0` fires — bit-for-bit matching vanilla
    `LivingEntity.pushEntities`. Closes the v2 deferral.
  - **`isPassengerOfSameVehicle` skip implemented.** Two mobs sharing
    a root vehicle no longer push each other. Mirrors vanilla
    `Entity.push` line 1822. Schema-friendly (4-byte slot in input
    buffer was unused; no buffer resize).
- Mob → non-mob pushing (items, boats) remains the only documented
  vanilla gap. Edge-case in practice; deferred for a follow-up.

---

## [0.4.0-alpha] — 2026-04-22

### Changed

- **Per-cascade Rust BFS now enabled by default** for the AC wire
  algorithm (`FerriteWireConfig.RUST_BFS = true`,
  `RUST_BFS_MIN_NODES = 1`). On heavy redstone workloads, AC's per-
  cascade power propagation now runs in a Rust kernel via one batched
  JNI call per cascade. Java emits the resulting block/shape updates
  unchanged, so user-visible behavior is identical to AC alone.
  Disable with `/ferrite redstone bfs off` if you observe issues on a
  specific contraption.

### Performance

Lag-machine measurement (Ryzen 9 5900X, 4-core CPU affinity, same
methodology as the 0.3.0 redstone numbers). Per-bucket avg cascade
time, three windows (Java baseline → Rust forced → Java cross-check):

| Cascade size | Java       | Rust       | Speedup |
| -----------: | ---------: | ---------: | ------: |
| 1–4 nodes    | 0.009 ms   | 0.007 ms   | 1.29×   |
| 5–8 nodes    | 0.023 ms   | 0.015 ms   | 1.53×   |
| 9–16 nodes   | 0.052 ms   | 0.034 ms   | 1.53×   |
| 17–32 nodes  | 0.052 ms   | 0.025 ms   | 2.08×   |

Aggregate: avg cascade time drops from 0.020 ms to 0.014 ms, **and**
cascade throughput rises from ~240K to ~340K per 5 s window — the
server tick has more headroom, so more cascades fit per second.

Oracle reports 0 mismatches across the entire experiment; output is
bit-for-bit identical to AC-Java and to vanilla on the validated
windows.

### Caveats

- **Workload-shape dependent.** Measured on a sustained high-volume
  lag machine. A 64-wire repeater clock measurement showed Rust
  ~20µs per-cascade slower (0.026 ms → 0.046 ms) on cold/bursty small
  cascades — likely JIT warmup-bound on the Rust glue path. Absolute
  cost is imperceptible (<0.05 ms / tick) but if you have a
  contraption that regresses, `/ferrite redstone bfs off` reverts to
  AC-Java without restart.
- **Manual override.** `/ferrite redstone bfs-min <n>` raises the
  per-cascade size threshold above which Rust takes over. Set high
  to gate the Rust path out for small cascades while keeping AC's
  Java algorithm.

See [docs/REDSTONE_PORT_PLAN.md](docs/REDSTONE_PORT_PLAN.md) Phase 2c
for the full per-bucket data and methodology.

---

## [0.3.0-alpha] — 2026-04-20

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

Measured on the reference redstone lag machine (default controller,
no experimental toggle), Ryzen 9 5900X limited to 4 active cores via
CPU affinity — the same constrained-hardware baseline used for all
prior Ferrite benchmarks. Single A/B run, 5s windows:

| Metric                | Vanilla default      | AC (Ferrite)          | Change                    |
| --------------------- | -------------------: | --------------------: | ------------------------- |
| Cascades / tick       | ~127,000             | ~8,250                | ~15× fewer                |
| Gate throughput / tick| ~663                 | ~2,780                | ~4× more                  |
| Wire cost / gate tick | ~0.378 ms            | ~0.062 ms             | ~84% less                 |
| Effective TPS         | ~4                   | ~5.6                  | **+40%**                  |
| Oracle mismatches     | —                    | 0 / 149,669 checked   | bit-for-bit correct       |
| Vanilla controller    | active               | `default=0`           | fully bypassed            |

Two independent effects combine in the user-visible result:

1. **Gate throughput per server tick rises ~4×** — each wire cascade
   now collapses into a single network settle (~84% less wire time per
   gate tick), so the same per-tick budget processes more gate ticks.
   Contraptions animate faster at equivalent server load.

2. **Server TPS rises ~40% on CPU-bound hardware** — when the server
   is actually saturated (as it is on a 4-core baseline running a
   redstone lag machine), wire-cost savings convert directly into more
   completed ticks per second. A run on unconstrained hardware showed
   the TPS delta vanishing (~5 → ~5) because there was headroom; the
   gate-throughput win persisted.

`default=0` in every AC window confirms vanilla's `DefaultRedstoneController`
is completely bypassed; the @Redirect installation mixin is doing its
job.

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
