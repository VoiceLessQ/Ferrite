# Seed-driven dispatch — Ferrite's worldgen design philosophy

The architectural pattern Ferrite uses (and will keep using) for any
Rust port that touches deterministic worldgen state — surface rules,
structure placement, biome lookup, density functions, anything where
the answer is `f(seed, x, y, z)`.

Captures the lessons from the surface-dispatcher arc (commits
`c90ad8f` → `8822605`) and frames them as the design rule for every
future port in this space.

---

## The Golden Rule

> **You don't use Rust to generate raw data for Java to process. You
> move the flat data to Rust, do the math AND the PRNG together in
> native code, and hand back the final answer.**

Equivalent restatements:

- If Rust has the seed, and Rust knows the math, Rust doesn't need
  to ask Java for the data — it can just predict it.
- The world seed is a 64-bit long. That's the flattest, cheapest JNI
  boundary in existence.
- Java handles the block-setting, rendering, and Minecraft-API
  integration. Rust natively simulates the world's PRNG blueprint.

This is the same pattern that made cramming and AC-redstone-Rust win
decisively. Inverting it (passing per-call random numbers from Rust
back to Java via a refill buffer) is the anti-pattern that loses to
JIT-inlined Java math.

---

## Why this works

### Vanilla worldgen IS deterministic

Every per-(x, y, z) value vanilla computes during chunk gen is
`f(seed, x, y, z)` — pure math, no JVM state needed:

| Vanilla state | Computed from |
|---|---|
| `runDepth` (yarn) / `surfaceDepth` (mojmap) | `noise.getValue(x, 0, z) * 2.75 + 3.0 + factory.at(x, 0, z).nextDouble() * 0.25` |
| `secondaryDepth` | `surfaceSecondaryNoise.getValue(x, 0, z)` |
| Biome at (x, y, z) | `multiNoiseBiomeSource.sample(...)` over 6 climate noises |
| Structure presence at chunk | `factory.at(chunkX, 0, chunkZ).nextFloat() < threshold` |
| Bedrock floor at y | `factory.at(x, y, z).nextFloat() < probability(y)` |

Every noise sampler is `randomState.getOrCreateNoise(key)` — and
`randomState` is built from the world seed at world load. Vanilla's
`SurfaceSystem.Context` is just a memoization layer over these
deterministic functions; it's not authoritative state, just a cache.

If we initialize Rust's own noise / random stack from the same seed,
Rust computes the same answers vanilla would — bit-exact, by
construction.

### JNI is cheap when the boundary is flat

PROFILING.md measures two distinct JNI cost regimes:

- **Snapshot regime (200-500 ns/call)** — caller materializes a Java
  object per crossing. Object construction + GC pressure dominates.
- **Direct-buffer regime (single-digit ns/call)** — caller passes a
  pre-allocated direct ByteBuffer + scalar args. No allocation, no
  object lookup. Effectively free.

Seed handoff lives at the cheapest end of the cheapest regime: pass
**one i64**. Per-chunk dispatch lives at the second-cheapest:
`(chunk_x, chunk_z, position_array_buf, position_count)` — a few
scalars and one buffer reference.

### What you DON'T do (the anti-pattern)

The "PRNG refill buffer" temptation: build a Java `RandomSource`
backed by a direct buffer that Rust periodically refills with N
random numbers in bulk. Sounds like a batched-JNI win.

It's not. Three reasons:

1. **Vanilla's `XoroshiroRandomSource.nextLong()` is ~6 inlined CPU
   instructions** after JIT. The PRNG itself isn't a bottleneck.
2. **Buffer reads aren't free.** Each `ByteBuffer.getLong()` requires
   bounds checking, off-heap memory fetch, and the JIT can't inline
   the math anymore. You replace 6 register-only instructions with a
   memory load — the load takes longer than the math would have.
3. **What Minecraft does with the random number** is the actual
   bottleneck. Generating the number is rarely the cost; consuming
   it (placing a block, deciding a spawn, etc.) is.

So you don't refill Java's PRNG from Rust. You **move the consumer**
into Rust along with the PRNG.

