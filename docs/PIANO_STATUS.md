# Piano Architecture — Status & Next Move (2026-04-28)

## TL;DR — Piano model is built. Scaffolding is not the bottleneck.

The "Piano" mental model — vanilla hands Rust the seed once at world
load, Rust holds derived noise/biome/density state, samples without
crossing JNI per-position, vanilla writes results — is **fully
implemented and parity-confirmed**.

| Piece | Status | Reference |
|---|---|---|
| `WorldgenState` (seed-derived state container) | ✅ shipped | [rust/mod/src/worldgen_state.rs](../rust/mod/src/worldgen_state.rs) |
| `ImprovedNoise` / `PerlinNoise` / `NormalNoise` (yarn `DoublePerlinNoiseSampler`) | ✅ shipped, ~30 unit tests | [rust/mod/src/perlin.rs](../rust/mod/src/perlin.rs) |
| `BlendedNoise` (yarn `InterpolatedNoiseSampler`) | ✅ shipped | same file |
| `NoiseConfig` map (full identifier → `NormalNoise`) | ✅ shipped, populated from `NOISE_PARAMETERS` registry at world load | [WorldgenStateBootstrap.java](../src/main/java/me/apika/apikaprobe/WorldgenStateBootstrap.java) |
| Climate + biome R-tree | ✅ shipped, 1000/1000 sample tests bit-exact | [rust/mod/src/climate.rs](../rust/mod/src/climate.rs) |
| Seed-handoff JNI (`initWorldgenState`, `registerNoiseParameter`, `registerBiomeEntries`, `registerDensityFunction`, `finalizeWorldgenState`) | ✅ shipped | [rust/mod/src/worldgen_jni.rs](../rust/mod/src/worldgen_jni.rs) |
| `NoiseConfig` capture mixin (live-instance handle for parity validator) | ✅ shipped | [NoiseConfigCaptureMixin.java](../src/main/java/me/apika/apikaprobe/mixin/NoiseConfigCaptureMixin.java) |
| Density function port (41/42 DFs bit-exact) | ✅ shipped | [rust/mod/src/density.rs](../rust/mod/src/density.rs) |

If a future agent says "build WorldgenState skeleton" or "port
PerlinNoiseSampler" — the agent is working from stale context. Stop and
re-read this doc.

## The actual wall — Rust DF interpreter < vanilla's JIT

Two bulk paths exist, both default-OFF behind JVM flags because they
**regress vs vanilla** despite 100% parity:

- `-Dferrite.bulkSlice=true` (intercepts `ChunkNoiseSampler.sampleDensity`)
- `-Dferrite.bulkChunkDensity=true` (intercepts the synthetic outer
  `cacheAllInCell(add(finalDensity, beardifier))`)

Live-measured at commit `7040494`:

| Path | Cost per chunk | vs vanilla |
|---|---|---|
| Vanilla per-block | ~2 ms steady state (~20 ns/cell × 98,304) | baseline |
| Bulk-chunk-density on | ~50–56 ms JNI fill + ~5 ns/cell reads = ~56 ms floor | **+25–50 ms regression** |

Vanilla's per-cell cost is ~30× cheaper than ours at steady state,
because:

1. C2 inlines `CacheOnce.sample → buffer[i]` to ~15 native instructions.
2. `Marker(CacheOnce, X)` makes shared-subtree evaluation effectively
   free — same `X` evaluates once per `interpolationCounter`, reused
   across ~25 Y entries.
3. Our Rust DF evaluator is an **interpreter** with no equivalent
   memoization layer.

## Three root causes (in order of fix-cost)

