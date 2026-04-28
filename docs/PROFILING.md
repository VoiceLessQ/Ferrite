# Profiling investigation — Rust worldgen on vanilla overworld

This document captures the full measurement and architecture investigation
performed on the `ferrite` branch. The goal was to determine whether Rust
could meaningfully accelerate vanilla Minecraft 1.21.11 chunk generation
under low-end hardware constraints.

**Outcome:** viable in principle, but completing it is a multi-week
project equivalent in scope to C2ME. The PoC is complete and sealed —
further work is a different project, not a continuation.

## 2026-04-28 update — JFR session, diagnostic gating shipped

A profiler-driven session replaced the earlier estimate-from-source
approach. Key findings:

- **JFR built into JDK 21** (no async-profiler install needed on
  Windows). Launch with `JAVA_TOOL_OPTIONS="-XX:StartFlightRecording=
  duration=120s,filename=ferrite.jfr,settings=profile,dumponexit=true,
  disk=true"` then `jfr print --events jdk.ExecutionSample` for
  parsing.
- **Aggregate-by-frame-count is misleading.** The first JFR pass
  ranked frames by total stack-appearance count across all chunkgen
  workers — which surfaced `CacheRouteCaptureMixin` and
  `AquiferMonitor` as #1 cost contributors. Both were correctly
  identified as expensive (~8-10 ms/chunk combined) but they fired
  during `noise-sync`, not `surface-buildSurface`. Gating them
  saved ~8-10 ms on the `noise-sync` band (real win, shipped
  default-on, commit `c91a12b`) but did **not** move the surface
  dispatcher band. Phase-aware filtering of JFR samples is required
  for per-band cost analysis.
- **Audit-by-reading was wrong about supplier chain cost.** A
  pre-profiler "biome cache eliminates duplicated supplier-chain
  resolution" change was estimated at ~12-15 ms savings; actual
  measurement showed ~0.7 ms (commit `29975e7`). HotSpot had already
  inlined the supplier chain. **Lesson: estimate cost from the
  profiler, not from reading the code.**

See `PIANO_STATUS.md` "diagnostic gating" section for the full
JFR-data → finding → action chain.

## Test environment

- MC 1.21.11 / Fabric 0.18.4 / Yarn mappings
- 4-core CPU affinity + 3 GB heap cap (simulated low-end laptop)
- Active-flight chunk generation through ungenerated terrain

## What was measured

### Chunk-gen phase breakdown

Vanilla chunk gen walks through a pipeline: EMPTY → STRUCTURE_STARTS →
STRUCTURE_REFERENCES → BIOMES → NOISE → SURFACE → CARVERS → FEATURES → LIGHT.

`ChunkPhaseMixin` + `ChunkGenMonitor` instrument the NOISE and SURFACE
phases at `@Inject` points on `NoiseChunkGenerator.populateNoise` (both the
public async overload and the private 6-arg sync overload) and
`buildSurface`. `NoiseStageMonitor` + `NoiseStageMixin` go one level deeper
inside noise, timing `sampleStartDensity` and `sampleEndDensity` on
`ChunkNoiseSampler` via a `@Inject` on `ChunkNoiseSampler$Impl`.
Steady-state numbers (post-JIT warmup, during active flight):

| Phase | Avg | Max |
|---|---|---|
| noise-dispatch (async future creation) | ~0.01 ms | ~0.2 ms |
| **noise-sync (real compute)** | **~17 ms/chunk** | **~40–50 ms** |
| surface | ~5 ms/chunk | ~30 ms |

Noise-sync dominates chunk-gen CPU cost.

### Noise-sync internal breakdown

Inside noise-sync, three sub-stages were instrumented:

| Stage | Cost | Note |
|---|---|---|
| sampleStartDensity (1×/chunk) | ~1 ms | density function setup + interpolator start buffer fill |
| sampleEndDensity (~4×/chunk) | ~3–4 ms total | per-cellX end buffer updates |
| **sampleBlockState (~98K×/chunk)** | **~13 ms inferred** | **per-block noise → blockstate mapping** |

`sampleBlockState` accounts for **70–75 % of noise-sync** — the dominant
hotspot.

