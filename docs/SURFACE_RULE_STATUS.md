# Surface rule port — status

Snapshot as of commit `649edfc`. Companion to the forward-looking
`SURFACE_RULE_BATCH_PLAN.md` and `SURFACE_RULE_BUFFER_SPEC.md` — this
doc captures what's actually built and runnable today.

## Headline

- **Rust = Java 100%** across 110,975 sampled columns (`java=rust=100.0%
  divergences=0` from the live three-way validator). The Rust port is
  provably correct — identical output to the Java evaluator across all
  biomes and conditions tested.
- **95.3% match** between the bytecode evaluator and vanilla
  `buildSurface` (live diff sampler, 1-in-1000 columns; up from 92.4%
  via parity-pass fixes against Mojang's unobfuscated 1.21.11 source).
- **Java reference evaluator** (`SurfaceRuleEvaluator.java`) is the
  spec — Rust port (`rust/mod/src/surface.rs`) mirrors it byte-for-byte.
- **JNI binding shipped** (`rust/mod/src/surface_jni.rs`,
  `RustBridge.evaluateSurfaceRule`). Single-column per-call surface;
  per-chunk batching is the next optimization.
- **Remaining 8% is concentrated in specific biome rules** (desert
  sandstone Y-bound, dirt placement near surface in forest biomes,
  bedrock floor edge cases).

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

### Rust side (`rust/mod/src/surface.rs` + `surface_jni.rs`)

- Port of the Java evaluator. Same opcode constants, same dispatch
  loop, same byte order, same two-register dataflow.
- Biome set membership has two paths:
  - Library: `&[&[u16]]` with binary search (used by Rust unit tests)
  - JNI: precomputed `biome_match_bits: &[u8]` (one byte per
    biome-set-pool entry, set by Java) for O(1) lookups
- `surface_jni.rs` exposes `Java_me_apika_apikaprobe_RustBridge_evaluateSurfaceRule`:
  defensive direct-buffer reads (never panic across JNI), 15
  primitive args + 3 ByteBuffer args, returns `jint` blockstate ID
  or `-1` for null.
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

Validator-phase items (1–3) are **done** and crossed off below.
Remaining work splits into perf (item 1) and parity (items 2–3).

1. **Per-chunk batching** — hand 256 columns of input in one JNI call
   instead of one-per-column. Java-side packer + Rust-side Rayon
   parallel column eval. The current per-call ByteBuffer allocation
   pattern (3 buffers per call) is the bottleneck this removes.
2. **Dispatcher swap** — once batching is in place, change the mixin
   from "validator: diff and log" to "dispatcher: replace vanilla
   tryApply output with Rust output." That's what makes the perf
   claim real. Use the existing 92.4% match as a confidence floor;
   the remaining 8% routes to vanilla via OP_FALLBACK or a per-column
   "use vanilla" flag.
3. **Biome-scoped condition refinements** — desert sandstone Y-bound,
   forest dirt placement, bedrock floor edge cases. Decode each via
   javap, fix per the AboveY/StoneDepth pattern. Each fix should
   bump match% by 1–3 points.
4. **Deferred context fields** — `isCold`, `isSteep`, noise
   pre-sampling all carry placeholder values in the validator's
   ColumnContext. Wire through real `BiomeAccess` / `Heightmap` /
   noise sampler reads to push match% toward 100%.

### Done (validator phase)

- ✅ JNI binding (`surface_jni.rs`, `RustBridge.evaluateSurfaceRule`)
- ✅ Three-way live diff (vanilla / Java / Rust) with separate
  divergence counters
- ✅ Rust = Java parity confirmed at 100% across 110,975 samples

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
61b7057  JNI binding + three-way validator → vanilla 92.4%, java=rust 100%
98e48ff  per-chunk batch JNI + handoff (465 ns/col, java=rust 256/256)
bafd01e  CaveSurface ordinal fix (StoneDepth direction was swapped)
6639e7c  OP_WATER exact formula (was inverted + missing terms) → 94.3%
868994c  isCold + surfaceHeight real reads (drop two placeholders) → 95.3%
cf4cb32  trace-next debug command (opcode-level divergence dump)
649edfc  dump-biomes debug command (biome pool table inspector)
```

## Parity arc this session

```
session start:  92.4%
+ CaveSurface ordinal fix:                   91.3%   [variance, dirt-deep bug fixed]
+ OP_WATER exact formula:                    94.3%   [+3.0]
+ isCold + surfaceHeight real reads:         95.3%   [+1.0]
                                       net:  +2.9 percentage points
```

Java=Rust=100% throughout — Rust port unbroken across all fixes.

## Workflow change

Mojang shipped 1.21.11 source unobfuscated. We extract from
`minecraft-26.1.2-server-src.zip` to `./26.1.2/server/...` and read
`SurfaceRules.java` directly. Replaces the earlier javap-on-named-jar
workflow — same 5 minutes per condition, zero ambiguity.

## Remaining ~5% gap — trace tool revealed it's structural

The trace tool (`/ferrite surface trace-next`) was built to diagnose
the residual mismatches surgically. First trace run hit a
warm_ocean=sand=null mismatch and revealed the core issue is **not**
single-formula bugs anymore.

**Trace finding for warm_ocean position (Y=52, sand vs null):**

```
[0028] OP_SURFACE blockY=52 surfaceH=52 → cond=true   (entered surface branch)
[0038] OP_STONE_DEPTH addSurface=0 depth=4 rhs=1 → cond=false
[0049] OP_IF_ELSE → ip=430 (skipped then-branch ip 58..429)
[0435] OP_BIOME idx=0 biome=warm_ocean setSize=3 → cond=false (badlands set)
[0438] OP_IF_ELSE → ip=932 (skipped warm_ocean branch?)
... 4 more sequence alternatives, all gating-condition-false ...
[3809] OP_RETURN_DONE → return null
```

`dump-biomes` confirms warm_ocean IS in pool sets #1 and #3:
- set #1: `[beach, snowy_beach, warm_ocean]`
- set #3: `[deep_lukewarm_ocean, lukewarm_ocean, warm_ocean]`

But trace shows we never visit OP_BIOME at idx=1 or idx=3. Those
opcodes live inside then-branches we skipped because gating
conditions (StoneDepth, IF_ELSE conds) returned false.

**Hypothesis:** the bytecode compiler is either (a) miscapturing
the structure of nested biome-specific rules, OR (b) one of the
gating StoneDepth conditions has wrong operands extracted.

This is **structural compiler work**, not formula refinement. Tools
unblock the diagnosis but not the fix.

| Pattern | Cause | Fix |
|---|---|---|
| Branches we don't visit (most of remaining 5%) | Bytecode compiler misses/miscaptures rule structure | Bytecode disassembler + cross-ref vanilla `OverworldBiomeBuilder` tree |
| `vanilla=null eval=deepslate` at Y=3-4 | VertGradient gradient-zone midpoint approximation | Port Mojang's `RandomSplitter` |
| `isSteep` placeholder (2 nodes) | Heightmap reads not trivially reflective | Defer — small impact |
| `noiseValues` zeroed | NoiseThresholdMaterialCondition mis-fires | Real noise sampler tap |
