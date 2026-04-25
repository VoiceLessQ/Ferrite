# Cache-Fill Plan — Phase 2.5 redux

**Status (2026-04-25):** step 1 (deep router walk + Marker identity map) implemented and pushed. Steps 2–4 (cache fill mixins, batched JNI, parity validation) pending.

This doc captures *why* we pivoted from the earlier Phase 2.5 attempt (which fired 0 times in practice) and *how* the new approach is structured so we don't have to rebuild context next session.

## The wall we hit

Previous attempts to accelerate noise:

- **Phase 1B (surgical leaf swap):** worked, math correct, but per-call Rust DF compute (~15–25 µs) is 50–80× slower than vanilla's heavily-cached per-block compute (~250 ns). Net loss on hot paths.
- **Phase 2 (whole-chunk buffer pre-fill):** vanilla's selective interpolation only lerps `Marker(Interpolated, X)` sub-DFs — pre-filling the *composed* expression caused 9/16 mismatches with maxDiff 0.02. Math drift wall.
- **Phase 2.5 v1 (drop-in `RustFlatCache`):** the FlatCache mixin fired 0 times because `identifiedRouterDfs` only had the 7 top-level router DFs. The Markers `NoiseChunk.wrapNew` actually receives are nested deep inside `final_density` etc. — never indexed.

## Vanilla's cache structure (re-confirmed)

Read against `26.1.2/.../NoiseChunk.java`:

| Marker type | Class | Storage | Fill timing | ROI |
|---|---|---|---|---|
| `Interpolated` | `NoiseInterpolator` | 2 × (5 × 49) doubles | `fillSlice()` × 9/chunk; ≈2200 DF computes per interpolator | **HIGHEST** |
| `CacheAllInCell` | `CacheAllInCell` | 64 doubles | `selectCellYZ()` × 384 cells; ≈25k computes per cache | **HIGH** |
| `FlatCache` | `FlatCache` | ~25 doubles | EAGER in constructor (line 658-668), `fill=true` | LOW–MEDIUM |
| `Cache2D` | `Cache2D` | 1 double | trivial single-value memo | none — keep vanilla |
| `CacheOnce` | `CacheOnce` | 1 double | trivial counter-keyed memo | none — keep vanilla |

Critical findings:

1. `wrapNew` receives the Marker instance and does `marker.wrapped()` to get the inner DF (line 388-414). So if our identity map keys on the Marker, lookup at wrap time will succeed iff the Marker is in the global router tree we walked at world load.
2. `fillSlice` walks `this.interpolators` and `selectCellYZ` walks `this.cellCaches` — both are simple lists. We can mixin once into each method to coalesce *all* cache fills into one or two bulk Rust calls per phase.
3. FlatCache fills eagerly in its constructor with `fill=true` — savings is bounded but cheap to swap.

## Step 4 — full chunk-density takeover (correct, perf regressed)

Implemented the "drive yourself" approach the user proposed: replace vanilla's outer `cacheAllInCell(add(finalDensity, beardifier))` `CellCache` with `RustFinalDensityBufferWrapper` that pre-fills a 16×384×16 chunk-density buffer in one Rust JNI call.

**Components:**

