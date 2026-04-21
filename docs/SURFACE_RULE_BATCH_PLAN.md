# Surface rule batch evaluator — design plan

Scoped at the end of the 0.3.0-alpha cycle as the natural next chunkgen
target after the density-function port shelved (see `docs/PROFILING.md`
for why DF-layer interception is blocked behind a multi-week
composition-tree port).

Surface rules sit at the right layer: they run **after** density
resolves, with a clean boundary, on data that's already materialised.
No DF interleaving, no `final` interpolator state to capture mid-flight.

This document is the design settled-before-code for the next session.

---

## What we know

From `docs/PROFILING.md` measurements:

- **Surface phase: ~5ms/chunk** measured (with the caveat that the
  measurement itself inflated the number — outer-envelope timing is
  trustworthy, inner-loop hooks were not).
- **`tryApply` is ~25% of surface phase**: ~1.25ms/chunk of pure rule
  evaluation, the rest is block writes + boundary work.
- **256 columns per chunk**, each column evaluated independently — the
  rule tree is queried per-column with no inter-column state. That's
  embarrassingly parallel; matches Rayon's strengths exactly.
- **Runs post-density** — by the time surface rules execute, the noise
  column is already classified into blocks, biome lookup is stable, and
  there's no half-resolved DF state to worry about. Clean input → clean
  output transformation.
- **Data-driven JSON rule tree** — vanilla loads the overworld surface
  rule from `data/minecraft/worldgen/...`. The hot loop is an interpreter
  walking a tree of `MaterialRules.MaterialRule` nodes. **Porting that
  interpreter to Rust is the real work**, not the batch loop.

## The cramming analogy

The shape that worked for cramming should work here:

| | Cramming | Surface rules |
|---|---|---|
| Vanilla cost | N² pair checks per mob per tick | 256 columns × tree-walk per chunk |
| Win mechanism | spatial hash, one JNI call per tick | batch buffer, one JNI call per chunk |
| Per-call work | Chebyshev distance + push delta | rule-tree evaluation → block ID |
| Output | `(dx, dz)` velocity deltas | `(column, y_offset) → blockstate ID` |
| Bit-for-bit replica? | yes (exact vanilla push formula) | required — surface mismatches are visible to players |

Same JNI shape: pack input into a direct buffer, one call across the
boundary, Rust does parallel column evaluation, return a flat output
buffer Java applies.

---

## Design questions to answer before coding

### 1. Per-column input requirements

What does each column actually need on the Rust side to evaluate the
rule tree? Best estimate:

- **Biome ID at column** — already in `ChunkNoiseSampler` outputs
- **Y range** — fixed per chunk (vanilla overworld -64..320)
- **Surface noise value(s)** — used by some `MaterialRules.Condition`
  variants (e.g., `Noise`, `VerticalGradient`)
- **Block state at position from density** — to know "is this stone, is
  this air" before surface decoration
- **Estimated water level / aquifer state** — `MaterialRules.Condition`
  has water-related conditions
- **Chunk-relative position** (x, z, y) — some conditions are
  position-driven

**Open**: confirm the full input set by reading every concrete
`MaterialRules.Condition` subclass in 1.21.11 yarn. The condition list
defines the input contract.

### 2. Rule node types in vanilla 1.21.11

Need the complete list before designing the Rust evaluator. From memory
of the vanilla data structure (confirm by reading
`net.minecraft.world.gen.surfacebuilder.MaterialRules`):

- **Rules** (return blockstate or empty):
  - `BlockRule` — leaf, returns a fixed blockstate
  - `SequenceRule` — try children in order, first non-empty wins
  - `ConditionRule` — if condition matches, evaluate child rule

- **Conditions** (return boolean):
  - `Biome` — biome ID matches set
  - `NoiseThreshold` — noise sample > threshold
  - `VerticalGradient` — Y in interpolated range
  - `YAbove` — Y above absolute threshold
  - `Water` — water depth condition
  - `Temperature` — biome temperature condition
  - `Steep` — slope condition
  - `Hole` — surface depression condition
  - `AbovePreliminarySurface` — relative to preliminary surface Y
  - `StoneDepth` — distance from surface in stone
  - `Not` / `Or` — combinators

The leaf-vs-combinator structure is what makes pre-compilation viable
(see next question).

### 3. Pre-compile the rule tree, or recursive dispatch?

**Strong preference: pre-compile to flat bytecode.** Two reasons:
- Rust dispatch over a `Box<dyn Rule>` recursion has predictable
  per-node overhead but loses inlining; on a hot per-column loop this
  matters.