### sampleBlockState internals

`sampleBlockState` delegates to a `MaterialRuleList` that iterates one or
two `BlockStateFiller` lambdas. The primary filler is:

```java
p -> aquifer.computeSubstance(p, densityFunction.compute(p))
```

Per-block work: one density evaluation (cached at cell scope, so ~768 real
computations per chunk amortised over 98K queries) plus one aquifer
decision (not cached — runs every block).

### Aquifer.apply sampling

`AquiferMonitor` sampled `AquiferSampler$Impl.apply(NoisePos, double)` at 1
in 100 calls. At 98K calls/chunk and ~100 ns/call, aquifer work totals
~10 ms/chunk — most of the `sampleBlockState` cost.

**Critical finding from this measurement:** Mixin dispatch + counter
increment overhead was ~130 ns/call, which exceeded the function's real
cost. The measurement itself inflated noise-sync from its ~17 ms baseline
to ~30 ms. Per-call instrumentation at this call frequency is
information-theoretically exhausted — finer resolution requires a
different measurement technique.

## What was proved

### Rust bulk handoff compute is 7× faster than vanilla equivalent

> **Retroactive status note (updated after 0.4.0-alpha's redstone BFS
> shipped).** The 7× figure below is accurate as an isolation benchmark
> but did not survive integration with vanilla's chunk-gen pipeline.
> Capturing density-function state to feed the Rust kernel costs more
> than the kernel saves, because vanilla's interpolator state machine
> does not expose the corner grid as a flat buffer we can snapshot
> cheaply (see "What blocked completion" below).
>
> The contrast with redstone is instructive. The 0.3.0-alpha redstone
> Rust BFS was initially shelved on the same "per-call JNI cost exceeds
> compute saving" reasoning. It was un-shelved after Phase 1 (commit
> `0cfac8c`) measured the actual JNI cost for the redstone call shape at
> single-digit nanoseconds, two orders of magnitude below the worldgen
> figure. The BFS port shipped default-on in 0.4.0-alpha because its
> inputs marshal cleanly into direct ByteBuffers. The worldgen density
> tree does not, because Mojang's interpolator design holds state across
> calls. Same project, same JNI library, two different regimes. See
> "JNI cost regimes" below for the full recalibration.
>
> Ferrite still does not advertise a 7× chunk-gen speedup as a
> forward-looking claim. The measurement framework is retained in the
> jar for diagnostic value, output gated off. See the README and
> CurseForge description for current scope.

`TerrainBulkHandoff` + `terrain.rs` implemented a bulk pipeline: Java
samples finalDensity at 1225 cell corners, hands them to Rust, Rust
performs trilinear interpolation + simplified aquifer decisions for all
98,304 blocks using Rayon across 4 worker threads.

| Component | Cost |
|---|---|
| Rust compute (98K blocks: interp + classify, parallel) | ~2.5 ms/chunk |
| Vanilla equivalent work (measured as noise-sync clean) | ~17 ms/chunk |
| **Speedup on pure compute** | **~7×** |

### Per-call JNI is non-viable at this call frequency

JNI boundary crossing costs 200–500 ns per call. `aquifer.apply` costs
~100 ns per call. Porting per-call would make the code **2–5× slower**, not
faster, even though the function itself runs faster in Rust.

**Architectural conclusion:** only bulk handoff (one JNI call per chunk,
amortising boundary cost across 98K blocks) can win on this workload.

### JNI cost regimes (recalibrated after redstone Phase 1)

The 200-500 ns figure above is accurate for the JNI call shape used in
this worldgen investigation: each crossing materializes a snapshot
object (boxed primitive wrappers, registry lookups, small arrays built
per call). That cost structure applies to any port target that needs
to carry more than primitives across the boundary.

It is not a universal JNI number. Redstone Phase 1 (live bench in
`docs/REDSTONE_PORT_PLAN.md`, commit `0cfac8c`) measured a different
regime: pass two direct ByteBuffers and an int, receive an int back.
That call shape clocks in at single-digit nanoseconds amortized,
roughly two orders of magnitude below the worldgen estimate.

How to tell which regime a port sits in:

