# Changelog

All notable changes to Ferrite are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions
follow [Semantic Versioning](https://semver.org/); the `-alpha` suffix
marks pre-release research builds.

## [Unreleased]

### Direction

0.6.0-alpha closes the feature-add stretch that started at 0.4.0-alpha. The next stretch deepens existing code rather than broadening surface area. Concrete shapes that may shift:

- The default-off Rust ports that lost their first measurement pass (`BulkChunkDensityFill`, physics dispatcher) need a different structural approach before they go default-on, not more tuning of the current shape.
- The aquifer port at 99.895% parity has visible surface-grid artifacts that the existing grid abstraction cannot resolve; the rewrite path is per-column, not stride-tuned.
- The surface dispatcher's 7 ms gap above vanilla is structural, not algorithmic; closing it requires bypassing a chunk API rather than optimizing inside one.
- Pre-gen gains a heap-pressure-aware throttle (semaphore size adapts to observed mspt) so the daemon thread does not outrun the chunk-load pipeline on memory-constrained hosts.

User-facing toggles and parity validators do not change. Where an internal rewrite shifts behavior under a toggle, the toggle name stays the same and the CHANGELOG entry calls out exactly what changed.

## [0.6.3-alpha] — 2026-05-03

### Added

- AC offer-based Rust kernel (`/ferrite redstone ac-rust on`).
  Mirrors AC's `powerNetwork()` loop in Rust: offer-based propagation
  with flow-direction tracking, priority-queue ordered output.
  Parity-clean (0 oracle mismatches sustained across Phase 3
  validation). **~16% aggregate wire-cost reduction** vs the
  existing relaxation kernel on heavy workloads.

  Per-bucket vs relaxation kernel (lag-machine measurement):
  ```
  1-4 wires:  tied (JNI dispatch dominates at this size)
  5-8 wires:  1.20x faster
  9-16 wires: 2.09x faster
  ```

  Default OFF, requires both AC and AC-Rust enabled:
  ```
  /ferrite redstone ac on
  /ferrite redstone ac-rust on
  ```

  Will flip default-on in a future release after a full alpha cycle
  of clean user reports. Oracle validation opt-in via
  `-Dferrite.redstone.ac.validate=true`.

### Notes

- Existing relaxation kernel (`RUST_BFS`) stays in tree as fallback.
  Both kernels coexist; AC-Rust activates first when enabled, BFS
  takes over if the AC path bails (overflow / native unavailable).
- Phase 3 depower fix: `runRustAcBatch()` now calls
  `findPower(wire, true)` after `findExternalPower()` to pull
  outside-cascade wire contributions before serializing. Fixes
  boundary wire power=0 mismatches the oracle surfaced during
  initial validation (~5% mismatch rate before fix, 0 after).

## [0.6.2-alpha] — 2026-05-03

Completes the block-entity ticker hygiene story started in 0.6.1.
0.6.1 fixed signs; 0.6.2 fixes furnaces and unifies the gate
infrastructure so future ticker suppressions are additive instead
of conflicting.

### Performance

- **Idle furnaces, blast furnaces, and smokers no longer tick** when
  empty and not burning. Vanilla registers a `BlockEntityTicker` for
  every furnace at chunk load; the body does nothing useful when
  `litTimeRemaining == 0 && cookingTimer == 0` and both fuel and
  input slots are empty. Suppression gates `LevelChunk.updateBlockEntityTicker`
  via `@Redirect` on `BlockState.getTicker`, returning
  null for the three vanilla types (strict-class check) when all four
  conditions hold. Re-registers via `@Inject RETURN` on
  `setItem(int, ItemStack)`. Measured at 500 idle furnaces:
  **zero measurable BE-tick increase** vs the empty-area baseline
  (0.04-0.05 ms / tick pre and post, within noise floor). Default-on.
  Mod subclasses untouched.

### Changed

- **Sign and furnace ticker gates collapsed into one composite mixin**
  (`WorldChunkBlockEntityTickerGateMixin`). Two separate `@Redirect`
  mixins on the same INVOKE site conflict at load time, Mixin keeps
  the first-loaded one and silently skips the second. Caught at boot
  via the `[Mixin/WARN]` line on the first dev build. The composite
  gate has both sign and furnace cases as independent branches;
  future BE-type suppressions add as additional branches.

### Notes

- Audited the rest of vanilla's common block entities (chest, barrel,
  bed, decorated pot, lectern, jukebox, comparator, piston). Mojang
  already applied the dynamic-ticker pattern correctly to all of them.
  Signs and furnaces were the two outliers. Both now closed. The
  cheap obvious targets are exhausted; further tick-cost reductions
  will be measurement-driven from real server logs rather than source
  scans of vanilla.

