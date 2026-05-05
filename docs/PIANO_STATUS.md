# Piano Architecture — Status & Next Move (2026-04-28)

> **Measurement scope.** All ms-per-chunk and ms-per-tick figures in
> this doc were captured on the **MC 1.21.11 / yarn** codepath (the
> shipped line as of writing). The 26.1.x port (mojmap + JDK 25) is
> at parity correctness-wise (noise 62/62 + density 50/50 bit-exact,
> see "26.1.x branch update" callout below) but its perf numbers
> have **not yet been re-measured on 26.1.2**. Treat the figures
> below as 1.21.11 baselines; 26.1.2 may shift them up or down once
> JFR is re-run on that branch. The shapes (which subsystems regress
> vs win, which gates fail Piano questions) should carry over since
> the underlying algorithms didn't change in the port.

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

> **26.1.x branch update (2026-05-02):** the carry-over to MC 26.1.2
> (mojmap, JDK 25) ports `FindTopSurface` and `EndIslandDensityFunction`
> as Rust DF variants and lifts density parity to **50/50 bit-exact**
> at samples=2000, ahead of the 41/42 baseline above. The
> `EndIslandDensityFunction` port required `SimplexNoise` (2D) and
> `LegacyRandomSource` as new building blocks. See `JOURNEY.md`
> "26.1.x port: parity carry-over" for the full breakdown and the
> `26.1.x` git branch for the code.

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

## surface dispatcher noise routing — investigated, reverted (2026-04-28)

Attempted to push the surface dispatcher closer to the Piano model by
having Rust sample noise channels itself from `WorldgenState` instead of
receiving vanilla-fed noise values via the existing per-channel JNI
loop. Parity passed cleanly; perf regressed.

### The plan that didn't work

Step 1 (parity validator) shadow-sampled every (column × channel) on
the Rust side and compared to the vanilla-fed buffer Java already
packed. **100.0000% match across 81,445,189 samples** — Rust
`NormalNoise.get_value(x, 0, z)` is byte-identical to Java's
`DoublePerlinNoiseSampler.sample(x, 0, z)` for every registered
channel. Math was never the question.

Step 3 (route via `WorldgenState`) deleted the Java reflection +
per-channel JNI loop and had Rust sample inside the per-column closure
of `evaluateSurfaceRuleBatch` — eager 7-channel sampling, then eval.

### What the breakdown showed

Surface band measured at ~28 ms/chunk steady-state with dispatch ON,
vs ~9.3 ms vanilla baseline (~+18 ms regression). Per-chunk Rust
breakdown over ~3000 chunks of live flight:

| Phase | Per chunk |
|---|---|
| Noise buffer alloc | ~15 µs |
| Noise sampling (parallelised, ~31K cols × 7 channels) | **~9.3 ms** |
| Bytecode eval (parallelised) | ~280 µs |
| Per-column noise (all 7 channels) | **~285 ns** |
| Per-column eval | ~9 ns |

### The finding

**Rust `NormalNoise.get_value` ≈ 285 ns/column. Java JIT-compiled
`DoublePerlinNoiseSampler.sample` ≈ 50 ns/column.** Rust scalar Perlin
is **~6× slower** than JIT on this specific math because HotSpot
inlines and vectorises the smoothstep + lerp3 + gradient-table lookups
in ways the Rust scalar implementation doesn't match.

Same JIT wall as bulk-chunk-density. Rust scalar Perlin cannot beat
JIT-compiled Java on per-column sequential access.

The ~28ms surface band breaks down as:

- **~9.3 ms** Rust noise sampling (eager all-channels, parallelised)
- **~0.3 ms** Rust bytecode eval
- **~18 ms** everything else (Java-side per-position reflection for
  biome/depth context, JNI overhead for the dispatch crossing,
  per-position chunk writeback)

Even if Rust noise sampling went to zero, the ~18 ms of Java-side
dispatch overhead still leaves the surface band well above the ~9.3 ms
vanilla baseline. The dispatcher's existing per-channel
`sampleWorldgenNoise` JNI loop (~17.7 ms ON before this experiment)
turned out to be the local optimum — JIT samples the noise faster than
Rust does, and the existing reflection cost is lower than the
two-pass-with-allocation we replaced it with.

