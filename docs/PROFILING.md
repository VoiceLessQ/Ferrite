# Profiling investigation — Rust worldgen on vanilla overworld

This document captures the full measurement and architecture investigation
performed on the `ferrite` branch. The goal was to determine whether Rust
could meaningfully accelerate vanilla Minecraft 1.21.11 chunk generation
under low-end hardware constraints.

**Outcome:** viable in principle, but completing it is a multi-week
project equivalent in scope to C2ME. The PoC is complete and sealed —
further work is a different project, not a continuation.

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