- Brewing stand `getSlotsEmpty()` allocates `boolean[3]` per tick.
  Realistic-scale impact (30 stands → 14 KB/sec GC pressure) is below
  TPS-relevant. Fix shape (three coordinated `@Redirect` on a 40-line
  method) is brittle and outweighs the savings. Deferred to
  `docs/FUTURE_PLANS.md`. Revisit if a real server surfaces brewing
  stand tick cost as measurable.

## [0.6.1-alpha] — 2026-05-03

Consolidation release. Builds on 0.6.0's hopper highway and pre-gen
with audit-driven correctness, perf cleanups, and a sign-tick fix.

### Performance

- **Sign block entities no longer tick when no player is actively
  editing them.** Vanilla registers a `BlockEntityTicker` for every
  sign at chunk load. The body is one null check on the
  playerWhoMayEdit field (useful only while a player has the edit
  screen open), but the per-tick infrastructure (range check, ticker
  map walk, lambda dispatch, profiler push/pop) costs ~120 ns per
  sign per tick regardless. Fix gates `LevelChunk.updateBlockEntityTicker`
  via `@Redirect` on `BlockState.getTicker`, returning null for vanilla
  `SignBlockEntity` and `HangingSignBlockEntity` (strict-class check)
  with `getPlayerWhoMayEdit() == null`. Vanilla's existing
  `if (ticker == null) removeBlockEntityTicker(...)` branch handles
  deregistration. `SignBlockEntity.setAllowedPlayerEditor(UUID)`
  re-evaluates the gate via the chunk invoker the moment the editor
  field flips. Measured on 961 placed signs:
  **0.20 ms / tick → 0.06 ms / tick (~70% reduction)**. Default-on.
  Mod subclasses untouched. Self-heals from persisted-editor state
  within 2 ticks of chunk load.

- **Cramming Rust kernel: thread-local FxHashMap reuse + small-N
  brute-force fast path.** Spatial-hash structure now persists across
  ticks instead of allocating per call (`fxhash` crate added). Below
  16 mobs, a brute-force O(N²) sweep beats hash setup. Both default-
  on. No behavior change.

- **Cramming monitor inject gated on dispatcher state.** When the
  Rust dispatcher is enabled, the timing mixin's HEAD/RETURN injects
  early-return instead of running monitor work that would never be
  observed (the vanilla body is cancelled). Eliminates ~6,000
  `CallbackInfo` allocations per tick at 2,000 mobs.

- **Redstone Rust kernel: thread-local `Vec<u8>` buffer reuse.**
  Eliminates per-cascade allocation in the relaxation loop on the
  AC `runRustBatch` path. Same pattern as the cramming kernel fix.

### Correctness

- **Cramming: standalone vehicles no longer push their own
  passengers.** The Java side now sets `rootVehicleId = e.getId()`
  for entities with no vehicle (mirroring vanilla's
  `getRootVehicle() == self`), so equality alone covers both
  same-vehicle pairs and vehicle⇄passenger pairs. Pre-fix, the
  `-1` sentinel for standalone vehicles never matched the
  passenger's `vehicle.getId()`, leaving the pair processed and
  the passenger pushed by its own mount. Validated by a new
  `vehicle_does_not_push_its_own_passenger` Rust unit test.