### Decision

- **Reverted** all Step 1 + Step 3 + Step 3.5 changes. Codebase back to
  the pre-investigation state (per-channel `sampleWorldgenNoise` JNI
  loop, ~17.7 ms ON / ~9.3 ms OFF baseline).
- **Surface dispatcher stays default-OFF.** No new "ship it" change.
- **Parity validator JNI removed too** — served its purpose
  (proved math equivalence), no further use without a reason to
  re-validate.

### SIMD batch noise — the only viable path to surface dispatcher default-on

Root cause confirmed: Rust scalar `NormalNoise.get_value()` = ~290 ns/col
vs Java JIT ~50 ns/col. **6× slower. JIT wall, not algorithm problem.**

**Why SIMD works here:**

- 256 columns per chunk are fully independent (no cross-column dependencies)
- Same 7 noise channels, same Perlin math, applied to each column
- Perfect SIMD shape: same instruction, N independent inputs

**Target hardware:** RTX 4090 machine = AVX2 minimum (8-wide f64).

**Math:**

```
290 ns × 256 cols                = 74 ms scalar
290 ns × 32 groups of 8          = ~9 ms with AVX2 8-wide
Result: surface dispatcher ON drops from ~28 ms to ~10 ms
        At or below vanilla baseline of ~9.3 ms
```

**Implementation path:**

- Port `NormalNoise` math to operate on `f64x8` (AVX2) or `f64x4`
- Use Rust `wide` crate or `std::simd` (nightly)
- Process 256 columns in 32 batches of 8
- Everything else (bytecode eval, JNI) unchanged

**Pre-condition:** one focused session dedicated to SIMD Perlin only.

**Gate:** SIMD noise must produce bit-identical results to scalar path
before replacing it. Same parity discipline as every other port —
mirror the validator that proved 81M-sample equivalence in Step 1 of
this investigation, but apply it to scalar-vs-SIMD.

The Piano model is right. The instrument needs SIMD strings.

## diagnostic gating — real noise-sync win shipped (2026-04-28)

After AVX2-only hardware ruled out the SIMD path, ran a JFR profiler
session under live chunkgen with surface dispatch on. Two findings:

1. **The audit-by-reading approach was wrong about where the surface
   dispatcher's overhead lives.** A "biome cache + Mutable BlockPos +
   Identifier intern" change (commit `29975e7`) projected ~12-15 ms of
   savings; actual measurement showed ~0.7 ms (17.7 → 17.0 ms median).
   JIT had already inlined the duplicated supplier chain that the
   audit estimated at ~250 ns/call. Real cost was much lower than
   the source-reading estimate.
2. **Two diagnostic mixins were eating ~8-10 ms/chunk in the
   `noise-sync` phase**, unrelated to the surface dispatcher itself:
   - `CacheRouteCaptureMixin` fired reflective `DensityFunctionWalker.fingerprint`
     per Marker wrap during `ChunkNoiseSampler.getActualDensityFunctionImpl`.
     Diagnostic for the Phase 2.5 step 2a/2b bulk-density experiments —
     both of which are themselves default-off.
   - `AquiferMonitor` wrapped every `AquiferSampler.apply` call (~98K
     per chunk) with `@Inject HEAD/RETURN` — pure observation cost.

   Both gated behind default-off flags in commit `c91a12b`
   (`CacheRouteStats.ENABLED` and `AquiferMonitor.ENABLED`). Flip
   true only when actively debugging density/aquifer work.

### Measured impact

