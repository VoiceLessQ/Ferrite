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