- **Redstone: once-per-world warning when AC is enabled on an
  experimental-redstone world.** Vanilla's `RedstoneWireBlock.update`
  routes to `new ExperimentalRedstoneController(...).update(...)`
  before reaching `this.redstoneController.update(...)` when the
  world has the `redstone_experiments` feature flag set, so AC's
  installed controller never sees the cascade. The warning surfaces
  the silent-bypass case so users know `/ferrite redstone ac on`
  has no effect on that world. Deduped per `RegistryKey<World>`.

### Removed

- **`RedstoneRustDispatcher` and its `RedstoneRustMixin` deleted
  (~500 LOC).** Superseded by `WireHandler.runRustBatch` in 0.4.0;
  default-off `USE_RUST` toggle since. The
  `/ferrite redstone rust on|off|status` subcommand and
  `RedstoneHandoff.USE_RUST` field also removed. Buffer infrastructure
  shared with the live `runRustBatch` path stays in
  `RedstoneHandoff`.

### Fixed

- **README + project memory: AC and Rust BFS shipping defaults
  documented correctly.** AC ships default-off (user opt-in via
  `/ferrite redstone ac on`); Rust BFS is default-on but unreachable
  until AC is enabled. README line 56 was reading as if AC were on
  by default; corrected. Code and the user-facing defaults table
  were always correct, only the prose around them needed alignment.

### Added

- **`[sign-tick]` diagnostic line.** Reports `signs=N/tick`,
  per-call body time, and total body time per 5-second window when
  signs actually tick. Used to validate the suppression fix; kept
  in tree as a regression detector.

### Notes

- Two audit findings turned out to be false alarms on closer reading
  of vanilla source. (1) A `@Redirect` on `RedstoneController.update`
  was claimed to catch both the experimental and default branches,
  opening a hidden footgun; javac's invokevirtual receiver type
  (`ExperimentalRedstoneController`, not the parent) means Mixin's
  descriptor-based match never fires on the experimental branch. The
  redirect already worked correctly. (2) The per-wire
  `findExternalPower` call in `runRustBatch` was claimed redundant
  vs AC-Java's selective call; tracing the lazy-resolution semantics
  showed Rust takes a static snapshot and can't reproduce AC's
  deferred priority-queue resolution, so the per-wire call is
  necessary correctness work. Both would have shipped regressions if
  patched. See [docs/JOURNEY.md](docs/JOURNEY.md) "The May 2026 audit
  pass" for the retrospective.