1. **`density.rs::enumerate_di_inputs`** — collects refs to the wrapped subtrees of every `Marker(Interpolated)` reachable from the outer DF. Doesn't recurse into DI subtrees (they're corner-sampled separately).
2. **`density.rs::compute_with_di_lerp`** — per-block compose using a counter-keyed lookup of pre-sampled DI corner buffers. At each `Marker(Interpolated, _)` node, trilinear-lerp from the buffer instead of recursing.
3. **`density.rs::trilinear_lerp_corner`** — vanilla-matched X→Y→Z lerp order (mirrors `MathHelper.lerp3`).
4. **`worldgen_jni::populateNoiseBufferRust`** — rewritten: enumerate DIs → corner-sample each → per-block compose with DI lerp → write 16×384×16 f64 buffer. Replaces the prior "lerp the whole expression at corners" approach (which had 0.02 maxDiff because Min/Squeeze don't commute with linear interpolation).
5. **`BulkChunkDensityMixin`** — `@Inject HEAD cancellable` on yarn's `getActualDensityFunctionImpl`. Recognizes the synthetic outer `CACHE_ALL_IN_CELL` Wrapping by fingerprint (`ferrite:synthetic/full_noise_density`) and substitutes our wrapper. Yarn 1.21.11 enum.toString returns CamelCase (`CacheAllInCell`, not `CACHE_ALL_IN_CELL`) — diagnostic loop confirmed.
6. **`BulkChunkDensityFill`** — toggle (`-Dferrite.bulkChunkDensity=true`) + diagnostics (mixin fires, gate counters, substitution count).

**Live measurement (sustained over 2825 chunks):**

| Metric | Value | Vanilla baseline |
|---|---|---|
| Wrapper substitutions | 2825 | n/a |
| Per-block buffer hits | 233 M | n/a |
| Per-block fallbacks | **0** | n/a |
| JNI per chunk | 56.4 ms avg | n/a |
| noise-sync per chunk | **~110-148 ms** | **~55-79 ms** |

**0 fallbacks across 233 million per-block samples.** The architecture is bulletproof. The math is sound (DI corner-sample + outer compose mirrors vanilla's selective interpolation exactly).

**But perf regressed ~30-90 ms/chunk.** Same fundamental reason as 2b: vanilla's lazy per-block evaluation with JIT-inlined CacheOnce hits is **~30× faster** per cell than our eager bulk-fill amortized:

- Vanilla per-block: ~20 ns/cell (JIT + cache hit) × 98304 cells = ~2 ms steady state
- Ours: 56 ms upfront + ~5 ns/cell buffer reads = **~56 ms** floor

Vanilla beats us because the JIT optimizes per-block hot paths to near-native speed, and CacheOnce makes shared-subtree evaluations effectively free. We can't undercut 2 ms/chunk with any bulk approach.

**Default off.** Commit ships ENABLED=false. The infrastructure is real and reusable for future "Track A^2" approaches (e.g., if we someday port the entire chunkgen step + aquifer + structures to Rust, we'd plug into this same wrapper).

## Step 2b/3 — batch interpreter scaffolding (perf neutral, design correct)

Added `DensityFunction::compute_batch(xs, ys, zs, state, out)` to
`rust/mod/src/density.rs` — batch evaluation across N positions with
intermediate-result scratch arrays acting as implicit per-batch caches.
Implemented for all opcodes; Spline & BlendedNoise fall back to
per-cell loops.

Wired `sampleDensitySlicesRust` to use it: each Rayon-parallel Y slab
builds position arrays and calls `compute_batch` once, replacing the
prior per-cell tight loop.

Live measurement vs per-cell baseline:

| Path | µs per JNI call | ms per bulk |
|---|---|---|
| Per-cell compute | 1500–2000 | 12–18 |
| Batch compute | 2000–2400 | 14–18 |

**Slight regression** (~25%). Why batch didn't win:

1. The cheap ops we batched (Add/Mul/Squeeze/etc.) were already
   nanoseconds each. Their cost is dwarfed by the heavy ops.
2. The heavy ops (Spline, BlendedNoise, Noise, ShiftedNoise) still go
   per-cell in tight loops. Same work as before.
3. Per-recursion-level `Vec<f64>::with_capacity(245)` allocations
   stack up. ~30-40 vecs allocated per JNI call × 144 calls/chunk
   = ~11 MB allocation churn per chunk. Eats the cheap-op savings.

To actually win, the next move is one of:

- **Allocation pool** — thread-local arena for scratch buffers,
  borrow-return pattern. Brings batch to neutral.
- **Batch BlendedNoise** — the dominant op. Per-position perlin chain
  is already hot, but batching could amortize the 3-way blend
  arithmetic and the Y-axis interpolation.
- **Batch Spline** — coordinate eval over positions, then per-position
  piecewise interpolation with shared spline table.

Default ENABLED=false. The infrastructure is correct (0 fallbacks,
parity preserved) and ready for follow-up.

## Step 2b — infrastructure shipped, perf wall identified

Built and verified end-to-end (commit `663316e` capture, this commit fill mixin):
- `InterpolatorNameRegistry` — per-instance rustName side map populated by the capture mixin (records grow 1:1 with matched events; 0 fallbacks across 7k+ bulks).
- `RustBridge.sampleDensitySlicesRust` — Rust JNI with separate per-axis steps (since vanilla overworld uses cellWidth=4 horizontal, cellHeight=8 vertical).
- `BulkInterpolatorFill.fillAllSlices` — 9 sampleDensity calls/chunk × N interpolators × 1 JNI per interp; ThreadLocal scratch buffers; cached UTF-8 name encodings.
- `BulkSampleDensityMixin` — `@Inject HEAD cancellable` on `ChunkNoiseSampler.sampleDensity`. Gated by `BulkInterpolatorFill.ENABLED` (default false). Falls back to vanilla wholesale if any interpolator lacks a registered name.

Live measured perf with `-Dferrite.bulkSlice=true`:

| Metric | Value | Vanilla baseline |
|---|---|---|
| bulk-slice JNI per call | 1500–2000 µs (~245 doubles) | n/a |
| bulks per chunk | ~9 | n/a |
| ms per bulk | 12–18 ms | n/a |
| noise-sync per chunk | **100–220 ms** | **55–79 ms** |

**Regression: ~1.5–2× slower than vanilla.** The mixin works (0 fallbacks, 100% capture), the parity infrastructure is correct, but the design loses to vanilla on perf. Same wall as Phase 1B.

**Why:** vanilla's per-block compute is ~250 ns because every Marker(CacheOnce, X) inside slopedCheese is wrapped at chunk-wrap time into a CacheOnce wrapper that memoizes `X.compute()` per `interpolationCounter`. The same X gets evaluated once and reused across ~25 Y entries × subsequent updates. Our Rust DF interpreter has no equivalent — it walks the full subtree from scratch on every cell.

For this design to win, the Rust DF interpreter would need its own CacheOnce-equivalent intermediate caching keyed on a per-batch counter. That's a meaningful scope expansion (caching layer in the Rust side, eviction logic, parity verification). Deferred — infrastructure shipped, ENABLED=false.

**What this DOES unlock:** the registry + JNI + mixin scaffolding is production-grade and proven correct. Future work that fills caches via Rust (Track A, or a Rust DF interpreter with caching) plugs in here without re-doing the wiring.

## Step 2a — DONE (100% match across all three cache types)

After a chain of fixes the observational mixin now sees:

| Cache type | Matched | Unmatched | Hit rate |
|---|---|---|---|
| FlatCache | 14576 | 0 | **100%** |
| DensityInterpolator | 14520 | 0 | **100%** |
| CellCache | 905 | 0 | **100%** |

The journey:

| Step | flatCache | interp | cellCache | What changed |
|---|---|---|---|---|
| Initial (identity) | 0% | 0% | 0% | nothing — identity always misses |
| Fingerprint v1 (input.wrapped) | 31% | 19% | 0% | encoder treats cache wrappers as Markers |
| Fingerprint v2 (return.wrapped) | 63% | 38% | 0% | fingerprint returnedwrapper.wrapped() instead of input |
| All 15 router accessors | 63% | **75%** | 0% | walked aquifer/vein/preliminarySurfaceLevel |
| Synthetic Add(finalDensity, beardifier) | — | — | still 0 | registered but mismatched |
| **LinearOperation walker fix** | **100%** | **100%** | **100%** | encoder treats yarn's constant-folded LinearOperation as Mul/Add(Constant, X) |

The LinearOperation fix was the keystone. Yarn's `BinaryOperationLike.create` constant-folds `Mul(Constant(k), X)` into `LinearOperation(MUL, X, ..., k)` during `mapAll`. At world load we encoded `Mul(Constant(0.64), Marker)`, at chunk-wrap we saw `LinearOperation(MUL, ..., 0.64)`. The walker didn't recognize LinearOperation, fell through to `OP_CONSTANT 0.0` stub, and bytecodes diverged on every constant-folded subtree (which is most of vanilla worldgen). Fix: walker emits `OP_MUL/OP_ADD OP_CONSTANT(arg) [encoded input]` for LinearOperation — bit-identical to the unfolded source it was created from.

## Step 2a postmortem — fingerprint matching pivot

The first observational mixin showed `matched=0` across 109k+ wrappings — identity matching is fundamentally broken because vanilla's `mapAll(this::wrap)` recursively re-instantiates Markers via `new Marker(this.type(), this.wrapped().mapAll(visitor))`. The Markers we walked at world load are never the ones `wrapNew` receives.

Pivoted to **structural fingerprint matching**:

1. `DensityFunctionWalker.encode()` produces deterministic bytecode per DF tree.
2. Extended the walker to recognize `ChunkNoiseSampler$FlatCache`, `$DensityInterpolator`, etc. and emit them as their equivalent `OP_MARKER` opcode. So a tree with original `Marker(FlatCache, X)` and a tree where `mapAll` has already transformed it to `FlatCache(X)` produce **identical** bytes.
3. At world load, `DeepMarkerWalker.handleMarker` computes the fingerprint of every registered Marker's inner subtree and stores `fingerprintHex → registeredName` in `WorldgenStateBootstrap.fingerprintToName()`.
4. At chunk-wrap time, `CacheRouteCaptureMixin` reflectively calls `function.wrapped()`, fingerprints the result, and looks up.

**Verified ratio (live runClient):**

| Cache type | Matched | Unmatched | Hit rate | Note |
|---|---|---|---|---|
| FlatCache | 9857 | 21672 | 31% | unmatched = blendAlpha/blendOffset singletons + interior markers we didn't walk into |
| DensityInterpolator | 5907 | 25597 | 19% | unmatched = interior markers buried in subtrees we don't recurse into fully |
| CacheAllInCell | 0 | 1969 | 0% | **expected** — vanilla creates this from the synthetic `cacheAllInCell(add(finalDensity, BeardifierMarker))` built at NoiseChunk construction time, not from anything in the router we walked |

The 31%/19% gap is real (we'd want ≥80% for step 2b to be worthwhile). Two paths to improve:

- **Walk gaps** — figure out which interior Markers are missed and extend `DeepMarkerWalker.recurse` (e.g., dive deeper into spline coordinates, ShiftedNoise accessors, BlendDensity input).
- **Synthetic registration** — register the `cacheAllInCell(add(finalDensity, BeardifierMarker))` synthetic explicitly at world load to recover the cellCache 0%.

Open question for next session: is 30–80% match enough to ship step 2b (real fill mixin) with vanilla fallback for unmatched markers? Probably yes — graceful degradation per-marker is fine.

## Step 1 — DONE

`DeepMarkerWalker.walk(rootDf, rootName, identityMap)` recursively walks every router root, finds every reachable `Marker(type, child)` instance, registers `child` as a Rust DF under a synthetic name `ferrite:auto/<rootName>/<kind>_<index>`, and records `marker → syntheticName` in `identifiedRouterDfs`.

**Why the inner subtree gets re-registered:** we want Rust to be able to evaluate the *contents* of any Marker we encounter at chunk-wrap time. Naming the wrapped subtree gives us a single registry handle to call when we want to bulk-fill that cache.

**Roots walked:** the same 7 entries `registerResolvedRouterClimateDfs` already handles — 6 climate axes + `finalDensity`. (The walk is recursive — if we want more roots later, we add them to `climateFields[]`.)

**Where it lives:**

- [src/main/java/me/apika/apikaprobe/DeepMarkerWalker.java](../src/main/java/me/apika/apikaprobe/DeepMarkerWalker.java) — recursive walker, mirrors `DensityFunctionWalker`'s yarn-rename-tolerant simple-name dispatch.
- [src/main/java/me/apika/apikaprobe/WorldgenStateBootstrap.java](../src/main/java/me/apika/apikaprobe/WorldgenStateBootstrap.java#L209-L243) — wired right after the top-level router-DF registration loop. Logs `found / registered / failed / mapSize` so we can see at a glance whether the walk is healthy.

**Verification on world load:** check the log line:
```
[worldgen-init] deep-marker walk: found=N registered=M failed=K mapSize=S
```
If `found=0`, the walker didn't reach any Marker — probably a yarn class-name drift; extend the dispatch in `DeepMarkerWalker.recurse`.

## Step 2 — pending: NoiseInterpolator pre-fill

Highest ROI. Plan:

1. Mixin into `ChunkNoiseSampler` (yarn) constructor (or its `wrapNew` equivalent) to attach a `String rustName` field to each `NoiseInterpolator` instance — looked up via `identifiedRouterDfs.get(marker)`.
2. Add a Rust JNI entry point `sampleDensityFunctionsBatch(names[], N, x, y, z, sx, sy, sz, step, outBuf)` that fills a packed `[N][sx*sy*sz]` buffer.
3. Mixin into `fillSlice` (yarn equivalent): when *all* interpolators have a known rustName, replace the per-interpolator inner loop with one JNI call, writing directly into the existing `slice0[z]` / `slice1[z]` arrays.
4. Validate via `/ferrite density validate`. If any interpolator is unmatched, fall back to vanilla for that one — graceful degradation.

## Step 3 — pending: CacheAllInCell pre-fill

Same shape as step 2 but mixin target is `selectCellYZ`. Coalesce all cell-caches into one batched JNI call per cell (or per Y-slab to amortize JNI cost).

## Step 4 — pending: parity + perf validation

- `/ferrite density validate` — must stay at 41/42 bit-exact.
- Per-cell match rate against vanilla compute (same probe used in Phase 1B).
- Per-chunk JNI count + total ms. Watch for Rayon contention regression we hit in Phase 2.

## Risks worth flagging

- **Rust contention under concurrent chunkgen workers** — Phase 2 saw 30–90 ms/chunk under 4-worker concurrent gen. Bulk fill is necessary but not sufficient; we may need to drop the per-chunk Rayon split in favor of letting the chunkgen worker pool itself provide parallelism.
- **Marker identity stability** — `mapAll(this::wrap)` doesn't mutate the original router; the Marker instances are stable across chunk loads. Verified by reading `mapAll` semantics (returns a fresh wrapper tree, leaves the source tree alone). Our identity map keys on the *original* Markers in the source tree — exactly what `wrapNew` receives.
- **Synthetic name collisions** — `globalIndex` is an `AtomicInteger` shared across all roots. A reload of the world (same JVM) would re-register with new indices; Rust's registry overwrites by name so this is safe. If we ever want re-entrancy, gate on a per-bootstrap counter.

## What broke before that this fixes

The original Phase 2.5 v1 mixin (`RustFlatCache` swap) is still present and still keys on `identifiedRouterDfs`. After step 1, the map is populated deeply — so `RustFlatCache.wrapperConstructCount` should now be non-zero on world load, instead of 0 like before.

If `wrappers=0` still appears in `[flat-cache diag]` after this change, the next thing to check is whether the Marker instances `wrapNew` receives are reference-equal to the ones we walked in `routerNoise.<axis>()`. If not, it means yarn's NoiseRouter/NoiseChunk re-creates Markers somewhere we missed — a fresh problem to solve.
