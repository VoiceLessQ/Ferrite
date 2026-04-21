# Surface rule batch evaluator — buffer schema + opcode spec

Follow-up to `docs/SURFACE_RULE_BATCH_PLAN.md` after the
`MaterialRules` research pass confirmed every condition node in the
default overworld tree resolves against pre-sampled scalar inputs —
no mid-evaluation Java callbacks required from Rust.

This document is the input/output contract and bytecode opcode table
the implementation will be built against. Frozen design — changes here
need a paired commit updating both Java packer and Rust evaluator.

---

## Input buffer schema

One JNI call per chunk. Inputs split into three regions packed into a
single direct `ByteBuffer` (little-endian, native order on x86_64).

### Per-chunk header

| Field | Type | Notes |
|---|---|---|
| `bytecode_handle` | `u64` | Opaque pointer / cache key for per-world rule bytecode. Bytecode itself is **not** in this buffer — it's uploaded once per world load and Rust holds it. |
| `anchor_y` | `i32` | Surface anchor Y for `AboveY` evaluation |
| `min_y` | `i32` | Dimension min Y |
| `max_y` | `i32` | Dimension max Y |
| `y_range` | `i32` | `max_y - min_y` — used to size per-(column, Y) region |

### Per-column region (256 entries, x in [0..16) × z in [0..16))

| Field | Type | Source |
|---|---|---|
| `biome_id` | `u16` | `posToBiome.apply(pos)` resolved to int via per-world biome ID table (built at world load) |
| `_pad` | `u16` | alignment to 4 |
| `run_depth` | `i32` | `surfaceBuilder.sampleRunDepth(...)` |
| `secondary_depth` | `f64` | `surfaceBuilder.sampleSecondaryDepth(...)` (pre-sampled, eliminates the lazy memoise) |
| `surface_height` | `i32` | from `estimatedSurfaceHeights[quadrant]` |
| `is_cold` | `u8` | `biome.value().isCold(x, surface_y, z)` pre-evaluated |
| `is_steep` | `u8` | precomputed from `chunk.sampleHeightmap` at (x±1, z±1) |
| `_pad` | `u16` | row alignment to 8-byte multiple (24 bytes per column) |

Total: **256 × 24 bytes = 6144 bytes per chunk** for the per-column
region.

### Per-(column, Y) region (256 × y_range entries)

Surface rules only evaluate within the active surface band, not the
full -64..320 range. **Pre-trim:** Java packer should compute
`y_band_lo` / `y_band_hi` per column based on where surface eval
actually fires, and pass `(y_band_lo[256], y_band_hi[256])` as part of
the per-column region (added to schema above as needed at impl time).
For now, conservative spec assumes full y_range:

| Field | Type | Source |
|---|---|---|
| `fluid_height` | `i32` | from surface walk per Y |
| `stone_depth_above` | `i16` | from surface walk per Y |
| `stone_depth_below` | `i16` | from surface walk per Y |

8 bytes per (column, Y). For a typical y_band of ~12 active Y values
per column: 256 × 12 × 8 = **24KB** per chunk. Worst case full Y range
is 256 × 384 × 8 = 786KB — too big, **must y-band-trim** before
shipping.

### Output buffer

Triples packed into a separate direct `ByteBuffer`:

| Field | Type | Notes |
|---|---|---|
| `column_index` | `u16` | 0..255 |
| `y_offset` | `u8` | 0..255 — relative to column's `y_band_lo` |
| `_pad` | `u8` | |
| `blockstate_id` | `u32` | global blockstate ID; resolved via per-world ID table |

8 bytes per write. Java consumes the triple stream, looks up the
blockstate by ID, writes via the existing direct chunk-section path
already used by `RedstoneOracle` / cramming infrastructure.

Pre-allocated to a worst-case bound (e.g. 256 × 16 = 4096 writes max
per chunk = 32KB) and reused per chunk.

---

## Condition → opcode mapping

Bytecode is a flat `Vec<u32>` opcode stream compiled once per world
from the JSON rule tree. Each opcode is 1 word; immediates follow
inline. The evaluator runs a tight `match` loop with no allocations
per column.

| Opcode | Mnemonic | Vanilla node | Inline immediates | Reads from |
|---|---|---|---|---|
| `0x01` | `COND_ABOVE_Y` | `AboveYMaterialCondition` | `surface_offset: i32`, `add_stone_depth_below: u8` | `blockY`, `anchor_y`, `stone_depth_below` |
| `0x02` | `COND_NOISE_THRESHOLD` | `NoiseThresholdMaterialCondition` | `noise_idx: u16`, `min_threshold: f64`, `max_threshold: f64` | pre-sampled noise value at `(blockX, blockZ, noise_idx)` |
| `0x03` | `COND_VERTICAL_GRADIENT` | `VerticalGradientMaterialCondition` | `random_seed: u64`, `true_at_y: f32`, `false_at_y: f32` | `blockY` |
| `0x04` | `COND_STONE_DEPTH` | `StoneDepthMaterialCondition` | `offset: i32`, `add_surface_depth: u8`, `secondary_depth_range: i32`, `surface_type: u8` | `stone_depth_above` / `stone_depth_below`, `secondary_depth`, `run_depth` |
| `0x05` | `COND_WATER` | `WaterMaterialCondition` | `offset: i32`, `surface_depth_multiplier: i32`, `add_stone_depth_below: u8` | `fluid_height`, `blockY`, `stone_depth_above`, `run_depth` |
| `0x06` | `COND_HOLE` | `HoleMaterialCondition` | (none) | `run_depth` |
| `0x07` | `COND_SURFACE` | `SurfaceMaterialCondition` | (none) | `surface_height`, `blockY` |
| `0x08` | `COND_BIOME` | `BiomeMaterialCondition` | `biome_set_idx: u16` (offset into pooled biome-ID set table) | `biome_id` (integer set membership against pooled set) |
| `0x09` | `COND_TEMPERATURE` | `TemperatureMaterialCondition` | (none) | `is_cold` — vanilla's condition is a parameterless singleton that always tests `biome.isCold`; no `expect_cold` immediate exists to extract |
| `0x0A` | `COND_STEEP` | `SteepMaterialCondition` | (none) | `is_steep` |
| `0x0B` | `COND_NOT` | `NotMaterialCondition` | followed by inline child opcode stream until matching `END` | inverts child result |