- **Snapshot regime (200-500 ns per call).** The JNI call builds
  per-call Java objects: wrapper types, registry lookups, array
  allocations, string interning. Crossing cost is dominated by
  object construction and downstream GC pressure, not the native
  call itself. Per-call rate above ~5K per chunk is unviable.
  Covers: aquifer.apply, density samples, any path that needs a
  live JVM object graph on the other side.

- **Direct-buffer regime (single-digit ns per call).** Inputs and
  outputs are pre-allocated direct ByteBuffers plus small scalar
  args. No per-call allocation, no per-call object lookup. Crossing
  cost is the native-call instruction plus buffer address handoff.
  Per-call rate above 100K per tick is viable. Covers: the cramming
  tick dispatch, the redstone BFS per-cascade call, any port whose
  inputs can be marshalled once at subsystem entry and drained once
  at exit.

The boundary between the two is object allocation per crossing. If
you cannot remove it, you are in the snapshot regime and your
per-call budget is small. If you can pass everything as flat
pre-allocated buffers, per-call JNI cost effectively disappears.

**Consequence for port selection:** the original 200-500 ns figure
ruled out some targets that are actually viable in the direct-buffer
regime. Before shelving a port on per-call cost, verify which regime
the call shape actually sits in. The redstone BFS port shipped
default-on in 0.4.0-alpha because Phase 1 measured its crossing at
the cheap end, not the worldgen snapshot end. The worldgen
conclusion is unchanged (the density-function case legitimately
needs snapshot-style state to cross) but the reasoning no longer
generalizes to every candidate port.

## What blocked completion

### Vanilla doesn't expose finalDensity as a single corner buffer

`InterpolatorDiagnosticMixin` inspected `ChunkNoiseSampler.interpolators`
via `@Accessor`. Found 8 interpolators — NOT one for `finalDensity`. Each
holds a rotating `[2][49]` corner buffer for one sub-function of
finalDensity's tree:

| Index | Delegate | Role |
|---|---|---|
| [0] | BlendDensity | world-edge blending |
| [1] | RangeChoice (YClampedGradient → Noise) | terrain noise, full Y range |
| [2] | RangeChoice (YClampedGradient → LinearOperation) | scaled barrier/fluid noise |
| [3–4] | RangeChoice (YClampedGradient → Noise) | terrain noise, full Y range |
| [5–7] | RangeChoice (YClampedGradient → Noise) | cave-layer noise, Y ≤ 51 |

### Assembling the full corner grid requires a state machine

Each interpolator's `[2][49]` buffer holds 2 Z-corners × 49 Y-corners at a
time. Vanilla rotates through cellZ during generation, overwriting the
buffer each step. Assembling the full 5×5×49 corner grid requires
**~25 snapshots per interpolator × 7 terrain interpolators = ~175 capture
points per chunk**, plus state tracking and reassembly.

### Full port requires porting the density composition tree

Even with all corner buffers captured, `finalDensity` is a tree that
combines the 7 interpolator outputs through LinearOperation, add,
multiply, clamp, and other operations. Reimplementing this composition
correctly in Rust requires:

- Data-driven interpretation of the vanilla JSON density-function graph, OR
- Hand-porting each composition operator with a versioned Rust mirror of
  the overworld noise router

Either path is weeks of work and breaks on MC version changes that modify
the density function graph or ChunkNoiseSampler internals (~1–2 releases
per year).

**Scope:** 2–4 weeks of careful engineering, high version fragility.
Equivalent to building a C2ME-class mod from scratch.

## Sample vs Rust vs vanilla — A/B results

Actually running the bulk handoff in "run alongside, discard result" mode:

| Pipeline stage | Cost |
|---|---|
| Java-side corner sampling (1225 × finalDensity.sample) | ~30–37 ms/chunk |
| Rust compute | ~2.5 ms/chunk |
| **Total with resampling** | **~37 ms/chunk** |
| Vanilla clean baseline | ~17 ms/chunk |

