# Journey

What we tried, what stuck, what vanilla Minecraft taught us.

This document is the retrospective we wish we had when we started. It is
not a plan, not a spec, not a changelog. It is the story of which Rust
ports worked, which did not, and the shape of the wall we kept hitting.
Future contributors (including future us, after a long context gap)
should read this first. Per-subsystem plan docs in `docs/` go deep on
specifics; this one is the map above them.

---

## The frame

Every Rust port that shipped found an accidental flat boundary in
vanilla's data. Every port that failed hit vanilla's internal state
being coupled to world objects by design. That is the whole project, in
one sentence.

The JNI boundary is cheap (single-digit nanoseconds for a direct
ByteBuffer pass, measured in `docs/REDSTONE_PORT_PLAN.md` Phase 1). The
cost of assembling enough flat state on the Java side to cross that
boundary is not. Whenever vanilla has already done the flattening for us
(entity position arrays, an already-built graph, 256 independent
post-density columns), Rust wins decisively. When it has not, Rust
cannot buy us anything; we spend the win on serialization before we ever
call the kernel.

---

## What shipped and why

### Cramming (v0.5.0-alpha, full vanilla parity)

The entity cramming path is the flagship. Vanilla's `Entity.push`
iterates O(N^2) over all entities in a chunk to resolve overlap
pushing, plus applies cramming damage when overlap count exceeds the
gamerule threshold. For dense mob piles (lag machines, zombie farms)
this is the dominant entity-tick cost.

Ferrite replaces the inner math with a Rust batch: entities' positions
and flags go into a direct buffer once per tick, Rust computes pair
overlaps and push deltas using a spatial hash, Java applies the results.
The JNI boundary is crossed once per tick, not once per pair.