Result-side opcodes:

| Opcode | Mnemonic | Vanilla node | Inline immediates | Behaviour |
|---|---|---|---|---|
| `0x20` | `EMIT_BLOCK` | `BlockMaterialRule` / `SimpleBlockStateRule` | `blockstate_id: u32` | append output triple, return |
| `0x21` | `IF_ELSE` | `ConditionMaterialRule` / `ConditionalBlockStateRule` | `then_offset: u32`, `else_offset: u32` (relative jumps) | jump based on top-of-stack condition result |
| `0x22` | `SEQUENCE_NEXT` | `SequenceMaterialRule` / `SequenceBlockStateRule` | `next_offset: u32` | jump to next branch if previous branch returned empty |
| `0x23` | `RETURN_EMPTY` | end-of-sequence-no-match | (none) | column produces no surface block at this Y |
| `0x24` | `RETURN_DONE` | post-emit | (none) | terminate column eval, advance to next Y |

### Fallback path

Any condition or rule subclass not in the table above triggers a
**compile-time** fallback flag on the bytecode struct. The dispatcher
checks the flag before JNI:

- If flag is **clear** → JNI dispatch, Rust evaluates whole chunk
- If flag is **set** → fall back to vanilla `buildSurface` path for
  this chunk. Per-world cached, so the cost is the JSON-tree walk once
  at world load, not per chunk.

This keeps datapack-modified worlds correct: unknown node = vanilla
runs the chunk, no bytecode-shaped silent miscompile.

---

## Bytecode compilation flow

Once per world, in Java:

1. Walk the JSON-loaded `MaterialRule` tree of `surfaceBuilder.rule`
2. For each node:
   - Match against the table above
   - If unknown → set `fallback = true`, abort compilation, return
     bytecode-not-available marker
   - Else → emit opcode + immediates into a growing `IntArrayList`
3. Resolve forward jump offsets in a second pass
4. Build pooled-biome-set table (deduplicate identical biome ID sets)
5. Build per-world biome ID → `u16` mapping table (registry order is
   stable per save)
6. Hand bytecode + tables to Rust via `installRuleBytecode(worldId, …)`
   JNI call — Rust caches keyed by `worldId`. `bytecode_handle` in the
   per-chunk header is just `worldId`.

Compile cost: one tree walk per world load. Negligible.

---

## Open items before coding

1. **Noise-threshold count** — count `NoiseThresholdMaterialCondition`
   occurrences in the default overworld surface rule. If <5, pre-sample
   each used noise channel per column once and pack into the per-column
   region (small fixed cost). If ≥5, may want a per-(column, channel)
   sub-region instead. Decides the schema of the noise-input section.
2. **Datapack fallback verification** — confirm the fallback path stays
   correct when datapacks add new condition types. Specifically:
   - If a datapack rule is structurally identical to vanilla but
     swaps in a custom `Condition` impl, our match should fail to the
     unknown-node branch (not silently miscompile). Verify by writing a
     synthetic test rule with a stub custom condition once the compiler
     is in place.
   - If a datapack overrides only the leaf `BlockState`, that's fine
     — `EMIT_BLOCK` just carries a different blockstate ID.
3. **Biome-ID recompile trigger** — biome registry is per-world and
   stable across save loads but may change if datapacks are
   added/removed between sessions. Bytecode + biome-ID table must be
   recompiled on every world load (not just first install). Hook the
   compile step to `ServerWorld` initialisation, not mod init.
4. **Y-band per column** — the per-(column, Y) region is the biggest
   memory cost and the easiest to over-allocate. Spike how vanilla
   bounds the surface-eval Y range per column (likely surface_y ± some
   constant) before fixing the schema.
5. **Negative biome IDs / unregistered biomes** — defensive: if
   `posToBiome` returns a biome not in the per-world ID table (would
   require a runtime registry mutation, very rare), pack a sentinel and
   make `COND_BIOME` always return false for that column. Don't crash.

---

## Next-session entry point

Step 2 of the original plan: **Java-only bytecode compiler spike.**
- Build `RuleBytecode` (`int[] ops`, `int[] biomeSetTable`,
  `BlockState[] blockstateTable`, `boolean fallback`)
- Build `RuleBytecodeCompiler` (visit-pattern over `MaterialRule`)
- Write a unit test: load the default overworld rule via vanilla,
  compile it, assert `fallback == false` and the opcode count is in a
  sane range (probably ~200–500 ops)

Then step 3: a Java-only evaluator from the bytecode, validated
chunk-by-chunk against vanilla `buildSurface`. Same shape as
`RedstoneOracle` validation. **No Rust until the Java path is bit-for-bit
correct.**