The `DensityFunction.fill(double[], EachApplier)` batch API was also
tested (Path A'). It reduced corner sampling from ~37 ms to ~30 ms
(20 %), but not enough to beat vanilla — density function wrapping
layers don't override `fill` aggressively.

**Conclusion:** any path that re-samples finalDensity ourselves loses to
vanilla. Only direct buffer extraction + composition-tree port wins, and
that is a multi-week project.

## What the ferrite branch provides

### Proven bulk handoff pattern

`rust/mod/src/terrain.rs` + `src/main/java/me/apika/apikaprobe/TerrainBulkHandoff.java`
demonstrate the full JNI handoff end to end:

- Java allocates a direct float buffer for input, direct short buffer for output
- One JNI call transfers work to Rust
- Rust uses Rayon for per-column parallelism and returns block IDs
- Timing instrumentation separates Java-side cost from Rust-side cost

Reusable for any chunk-level compute task that can take
(pre-computed input) → (output buffer).

### Instrumentation framework

Four monitors that follow the same pattern (atomic accumulators,
server-tick periodic reporter with per-window reset):

- `TpsMonitor` — real tick duration (START+END events, not interval)
- `ChunkGenMonitor` — noise and surface phases with thread-local start
  times
- `NoiseStageMonitor` — sub-phases inside noise-sync with live cross-
  monitor reads
- `AquiferMonitor` — sampled (1-in-N) timing for hot per-block functions

Reusable for profiling any MC hotspot — same pattern, different
`@Inject` targets.

### Clean foundation for non-worldgen targets

The `ferrite` branch keeps only the JNI scaffolding, the Rayon engine, and
the instrumentation library. No worldgen-specific code. Suitable for:

- Compute-heavy features that don't sit inside vanilla's noise router
  (custom structures, post-gen passes, physics simulations)
- Event-driven mods where Rust computes on demand rather than in every
  chunk
- Research on other MC bottlenecks using the instrumentation framework

## Recommendation

**Stop Rust worldgen here.** The investigation is complete. The
architecture decision is documented. Continuing into the density tree
port is a new project, not a continuation of this one.

The `ferrite` branch is sealed as a working foundation. Future work on
different targets can build on it without repeating the investigation.

---

## Instrumentation lessons learned

### High-call-count timing self-contamination

Outer-envelope timing (one HEAD/RETURN pair per chunk or tick) is safe —
overhead is negligible relative to the measured work.

Inner-loop timing (tens of thousands of calls per chunk) is not safe.
`System.nanoTime()` costs ~20–50ns on Windows. At 65K calls/chunk × 4
nanoTime calls = 5–13ms of pure overhead per chunk. The measurement
dominates the signal.

**Rule:** any hook that fires more than ~1000 times per chunk needs either:
- Sampling (1-in-N calls, same pattern as AquiferMonitor)
- Count-only (no per-call timing, just increment an AtomicLong)
- Outer-envelope timing on the method that calls the hot loop

SurfacePhaseMixin was disabled after this was discovered — tryApply and
blockRead hooks fired 16K–65K times per chunk, adding ~7ms of overhead
and inflating buildSurface from ~5ms baseline to ~12ms measured.
The port verdict (tryApply at 25%, not a Rust candidate) still holds
since even halving the measured cost doesn't change the conclusion.

### Low-call-count @Inject deoptimizing the calling method

A BEFORE+AFTER @Inject pair on a hot method can add significant overhead
even when the hook fires rarely — not from nanoTime cost but from JIT
deoptimization of the surrounding call site.

ChunkManager.tick hook fired ~60 times/sec (once per world per tick).
nanoTime overhead alone: ~60 × 100ns = ~6µs/sec — negligible.
Actual measured overhead: ~1.1ms/tick = ~22ms/sec — 3600× more than
nanoTime alone.

Most likely cause: @Inject splits the hot ServerWorld.tick body at the
call site, preventing JIT from inlining ChunkManager.tick into the
surrounding loop. The deoptimization cost scales with how hot the
calling method is, not how often the hook fires.

**Rule:** for methods called inside hot loops, prefer:
- Computed-other buckets (measure the envelope, subtract known phases)
- Single HEAD inject only (RETURN/AFTER adds a second split point)
- Avoid BEFORE+AFTER pairs on any method called inside a tick loop

The chunkTick cost (~3.4ms) is still recoverable as `other =
total - scheduledTicks - entities - blockEntities`.