- AC fidelity audit confirmed Ferrite's Alternate Current port is a
  faithful adaptation of [Space Walker's upstream](https://github.com/SpaceWalkerRS/alternate-current)
  at commit `89609e4` (2026-03-23). No upstream changes to backport;
  full algorithmic parity preserved modulo correct yarn renames.
  Three Ferrite-side improvements (`rustIndex`, `head()` accessor,
  scratch buffer pre-alloc) verified present. Documented in
  [docs/REDSTONE_PORT_PLAN.md](docs/REDSTONE_PORT_PLAN.md) "Fidelity
  audit".

- Redstone `RUST_BFS_MIN_NODES = 1` default investigated and kept.
  Per-bucket measurement on a parallel-repeater farm showed Rust
  losing 1.48× in the 9-16 bucket but winning 1.29× and 1.50× in
  1-4 and 5-8. Aggregate wall-time per second is lower with Rust on
  every cascade than with any threshold tested. Per-cascade losses
  in narrow buckets get drowned out by aggregate wins on the wider
  distribution. Documented in [docs/JOURNEY.md](docs/JOURNEY.md).

## [0.6.0-alpha] — 2026-05-03

### Features

- **World creation pre-generation (default OFF toggle).** New "Pre-generate spawn area" toggle + radius slider (5-50 chunks) on the Create World "More" tab. When enabled, a background-thread driver walks a concentric annulus iterator around spawn after `SERVER_STARTED` and feeds chunks through vanilla's ticket API with a `Semaphore(50)` backpressure cap. A boss bar reports progress to the host. Cancel writes `<world>/ferrite_pregen.dat`; the next world load auto-resumes from the saved iterator state. Graceful completion deletes the snapshot and writes `<world>/ferrite_pregen.done` (also the first-launch gate for the dedicated-server `-Dferrite.pregen.radius=N` property). Test commands `/ferrite pregen <radius>`, `/ferrite pregen at <cx> <cz> <radius>`, `/ferrite pregen cancel`, `/ferrite pregen status`. Validated: 53-104 chunks/sec depending on server load (steady-state around 80/s with no contention; ~50/s when competing with active player chunkforce; ~100/s when chunkforce is on but inactive in the pre-gen area). TPS 20.00 holding under active flight + pre-gen load (max ticks under the 50ms budget). Coexists with `/ferrite chunkforce`: graceful throughput split when both target the same region, no TPS loss, no corruption. Iterator is clean-room (Chunky GPL-3.0 was mined for intent, not code).

### Migration to Minecraft 26.1.2

- **Mojmap port.** Bulk class/method/package translation from yarn 1.21.11 to mojmap 26.1.2 across 166 source files. Architectury Loom + `disableObfuscation=true` consumes a pre-deobfed jar from NeoForge maven; no Loom remap step.
- **JDK 25.** MC 26.1.2 mandates Java 25; build.yml provisions it via `actions/setup-java` (Temurin distribution).
- **Fabric API 0.147.0+26.1.2.** Per-level lifecycle hooks (`ServerWorldEvents` → `ServerLevelEvents`) and `ServerChunkEvents.Load` now fire on the renamed accessors; reflective probes in the worldgen bootstrap accept both mojmap and yarn names so future drift is absorbed.
- **Density function port: 50/50 bit-exact** vs vanilla on 26.1.2 at samples=2000, beating the 1.21.11 baseline of 41/42. New variants `FindTopSurface` (overworld/caves/noodle) and `EndIslandDensityFunction` (end/sloped_cheese) are now interpreted in Rust; `SimplexNoise` (2D) and `LegacyRandomSource` (java.util.Random LCG) added as Rust building blocks. The bigger win surfaced during the port was a single yarn-name-drift bug in `resolveNoiseName` that had silently zeroed every Noise leaf and was hiding 41 working DFs as failures; fixing it lifted parity from 5/50 to 41/50, and the new ports closed the rest.
- **Walker now handles `private record` DF types.** `Class.getMethod` + `invoke` silently fails on private records (auto-generated accessor is public but the declaring class is unexported, so invoke throws IllegalAccessException). Walker now uses `getDeclaredMethod` + `setAccessible(true)`.
- **Autovalidate harness.** `./gradlew runClient -Pferrite.autovalidate=N` now boots the client headlessly via Mojang's `--quickPlaySingleplayer`, runs noise + biome + density parity validators at sample count N, and exits. Roughly 35 seconds end-to-end. Plain `./gradlew runClient` still goes to the title screen.
- **16 diagnostic mixins stubbed.** Default-off in 1.21.11 already; need redesign against renamed 26.1.2 APIs. **One exception:** the `MaterialRuleContext` invoker/accessor pair was the 1.21.11 chunkgen baseline win (~3 ms/chunk default-on) and that surface does not exist on 26.1.2 — the renamed `Context` class doesn't expose the same hot fields/methods. The pair is gated out of `ferrite.mixins.json` on this branch; the win does not carry over.

### Logging

- **`/ferrite log monitors on|off|status`** — runtime gate for the periodic monitor reports (`[entity-tick]`, `[chunkgen]`, `[redstone-phase]`, etc.). About 5 lines/sec across 24 buckets in normal play, plus the disk I/O. Added preemptively to avoid log-volume lag on long sessions and on hardware where I/O is the bottleneck. JVM-arg equivalent: `-Dferrite.log.monitors.off=true`. Counters keep ticking when disabled, so re-enabling picks up cleanly from the next 5s window — no backlog dump. Also applied on the 1.21.11 branch.

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
