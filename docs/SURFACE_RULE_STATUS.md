# Surface rule port — status

Snapshot as of commit `933c8a2`. Companion to the forward-looking
`SURFACE_RULE_BATCH_PLAN.md` and `SURFACE_RULE_BUFFER_SPEC.md` — this
doc captures what's actually built and runnable today.

## Headline

- **89.8% match** between the bytecode evaluator and vanilla
  `buildSurface` on real chunks (live diff sampler, 1-in-1000 columns).
- **Java reference evaluator** (`SurfaceRuleEvaluator.java`) is the
  spec — Rust port mirrors it.
- **Rust eval loop ported** (`rust/mod/src/surface.rs`), 9/9 Rust unit
  tests pass. JNI binding deferred to a follow-up session.
- **Remaining 10% is one isolated pattern**: desert sandstone Y-bound
  (vanilla=deepslate, eval=sandstone at deep Y in desert biomes).

## What's built

### Java side (`src/main/java/me/apika/apikaprobe/surface/`)

| File | Role |
|---|---|
| `RuleBytecode.java` | Opcode constants. Single source of truth — Rust mirrors these byte values. |
| `CompiledRuleTree.java` | Output record: bytecode + 3 tables (blockstate, biome set, noise channel) + hasFallback flag. |
| `SurfaceRuleCompiler.java` | Walks vanilla `MaterialRules` tree via reflection, emits flat opcode stream. Patches forward jump offsets after children are written. |
| `PerWorldBlockStateTable.java` | Intern + sort-on-freeze table for unique BlockState references. |
| `BiomeSetPool.java` | Same shape, but for biome ID sets. Equality by content (sorted canonical key). |
| `NoiseChannelPool.java` | Same shape, single-string keys for noise channel registry names. |
| `ColumnContext.java` | Per-column input record passed to the evaluator. |
| `SurfaceRuleEvaluator.java` | Two-register (cond, value) bytecode interpreter with forward-only IP. |
| `SurfaceRuleAccess.java` | Defensive reflection extractor for the active world's surface rule. |
| `SurfaceValidator.java` | Live diff sampler — installs a tree, intercepts vanilla `tryApply` via mixin, compares both paths. |
| `SurfaceRuleCompilerSelfTest.java` | 26 self-tests for compiler/operand-extraction/jump-offset patching. |
| `SurfaceRuleEvaluatorSelfTest.java` | 9 self-tests for evaluator dispatch. |

### Mixins

- `SurfaceValidatorMixin` — two `@Redirect`s on `SurfaceBuilder.buildSurface`:
  - On `MaterialRules$BlockStateRule.tryApply(III)` — runs vanilla, then optionally diffs against our evaluator
  - On `MaterialRules$MaterialRuleContext.initVerticalContext(IIIIII)V` — captures the context receiver + 6 vertical-state ints into ThreadLocals
- Uses `@Coerce` on the package-private `BlockStateRule` receiver type
- Co-exists with the existing `SurfacePhaseMixin` (timing brackets).

### Commands

| Subcommand | Action |
|---|---|
| `/ferrite surface compile` | Compile the active world's rule, report opcode count + bytecode length + table sizes + hasFallback verdict. |
| `/ferrite surface stats` | Walk the rule tree counting nodes by simple-name; full breakdown to `[surface]` log. Answers "which conditions does the default tree actually use?" |
| `/ferrite surface validate` | Compile + install tree as the validator's active tree; mixin starts diffing. |
| `/ferrite surface validate-off` | Clear active tree, log final stats. |
| `/ferrite surface validate-stats` | Print current diff stats line. |

### Rust side (`rust/mod/src/surface.rs`)

- Library-only port of the Java evaluator (no JNI binding yet).
- Same opcode constants, same dispatch loop, same byte order, same
  two-register dataflow.