- The rule tree is **immutable per world** (loaded once at world load
  from JSON). Compile cost is paid once, evaluation cost is paid 256×
  per chunk × thousands of chunks. The amortisation ratio is excellent.

Bytecode shape sketch:
```
op codes:
  PUSH_CONDITION_RESULT <condition_id>
  JUMP_IF_FALSE <offset>
  EMIT_BLOCKSTATE <blockstate_id>
  RETURN_EMPTY
  RETURN
```
Compile JSON tree → flat `Vec<u32>` opcode stream. Rust evaluator is a
single tight loop over the stream with a small input-context struct.
Cramming-shape: no allocations per column, no virtual dispatch.

### 4. Output buffer layout

Per chunk: 256 columns × N surface block writes per column. Surface
rules typically only write a few blocks deep into the stone (top
soil + sub-surface), so realistic per-column output is 1–8 writes.

Proposed: `(short column_index, byte y_offset_from_surface, short blockstate_id)` triples
packed into a `ByteBuffer`. Java consumes triples and writes via the
existing direct-section path used elsewhere.

Alternatively a fixed-size column array (e.g. 16 slots × 256 columns)
with an unused-sentinel — simpler to allocate but wastes bandwidth on
typical biomes. **Pick after measuring typical write count per column
in vanilla.**

### 5. Instrumentation approach

**Sampling, not BEFORE+AFTER pairs.** Hard lesson from
`docs/PROFILING.md`:
- The previous SurfacePhaseMixin's `tryApply` and `blockRead` hooks
  fired 16K–65K times per chunk and added ~7ms of measurement overhead
  on a ~5ms baseline.
- Only the outer-envelope timer (one HEAD/RETURN per chunk on
  `buildSurface`) was trustworthy.

Approach for the port:
- Keep the existing `ChunkGenMonitor` outer-envelope on `buildSurface` —
  it already works and it's the comparison baseline.
- Add 1-in-N (e.g. 1-in-100) sampling on the JNI dispatch itself, not
  the per-column loop. Per-call timing inside the Rust loop is fine
  because there's no `nanoTime` cost in Rust — measure at JNI boundary
  only.
- For correctness validation: shadow-compute pattern from
  `RedstoneOracle`. Pick 1-in-N chunks, run both vanilla and Rust
  evaluator, diff outputs, log mismatches. Cheap because surface output
  is small per chunk.

---

## Open questions

1. **Does biome data require world reads mid-evaluation?** Some
   `Condition` types (e.g. `Biome`) need biome-at-position. If biome
   resolution itself crosses back into Java mid-rule-eval, the snapshot
   trap from `adjustMovementForCollisions` recurs — cost of the
   snapshot dominates the win. Prefer: precompute the biome-per-column
   map on Java side once, hand it to Rust as part of the input buffer.
2. **Are custom datapack surface rules structurally different from
   vanilla?** If a datapack adds a new `MaterialRules.Condition` subclass
   not in our enum, the bytecode compiler can't handle it. Two options:
   (a) fall back to vanilla per-chunk if the rule tree contains an
   unknown node — safe but reduces win on heavily-modded servers, or
   (b) port only when the tree is "vanilla-shaped" and skip otherwise.
   Decide based on how common datapack surface customisation actually
   is in the Ferrite target audience.
3. **How big is a typical compiled bytecode stream?** If the overworld
   surface rule compiles to <10KB of opcodes, a per-world cache is
   trivial. If it's hundreds of KB, may need to think about cache
   layout for the evaluator's tight loop.
4. **Interaction with existing `SurfacePhaseMixin`** — that mixin is
   currently disabled (per PROFILING.md, after the inner-loop overhead
   discovery). Confirm it's actually disabled in current code before
   building on top of `buildSurface` instrumentation, or reuse its
   `@Inject` target if it's still wired.

---

## Next-session entry point

1. Confirm vanilla 1.21.11 `MaterialRules` node enumeration by reading
   the yarn source (one focused read pass — list every `Condition` and
   `MaterialRule` subclass).
2. Spike the bytecode compiler in Java only — JSON tree → opcode `int[]` —
   to confirm the rule shape compiles cleanly. No Rust yet.
3. Write a Java-only evaluator from the bytecode and validate it
   against vanilla on one chunk. If it matches, the bytecode is the
   correct IR and the port is mechanical.
4. Then port the evaluator to Rust, wire the JNI handoff, measure.

The cramming session followed this exact sequence (validate Java path
first, then move math across the boundary) and it kept the JNI port
honest. Same shape applies here.