---

## How to apply it (port template)

For any new candidate worldgen port, the design follows a fixed
shape:

### Step 0 — Confirm the subsystem is deterministic

Open the vanilla source. Trace from the per-chunk entry point to
the math. If every value reads from `randomState`, `noise`, or a
`PositionalRandomFactory`, you're in scope. If it reads from a
chunk's existing block state or a player's inventory or a tick
counter, you're not.

### Step 1 — One-time seed handoff at world load

Mixin on the world-construction path (or `ChunkGenerator` init)
captures the seed and pushes it to Rust:

```java
@Inject(method = "...", at = @At("HEAD"))
private void ferrite$captureSeed(...args, CallbackInfo ci) {
    long seed = ...; // extract from chunk generator settings
    RustBridge.initWorldgenState(seed);
}
```

Rust side:

```rust
pub static WORLD_STATE: OnceLock<WorldgenState> = OnceLock::new();

#[no_mangle]
pub extern "system" fn Java_..._initWorldgenState(seed: jlong) {
    WORLD_STATE.get_or_init(|| WorldgenState::from_seed(seed as i64));
}
```

`WorldgenState` holds: `XoroshiroPositionalRandomFactory`s for each
named random (already in `xoroshiro.rs`), `DoublePerlinNoiseSampler`s
for each named noise channel (next port), per-biome-source state,
etc. Built once, lives for the world's lifetime.

### Step 2 — Per-chunk dispatch hands Rust positions only

Java collects the (x, y, z) positions where the subsystem needs to
fire and passes them to Rust:

```java
// Per chunk:
ByteBuffer positions = ...; // pre-allocated, packed (x, y, z) i32 triples
ByteBuffer results = ...;   // pre-allocated, output size
RustBridge.evaluateWorldgenBatch(chunkX, chunkZ, positions, count, results);
```

Rust:

```rust
let state = WORLD_STATE.get().unwrap();
positions.par_chunks(3).enumerate().for_each(|(i, pos)| {
    let (x, y, z) = (pos[0], pos[1], pos[2]);
    let biome = state.sample_biome(x, y, z);
    let depth = state.surface_depth(x, z);
    // ... eval bytecode, etc
    results[i] = compute_result(biome, depth, ...);
});
```

No reflection. No per-call Java work. Rust does the math and the
PRNG together.

### Step 3 — Java applies the result

```java
for (int i = 0; i < count; i++) {
    BlockState state = lookupResult(results, i);
    if (state != null) chunk.setBlockState(positionAt(i), state);
}
```

This is the only Java-side per-position work, and it's just a
write — no PRNG, no noise, no biome lookup.

---

## The Four Checks, applied

For every candidate port, ask:

### Check 1 — Is vanilla actually the bottleneck?

If vanilla's per-call cost is already ~1 μs (e.g., the PRNG itself),
porting won't help. Look for subsystems where vanilla spends
milliseconds-to-seconds per chunk on the math.

The surface dispatcher passes this check: vanilla's `tryApply` walks
a tree of MaterialRule objects with virtual calls, condition
checks, and biome lookups — measurably ~10ms/chunk on the warm
overworld.

### Check 2 — Is the boundary flat?

Seed handoff (1 i64) and position arrays (i32 triples in a direct
buffer) are flat. State-snapshot ports (per-call biome objects, per-
call density samples) are not.

### Check 3 — Does Rust beat Java by ≥2×?

Once the boundary is flat, the question reduces to: is Rust's
implementation of this math meaningfully faster than vanilla's?

For PRNG-dominated workloads with thousands of evaluations per
chunk, yes — Rayon parallelism + SIMD-friendly code can hit 5-10×
on the math itself. For one-shot calls, no.

### Check 4 — Oracle testable?

The seed-driven approach is provably testable: same seed + same
position must produce the same output as vanilla. If Rust's
`Xoroshiro128PlusPlus.next_long()` produces the same i64 vanilla
does for the same input state, the port is correct.

The validator infrastructure (`SurfaceValidator`, `[surface-validate]`
log lines) is the model. Every Rust subsystem port should land with
its own validator.