| Phase | Pre-gating | Post-gating | Δ |
|---|---|---|---|
| `[chunkgen] noise-sync` | ~60 ms steady | **~50 ms steady** | **−8 to −10 ms ✓** |
| `[chunkgen] surface` (dispatch ON) | ~17 ms | ~17.6 ms | flat (the diagnostics weren't on this path) |

**That's a real win for everyone running Ferrite.** ~8-10 ms/chunk
saved on every chunk generated — cumulative across exploration,
pre-gen runs, and new-area loads. Default-on, no flag flip required,
no parity risk.

### What this confirms about the surface dispatcher

The surface dispatcher's ~17 ms gap to vanilla baseline (~9 ms) is
**intrinsic to the dispatch path itself** — per-position context
capture, per-thread array writes, JNI crossing, post-dispatch
writeback loop. It is not contaminated by unrelated diagnostics.

The JFR aggregate-by-frame-count approach was misleading because it
didn't isolate which chunkgen phase each frame burned time in.
`CacheRouteCaptureMixin` and `AquiferMonitor` had high stack-appearance
counts during chunkgen overall, but neither fires during
`SurfaceBuilder.buildSurface` — the phase the surface band measures.

A future surface-specific profiler pass would need to filter samples
to only the `SurfaceBuilder.buildSurface` subtree per worker thread
to identify the actual dispatch hot frames. JFR's per-event stack
data supports this; the parser used in this session aggregated only
the top-of-stack and top-N anywhere-in-stack frames, which conflated
phases.

### JFR frame-count overstates recoverable cost — three-strike rule

This pattern has now repeated three times. Treat it as a permanent
constraint on how JFR data drives Ferrite work:

1. **Biome supplier-chain caching (commit `29975e7`).** Read of source
   suggested ~12-15 ms recoverable from caching duplicated supplier
   chain resolution. Measured: ~0.7 ms. HotSpot had already inlined
   the supplier chain.
2. **Phase-blind frame aggregation (commit `c91a12b`).** First JFR
   pass ranked `CacheRouteCaptureMixin` + `AquiferMonitor` as #1 cost
   contributors by total frame count. Gating them shipped a real
   ~8-10 ms noise-sync win, but **zero impact on the surface band** —
   those frames burned time during noise-sync, not surface. Frame
   count without phase filtering conflates unrelated work.
3. **MethodHandle micro-opt (commits `4ed0d89` + `2beaa5b`).**
   Surface-filtered JFR showed `Invokers.checkCustomized` at 4.5%
   and `fastReadObjectField` at 3.5% of in-buildSurface samples,
   projected ~1-1.5 ms recoverable. Replaced with @Accessor/@Invoker
   typed calls. Measured surface band: no movement (within ±0.5 ms
   noise). HotSpot specializes warm MethodHandle callsites; the
   sampler caught frames that weren't actually wall-time bottlenecks.

**Why this happens.** JFR's `jdk.ExecutionSample` is a stack snapshot
on each sample tick. Frames near the leaf are over-represented because
shallow native-transition stalls and JIT-deopt slow paths catch the
sampler's eye more than steady-state JIT-inlined work. Frame count
correlates with sampler attention, not always with wall time.

**Working rules going forward:**
- A JFR-attributed cost is a hypothesis, not a measurement. Validate
  by removing the frame and re-measuring the band that contains it.
  If the band doesn't move, the frame wasn't on the critical path.
- Reserve micro-opts for cases where one window of measurement gives
  a strong directional signal (~3+ ms band movement). For sub-2 ms
  changes, the noise floor of the chunkgen pipeline (~±1 ms) eats
  the signal — multiple runs needed to detect, often not worth it.
- For the surface dispatcher specifically: the gap is structural.
  Further reflection/MH/cache tuning will not close it. The one
  remaining recoverable optimization (~2-3 ms) is **batched heightmap
  updates in flushChunk** — see "Surface dispatcher source-level
  audit" below. There is no clean setBlockState bypass.

### Surface dispatcher status

- **Stays default-OFF.** Surface band ON ~16.0 ms vs vanilla baseline
  ~6.3 ms = ~+9.7 ms structural gap. Two follow-up reflection-removal
  fixes confirmed the gap is **not** reflection — it is intrinsic to
  the dispatch path (per-position context capture, JNI crossing, and
  flushChunk's per-write `ProtoChunk.setBlockState` calls including
  per-write heightmap `trackUpdate`).
- **Biome cache + Mutable BlockPos + Identifier intern (commit
  `29975e7`) ships as-is** — small but real (~0.7 ms), parity-clean
  (99.9% match, java=rust=100%), no risk to leave in tree even though
  dispatcher is off.
- **Diagnostics gating (commit `c91a12b`) ships as-is** — ~8-10 ms
  saving on noise-sync regardless of dispatcher state.
- **@Invoker on `MaterialRuleContext.initVerticalContext` and
  `BlockStateRule.tryApply` (commit `4ed0d89`) ships as-is** —
  universal ~3 ms win on the vanilla path (baseline dropped from
  ~9.3 ms to ~6-7 ms). The `captureContext` redirect was hitting
  vanilla too, so killing its reflection helped the vanilla baseline,
  not just the dispatcher path.
- **@Accessor on `MaterialRuleContext` hot fields + @Invoker on three
  more methods (commit `2beaa5b`) ships as-is** — clean code,
  parity-clean (99.9% match, java=rust=100%), zero measurable perf
  movement on the surface band. See "JFR frame-count overstates
  recoverable cost" below.
- **Heightmap parity validator + batched flushChunk (commits `e4e7a41`
  + `a26e2ee`) shipped** — replaces per-write `ProtoChunk.setBlockState`
  with raw `ChunkSection.setBlockState` grouped by section + per-column
  `trackUpdate` post-pass (~32K → ~512 trackUpdate calls per chunk;
  ~16K → ~24 section lookups). Parity validator confirmed bit-identical
  output across 21,012 + 2,192 chunks (100% match, 0 cell mismatches
  for both `WORLD_SURFACE_WG` and `OCEAN_FLOOR_WG`). Validator stays
  in tree as a regression check via `/ferrite surface heightmap-parity
  on|off|stats|reset`. **Clean perf measurement (validator off): ON
  path 15.6 ms → 13.4 ms (-2.2 ms recovered). Gap to baseline closed
  from ~9.2 ms to ~7.0 ms.** Projection from source audit was 2-3 ms;
  actual landed within band — see "source-audit projections" below.

### Surface dispatcher source-level audit (2026-04-28)

Read of yarn 1.21.11 `SurfaceBuilder.buildSurface` + `ProtoChunk.setBlockState`
to design the bypass. Result: **the bypass framing was wrong, and there
is no clean architectural change available.** Findings:

**1. There is no duplicate write.** `tryApply` returns `null` when no
rule matches; vanilla's loop guards on this and skips `setBlockState`
entirely (`SurfaceBuilder.java:158`). When Ferrite's `@Redirect` returns
null in batch mode, vanilla never calls `setBlockState`. The dispatcher
then writes via `flushChunk` → `ProtoChunk.setBlockState`. **Net write
count is identical to vanilla** — the dispatcher pays the full vanilla
write cost, just relocated from the inline column loop into flushChunk.
The earlier "structural duplication" framing was wrong.

**2. The 23.3% `ProtoChunk.setBlockState` JFR frames are entirely
flushChunk's own writes**, not vanilla duplicating work. Confirmed at
[`SurfaceDispatcher.java:204`](../src/main/java/me/apika/apikaprobe/surface/SurfaceDispatcher.java#L204).

**3. The 18.7% `SurfaceBuilder.isDefaultBlock` JFR frames are vanilla's
column-boundary scanner** (yarn `SurfaceBuilder.java:144-150`), which
fires per Y-down step regardless of dispatcher state. Pure vanilla
cost the dispatcher cannot touch.

**4. `ProtoChunk.setBlockState` cost decomposition** (per call, yarn
`ProtoChunk.java:113-167`):

| Step | Skippable for surface rule writes? |
|---|---|
| section lookup (`getSection`) | yes — cluster batch by section, ~24 lookups vs ~16K |
| palette write (`chunkSection.setBlockState`) | **no** — this is the data change |
| lighting flag flip (`setSectionStatus`) | no — fires on empty↔non-empty transitions |
| skylight check + light queue | usually no-op for surface rule swaps (default→default opacity unchanged) |
| heightmap presence loop | cheap, leave |
| per-heightmap `trackUpdate` | **yes** — dispatcher knows the whole batch; heightmap only cares about top-Y per (x,z) |

**5. The one real recoverable optimization: batched heightmap updates.**
Vanilla calls `trackUpdate` once per write because it processes one
position at a time. The dispatcher knows the full batch up-front, so
it can write to `ChunkSection` directly (skipping the per-write
heightmap loop) and then call `trackUpdate` ONCE per (x,z) column with
the highest changed Y. Estimated saving: **~2-3 ms** (~16K → ~256
heightmap calls per chunk). Brings dispatcher from ~14 ms toward
~11 ms — still above the ~6.3 ms vanilla baseline but the gap closes
to ~5 ms.

**Shipped in commits `e4e7a41` (parity validator) + `a26e2ee` (batched
path).** Clean perf measurement post-ship: ON path 15.6 ms → 13.4 ms
(-2.2 ms), within the projected band. Parity 100% across 23K+ chunks.

### Honest ceiling — measured

Post-batched-heightmap measurement: dispatcher ON ~13.4 ms vs vanilla
baseline ~6.4 ms = **~7.0 ms structural floor**. Decomposition (from
JFR + source audit, post-Step 2):

- Palette writes (`ChunkSection.setBlockState` × ~16K) — ~2-3 ms
- Vanilla's `isDefaultBlock` column-boundary scanner that fires
  regardless of dispatcher state — ~2-3 ms
- Biome supplier chain in vanilla's surface rule machinery
  (`Suppliers$NonSerializableMemoizingSupplier.get` + downstream) —
  ~1-2 ms
- Ferrite dispatch ceremony even with all wins (`dispatchEnqueue`
  per-position context capture + JNI crossing + `internIdentifierToString`)
  — ~1-2 ms

The dispatcher's structural purpose — running rule evaluation in Rust —
does not reduce any of these. **Without an architectural change that
either eliminates palette write overhead or bypasses the biome
supplier chain entirely, ~7 ms is the floor.**

### Source-audit O(N) projections vs JFR frame-count guesses

A second pattern emerged from the batched-heightmap work that's
worth contrasting against the JFR three-strike rule above:

- **Three JFR-frame-count micro-opts (29975e7, c91a12b, 4ed0d89/2beaa5b):**
  projected savings ranged 1-15 ms. Actual savings on the surface
  band: 0-0.7 ms each. Frame counts overstated wall-time cost.
- **Batched heightmap (e4e7a41 + a26e2ee):** projected 2-3 ms based
  on a counted O(N) reduction (vanilla makes 32K trackUpdate calls
  per chunk; batched path makes 512 = 60× reduction). Actual: 2.2 ms.
  Within the projected band on the first try.

**The difference isn't profiler vs no profiler — it's whether the
projection has a counted floor.** "60× fewer calls and each call has
this much overhead" is a verifiable arithmetic claim. "This frame
shows up at 8% in the sampler" is an inference about wall-time that
HotSpot's specialization, sampler bias, or phase confusion can
invalidate. Reserve micro-opt budget for the former; treat the latter
as a hypothesis until removed-and-remeasured.

### Next move (when this thread reopens)

The dispatcher gap is now structural floor, not optimization debt.
Closing the remaining ~7 ms requires architectural work:

- **Option A: bypass palette write overhead.** Write directly to the
  `PalettedContainer`'s underlying storage instead of going through
  `ChunkSection.setBlockState`. Risks: palette resize semantics, block
  entity/state interaction, light recompute correctness.
  Validator-required (palette-level parity).
- **Option B: eliminate the biome supplier chain.** Replace vanilla's
  `Suppliers.memoize(() -> posToBiome.apply(pos))` per-position chain
  with a precomputed (x,z) → Biome cache populated once at chunk
  start. ~1-2 ms recoverable. Lower risk than (A) but smaller win.
- **Option C: combine A + B + further dispatch ceremony cleanup.**
  Theoretical ceiling ~3-4 ms, dispatcher would land at ~10 ms vs
  ~6 ms baseline = ~4 ms gap. Still does not beat vanilla.

**Honest assessment: even (C) leaves the dispatcher above baseline.
Without a fundamentally different design (e.g. SurfaceBuilder fully
ported to Rust including the column-boundary scanner), the dispatcher
cannot ship default-on.** The Piano model's per-call dispatch cost
exceeds the savings on this workload.

**Do not pursue further reflection/cache micro-opts on this path.**
The three-strike rule is documented above; it has held. Source-audit
projections are reliable when the win is counted-O(N), not inferred
from JFR frames.

### What the data says about the rest of the chunkgen / tick-time map

Across density, aquifer, structure-placement scoring, decoration,
physics, and now surface noise sampling: **the per-call JIT-vs-JNI
wall is the dominant pattern**.
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
