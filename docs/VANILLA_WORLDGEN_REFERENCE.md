# Vanilla 1.21.11 Worldgen Reference

Single-source reference for everything seed / PRNG / noise / math
related in vanilla `26.1.2/server/`. Built so future Rust ports can
look up "what's vanilla doing here" in one doc instead of digging the
source each time.

**Maintenance:** when you finish a port (move a row from "not ported"
to "ported"), update this doc's TL;DR table and add a note to the
relevant section. When 1.21.x → 1.22 lands and signatures shift, this
doc needs a re-audit pass — the file paths are stable but yarn names
and method signatures will move.

**Companion docs:**
- `SEED_DRIVEN_DISPATCH.md` — the design philosophy these ports serve
- `SURFACE_RULE_STATUS.md` — current dispatcher status
- `JOURNEY.md` — broader cross-port retrospective

---

## TL;DR table

Components Track B needs, in dependency order. "Ported" means
present in `rust/mod/src/` with parity tests. "Difficulty" reflects
algorithm complexity, not yarn-rename friction.

| # | Vanilla class (mojmap) | Yarn name | Status | Effort | Notes |
|---|---|---|---|---|---|
| 1 | `Mth.getSeed` | same | ✅ ported | — | `xoroshiro.rs::get_seed` |
| 2 | `RandomSupport.mixStafford13` etc | same | ✅ ported | — | `xoroshiro.rs::mix_stafford_13` etc |
| 3 | `Xoroshiro128PlusPlus` | `Xoroshiro128PlusPlusRandomImpl` | ✅ ported | — | `xoroshiro.rs::Xoroshiro128PlusPlus` |
| 4 | `XoroshiroRandomSource` | `Xoroshiro128PlusPlusRandom` | ✅ ported (subset) | — | next_long/int/float/double/bool/bits ✓; nextGaussian skipped |
| 5 | `XoroshiroPositionalRandomFactory` | `Xoroshiro128PlusPlusRandom$Splitter` | ✅ ported | — | `xoroshiro.rs::XoroshiroPositionalRandomFactory` |
| 6 | `LegacyRandomSource` (LCG) | `LocalRandom` / `CheckedRandom` | ❌ not ported | EASY (~50L) | Older `java.util.Random`-style; needed for legacy worldgen paths only |
| 7 | `MarsagliaPolarGaussian` | similar | ❌ not ported | EASY (~30L) | Box-Muller polar; not on critical path |
| 8 | `WorldgenRandom` | `ChunkRandom` | ❌ not ported | EASY (~80L) | Wraps RandomSource for feature/structure decoration salts |
| 9 | `ImprovedNoise` (single Perlin) | `PerlinNoiseSampler` | ❌ not ported | **MEDIUM (~150L)** | Permutation table + 8-corner gradient + smoothstep + trilinear |
| 10 | `PerlinNoise` (multi-octave) | `OctavePerlinNoiseSampler` | ❌ not ported | **MEDIUM (~200L)** | FBM over `ImprovedNoise[]`; lazy octave seeding |
| 11 | `NormalNoise` (double Perlin) | `DoublePerlinNoiseSampler` | ❌ not ported | **EASY (~80L)** | Composes 2× PerlinNoise w/ frequency offset |
| 12 | `Noises` (registry) | `BuiltinNoises` | ❌ not ported | EASY (~80L) | Registry of named NoiseParameters; ~50 noise key constants |
| 13 | `RandomState` | `NoiseConfig` | ❌ not ported | **HARD (~150L)** | Per-world cache; wires noises + density tree + biome sampler |
| 14 | `Climate` (`Sampler`, `TargetPoint`, `Parameter`, `RTree`) | same | ❌ not ported | **HARD (~500L)** | 6D climate-vector → biome via R-tree. Self-contained; long but math-only |
| 15 | `MultiNoiseBiomeSource` | same | ❌ not ported | MEDIUM (~100L) | Thin wrapper over `Climate.ParameterList`; needs Climate first |
| 16 | `DensityFunction` machinery | same | ⏭ skip for now | HARD (weeks) | Visitor pattern + ~50 impl classes; needed for full chunkgen replacement, not for surface dispatcher |

