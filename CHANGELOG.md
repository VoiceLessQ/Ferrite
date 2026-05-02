# Changelog

All notable changes to Ferrite are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions
follow [Semantic Versioning](https://semver.org/); the `-alpha` suffix
marks pre-release research builds.

## [Unreleased]

### Migration to Minecraft 26.1.2

- **Mojmap port.** Bulk class/method/package translation from yarn 1.21.11 to mojmap 26.1.2 across 166 source files. Architectury Loom + `disableObfuscation=true` consumes a pre-deobfed jar from NeoForge maven; no Loom remap step.
- **JDK 25.** MC 26.1.2 mandates Java 25; build.yml provisions it via `actions/setup-java` (Temurin distribution).
- **Fabric API 0.147.0+26.1.2.** Per-level lifecycle hooks (`ServerWorldEvents` → `ServerLevelEvents`) and `ServerChunkEvents.Load` now fire on the renamed accessors; reflective probes in the worldgen bootstrap accept both mojmap and yarn names so future drift is absorbed.
- **Density function port: 50/50 bit-exact** vs vanilla on 26.1.2 at samples=2000, beating the 1.21.11 baseline of 41/42. New variants `FindTopSurface` (overworld/caves/noodle) and `EndIslandDensityFunction` (end/sloped_cheese) are now interpreted in Rust; `SimplexNoise` (2D) and `LegacyRandomSource` (java.util.Random LCG) added as Rust building blocks. The bigger win surfaced during the port was a single yarn-name-drift bug in `resolveNoiseName` that had silently zeroed every Noise leaf and was hiding 41 working DFs as failures; fixing it lifted parity from 5/50 to 41/50, and the new ports closed the rest.
- **Walker now handles `private record` DF types.** `Class.getMethod` + `invoke` silently fails on private records (auto-generated accessor is public but the declaring class is unexported, so invoke throws IllegalAccessException). Walker now uses `getDeclaredMethod` + `setAccessible(true)`.
- **Autovalidate harness.** `./gradlew runClient -Pferrite.autovalidate=N` now boots the client headlessly via Mojang's `--quickPlaySingleplayer`, runs noise + biome + density parity validators at sample count N, and exits. Roughly 35 seconds end-to-end. Plain `./gradlew runClient` still goes to the title screen.
- **16 diagnostic mixins stubbed.** Default-off in 1.21.11 already; need redesign against renamed 26.1.2 APIs but don't gate any default-on path. Tracked for follow-up.

### Logging

- **`/ferrite log monitors on|off|status`** — runtime gate for the periodic monitor reports (`[entity-tick]`, `[chunkgen]`, `[redstone-phase]`, etc.). About 5 lines/sec across 24 buckets in normal play, plus the disk I/O. Added preemptively to avoid log-volume lag on long sessions and on hardware where I/O is the bottleneck. JVM-arg equivalent: `-Dferrite.log.monitors.off=true`. Counters keep ticking when disabled, so re-enabling picks up cleanly from the next 5s window — no backlog dump. Also applied on the 1.21.11 branch.

### Performance

- **Hopper extract hint (active by default).** Per-source-inventory
  hint tracks the first non-empty slot; extract loops start there
  instead of iterating from slot 0 every fire. On a partially-drained
  chest, the savings scale linearly with how many leading slots are
  empty: **~23 µs/call at avgStartIdx=16 (~60% reduction), ~110 µs/call
  at avgStartIdx=53 (~85%)**. Vanilla cost was measured at
  ~2.4 µs per slot probe; hint cost is ~21 µs/call envelope (allocation
  in `getInputInventory`, mixin dispatch) plus ~3 µs for the one
  successful extract attempt. Per-attempt nsPerAtt baseline came from
  diagnostic probe `[hopper-slot]` (commit `c34b05c`) which surfaced
  `wasted/call=4.16` on a 100-hopper chain draining a double chest.
  Hint maintenance hooks fire on `setStack`, `removeStack(int)`,
  `removeStack(int, int)` for both `LootableContainerBlockEntity`
  (chests, barrels) and `HopperBlockEntity`'s overrides; `DoubleInventory`
  computes its hint by delegating to underlying chest BEs. Validator
  shadow-runs at `-Dferrite.hopper.extract.validate=true` and reported
  **0 stale events across 450+ extracts during chest-drain testing**.
  Boot flag `-Dferrite.hopper.extract.useHint=true` (default).

- **Hopper highway, opt-in (`/ferrite hopper highway on`).** Per-slot
  independent cooldowns + round-robin destination routing. Each
  hopper's 5 slots get individual 8-tick cooldown counters with
  staggered init `{0, 1, 2, 3, 4}`; round-robin enforces at most one
  fire per server tick per hopper, so per-tick item count stays
  **≤ 1** (preserved comparator transition rate). Aggregate per-hopper
  throughput climbs from vanilla 1/(8 ticks) to up to 5/(8 ticks).
  Measured **3.1× chain throughput** under back-pressure on the
  100-hopper test chain. Validator probe `[hopper-perslot]` reports
  zero `tickViolations` and zero `staggerCollapses` across 20K+
  fires; per-slot interval probe shows `min=8, max=8, avg=8.00` once
  steady state reaches (each slot at exactly vanilla 8-tick pace).
  Phase 3 round-robin destination puts incoming items into
  `(lastInsertSlot + 1) % 5` instead of always slot 0, so chain
  hoppers visibly distribute items across lanes 0-4 in the UI.
  Default off - turn on for hopper-heavy storage systems, leave off
  for sorters tuned to vanilla 8-tick clocks. Per-tick decrement and
  NBT write are gated behind `ENABLE`, so default-off users pay
  zero per-tick overhead and zero NBT bloat.

### Added

- **`/ferrite hopper highway on|off|status`** runtime command toggles
  the entire hopper layer (extract hint + per-slot fire + lane
  routing) for the current server session. Status command reports
  flag state and validator state.

- **Probe `HopperSlotMonitor`** - extract/insert slot-attempt
  distribution (succ@0, succ>0, fail) plus wall-time per call and
  per-attempt. Active when ferrite is loaded; produces `[hopper-slot]`
  log lines every 5s during hopper activity. Used to characterize
  per-call cost before the hint port; remains as a regression detector
  if anyone disables `useHint`.

- **Probe `HopperHintMonitor`** - hint hits/wraps/fails plus opt-in
  validator at `-Dferrite.hopper.extract.validate=true`. Validator
  checks the invariant "slots [0..hint-1] are empty" on every extract;
  any stale hint logs to warn and increments a counter, capped at 20
  warns per session.

- **Probe `HopperPerSlotMonitor`** - per-slot fire counts, items
  moved, lane hits/fallbacks, plus an opt-in invariant validator at
  `-Dferrite.hopper.perslot.validate=true` that catches per-tick
  count > 1 (comparator-safety violation) and stagger collapse
  (slot cooldowns synchronizing back to whole-hopper pacing).

### Notes

- The "highway" design rejected one earlier scope: removing the
  return-true break to fire multiple items per call. That violates
  vanilla's 1-item-per-fire contract, which `ScreenHandler.calculateComparatorOutput`
  ([1.21.11/common/net/minecraft/screen/ScreenHandler.java#L1062-L1077](1.21.11/common/net/minecraft/screen/ScreenHandler.java))
  + the chain-feed cooldown trick at
  [HopperBlockEntity.java#L323-L331](1.21.11/common/net/minecraft/block/entity/HopperBlockEntity.java)
  both depend on. The shipped highway preserves the contract: 1
  item per fire, vanilla 8-tick cooldown per slot, multiple slots
  in parallel. See [docs/HOPPER_HIGHWAY.md](docs/HOPPER_HIGHWAY.md).

## [0.5.1-alpha] — 2026-04-29

### Performance

- **Vanilla surface phase ~3 ms faster — applies to every user, no toggle.**
  Replaced the per-call `getClass().getMethod(...).invoke(...)` reflection
  in `SurfaceValidatorMixin`'s `captureContext` redirect (firing ~30K
  times per chunk during SURFACE phase, on every chunk regardless of
  Ferrite settings) with `@Invoker` mixins on
  `MaterialRules.MaterialRuleContext.initVerticalContext` and
  `BlockStateRule.tryApply` (commit `4ed0d89`). Vanilla's per-chunk
  surface baseline drops from ~9.3 ms to ~6.4 ms across the same
  measurement methodology — universal win, ships invisibly.
  Subsequent `@Accessor` cleanup on hot fields + three more
  `@Invoker`s for protected methods on the same class (commit
  `2beaa5b`) shipped clean code with parity-clean output but no
  additional measurable perf movement (HotSpot was already
  specializing the MethodHandle callsites under JIT).
  See `docs/PIANO_STATUS.md` "JFR frame-count overstates recoverable
  cost" for the three-strike-rule context.

- **Surface dispatcher (when enabled): -2.2 ms per chunk via batched
  heightmap updates.** Replaced per-write `ProtoChunk.setBlockState`
  in `SurfaceDispatcher.flushChunk` (which fires
  `Heightmap.trackUpdate` per write × 2 heightmap types = ~32K calls
  per chunk during SURFACE phase) with a section-grouped raw
  `ChunkSection.setBlockState` loop plus a per-column `trackUpdate`
  post-pass (~512 calls per chunk) — commit `a26e2ee`. Validated
  bit-identical to vanilla per-write `trackUpdate` across **23,204
  chunks combined (21,012 in Step 1 + 2,192 in Step 2 verification),
  100% match, 0 cell mismatches** for both `WORLD_SURFACE_WG` and
  `OCEAN_FLOOR_WG`. Clean post-ship measurement: dispatcher ON
  drops from ~15.6 ms to **~13.4 ms** (-2.2 ms recovered, within the
  source-audit projection of 2-3 ms). Gap to vanilla baseline closed
  from ~9.2 ms to **~7.0 ms** structural floor — palette writes +
  vanilla's `isDefaultBlock` column scanner + biome supplier chain +
  ferrite dispatch ceremony. Surface dispatcher remains default-OFF
  pending architectural work that bypasses the structural floor; the
  lesson from this session was that **counted-O(N) projections from
  source audit are reliable** (this win held on the first try),
  while JFR-frame-count projections are not (three consecutive misses
  documented in `docs/PIANO_STATUS.md`).

- **Noise-sync chunkgen phase: ~8-10 ms/chunk faster** for everyone.
  JFR profiler session (2026-04-28) identified two diagnostic mixins
  firing during the noise-fill phase that contributed combined
  ~8-10 ms/chunk of pure observation overhead in normal play:
  - `CacheRouteCaptureMixin` was running a reflective DF tree walk
    (`DensityFunctionWalker.fingerprint`) per Marker wrap during
    `ChunkNoiseSampler` init — diagnostic for the Phase 2.5 step 2a/2b
    bulk-density experiments which are themselves default-off.
  - `AquiferMonitor` was wrapping every `AquiferSampler.apply` call
    (~98K per chunk) with `@Inject HEAD/RETURN` — pure timing overhead.

  Both gated behind default-off flags (`CacheRouteStats.ENABLED`,
  `AquiferMonitor.ENABLED`). Re-enable only when actively debugging
  density or aquifer work. Cumulative win across exploration,
  pre-gen runs, and new-area loads — every chunk generated.

### Changed

- **Surface dispatcher hot path: biome supplier cache + reused
  `BlockPos.Mutable` + `Identifier.toString` intern.** Dispatcher-
  internal optimisation. Eliminates the duplicated supplier-chain
  resolution and ~30K `BlockPos` allocations per chunk that fired
  per-Y position. Parity validated: 99.9% match vs vanilla,
  java=rust=100%, divergences=0. Measured savings on the dispatcher
  path: ~0.7 ms/chunk (17.7 → 17.0 ms median). Smaller than projected
  because HotSpot had already inlined most of the supplier chain.
  Surface dispatcher remains default-OFF — see `docs/PIANO_STATUS.md`
  "diagnostic gating" section for the full finding and why a
  surface-specific profiler pass is needed before flipping default-on.

### Added

- **`[ferrite] SIMD: avx512f={} avx2={} sse4.2={}` startup log line.**
  One-shot probe at engine init reports the host CPU's SIMD
  capabilities. Decides whether a future SIMD-Perlin port targets
  f64x4 (AVX2) or f64x8 (AVX-512). Diagnostic only — no behaviour
  change today.

- **`PhysicsOracle` parity validator.** Mirrors the aquifer/redstone
  oracle pattern. When `PARITY_MODE=true` (default false), every
  `PhysicsDispatcher.adjust` call shadow-runs the Rust path against
  vanilla and logs `[physics-parity]` mismatches per 5-second window.
  Validated: 100.0000% match across 700K+ dispatches at 1000-mob
  scale; physics dispatcher itself stays default-OFF (would regress
  perf vs vanilla's JIT-inlined path under load).

### Internal

- **Java package layout reorganised into 7 subpackages** (`bridge/`,
  `command/`, `entity/`, `monitor/`, `worldgen/`, `worldgen/chunk/`,
  plus the existing `surface/`, `redstone/`, `mixin/`). Only
  `RustBridge.java` remains at the root package — JNI symbol
  stability (the 30 `Java_me_apika_apikaprobe_RustBridge_*` exports
  in `rust/mod/src/` would all need renaming if it moved). Six
  refactor commits, every commit verified by `compileJava` clean +
  `runClient` confirming `[ferrite] Loaded rust_mod` on boot.
  No behaviour change.

### Added

- **`/ferrite surface heightmap-parity on|off|stats|reset`** —
  regression check for the batched heightmap update path (commit
  `e4e7a41`). When ON, every flushChunk snapshots
  `WORLD_SURFACE_WG` + `OCEAN_FLOOR_WG` pre-flush, runs the
  production batched path, then replays vanilla's per-write
  `trackUpdate` from the snapshot as a reference and diffs cell-by-
  cell. Logs `[surface-heightmap-parity]` lines with running match %
  and mismatch counts. ~1 ms/chunk overhead when on; default OFF.
  Useful if you've changed surface rules and want to confirm the
  predicate-preserving assumption (writes never flip `NOT_AIR` or
  `SUFFOCATES` for the highest Y in a column) still holds.

- **Surface rule dispatcher** (`/ferrite surface dispatch on|off|status`).
  Batched JNI architecture: vanilla's `tryApply` calls are deferred,
  packed into one batch per chunk, evaluated by the Rust bytecode
  evaluator, then written back. Default OFF. Requires
  `/ferrite surface validate` first (uses the installed compiled tree).

  Correctness is solid (vanilla-equivalent terrain at 99.8% match
  vs vanilla per the validator). Performance is the open work item:
  currently ~2.5× chunkgen cost (~25 ms vs vanilla ~10 ms). Default
  flips ON when the seed-driven Track B architecture closes the gap.

- **Xoroshiro128++ Rust port** (`rust/mod/src/xoroshiro.rs`).
  Bit-exact port of vanilla's `Xoroshiro128PlusPlus`,
  `XoroshiroRandomSource`, and `XoroshiroPositionalRandomFactory`.
  11/11 unit tests pass including a hand-traced first-call value
  that matches the Java algorithm exactly. Now drives Rust's
  `OP_VERT_GRADIENT` per-block PRNG (closed the previous Java=Rust
  97.5% gap to **100%**). Foundation for Track B (seed-driven Rust
  dispatcher).

### Changed

- **Surface validator parity: 95.3% → 99.8%** vs vanilla. Four
  reflection / evaluator fixes against the unobfuscated 1.21.11
  source: `getMinSurfaceLevel` → `estimateSurfaceHeight` (yarn
  rename), per-block PRNG for `OP_VERT_GRADIENT` (was midpoint
  placeholder), record-component accessor for vanilla's record-
  typed condition nodes (`Method.invoke` on declared component
  rather than direct field reflection), and live noise sampling
  via cached `DoublePerlinNoiseSampler` references (was zero-vector
  placeholder).

- **Bytecode operand for `OP_VERT_GRADIENT`** grew from 9 → 11 bytes
  (added `u16 randomNameIdx` for per-block PRNG factory lookup).
  `CompiledRuleTree` gained `String[] randomNameTable` to map index
  → registry-name string; resolved at install via reflection on
  vanilla's cached `RandomSplitter` instances.

### Performance

Surface dispatcher A/B (overworld walk-to-load, 4-core CPU
affinity, same methodology as the cramming and redstone
benchmarks). All values are warm averages.

| Architecture | Surface ms/chunk | Δ vs vanilla |
|---|---:|---:|
| Vanilla baseline | ~10-11 ms | — |
| Simple per-call dispatch | ~150-170 ms | 15× regression (captured experiment) |
| Batched JNI | ~70-80 ms | 8× regression |
| + Per-column cache (Opt B) | ~32-37 ms | 3.5× regression |
| + MethodHandle + direct typed Java (Opt A) | ~24-27 ms | **2.5× regression** |

Each iteration documented in `docs/SURFACE_RULE_STATUS.md` under
"Dispatcher swap arc". The remaining regression is dispatch-pipeline
overhead (per-position record allocation, ByteBuffer packing, JNI
hop); the structural fix is Track B.

### Track B (next session)

Seed-driven Rust dispatcher: at world load, push the seed once;
Rust holds its own `NoiseConfig` + `RandomSplitter` stack derived
from that seed. Per chunk Java sends only `(chunk_pos,
position_array)`; Rust computes biome / runDepth / noise / random
from its initialized state and runs the bytecode in one batch.
Foundation (Xoroshiro) is in place. Remaining ports:
`DoublePerlinNoiseSampler`, `NoiseConfig.getOrCreateNoise`,
`MultiNoiseBiomeSource`. Multi-session work.

---

## [0.5.0-alpha] — 2026-04-23

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

### Fixed

- Cramming damage was failing to fire when mobs spawned at identical
  coordinates (e.g. `/summon zombie ~ ~ ~ ×30`). The push-distance
  early-return in Rust (`f < 0.01`) was running before the
  `crowded_count` increment, so same-position piles registered zero
  overlapping neighbors. Vanilla counts via `getPushableEntities`
  (pure AABB overlap) separately from the push math; mirrored that
  ordering. Regression test added for the exact symptom.

### Verified

**Correctness (small pile, default `maxEntityCramming = 24`):**

- 24 zombies stacked at one block: no damage. ✓ (matches vanilla)
- 25th zombie added: cramming damage fires, mobs die. ✓
- `[cramming-dispatch]` log shows `damaged=N` climbing as the pile
  reaches the threshold and mobs cycle through the 1-in-4 random.

**Perf (~1246-mob pile, 4-core CPU affinity, `/gamerule maxEntityCramming 0`
so mob count stays stable across the A/B; same world, same pile, only
the toggle changed). Two independent runs:**

| State                | Run 1 mspt | Run 1 TPS | Run 2 mspt | Run 2 TPS |
| -------------------- | ---------: | --------: | ---------: | --------: |
| Ferrite ON           | ~48 ms     | **20.00** | ~41 ms     | **20.00** |
| Ferrite OFF (vanilla)| ~75 ms     | **13.3**  | ~58 ms     | **17.0**  |
| Ferrite ON (recover) | ~48 ms     | **20.00** | ~41 ms     | **20.00** |

Direction is identical both runs — vanilla blows past the 50 ms tick
budget and TPS drops; Ferrite stays under it and TPS holds at 20.
Magnitude varies (30–50% mspt reduction) with JIT warmth and system
load.

**Isolated cramming-math sub-budget** (from `[movement-internals] cramming`,
same run): ~0.06 ms with Ferrite vs ~18.81 ms vanilla on the same pile —
roughly **310× reduction in the cramming math itself**, isolated from
the rest of the entity tick. This is the sharpest version of the claim
because it strips out unrelated entity costs.

**Self-serve verification path for users:**

```
1. Spawn ~1000 zombies (e.g. /summon zombie ~ ~1 ~ from a repeating
   command block with NoAI:1b,PersistenceRequired:1b)
2. /gamerule maxEntityCramming 0   (so mobs don't die during the test)
3. /ferrite cramming on            (default, perf-optimized)
4. Observe TPS / [mspt] log line
5. /ferrite cramming off           (falls back to vanilla)
6. Observe TPS drop / [mspt] climb
7. /ferrite cramming on            (instant recovery)
```

Anyone can reproduce. The toggle is the falsifier.

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