- Biome set table is `&[&[u16]]` with sorted slices for binary-search
  membership (vs Java's `List<String>.contains`).
- 9/9 cargo tests pass — covers every opcode shape including
  IF_ELSE both branches, SEQUENCE_NEXT short-circuit, fallback
  soft-skip, biome membership, AboveY operand layout, VertGradient
  outside-range, unknown-opcode bail.

## Bytecode opcode table

Total: 17 opcodes in `RuleBytecode.java`.

| Opcode | Hex | Operands (after opcode byte) | Total bytes | Status |
|---|---|---|---|---|
| `OP_ABOVE_Y` | 0x01 | i32 anchorY, i32 surfaceDepthMul, u8 addStoneDepth | 10 | exact vanilla formula |
| `OP_NOISE_THRESH` | 0x02 | u16 channelId, f64 minThreshold, f64 maxThreshold | 19 | layout + dispatch ✓; semantics rely on noise pre-sample (currently zero in validator) |
| `OP_VERT_GRADIENT` | 0x03 | i32 trueAtAndBelow, i32 falseAtAndAbove | 9 | midpoint approximation (vanilla uses per-position random) |
| `OP_STONE_DEPTH` | 0x04 | i32 offset, u8 addSurfaceDepth, i32 secondaryDepthRange, u8 surfaceType | 11 | exact vanilla formula |
| `OP_WATER` | 0x05 | i32 offset, i32 surfaceDepthMul, u8 addStoneDepthBelow | 10 | spike formula (`blockY < fluidHeight + offset`); needs vanilla check |
| `OP_HOLE` | 0x06 | (none) | 1 | exact (`runDepth <= 0`) |
| `OP_SURFACE` | 0x07 | (none) | 1 | spike (`blockY >= surfaceHeight`); needs validation |
| `OP_BIOME` | 0x08 | u16 biomeSetIdx | 3 | exact (set membership against pool) |
| `OP_TEMPERATURE` | 0x09 | (none) | 1 | spike (`isCold` placeholder); needs `biome.isCold(x,y,z)` extraction |
| `OP_STEEP` | 0x0A | (none) | 1 | spike (`isSteep` placeholder); needs heightmap pre-sample |
| `OP_NOT` | 0x0B | (none) | 1 | inverts next condition's result |
| `OP_BLOCK` | 0x0E | u32 blockstateId | 5 | exact |
| `OP_TERRACOTTA_BANDS` | 0x10 | reserved | — | always emitted as OP_FALLBACK (random splitter not ported) |
| `OP_IF_ELSE` | 0x21 | u32 thenOff, u32 elseOff | 9 | forward jumps; spec divergence (no separate JUMP — else is implicit fall-through) |
| `OP_SEQUENCE_NEXT` | 0x22 | u32 endOff | 5 | jump past sequence if accumulator non-empty |
| `OP_RETURN_EMPTY` | 0x23 | reserved, not emitted | — | dropped from active design |
| `OP_RETURN_DONE` | 0x24 | (none) | 1 | single trailing terminator at end of root bytecode |
| `OP_FALLBACK` | 0x7F | (none) | 1 | soft skip (NOT eval abort — let enclosing Sequence fall through) |

## Operand tables

Three independent intern-and-sort-on-freeze tables. All three follow
the same shape: insertion-order IDs during compile, sorted final IDs
after `freeze()`, byte-offset patching in the bytecode array.

| Table | Element | Sort key | Pool size on default overworld |
|---|---|---|---|
| Blockstate | `BlockState` (or any Object) | `Registries.BLOCK.getId(state.getBlock())` else toString | 24 |
| Biome set | `List<String>` (sorted canonical) | element-wise compare | 22 |
| Noise channel | `String` (registry name) | identity | 7 |

## Live validator results

From the most recent run on the default overworld (4 random teleport
hops across ~3000-block jumps to vary biomes):

```
samples=296793  match=89.8%  mismatches=30189
nullVanilla=135754  evalNull=140270  ctxBuildFails=0
```

**Match rate timeline:**

| Commit | Fix | Match% |
|---|---|---|
| `bf18ecb` | OP_FALLBACK soft-skip semantic | first non-zero output |
| `7941eb1` | biome resolution | unlocked biome conditions |
| `7a5bb61` | OP_ABOVE_Y exact formula | 12.6 → 13.0 |
| `f4afdc7` | OP_STONE_DEPTH exact formula | 13.0 → 19.4 |
| `e90a830` | OP_VERT_GRADIENT spike approx | 19.4 → 10.7 (regression — wrong context) |
| `a1b374d` | initVerticalContext arg-order fix | 10.7 → **89.8** |

**Remaining 10% pattern:** desert biome, vanilla=deepslate, eval=sandstone
at deep Y. Fluid sentinel `Integer.MIN_VALUE` ("no fluid here") in those
columns. Likely a Y-bound check we don't model on the desert sandstone
emitter.

## Spec divergences from `SURFACE_RULE_BUFFER_SPEC.md`

| Spec item | Actual implementation |
|---|---|
| `OP_TEMPERATURE expect_cold: u8` | No immediate. Vanilla's `TemperatureMaterialCondition` is a parameterless singleton testing `biome.isCold(x,y,z)`. |
| `OP_IF_ELSE then_offset + else_offset + JUMP after then-branch` | Implicit fall-through. Else-target is the position right after the then-branch. Fewer opcodes. |
| `OP_SEQUENCE_NEXT next_offset` per child | Each `SEQUENCE_NEXT` carries the same end-of-sequence offset. |
| `OP_RETURN_EMPTY` and `OP_RETURN_DONE` per branch | Single `OP_RETURN_DONE` at end of root bytecode. Branch-local terminators would force a call-stack model. |
| Rule bytecode handle / per-world cache | Not yet — each `compile()` call produces a fresh tree. Caching is trivial when wired. |

## Open work for next session

1. **JNI binding for the Rust evaluator** — `surface_jni.rs` mirroring the
   pattern in `cramming_jni.rs`/`physics_jni.rs`. Pass bytecode + tables
   + ColumnContext fields, return blockstate ID (or sentinel for null).
2. **Per-chunk batching** — hand 256 columns of input in one JNI call
   instead of one-per-column. Java-side packer + Rust-side Rayon
   parallel column eval.
3. **Three-way live diff** — extend the validator to compare
   vanilla / Java eval / Rust eval. Same input, three outputs, log any
   divergence between Java and Rust (should be zero — they share the
   spec).
4. **Desert sandstone Y-bound** — the lone pattern keeping us off 100%
   parity. Probably one missing operand on a specific biome-scoped
   AboveY or StoneDepth condition.
5. **Deferred condition refinements** — `isCold`, `isSteep`, noise
   pre-sampling all carry placeholder values in the validator's
   ColumnContext. Match rate will tighten further once these are
   wired through real `BiomeAccess` / `Heightmap` / noise sampler
   reads.

## File-by-file commit history

The headline commits for this work:

```
465c303  bytecode compiler spike (5/5 tests)
05b4f2c  operand extraction items 1-5 (11/11)
12d4154  report blockstate count
82e7264  blockstate ID table + OP_BLOCK (14/14)
4263827  biome set pool + OP_BIOME (17/17)
c9027ed  noise pool + StoneDepth/Water/Not (24/24)
09d2478  control-flow + jump offset patching (26/26)
1a9a5d1  Java bytecode evaluator (35/35)
6985f27  validator scaffold
273ea30  @Coerce mixin fix
3148616  capture MaterialRuleContext
ac03ba7  drop hasFallback short-circuit
bf18ecb  OP_FALLBACK soft-skip
7941eb1  robust biome resolution
7a5bb61  OP_ABOVE_Y exact formula
f4afdc7  OP_STONE_DEPTH exact formula
e90a830  OP_VERT_GRADIENT spike approx
a1b374d  field arg-order fix → 89.8%
933c8a2  Rust eval loop port (9/9 Rust tests)
```