**Grand total to unlock surface dispatcher Track B:** 9, 10, 11, 12,
13 → ~660 lines + tests. About **3-5 sessions** at the pace of the
Xoroshiro port (one component per session, with parity test against
vanilla via the existing validator infra).

To also unlock biome lookup (and thereby drop the per-Y biome
reflection that's the current dispatcher bottleneck): add 14, 15 →
~600 more lines. ~3 more sessions.

---

## Foundation (already in `xoroshiro.rs`)

Confirming what's there so future-us doesn't re-port:

```
pub struct Xoroshiro128PlusPlus { seed_lo, seed_hi }
  - new(lo, hi) — zero-zero replaced by GOLDEN+SILVER
  - next_long() — bit-exact with vanilla

pub struct XoroshiroRandomSource { generator }
  - from_legacy_seed(i64) — applies upgrade_seed_to_128bit
  - from_pair(lo, hi)
  - next_long, next_int, next_boolean, next_bits, next_float, next_double

pub struct XoroshiroPositionalRandomFactory { seed_lo, seed_hi }
  - new(lo, hi)
  - at(x, y, z) — applies Mth::get_seed + xor with seed_lo

helpers:
  - mix_stafford_13(i64) → i64
  - upgrade_seed_to_128bit_unmixed(i64) → (i64, i64)
  - upgrade_seed_to_128bit(i64) → (i64, i64)
  - get_seed(i32, i32, i32) → i64

constants:
  - GOLDEN_RATIO_64, SILVER_RATIO_64
  - FLOAT_UNIT_F32, DOUBLE_UNIT_F32
```

11/11 unit tests including a hand-traced first-call value matching
vanilla's algorithm exactly. Wired into Rust's `OP_VERT_GRADIENT`
(commit `297f053`); validator measures Java=Rust at 100%.

**Not yet ported from `XoroshiroRandomSource`:**
- `next_gaussian()` — needs `MarsagliaPolarGaussian` port
- `consume_count(int)` — trivial loop, add when needed
- `fork()` / `fork_positional()` — needs to consume two `next_long()`s; add when WorldgenRandom port arrives

---

## PRNG family — what's there

### Hierarchy

```
RandomSource (interface, util/)
├─ BitRandomSource (interface, levelgen/) — adds next(bits)
│   └─ LegacyRandomSource (LCG, 48-bit Java-Random-equivalent)
├─ XoroshiroRandomSource (the modern one, used everywhere in 1.18+)
└─ ThreadSafeLegacyRandomSource (deprecated wrapper)
```

```
PositionalRandomFactory (interface)
├─ XoroshiroPositionalRandomFactory  (nested in XoroshiroRandomSource)
└─ LegacyPositionalRandomFactory     (nested in LegacyRandomSource)
```

### What needs porting eventually

**`LegacyRandomSource`** (`levelgen/LegacyRandomSource.java`)
- Java's classic 48-bit LCG: `seed = (seed * 25214903917 + 11) & ((1<<48)-1)`
- Used by some legacy structure placement and feature-decoration paths
- Yarn calls this `LocalRandom` (single-threaded) or `CheckedRandom`
  (throws on concurrent use)
- **Port when:** structure-placement port lands and we need the legacy
  PRNG for vanilla-parity on older worldgen
- **Effort:** EASY, ~50 lines + LegacyPositionalRandomFactory

**`MarsagliaPolarGaussian`** (`levelgen/MarsagliaPolarGaussian.java`)
- Box-Muller polar method for Gaussian samples
- State: caches a "next" Gaussian (the method generates pairs)
- Used by `XoroshiroRandomSource.nextGaussian()` only
- **Port when:** something downstream needs `nextGaussian` — currently
  nothing on Ferrite's roadmap does
- **Effort:** EASY, ~30 lines

**`WorldgenRandom`** (`levelgen/WorldgenRandom.java`)
- Wraps a `RandomSource` for feature/structure placement
- Key method: `setDecorationSeed(seed, chunkX, chunkZ)`:
  ```
  setSeed(seed)
  xScale = nextLong() | 1L  // ensure odd
  zScale = nextLong() | 1L
  result = chunkX * xScale + chunkZ * zScale ^ seed
  setSeed(result)
  ```
- Plus `setFeatureSeed`, `setLargeFeatureSeed`, `setLargeFeatureWithSalt`
- **Port when:** structure placement work begins
- **Effort:** EASY, ~80 lines

---

## Noise stack — the next porting target

### Algorithm summary

Vanilla's noise is **3D gradient (classical Perlin)**, NOT Simplex
despite the file naming. The stack:

```
NormalNoise          (double Perlin: composes 2× OctavePerlin)
├─ PerlinNoise       (multi-octave: FBM over N× ImprovedNoise)
│   └─ ImprovedNoise (single octave: gradient + smoothstep + trilinear)
└─ PerlinNoise       (the "second" instance with 1.0181... freq offset)
```

For surface rules and biome climate noise, the entry point is
`NormalNoise` — that's what `RandomState.getOrCreateNoise(key)`
returns and what `OP_NOISE_THRESH` consumes.

### `ImprovedNoise` — single-octave Perlin

`levelgen/synth/ImprovedNoise.java`. Yarn: `PerlinNoiseSampler`.

**State:**
- `byte[256] p` — permutation table, Fisher-Yates shuffled at init
  using the random's `nextInt(256 - i)` as we walk down
- `double xo, yo, zo` — random domain offsets in `[0, 256)`,
  initialized via `random.nextDouble() * 256`

**`noise(x, y, z)` algorithm:**
```
1. Add offsets:           x += xo; y += yo; z += zo
2. Floor to grid cell:    xf = floor(x); yf = floor(y); zf = floor(z)
3. Fractional remainder:  xr = x - xf;   yr = y - yf;   zr = z - zf
4. Smooth fractional:     u = smoothstep(xr); v = smoothstep(yr); w = smoothstep(zr)
                          (smoothstep(t) = t³(t(6t-15)+10))
5. Hash 8 grid corners via permutation table
6. Compute gradient·position dot product at each corner (16 fixed
   gradient vectors; corner picks one via permutation hash)
7. Trilinear interpolate the 8 dot products with (u, v, w)
8. Return scalar in approximately [-1, 1]
```

**Subtleties:**
- Permutation table is 256 entries, indexed mod 256 — wrap is implicit
- The 16 gradient vectors are hardcoded (mostly axis-aligned, plus
  some diagonals)
- There's an alternate `noise(x, y, z, yScale, yFudge)` for legacy
  terrain — can skip; surface/biome noise uses the simple form

**Effort:** MEDIUM. Permutation init + the hash chain are the fiddly
parts; the math itself is straightforward. ~150 Rust lines including
gradient table. **Test it by:** seed a known Java instance, compare
`noise(x, y, z)` outputs at 100 random positions. Both must match
bit-exact (or to the f64 precision of the algorithm).

### `PerlinNoise` — multi-octave (FBM)

`levelgen/synth/PerlinNoise.java`. Yarn: `OctavePerlinNoiseSampler`.

**State:**
- `ImprovedNoise[] noiseLevels` — array of single-octave Perlins,
  some entries may be `null` (octave skipped to save memory)
- `int firstOctave` — base octave index (negative for low-frequency)
- `DoubleList amplitudes` — per-octave weight (parallel to noiseLevels)
- `double lowestFreqInputFactor` = `2^(-firstOctave)`
- `double lowestFreqValueFactor` = `2^(n-1) / (2^n - 1)` where n is
  number of octaves — geometric-series amplitude normalization
- `double maxValue` — precomputed range bound

**Constructor:** Takes a `PositionalRandomFactory` (forked from the
world seed) and the octave config. For each octave i in [firstOctave,
firstOctave + amplitudes.size()):
- If `amplitudes[i] != 0`: instantiate `ImprovedNoise` with a
  `RandomSource` from `factory.fromHashOf("octave_" + octaveIndex)`
- Else: `noiseLevels[i] = null` (skipped)

This per-octave naming is why `RandomState` only needs the
`PositionalRandomFactory` — each noise's octaves derive their seeds
deterministically from the noise's name.

**`getValue(x, y, z)` algorithm:**
```
value = 0
inputFactor = lowestFreqInputFactor
valueFactor = lowestFreqValueFactor
for each octave i:
  if noiseLevels[i] != null:
    value += amplitudes[i] * noiseLevels[i].noise(
                wrap(x*inputFactor), wrap(y*inputFactor), wrap(z*inputFactor)
             ) * valueFactor
  inputFactor *= 2
  valueFactor /= 2
return value
```

`wrap(d)` periodically wraps to ±2^25 to keep coords in float-safe
range (vanilla uses `d - lfloor(d/3.3554432e7 + 0.5) * 3.3554432e7`).

**Effort:** MEDIUM. Logic is simple; complexity comes from the
optional-octave handling and the wrap step. ~200 Rust lines. Depends
on `ImprovedNoise` (item 9).

### `NormalNoise` — double Perlin (the public API)

`levelgen/synth/NormalNoise.java`. Yarn: `DoublePerlinNoiseSampler`.

**State:**
- `PerlinNoise first, second` — two independent multi-octave instances
  with the same octave config
- `double valueFactor = 0.16666666666666666 / expectedDeviation(...)`
- `double maxValue`
- `NoiseParameters parameters` — config record

**Constructor:** Takes the config and a `PositionalRandomFactory`.
Creates `first` and `second` from forks of that factory (so they have
different per-octave seeds despite identical structure).

**`getValue(x, y, z)` algorithm:**
```
const INPUT_FACTOR = 1.0181268882175227
return (first.getValue(x, y, z)
      + second.getValue(x*INPUT_FACTOR, y*INPUT_FACTOR, z*INPUT_FACTOR))
     * valueFactor
```

The second instance is sampled at a slightly higher frequency. The
resulting sum has approximately Gaussian/normal distribution
properties — hence the name.

`expectedDeviation(octaveSpan)` = `0.1 * (1 + 1/(octaveSpan + 1))` —
statistical normalization constant.

**`NoiseParameters` record:** `(int firstOctave, DoubleList amplitudes)`.
Has a Codec for JSON deserialization (worldgen data files).

**Effort:** EASY once `PerlinNoise` lands. ~80 Rust lines. Mostly
composition.

### `Noises` — the registry

`levelgen/Noises.java`. Yarn: `BuiltinNoises`.

50+ static `ResourceKey<NoiseParameters>` constants:
- Climate: `TEMPERATURE`, `VEGETATION`, `CONTINENTALNESS`, `EROSION`,
  `TEMPERATURE_LARGE`, `VEGETATION_LARGE`
- Cave: `AQUIFER_BARRIER`, `AQUIFER_FLUID_LEVEL_FLOODEDNESS`,
  `CAVE_ENTRANCE`, etc
- Ore: `ORE_VEININESS`, `ORE_VEIN_A`, `ORE_VEIN_B`, `SPAGHETTI_*`
- Surface: `SURFACE`, `SURFACE_SECONDARY`, `BADLANDS_PILLAR`, `SWAMP`,
  `BEACH`, `STONE`, `RIDGE`, `BIOMASS_*`, etc
- Special: `TEMPERATURE_NETHER`, `VEGETATION_NETHER`

**Factory:** `instantiate(HolderGetter<NoiseParameters>, PositionalRandomFactory, ResourceKey)`:
```
Holder<NoiseParameters> holder = noises.getOrThrow(name)
return NormalNoise.create(factory.fromHashOf(name.location()),
                          holder.value())
```

The `fromHashOf(name)` call is the key seeding step — each noise
gets a deterministic per-name `RandomSource` derived from the world
seed via MD5 hash of the registry name string.

**Port plan:** Keep the 50+ noise keys as `&'static str` constants.
Loading the actual `NoiseParameters` (firstOctave + amplitudes) per
key requires reading vanilla's worldgen data JSON files at world load
— OR hardcoding them, since they're stable across versions. For
Ferrite's purposes, hardcoding is simpler — the validator can verify
parameters match vanilla's at install time.

**Effort:** EASY (~80 lines for keys + loader); the hard part is the
data, not the code.

---

## Per-world state — `RandomState` (yarn `NoiseConfig`)

`levelgen/RandomState.java`. **The orchestration layer.**

This is what we'd hold in a Rust `WorldgenState` — initialized once
at world load from the world seed, then queried per chunk.

**State:**
- `PositionalRandomFactory random` — root, derived from world seed
- `HolderGetter<NoiseParameters> noises` — registry reference
- `NoiseRouter router` — density function tree (we mostly skip this
  for surface dispatcher Track B; it's the chunk-noise/aquifer side)
- `Climate.Sampler sampler` — 6D climate evaluator
- `Map<ResourceKey, NormalNoise> noiseInstances` — lazy noise cache
  (thread-safe ConcurrentHashMap)
- `SurfaceSystem surfaceSystem` — surface rule evaluator (Java side
  stays — Rust just provides the noise state it queries)

**Construction flow** (the bit Rust needs to mirror):
```
RandomState.create(GeneratorSettings settings, Holders noises, long seed)
  random = settings.getRandomSource().newInstance(seed).forkPositional()
  // Sub-factories for different concerns:
  aquiferRandom = random.fromHashOf("aquifer")
  oreRandom     = random.fromHashOf("ore")
  // ...
  // Wire noises into router (lazy via NoiseHolder visitor)
  // Build Climate.Sampler from router's 6 density functions
  // Build SurfaceSystem
```

**`getOrCreateNoise(ResourceKey)`:** Thread-safe lazy lookup —
computes via `Noises.instantiate(noises, random, key)` on first call,
caches.

**For Ferrite's Rust port:**
- Hold a `OnceLock<WorldgenState>` for global init
- `WorldgenState::from_seed(seed)`:
  - Build root `XoroshiroPositionalRandomFactory` from seed
  - Pre-create the noises that surface rules will query (the ~7 in
    Ferrite's `noiseChannelTable`) — eager, since the set is known
- `state.sample_noise(name, x, y, z)` → `f64`
- Eventually: `state.sample_biome(x, y, z)` once Climate ports

**Effort:** HARD (~150 Rust lines). Most of the difficulty is
designing the right Rust API surface, not the math. Depends on
`NormalNoise` (item 11) being in place.

---

## Biome lookup — Climate + MultiNoiseBiomeSource

### `Climate` (`level/biome/Climate.java`)

The 6D climate sampler that turns a position into a biome via
nearest-parameter-vector lookup.

**Records:**
- `TargetPoint(long temperature, long humidity, long continentalness,
              long erosion, long depth, long weirdness)` — the
  query: 6 quantized longs derived from a position
- `Parameter(long min, long max)` — a 1D range with distance metric
- `ParameterPoint(Parameter temperature, ..., long offset)` — 7D box
  in climate space + offset (the offset is the 7th distance term)
- `ParameterList<T>` — list of `(ParameterPoint, T)` mappings + an
  R-tree index for O(log N) nearest-point lookup

**Quantization:** `long quantizeCoord(float c) = (long)(c * 10000)`
— 4 decimal places of precision encoded as i64.

**`Sampler`:** Holds 6 density functions (one per dimension). Method
`sample(quartX, quartY, quartZ)` evaluates all 6 at the position and
returns a `TargetPoint`. **This requires the density function
machinery** — full Track B biome lookup needs DensityFunction in
Rust too.

**`RTree<T>`:** 6-ary tree built recursively, balanced by dimension.
Each node has a 7D bounding box (the union of children's parameter
points). Search is DFS minimizing fitness = sum of squared distances
in 7D.

**Effort:** HARD (~500 lines). The R-tree is the bulk of it; the
quantization and parameter records are trivial. The R-tree is
self-contained pure math — can be ported in isolation and tested with
known inputs.

### `MultiNoiseBiomeSource` (`level/biome/MultiNoiseBiomeSource.java`)

Thin wrapper over `Climate.ParameterList<Holder<Biome>>`:

```
getNoiseBiome(quartX, quartY, quartZ, Climate.Sampler sampler):
  TargetPoint target = sampler.sample(quartX, quartY, quartZ)
  return parameters().findValue(target)
```

Most of the work is in `Climate.ParameterList.findValue` (RTree
search). MultiNoiseBiomeSource itself is ~100 Rust lines.

**Effort:** MEDIUM, depends on Climate (item 14).

### Why biome lookup is the dispatcher's holy grail

The current dispatcher's per-Y reflective biome+isCold reads cost
~25ms/chunk. If Rust holds the Climate.Sampler + MultiNoiseBiomeSource
state, it can compute biome from (x, y, z) directly during the batch
eval — Java doesn't need to read biome at all. That eliminates the
last per-position reflection in the dispatch path.

---

## Density function machinery — overview only

`level/levelgen/DensityFunction.java` and `DensityFunctions.java`.

Visitor-pattern AST representing chunk-noise computations. Each node
implements `compute(SinglePointContext)` returning `double`. Nodes
include:
- Leaves: `Constant`, `EndIslandsBlend`, `Noise` (wraps a NormalNoise),
  `WeirdScaledSampler` (transforms noise output)
- Composers: `Add`, `Mul`, `Min`, `Max`, `Cache2D`, `CacheOnce`,
  `FlatCache`, `Interpolated`, `Beardifier`, `Marker`
- Special: `BlendDensity`, `OldBlendedNoise` (the legacy main density
  function), various aquifer / cave functions

Used to compose:
- The 6 climate dimensions for `Climate.Sampler` (temperature,
  humidity, continentalness, erosion, depth, weirdness)
- The main `finalDensity` for chunk terrain shape
- Aquifer / fluid / barrier functions

**Skip for now.** The surface dispatcher Track B doesn't need this;
it queries `NormalNoise` directly. Density functions are needed when
we eventually port chunk-shape generation (the bigger project that
PROFILING.md shelved on snapshot-state cost — which becomes viable
once noise is in Rust).

---

## Where the seed lives & flows

```
User input
  ↓
WorldOptions.seed (long)
  ↓
ChunkGenerator.create() — captures the seed
  ↓
RandomState.create(genSettings, noiseRegistry, seed)
  ↓
  random = genSettings.getRandomSource().newInstance(seed).forkPositional()
                                          (Xoroshiro by default in 1.21)
  ↓
  Sub-factories forked: aquifer, ore, ...
  ↓
  Lazy noise cache: getOrCreateNoise(key) →
                    Noises.instantiate(registry, random, key) →
                    NormalNoise(random.fromHashOf(key.toString()), params)
  ↓
  Climate.Sampler built from router's density functions
  ↓
  SurfaceSystem built (Java side; queries the noises above)
  ↓
Per chunk: SurfaceSystem.buildSurface(...) → tryApply(x, y, z)
                                              ↓
                                              Reads Context.surfaceDepth
                                              (= surfaceNoise.getValue(x, 0, z))
                                              Reads Context.biome
                                              (= biomeManager.getBiome(pos))
                                              Reads Context.minSurfaceLevel
                                              (= preliminarySurfaceLevel + ...)
```

**For Rust to replicate at world load:**
```rust
RustBridge.initWorldgenState(seed: jlong)
  ↓
WORLD_STATE.set(WorldgenState::from_seed(seed))
  - root = XoroshiroPositionalRandomFactory::from_seed(seed)
  - For each known noise key (the ~50 in Noises.java):
      noise_cache[key] = NormalNoise::create(
                          root.from_hash_of(key),
                          NoiseParameters::for_key(key))
  - climate_sampler = Climate::Sampler::new(...)
  - biome_source = MultiNoiseBiomeSource::new(...)
```

---

## Recommended port order

If the goal is **finishing the surface dispatcher** (drop per-chunk
cost to beat vanilla), this is the dependency-correct sequence:

1. **`ImprovedNoise`** (item 9) — MEDIUM
   - Foundation; no upstream deps
   - Validates with `noise(x, y, z)` parity test against a Java
     instance with known seed
2. **`PerlinNoise`** (item 10) — MEDIUM
   - Depends on `ImprovedNoise`
   - Validates with `getValue(x, y, z)` parity test
3. **`NormalNoise`** (item 11) — EASY
   - Depends on `PerlinNoise`
   - Validates by sampling at the same positions vanilla's
     surface noise samples and matching outputs
4. **`Noises` registry + hardcoded `NoiseParameters`** (item 12) — EASY
   - Hardcode the ~7 noise channels Ferrite's surface rules need
     (the rest can wait until they're queried)
5. **Seed handoff plumbing** — EASY
   - JNI: `RustBridge.initWorldgenState(long seed)`
   - World-load mixin to call it
   - Rust `OnceLock<WorldgenState>` global

After 1-5: the Rust evaluator can compute `runDepth`, `secondaryDepth`,
and noise channels itself. The dispatcher's per-Y reflection drops
from biome+isCold+the-rest to just biome+isCold. **Estimated impact:
33ms → ~15ms** (rough projection from removing ~10ms of
reflection-equivalent work).

To eliminate biome+isCold too:

6. **`Climate` core (TargetPoint, Parameter, ParameterList)** — MEDIUM-HARD
   - Records and quantization are trivial; the RTree is the work
   - Self-contained, can develop in isolation with synthetic data
7. **`Climate.Sampler`** — needs a partial DensityFunction port
   - Or hack it: pass the 6 climate noise samples from Java
     temporarily, replace later when DensityFunction lands
8. **`MultiNoiseBiomeSource`** — MEDIUM
   - Thin once Climate is in place

After 6-8: dispatcher per-Y reflection is **zero**. Estimated impact:
**~5-10ms/chunk = beats vanilla's ~10ms.** Default-on becomes
defensible.

The DensityFunction machinery (item 16) is a separate multi-week
project. It only matters for chunk-shape generation, not surface
dispatcher.

---

## Yarn ↔ mojmap quick lookup

| Mojmap | Yarn 1.21.11 |
|---|---|
| `Xoroshiro128PlusPlus` | `Xoroshiro128PlusPlusRandomImpl` |
| `XoroshiroRandomSource` | `Xoroshiro128PlusPlusRandom` |
| `XoroshiroPositionalRandomFactory` | `Xoroshiro128PlusPlusRandom$Splitter` |
| `RandomSource` | `Random` (`net.minecraft.util.math.random.Random`) |
| `PositionalRandomFactory` | `RandomSplitter` |
| `LegacyRandomSource` | `LocalRandom` / `CheckedRandom` |
| `RandomSupport` | `RandomSeed` |
| `RandomSupport.Seed128bit` | `RandomSeed.XoroshiroSeed` |
| `Mth.getSeed` | `MathHelper.hashCode(int, int, int)` |
| `Mth` (general) | `MathHelper` |
| `RandomState` | `NoiseConfig` |
| `NormalNoise` | `DoublePerlinNoiseSampler` |
| `NormalNoise.NoiseParameters` | `DoublePerlinNoiseSampler.NoiseParameters` |
| `PerlinNoise` | `OctavePerlinNoiseSampler` |
| `ImprovedNoise` | `PerlinNoiseSampler` |
| `Noises` | `BuiltinNoises` (or `NoiseParametersKeys`) |
| `WorldgenRandom` | `ChunkRandom` |
| `MarsagliaPolarGaussian` | `GaussianGenerator` (approximate) |
| `Climate` | `MultiNoiseUtil` |
| `Climate.TargetPoint` | `MultiNoiseUtil.NoiseValuePoint` |
| `Climate.Parameter` | `MultiNoiseUtil.ParameterRange` |
| `Climate.ParameterPoint` | `MultiNoiseUtil.NoiseHypercube` |
| `Climate.Sampler` | `MultiNoiseUtil.MultiNoiseSampler` |
| `Climate.ParameterList` | `MultiNoiseUtil.Entries` |
| `MultiNoiseBiomeSource` | `MultiNoiseBiomeSource` (same) |
| `BiomeManager` | `BiomeAccess` |
| `WorldOptions` | `GeneratorOptions` |
| `ChunkGeneratorStructureState` | `StructurePlacementCalculator` |
| `DensityFunction` | `DensityFunction` (same) |
| `NoiseChunk` | `ChunkNoiseSampler` |
| `NoiseRouter` | `NoiseRouter` (same) |

When porting, **always verify yarn names against the live mappings
file** at `~/.gradle/caches/fabric-loom/1.21.11/.../mappings.tiny` —
this table was assembled from earlier session lookups and may have
gaps for less-traveled classes.

---

## When this doc gets stale

Update triggers:

- Vanilla updates to 1.22+ → re-audit; the structure is stable but
  yarn names and field obfuscation will shift
- A row in the TL;DR table moves from ❌ to ✅ → update the row + add
  a section note + update `SEED_DRIVEN_DISPATCH.md` roadmap
- New noise types added by Mojang (rare; happens with major worldgen
  changes like the 1.18 caves & cliffs update)
- DensityFunction port begins → expand the "skip for now" section
  into a real reference

The audit was done against commit `297f053` of the Ferrite repo
referencing `26.1.2/server/` source (Minecraft 1.21.11 server jar,
unobfuscated mojmap).