---

## Current state and roadmap

### Foundation in place (commit `8dd8137`)

- `rust/mod/src/xoroshiro.rs` — bit-exact port of
  `Xoroshiro128PlusPlus`, `XoroshiroRandomSource`,
  `XoroshiroPositionalRandomFactory`, `RandomSupport.{mixStafford13,
  upgradeSeedTo128bit}`, `Mth.getSeed`. 11/11 unit tests + a
  hand-traced first-call value matching the Java algorithm.

- Wired into `OP_VERT_GRADIENT` (commit `297f053`) — the per-block
  PRNG roll for bedrock-floor and deepslate-transition rules.
  Java=Rust now 100% on the validator.

### Next ports (each its own commit, validator-gated)

In dependency order:

1. **`PerlinNoiseSampler`** — vanilla's per-octave Perlin noise.
   Pure math; samples 3D position to f64. Build on top of
   Xoroshiro for seed-derived gradients.

2. **`DoublePerlinNoiseSampler`** — composes two `PerlinNoiseSampler`
   with frequency / amplitude config. This is what `getOrCreateSampler`
   returns; what surface-rule `OP_NOISE_THRESH` consumes.

3. **`NoiseConfig`** state — Rust equivalent of yarn's `NoiseConfig`
   class. Holds the registry-name → `DoublePerlinNoiseSampler` map
   built from the seed. One-time init from seed at world load.

4. **`SurfaceSystem.getSurfaceDepth` / `getSurfaceSecondary`** — port
   the per-(x, z) noise sampling from vanilla. Once these are in
   Rust, the surface dispatcher stops needing per-column reflection
   for `runDepth` and `secondaryDepth`.

5. **`MultiNoiseBiomeSource`** — the climate-noise sampler that maps
   (x, y, z) → biome ID. The hardest piece (vanilla uses a 6D
   nearest-neighbor lookup against a registry). Once this lands, the
   surface dispatcher stops needing per-Y biome reflection
   completely.

After all five, the surface dispatcher becomes the seed-driven
shape: Java sends `(chunk_pos, position_array)` per chunk, Rust
returns `(blockstate_id_array)`. Per-position Java work disappears.

### Other targets the same pattern unlocks

Once the noise + biome stack is in Rust, several currently-shelved
or open subsystems become viable:

- **Structure placement** — vanilla checks per-chunk: "should a
  Bastion / Ocean Monument / village spawn at this (chunk_x,
  chunk_z)?" using `factory.at(chunkX, 0, chunkZ).nextFloat() <
  threshold`. Rust evaluates 1000 chunks in parallel via Rayon,
  returns the small list of successes.

- **Density function compiler** — currently shelved on
  "snapshot-style state to cross JNI" cost. Once Rust holds the
  noise samplers, the per-density-sample call cost vanishes (Rust
  computes from its own state, no callbacks).

- **Spawn attempt evaluation** — same shape: Rust runs the per-
  position random + condition check across many candidates in a
  single batch.

The pattern generalizes: **any deterministic per-position
worldgen subsystem where Rust can hold the seed-derived state
becomes a Ferrite Rust port candidate.** The Four Checks are the
gate; the seed-handoff template is the implementation.

---

## See also

- `docs/VANILLA_WORLDGEN_REFERENCE.md` — **the source-of-truth lookup
  doc** for every vanilla worldgen class involved in seed/PRNG/noise/
  math. Per-class effort estimates, yarn ↔ mojmap mappings, dependency
  ordering. Open this when you start any port from the roadmap above.
- [JOURNEY.md](JOURNEY.md) — the broader retrospective; this doc is the
  Track B / surface-dispatcher chapter expanded.
- [docs/SURFACE_RULE_STATUS.md](SURFACE_RULE_STATUS.md) — current numbers + the dispatcher
  arc that motivated capturing this design.
- [docs/PROFILING.md](PROFILING.md) — the JNI cost regimes section that
  underwrites the "flat boundary" framing.
- `rust/mod/src/xoroshiro.rs` — the foundation port.