| Cause | Fix | Cost |
|---|---|---|
| ~~Nested Rayon contending with `Util.getMainWorkerExecutor()` FJP~~ — **FALSIFIED 2026-04-28.** Stripping `par_chunks_mut → chunks_mut` in `populateNoiseBufferRust` step 2 measured ~136 ms/chunk steady-state vs ~70 ms vanilla baseline (worse than pre-strip ~110 ms documented at commit `7040494`). Under sustained flight, latency climbed catastrophically to 1000–3000 ms. Reverted. The per-block compose loop **was** benefiting from intra-chunk parallelism even under concurrent chunkgen; FJP contention concern was overstated. | n/a — hypothesis dead. | n/a |
| **Allocation churn** — batch interpreter does per-recursion `Vec::with_capacity(245)`; ~11 MB allocation per chunk eats the cheap-op savings ([CACHE_FILL_PLAN.md:139-141](CACHE_FILL_PLAN.md#L139-L141)). | Thread-local arena for scratch buffers, borrow-return pattern. | **Days.** Bounded-scope refactor. |
| **No `CacheOnce` equivalent in Rust** — shared-subtree re-evaluation per cell ([CACHE_FILL_PLAN.md:168-171](CACHE_FILL_PLAN.md#L168-L171)). | Counter-keyed memoization layer in the Rust DF evaluator. Eviction logic + parity verification. | **Weeks.** Real design pass. |

The eventual endgame — and only true win for the bulk path — is a
**Rust DF compiler** (mirroring the surface-rule bytecode evaluator
approach), not an interpreter ([CACHE_FILL_PLAN.md:70](CACHE_FILL_PLAN.md#L70)).
That's a multi-week investment and only worth doing once the cheaper
fixes have proven they can't close the gap.

## bulk-chunk-density thread CLOSED (2026-04-28)

The Rayon strip was tried, falsified, and reverted. The arena hypothesis
was deprioritized because `populateNoiseBufferRust` doesn't use
`compute_batch` (the function the 11 MB/chunk allocation number was
measured against) — it uses `compute_with_di_lerp` per block. So root
cause #2 may not even apply to this code path; the doc was overly
confident when it ranked the three causes.

**Decision: walk away from bulk-chunk-density tuning.** The data is
clear that vanilla's density path is JIT-optimized to ~20 ns/cell
amortized via `CacheOnce` + C2 inlining. Closing the gap requires a full
Rust DF compiler — multi-week investment with diminishing returns
relative to porting a subsystem that vanilla hasn't already optimized
to within an inch of its life.

**Infrastructure stays in tree.** Parity confirmed (0 fallbacks across
millions of samples). Code is correct. Reusable for future DF compiler
work or whole-chunkgen takeover (Track A^2). Toggle stays default-off.

### Same-session measurements (2026-04-28, seed `-5611065792945225750`)

| Path | noise-sync ms/chunk (steady state) | JNI per chunk |
|---|---|---|
| Baseline (no flag) | **~71 ms** median, 62–82 ms band, 14 warmed samples | n/a |
| Intervention (Rayon stripped, flag on) | **~136 ms** steady; degraded to **1000–3000 ms** under sustained flight | 56–74 ms |

### Next target

Pivot to a smaller subsystem that vanilla hasn't already JIT-optimized.
Candidates with documented vanilla cost:

- **Aquifer** — ~8 ms/chunk
- **Decoration** — ~3 ms/chunk
- **Lighting** — ~5–12 ms steady, ~15–19 ms init (but stateful — likely
  fails Piano check #5)

Apply the five Piano questions before picking. See "Next-target
analysis" section below.

## physics dispatcher thread CLOSED (2026-04-28)

The PhysicsDispatcher (Rust port of `Entity.adjustMovementForCollisions`,
infrastructure built but `ENABLED=false` since landing) was parity-validated
and perf-measured at 1000-mob scale. **Same wall as bulk-chunk-density.**

### Phase 1 — parity validator built and passed

Built [PhysicsOracle.java](../src/main/java/me/apika/apikaprobe/PhysicsOracle.java)
mirroring the aquifer/redstone oracle pattern. Added `PARITY_MODE` flag to
[PhysicsDispatcher.java](../src/main/java/me/apika/apikaprobe/PhysicsDispatcher.java) —
when on, every dispatch returns vanilla but shadow-runs Rust and feeds the
(vanilla, rust) pair to `PhysicsOracle.record(...)` for component-wise
diff (EPSILON=1e-9).

Live result at 1000-mob scene, ~30s AFK:

| Window | Dispatched | Matched | Mismatches | Fallbacks |
|---|---|---|---|---|
| 1 | 17 | 17 | 0 | 0 |
| 2 | 60,006 | 60,006 | 0 | 1 |
| 3 | 111,232 | 111,232 | 0 | 0 |
| 4 | 112,496 | 112,496 | 0 | 0 |
| 5 | 104,912 | 104,912 | 0 | 0 |
| 6 | 103,648 | 103,648 | 0 | 0 |
| 7 | 104,912 | 104,912 | 0 | 0 |
| 8 | 102,384 | 102,384 | 0 | 0 |
| **Total** | **~700K** | **~700K** | **0** | **1** |

**100.0000% match.** Rust collision math is bit-exact-equivalent to vanilla
under live load. Infrastructure is correct.

### Phase 2 — perf measured, regressed, reproduced

`ENABLED=true, PARITY_MODE=false`. Same scene, AFK. Reproduced twice (the
first run was flagged invalid because the player moved; both runs converge
on the same shape):

| Sample (warmed) | Total tick ms | vs vanilla baseline (~40 ms) |
|---|---|---|
| s2 (cold) | 29.8 | -10 (JIT cold) |
| s3 | 35.3 | -5 |
| s4 | 37.3 | -3 |
| s5 | 41.8 | +2 |
| s6 | 45.6 | +5 |
| s7 | 49.5 | +9 |
| **plateau (s10–s12)** | **~55.5** | **+15** |

TPS dropped from ~25 (vanilla) to ~18 (Rust on) at steady state. ~36K
dispatches/sec, 0 fallbacks. Per-call boundary cost (snapshot rebuild
~1:3 ratio with dispatches = ~12K rebuilds/sec) > what vanilla's
JIT-inlined `adjustMovementForCollisions` costs. Same JIT-defender wall
as density — vanilla has hot inline loops the Rust+JNI roundtrip can't
undercut at this call frequency.

### Decision: both flags default-off

- `PhysicsDispatcher.ENABLED = false` (would regress perf)
- `PhysicsDispatcher.PARITY_MODE = false` (validated; running it
  permanently doubles the cost)

Infrastructure stays in tree — parity-validated, correct, reusable. Two
realistic paths to revisit it:

1. **Per-tick batched dispatch** — single JNI call per chunk-bucket
   carrying all mobs in that bucket, instead of single-entity dispatch
   per `move()`. Amortizes the 1:3 snapshot rebuild ratio across many
   mobs. Would need refactoring how `Entity.move()` interacts with the
   dispatcher (probably move the intercept point earlier).
2. **In-process collision math without snapshot rebuild** — eliminate
   the `~12K rebuilds/sec` cost by keeping a long-lived snapshot and
   patching it incrementally on block changes. Higher complexity, real
   shared-state risk.

Neither is "small" — both are multi-session investments. Per the same
discipline that closed the bulk-chunk-density thread, walking away is
the right call until someone has a specific reason to invest.

### What the data says about the rest of the chunkgen / tick-time map

Across density, aquifer, structure-placement scoring, decoration, and
now physics: **the per-call JIT-vs-JNI wall is the dominant pattern**.
Vanilla's cheap-per-call hot paths beat Rust+JNI roundtrip at any
realistic call frequency, regardless of how clean the Piano shape looks.
The Piano wins (cramming, AC redstone, surface rules) all share a
property: vanilla's per-call cost is high enough (microseconds, not
nanoseconds) that the JNI overhead amortizes. Cheap-per-call vanilla
hot paths are a worked seam in 1.21.11.

Future Piano hunting should look for tick-time hot paths where vanilla
spends real microseconds-to-milliseconds per call, not the cheap inlined
PRNG / collision / per-block paths.

## Measurement protocol (mandatory before flipping any default-off toggle to on)

Same discipline as every other Ferrite port. **Microbenches lie about
this layer** — vanilla's win is JIT inlining + nested concurrency
behavior that only shows up under real load.

### Setup

- Build the native: `cd rust/mod && cargo build --release`, then
  `./gradlew runClient` (Loom copies the dll into the jar).
- Set `-Dferrite.bulkChunkDensity=true` in the JVM args for the
  intervention run. Leave it unset for the baseline run.
- Use the **same world seed** for baseline and intervention (the
  vanilla cost varies by biome distribution).

### Load profile

- **4-worker concurrent chunkgen.** Vanilla `populateNoise` is on
  `Util.getMainWorkerExecutor()` (a real FJP). The regression that
  motivates this work only appears with multiple chunks in flight.
  Solo-spawn idle chunkgen is a microbench by another name; reject it.
- **Live chunk generation, not idle.** Use `/tp` flight or the chunk
  forcer (`/ferrite chunkforce`) to drive ~40+ chunks/sec sustained
  for at least 60 seconds. Wait through JIT warmup (first ~10 seconds
  of any session).
- **Same session for baseline and intervention** is preferable when
  possible (toggle on mid-session via reload), to control for
  background system noise. If toggle requires restart, run baseline
  and intervention back-to-back on a freshly-booted machine.

### Metrics

Read from log lines emitted by Ferrite's diagnostics:

- `[chunkgen] noise-sync ms/chunk` — the headline. Vanilla baseline
  is ~55–79 ms at commit `7040494`; bulk path was ~110–148 ms before
  step 4b JIT-strip, ~97–118 ms after.
- `[bulk-chunk-density] JNI per chunk` — the upfront Rust cost.
  ~56 ms before this change; we expect a meaningful drop after.
- `[bulk-chunk-density] substitutions / per-block buffer hits / fallbacks`
  — sanity check that the mixin still fires at the same rate
  (regression here means we broke the path entirely, not perf).
- `[chunkgen] noise-sync` numbers from `FerriteDispatcherProbe` if
  available; otherwise sample 200+ chunks and report median.

### Pass criteria

- **Parity:** fallbacks remain at 0 across the test session. If any
  fallback fires, the change broke the math — revert and diagnose.
- **Perf:** intervention `noise-sync ms/chunk` median ≤ vanilla
  baseline median **measured in the same session**. Not the
  documented historical baseline — real numbers from the session
  the test runs in.
- **Concurrency stability:** no thread-related panics in Rust logs
  (sequential code under concurrent JNI calls should be fine, but
  verify).

### Decision

- Pass → ship `bulkChunkDensity` default-on. Update CACHE_FILL_PLAN
  and this doc. Commit message must include the before/after numbers.
- Fail → record numbers in CACHE_FILL_PLAN, leave toggle default-off,
  proceed to scratch-buffer arena (root cause #2 above). **Do not
  ship.**

The discipline here matters because the prior bulk-path attempts each
shipped with parity confirmed but perf regressed — and the toggle
existing default-off is fine, but flipping to default-on without live
measurement is the failure mode this doc exists to prevent.
