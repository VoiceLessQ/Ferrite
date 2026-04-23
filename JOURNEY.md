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