The win is real because vanilla already stores entity positions in a
flat layout (the chunk's entity list is walked anyway) and the
per-entity state we need is a fixed small struct. Nothing about the
compute needs mid-tick callbacks into the JVM. Handoff cost amortizes
instantly at any realistic entity count.

Result: ~310x reduction on the isolated cramming-math sub-budget,
~65% reduction in total entity-tick cost on the canonical lag machine,
full 1:1 parity with vanilla (damage application, same-vehicle skip,
everything). Shipped default-on with a live `/ferrite cramming on|off`
toggle for user-side A/B validation.

### Cramming post-ship: entity tick seam fully characterized

After cramming shipped, two correctness fixes and a full instrumentation pass
completed the entity tick picture.

**Dynamic cell size (correctness fix).** The original spatial hash used a
hardcoded `CELL_SIZE=2.0`, which works for vanilla mobs (max half-width ~0.9)
but silently misses pairs when modded mobs have wider bounding boxes. The fix:
compute `cell_size = max(2.0, 2.0 * max_half_width_in_batch)` per batch. No
performance change; pure correctness.

**Fingerprint cache (dead end).** Hypothesis: mob positions are stable
tick-to-tick on a cramming pile, so a hash of the input buffer would match
from tick to tick and let the spatial hash be skipped. Built a parity
validator, ran it on a 254-mob pile for 7400+ ticks. Result: `fpHits=0`. The
pile is moving because cramming physics itself applies velocity deltas to
roughly half the mobs each tick. The only ticks where the fingerprint would
repeat are ticks where cramming did nothing -- nothing to cache. The cache is
self-defeating by construction. Reverted; documented.

**Movement internals monitor formula (correctness fix).** The monitor
computing the "other" bucket had two errors that partially cancelled: it
subtracted `move` and `gravity` even though both fire inside `travel()`'s
probe window (double-deduction), and it omitted `adjustColl` from
`accountedTotal` (under-deduction). Net effect was a misleadingly
plausible-looking "other" of ~1.89ms when the true formula gives ~1.79ms.
Fixed; formula is now documented in comments with explicit nesting note.

**"other" bucket investigation.** After the formula fix, the 1.79ms "other"
bucket was the last unaccounted cost inside `tickMovement`. The three initial
suspects (potion effects, hand-swing animation, water-state detection) were
all found in `LivingEntity.baseTick()` or `Entity.baseTick()`, which fire
before `tickMovement()` is entered. They cannot contribute to
`movement_self`. Two probes were added instead: `tickHandSwing` (which does
fire inside `HostileEntity.tickMovement()` before the `super` call) and
`tickNewAi` (the full AI block: goal selectors, navigation, mob tick,
move/look/jump controls).

`tickNewAi` averaged 1.90ms. Subtract navigator (0.02ms, already probed
separately) and mobTick (0.01ms, already excluded from `movement_self`) and
goal selectors plus controls account for roughly 1.87ms -- the full "other"
budget. No mystery remains.

**Final entity tick picture at 254 hostile mobs (Ferrite active):**

| bucket | avg ms | verdict |
|---|---|---|
| goal selectors + controls | ~1.87 | world reads per call, fails check 2 |
| travel | 1.63 | JNI boundary cost exceeds win, fails check 3 |
| adjustColl | 1.07 | inside travel, same wall |
| blockCollision | 0.31 | world reads per step, too small |
| handSwing | 0.03 | trivial |
| navigator | 0.02 | trivial |
| cramming | 0.01 | shipped |

Total entity tick: ~4.7ms. Tick time ~9-10ms at 20 TPS. Goal selectors fail
check 2 because `goalSelector.tick()` calls `world.getClosestEntity()` and
`ActiveTargetGoal` scans for targets; no pure-math slice exists.

Cramming was the one clean algorithmic win in the entity tick. The seam is
fully worked.

### Alternate Current in Java (v0.3.0-alpha)

The redstone work started with the discipline that C2ME-class projects
miss: solve the algorithm before you solve the language. Vanilla's
default redstone evaluator is O(N^2), re-evaluating every wire for
every neighbor update. Space Walker's Alternate Current collapses that
to O(N) by tracking flow direction and avoiding redundant wire
re-evaluation.

AC lives in pure Java here. We preserved Space Walker's copyright
headers, credited upstream in `LICENSES.md`, and ported the algorithm
faithfully including the bits where Ferrite is ahead of vanilla's
own `ExperimentalRedstoneWireEvaluator` (external-power caching,
priority-queue ordering, connection graph caching). The audit against
Mojang's 1.21.11 source found one real correctness fix worth mirroring
(block-state re-validation at commit time, commit `63baf1a`) and two
divergences we already document as intentional.

Result: cascade count drops from ~2.25M/tick on vanilla default to
~350K/tick, without requiring the experimental-redstone world toggle.
That alone is the user-facing redstone win; Rust was a later
amplification.

### Per-cascade Rust BFS (v0.4.0-alpha, default ON)

With AC already collapsing the cascade count, the question was whether
Rust could accelerate what remained. The port path here is in
`docs/REDSTONE_PORT_PLAN.md` with every measurement, false start, and
pivot documented. Short version: first attempt shipped with a
conservative `MIN_NODES=32` gate that turned out to exclude 100% of
production cascades on the target workload, which made the feature look
neutral when it was actually unmeasured. The fix was diagnostic rather
than algorithmic: add an activation counter, plot per-bucket timing,
find that Rust wins 1.43x to 2.08x in every measured size bucket on
sustained workloads and loses ~1.77x on cold bursty small cascades.

Shipped default-on with `MIN_NODES=1` after the per-bucket data cleared
the gate. Power users who hit a contraption shape we did not measure
can `/ferrite redstone bfs off` as a one-command opt-out. Oracle
reports 0 mismatches against vanilla across every test window;
correctness is proven, not asserted.

---

## What did not ship and why

### Physics sweep

Vanilla's entity movement sweep does per-step block queries into the
surrounding volume to resolve collisions. Porting the sweep itself
to Rust was easy; feeding it block state was not. The block queries
mid-sweep are not a batched prelude, they are interleaved with
collision resolution. Either Rust needs live JVM callbacks (kills the
win via per-call JNI) or Java pre-materializes a local block volume
per entity per tick (kills the win via serialization cost).

There is no flat boundary. Shelved.

### Chunk-gen density function

The full story is in `docs/PROFILING.md`. Vanilla's `finalDensity`
evaluation is interleaved with an interpolator state machine that
rotates through a `[2][49]` buffer per sub-function, ~7 sub-functions
per chunk, ~25 snapshots per interpolator. Extracting the flat
5x5x49 corner grid we would need to feed Rust requires ~175 capture
points per chunk and a hand-port of vanilla's density composition
tree. That is weeks of careful engineering with high version
fragility (Mojang reshapes this tree every one to two releases).

The bulk-handoff compute was proven: Rust does the 98K interp + aquifer
classification in ~2.5 ms versus vanilla's ~17 ms. But the cost of
resampling `finalDensity` ourselves is ~30-37 ms, which eats the win
on the way in. Shelved, framework retained for diagnostic use.

### Surface rule dispatcher

Open but no longer stuck. The validator path is at **99.8%** vs vanilla
(up from 95.3% via four reflection / evaluator fixes against the
unobfuscated 1.21.11 source — `estimateSurfaceHeight` yarn rename,
per-block PRNG for `OP_VERT_GRADIENT`, record-component accessor for
vanilla's record-typed nodes, real noise sampling from cached
`DoublePerlinNoiseSampler` references). **Java=Rust now 100%** after
porting `Xoroshiro128++` to Rust bit-exact (closed the previous 97.5%
PRNG-divergence gap).

The dispatcher swap (production replacement of vanilla's per-call
`tryApply` with a batched Rust evaluator) ships behind a runtime toggle
(`/ferrite surface dispatch on`). Correctness is solid; performance is
~2.5× vanilla — too slow for default-on. Six-commit arc documented in
`docs/SURFACE_RULE_STATUS.md` "Dispatcher swap arc" with per-iteration
measurements: 15× → 8× → 3.5× → 2.5×, each step gated by an A/B that
proved which optimization moved the needle.

What the arc taught: per-call reflection cost dominates everything when
called per-(x,y,z) at chunkgen scale. Even MethodHandle.invokeExact +
direct typed Java only got us to 2.5×. The structural fix is **Track B**
— at world load Java pushes the seed once; Rust holds its own
NoiseConfig + RandomSplitter stack derived from that seed; per-chunk
Java sends only position arrays. Per-position Java work disappears
entirely. The Xoroshiro port is the first brick of that foundation.
Multi-session work ahead: `DoublePerlinNoiseSampler`,
`NoiseConfig.getOrCreateNoise`, `MultiNoiseBiomeSource`.

The full design philosophy — Golden Rule, port template, Four
Checks application, roadmap, and other targets the pattern
unlocks (structure placement, density compiler, spawn attempts) —
lives in `docs/SEED_DRIVEN_DISPATCH.md`. Read it before starting
the next subsystem port.

### Lighting palette reads

I/O-bound, coupled to vanilla's chunk palette representation. The hot
path spends more time decoding palette indices and chasing chunk
section pointers than doing arithmetic. Rust cannot help with pointer
chasing through JVM-owned memory. Shelved.

---

## The recurring pattern

For any new port candidate, ask this first:

**Where in vanilla does the target data already sit as a flat buffer,
array, or already-built graph?**

If the answer is "nowhere," estimate what it costs to flatten it on the
Java side. If that cost is more than what Rust saves, the port is dead
before it starts. No amount of kernel speed will recover it.

The wins we shipped all answered that first question with a concrete
vanilla data structure:

- Cramming: the chunk's entity list (already walked)
- AC redstone: the wire graph (built by AC's discovery phase)
- Surface rule candidate: 256 columns post-density (vanilla already has
  them as independent work units)

The ports we shelved all answered "nowhere without rebuilding vanilla":

- Physics sweep: block volume per entity (requires mid-sweep JVM calls)
- DF compute: corner buffers (requires interpolator state machine port)
- Lighting: palette entries (requires chunk section traversal)

It is not that Rust is bad at these. It is that the boundary we would
need does not exist in vanilla's architecture.

---

## Measurement discipline

The redstone arc is the clearest example of why the measure-then-gate
pattern is worth the process overhead. Three times during that work we
almost shipped the wrong conclusion:

1. **Phase 1's 17x looked like an easy green light.** It was, but only
   for pure compute with no per-cascade infrastructure. The real
   cascade path has HashMap lookups, lambda captures, and graph
   marshalling that Phase 1 did not measure.

2. **Phase 2's -78% looked like a hard dead end.** It was a dead end
   for that specific Java glue, which used a per-cascade
   `HashMap<Long,Integer>`, a captured-lambda walk, and per-wire int
   arrays. Phase 2b rewrote the glue (per-node `rustIndex` field,
   hoisted scratch buffers, direct linked-list walk) and the deficit
   dropped substantially. The "Rust can never win" conclusion from
   Phase 2 was actually "this Java glue can never win."

3. **Phase 2b's 0% on the lag machine looked neutral.** It was not
   neutral; it was the Rust path never activating because
   `MIN_NODES=32` was above every cascade size the workload produced.
   The activation counter (commit `68c059d`) turned a misleading 0%
   into a clear "Rust unreachable, try again."

Each correction cost one small diagnostic: an allocation profile, an
activation counter, a per-bucket sweep. None of them required
rewriting Rust. All of them changed the verdict.

Rule: **before accepting "the measurement says stop," ask "have we
measured the right thing."** The gate rule is sound. Its application
needs one layer of scrutiny before it fires.

---

## Calibrations that got revised

### JNI cost

We started the project with a working estimate of 200-500 ns per JNI
call, taken from the cramming and physics profiling work. That number
is accurate for calls that materialize a snapshot object per crossing.
It is wrong by an order of magnitude for "pass two direct ByteBuffers
and an int" crossings, where Phase 1 measured effective cost in the
single-digit nanoseconds range.

This matters for port-target selection. A 200 ns per-call JNI budget
rules out anything called more than ~5K times per chunk. A 5 ns budget
rules out only the tightest inner loops. See `docs/PROFILING.md`
"JNI cost regimes" for which regime applies to which call shape.

### Allocation dominance

Phase 2's -78% made allocation look like the dominant cost. Phase 2b
fixed the allocation patterns and recovered most of the gap, which
proved allocation was a large part of the problem but not all of it.
The remainder was JNI fixed cost plus serialization, neither of which
goes away with better allocation hygiene. They only go away with
persistent state, which is a multi-week architectural rebuild we
have not taken on.

### Workload shape

Same bucket label (cascade size 1-4) produced a 1.43x Rust win on the
lag machine and a 1.77x Rust loss on the repeater clock. Size alone
does not determine the outcome. JIT warmup state (lag machine had
millions of invocations, repeater clock had ~300) and per-wire work
density (repeater clock wires do more `findExternalPower` per wire)
both shift the result.

Lesson: benchmark the actual user-facing workload, not a micro-bench.
The micro-bench answers "is the kernel fast." It does not answer "does
shipping this default-on help real users."

---

## Five questions before the next port

1. Where in vanilla does my target data already sit as a flat buffer,
   array, or already-built graph?
2. If nowhere, what does it cost to flatten it on the Java side, and
   does that cost eat the win?
3. What is the call frequency of the hot loop? If it is >1K per chunk
   or >100K per tick, per-call JNI is dead; the port must be batched.
4. What is the workload shape on the user-facing case, not the
   micro-bench? JIT state and per-call work density matter as much
   as algorithmic complexity.
5. Do I have an oracle that proves my Rust path produces identical
   output to vanilla? No oracle, no ship.

Most of our shelved ports would have been caught by question 1 or 2.
Most of our measurement surprises would have been caught by question
4. The oracle requirement is non-negotiable; it is what lets us ship
aggressive defaults on features users cannot easily debug themselves.

---

## Things not to re-investigate

Listed so that future us, having forgotten why, does not re-open them:

- **Entity tick goal selectors.** `goalSelector.tick()` and
  `targetSelector.tick()` account for roughly 1.87ms at 254 hostile mobs
  and look like the biggest remaining prize inside `movement_self`. They
  fail check 2 immediately: `LookAtEntityGoal.canStart()` calls
  `world.getClosestEntity()`, `ActiveTargetGoal` scans for live targets,
  `WanderAroundFarGoal` queries path availability. Every goal evaluation
  touches world state. There is no pure-math slice to hand to Rust.
  Measured, documented, closed.
- **Batch-all-redstone-per-tick.** Semantically broken. Observers,
  comparators, pistons, and 0-tick pulses read wire state mid-cascade.
  No amount of clever batching preserves that.
- **Full vanilla redstone dust registry swap.** Breaks mod interop
  with anything that emits or consumes redstone power.
- **Per-call JNI for aquifer.apply, density samples, light reads, or
  any other inner-loop function.** Call frequency puts JNI cost above
  the compute saving regardless of how fast the Rust version runs.
- **Rewriting all of AC in Rust.** The 1,375 Java-side lines touch
  world state; porting them requires JVM callbacks that destroy the
  win. The ~600 compute-only lines are the only viable target, and
  Phase 2 already covers them.
- **Re-measuring with more hardware cores.** Server tick is
  single-threaded for anything that touches world state. More cores
  do not help until we have a subsystem that can run off the tick
  thread, which cramming partially does but redstone never can.
- **Cross-referencing other Rust Minecraft ports.** Mojang's source
  is the single source of truth for every port in Ferrite's Track B
  roadmap. Pumpkin, Valence, FerrumC, and any future Rust MC project
  are working on a different problem (replace vanilla, not accelerate
  it) and their ports may be pre-parity or approximate. Reading them
  would anchor our correctness judgments to potentially-drifting
  reference code and muddy the clean-room story. Port from Mojang
  source, validate against the live oracle, ship. The Rust ecosystem
  is small and the temptation to look will be real; the discipline is
  worth it.

---

## What's live now

Surface rule dispatcher swap is the open target. Validator sits at
95.3% match against vanilla with suspected measurement artifacts in
the validator's context capture. The next step is investigating the
validator, not fixing more evaluator formulas, because if the 4.7%
gap is a measurement bug the evaluator might already be at higher
parity than we can see.

After surface rules: density function compiler is the same shape,
bigger win, same version-fragility risk. Same pattern holds: find the
flat boundary vanilla already gives us, measure the user-facing
workload honestly, oracle the output, then ship.

The wall is real but it is not total. There are gaps in vanilla's
architecture where flat boundaries sit exposed by accident. Finding
them is the work.

---

## 26.1.x port: parity carry-over

The Yarn -> Mojmap migration to 26.1.2 is on the `26.1.x` branch.
After bulk class/method renames and per-mixin descriptor rewrites,
runClient is clean and a one-shot autovalidate run (gated by
`-Pferrite.autovalidate=<n>` on the gradle CLI, which also injects
`--quickPlaySingleplayer` so the run is fully headless) confirms:

- Noise stack: 62/62 pass at samples=2000, worst diff = 0.000e+00.
  Bit-exact carry-over. The ImprovedNoise + NormalNoise + BlendedNoise
  ports do not depend on anything that drifted between 1.21.11 and
  26.1.2, exactly as expected since they read seeds and sample inline.
- Biome R-tree: 1999/2000 pass at samples=2000. The single fail is a
  quantized-edge case (lush_caves vs dripstone_caves) that the
  ParameterPoint partitioning cannot disambiguate — same shape as
  the pre-migration miss profile.
- Density functions: 50/50 pass at samples=2000, worst diff = 0.000e+00.
  Bit-exact, beating the 1.21.11 baseline of 41/42.

Reaching DF parity on 26.1.2 took four distinct fixes:

1. 26.1.2 unified Add/Mul/Min/Max into a single `private record Ap2`
   with a `type` enum.  The walker dispatched by class-name substring
   ("TwoArgument" / "BinaryOperation"), missed Ap2, fell through to the
   unknown path, stubbed the whole subtree as CONSTANT(0).  Fixed.
2. Several DFs are `private record` types (YClampedGradient,
   FindTopSurface, EndIslandDensityFunction).  Walker reflection used
   `Class.getMethod`+`Method.invoke`, which silently fails on private
   records: the auto-generated accessor is public but the declaring
   class is not exported, so invoke throws IllegalAccessException
   inside our reflection catch-all.  Switched to `getDeclaredMethod`+
   `setAccessible(true)` walking the superclass chain.
3. `resolveNoiseName` in the walker used yarn `getKey`/`getValue` on the
   NoiseHolder's noiseData Holder.  Returned empty string on every
   call.  Rust looked up "" in state.noises, found nothing, returned
   0.0, and every Noise leaf silently zeroed.  This was the single
   biggest hit: lifted DF pass from 5/50 to 41/50 once corrected.
4. New 26.1.2 DFs: FindTopSurface (`overworld/caves/noodle`) and
   EndIslandDensityFunction (`end/sloped_cheese`) needed Rust
   interpreter ports.  EndIsland required SimplexNoise (2D port) and
   LegacyRandomSource (java.util.Random LCG) as new building blocks;
   wrapped in `LazyEndIsland` with `Arc<OnceLock<SimplexNoise>>` so
   the noise table is built lazily from `state.seed` and shared
   across enum clones.  Walker emits `OP_FIND_TOP_SURFACE` /
   `OP_END_ISLAND`; the validator visitor mirrors `RandomState.wrapNew`
   by rebuilding the registry's seed=0 EndIsland with the world seed
   so vanilla and Rust agree on the SimplexNoise table.

DensityParity also needed `synthNameToRouterField` extended to cover
the aquifer/* and vein/* entries — the original map only had climate
roots so 8 of the 50 names were silently failing with "no live
router DF" before this pass.

Lessons for the next major-version port:

- **Validators are first-class infrastructure, not optional.** The
  parity validators caught every walker bug in one autovalidate run.
  Without them, every DF would silently CONSTANT-fold and we would
  not know until somebody ran `/ferrite density validate` on a
  shipped jar.
- **Reflective code degrades silently.** Every yarn-named accessor
  drift returns null on miss, so the symptom is "everything is zero"
  not "everything throws."  Add explicit warn-once logs whenever a
  resolver returns null on a non-empty input — saves hours.
- **Private records are the new private inner classes.** Mojang has
  been moving DF types to private records for ~2 versions.  Default
  to `setAccessible(true)` on every reflective accessor walk; the
  cost is zero and the bug class is invisible until you hit it.
- **One walker bug can fake "many DFs are broken."** The empty-
  noise-name issue produced 45 distinct failures with diffs
  spanning 0.04 to 64, looking like an interpreter rewrite was
  needed.  In reality, all of them shared one root cause two layers
  up.  Sort failures by diff magnitude and look for the smallest
  *recurring* failure first; large diffs often share a common
  upstream null.
