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
ByteBuffer pass, measured in [docs/REDSTONE_PORT_PLAN.md](REDSTONE_PORT_PLAN.md) Phase 1). The
cost of assembling enough flat state on the Java side to cross that
boundary is not. Whenever vanilla has already done the flattening for us
(entity position arrays, an already-built graph, 256 independent
post-density columns), Rust wins decisively. When it has not, Rust
cannot buy us anything; we spend the win on serialization before we ever
call the kernel.

One more thing the frame implies, worth stating outright since
Henrik's GDC talk on the cliffs-and-caves overhaul made it visible.
Vanilla expresses worldgen as a tree of compositional density-function
ops plus spline points loaded from JSON, not as hardcoded math. When
Mojang ships a new MC version they mostly move spline dots around
and occasionally rename or add a DF type; the op set itself stays
remarkably stable. Ferrite's Java-side walker compiles whatever DF
tree the live registry produces into a small bytecode, and
`density.rs` interprets it. A Mojang version bump propagates as
"different bytecode flowing through the same interpreter," not as a
code change in our crate. The 1.21.11 → 26.1.2 port confirmed this
empirically: noise and density math carried over intact, parity
validators went from 41/42 on 1.21.11 to 50/50 bit-exact on 26.1.2,
and what broke was the Java-side classpath surface (yarn → mojmap
renames, vanilla class restructures) not the Rust kernel. The
"move the dots and the world changes" model Mojang teaches at GDC
is the same property that keeps our kernel decoupled from any
specific MC version. Modders writing custom DF trees for datapacks
get this for free too: the registry hands the new tree to our walker,
the walker compiles different bytecode, the kernel runs it. No
recompile of the mod required.

The constraint that everything must stay inside vanilla's call graph is
what made the project legible to itself. Every candidate port had to
survive the five questions in the section below before any code got
written, and that filter is what turned cramming and AC redstone into
measured wins instead of speculative ports. It is also what closed
bulk-chunk-density and the physics dispatcher cheaply: one JFR session
each, parity validator confirms the math is right, profiler confirms
vanilla is already at the floor, port goes default-off and the work
ends. The walls matter as much as the wins. Each closed thread is a
permission slip for future-us to not relitigate density functions or
entity collision when the temptation comes back. Wins came from places
vanilla was naive at the algorithm layer (cramming's O(N^2) pairs,
redstone's redundant cascade visits, hopper's empty prefix scans, BE
tickers that do nothing). Walls came from places vanilla had already
won at the JIT layer (DF tree with `CacheOnce` markers, entity
collision's type-stability, anything HotSpot has inlined to single-
digit nanoseconds steady-state). The constraint did not limit the
project. It defined where the work was, and it told us when the work
was done.

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
[docs/REDSTONE_PORT_PLAN.md](REDSTONE_PORT_PLAN.md) with every measurement, false start, and
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

### World creation pre-gen (26.1.x branch, default OFF toggle)

Not a Rust port. A UX/lifecycle feature that runs vanilla's chunkgen
pipeline (with all of Ferrite's existing accelerations stacked) at
world creation time, before the player ever explores the area. The
piano model still applies, just inverted: vanilla generates chunks
on demand, we ask it to generate them up-front in a known pattern.

Shape: a toggle + 5-50 chunk radius slider on Create World "More" tab.
On commit, a static request is consumed by `SERVER_STARTED` and a
daemon-thread driver walks a clean-room concentric annulus iterator,
feeding `ChunkForcer.submitAsync` through a `Semaphore(50)` backpressure
gate. Each submission bounces onto the server thread to satisfy the
chunk-system contract; the driver loop itself stays off the server
thread so ticking and chunk-system housekeeping are unblocked. A boss
bar reports progress to the host, throttled to every 5th chunk.

Cancel writes a snapshot to `<world>/ferrite_pregen.dat` (Properties
file, four ints of iterator state plus the request metadata). Auto-
resume on next world load reads the snapshot and continues from the
exact same iterator position. Graceful completion deletes the snapshot
and writes `<world>/ferrite_pregen.done`, which is also the first-launch
gate for the dedicated-server `-Dferrite.pregen.radius=N` property
(read once, marker prevents re-firing on every restart).

ChunkForcer split during the build: the existing `/ferrite chunkforce`
on-demand path stayed as `submitOneShot` with its non-blocking cap +
dedup. A new `submitAsync` returns a `CompletableFuture<Void>` for
driver-owned backpressure, no internal cap, no dedup, no `ENABLED`
gate. Both paths share the same underlying `addTicketAndLoadWithRadius`
call but track inflight separately so pre-gen and chunkforce do not
contend on a shared cap.

Validated: 88 chunks/sec at 441 chunks static, 70/s at 961 chunks during
world creation, 80/s peak at 3721 chunks during cancel-and-resume cycle.
Active flight + radius=15 pre-gen held TPS 20.00 across 100+ tick
samples (avg 8-11ms, max 47ms within the 50ms budget). Pre-gen and
chunkforce coexist correctly: when both target the same area the
chunk-load executor splits worker time gracefully (53/s competing,
104/s when chunkforce is active but inactive in the pre-gen area), no
TPS loss, no corruption. The user-controllable workaround for max
pre-gen throughput is `/ferrite chunkforce off`. A future cross-system
inflight coordinator is noted in [FUTURE_PLANS.md](FUTURE_PLANS.md) if operators ever
need guaranteed throughput during active play.

License clean-room: the iterator was rewritten from algorithmic
description, not copied from Chunky (GPL-3.0). The mining of Chunky's
internals (`docs/LOCAL_DESIGN.md`) was for *intent*, not code.

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

The full story is in [docs/PROFILING.md](PROFILING.md). Vanilla's `finalDensity`
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
lives in [docs/SEED_DRIVEN_DISPATCH.md](SEED_DRIVEN_DISPATCH.md). Read it before starting
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
rules out only the tightest inner loops. See [docs/PROFILING.md](PROFILING.md)
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

## AC redstone vs a real lag machine (2026-07-12)

First test of the AC port against a purpose-built lag machine
(schematic paste, flat world, view distance 16, simulation 12,
single player, oracle sampling active in both arms). Vanilla wire
path: 1.4-1.8 TPS, mspt 550-716 ms with a 1.47 s worst tick,
2.6-2.8M wire cascades per 5 s window, gate updates avg 0.38 ms.
Flipping `/ferrite redstone ac on` mid-choke recovered 1.4 to 3.9
to 8.6 to 20.00 TPS in about 40 s of backlog chewing, then flat
6 ms mspt with the machine still running (gate ticks per window
tripled because the server could finally keep up). Under AC the
same machine produced ~200k cascades per window at 0.065 ms per
gate update: roughly 13x fewer cascade operations and 6x cheaper
gates. Notably rust-bfs activations stayed 0 throughout; this win
is the AC graph algorithm alone, the Rust BFS layer never met its
activation threshold on this machine's network sizes.

Second finding, on the oracle: a machine this dense pushes the
sampling comparator far outside its design envelope (~10k node
mismatches per window, deltas mostly +-1/+-2, uniform across
positions). That is the documented mid-cascade sampling artifact at
scale, not a correctness signal, but the WARN spam itself becomes
measurable overhead. If lag-machine benchmarking becomes routine,
the oracle needs a rate-limited or summarized mismatch log.

## Pregen inflight cap: 50 was binding (2026-07-12)

The pregen driver's inherited inflight cap of 50 (with a comment
claiming no gain beyond it) was measured wrong on 26.1.2. Four
3721-chunk virgin-terrain runs, same world, alternating caps via the
new `/ferrite pregen inflight <n>`: cap 50 ran 90-96 chunks/s (twice,
different areas), cap 200 ran 114-118/s, cap 400 added nothing.
Default is now 200; ~25% more pregen throughput, TPS 20.00 held in
all four runs. Also observed: already-generated chunks pass through
the driver at effectively disk-load speed, so a skip-existing
optimization is not worth building; and pregen at ~115 chunks/s
outruns flight-driven generation (~60/s) because it pays no
render/network tax per chunk.

## The boundary tax, measured (2026-07-12)

`/ferrite ffm bench` runs the same three ops over JNI and over
java.lang.foreign against rust_mod, inside the live game JVM
(`FfmBoundaryBench`, C ABI twins in `rust/mod/src/ffm_bench.rs`).
In-game numbers on this machine, JDK 25:

| Op | JNI | FFM |
|---|---|---|
| no-arg call | 4.1 ns | 5.5 ns |
| sum 4 KB ints | 162 ns | 124 ns |
| fill 512 KB doubles | 70.0 us | 31.3 us (shared segment) |

Three conclusions worth keeping:

1. The per-call boundary was never the problem. A bare JNI call is
   ~4 ns on modern HotSpot; FFM is slightly slower per call. Every
   port that died, died to data marshalling or to vanilla having
   already won, not to call overhead.
2. FFM's real win is bulk transfer into shared native memory, ~2.2x
   on the 512 KB fill, because the copy disappears. But the moment
   vanilla needs the result back in a Java array or palette, the
   copy returns. This does not resurrect bulk-density (the ~50-56 ms
   JNI fill would drop to maybe ~30 ms against a +25-50 ms deficit).
3. If an owned-state design ever happens (Rust as the authoritative
   store, Java reads through MemorySegment), FFM is the boundary to
   build on: no copy at all when the mirror is the storage.

## The DFC question, measured (2026-07-13)

C2ME's `c2me-opts-dfc` module is a working bytecode density function
compiler: vanilla's DF tree to an AST, then an ASM-generated class per
unique DF, JIT-compiled in-JVM. It is the cheap variant of the "Rust DF
compiler" reopener from the bulk-density post-mortem, so it earned one
session of measurement before any scoping. Three results.

**Running C2ME beside Ferrite is a non-starter.** C2ME
0.4.2-alpha.0.13+26.2 boots clean next to all Ferrite mixins, and the
3721-chunk pregen bench read 125.5/s vs a 110-124/s baseline (within
run-to-run noise, no clear gain). But df-parity collapsed from 50/50
to 35/50: every live-router-captured DF failed (climate x6, terrain
x2, aquifer x4, vein x3, 2000/2000 samples each), with
`preliminary_surface_level` reading rust=0.0, meaning the walker
silently mis-encoded C2ME's compiled wrapper classes. Jar removed,
50/50 confirmed restored the same session. Pairing is out; the idea
is what's worth anything.

**StripBlending is already vanilla.** C2ME strips blending wrappers
from the DF tree; on 26.2 vanilla does it first. `NoiseChunk` skips
the `BlendDensity` wrapper entirely when the blender is empty (every
fresh world) and leaves `blendAlpha`/`blendOffset` as pass-through
constants (NoiseChunk.java:126-145,400 in the 26.2 decompile). Zero
cost to strip. Same lesson as hoppers, chunk save, lighting: check
what vanilla does NOW before scoping.

**The dispatch share puts a thin ceiling on a Ferrite DFC.** 60 s JFR
(settings=profile, 8.7K samples) mid-pregen of 6561 virgin chunks on
26.2: worker pool 6083 samples across 28 threads. Stacks touching
DF/NoiseChunk: 26.7%. But of that, real noise math (Perlin, Simplex,
Normal, Blended leaves) is 10.4 points, which a compiler still has to
execute, and NoiseChunk interpolation bookkeeping stays too. The
compilable-away dispatch is roughly 8-15% of worker CPU (top DF leaf:
`DensityFunctions$Ap2#compute` at 2.0%). Rest of the profile:
PalettedContainer#get 9.1% leaf, SurfaceRules 9.8%, workers
idle-joining in `CompletableFuture#reportJoin` 8.5%. A perfect DFC
buys maybe 5-10% chunks/s for multi-week work plus a parity oracle
rebuild against generated bytecode. The closure holds: deliberate
decision only, and this entry is the number to beat.

**The durable lesson: Ferrite does not pair with worldgen-optimizer
mods, by design.** Ferrite's correctness story rests on reading
vanilla's live worldgen state (the router walk, the parity oracles,
the prewarm predictions). Any mod that rewrites that state under us,
the way C2ME's compiled DFs did, blinds those instruments even when
the world it generates is still vanilla-correct. Supporting such mods
would mean restructuring Ferrite's capture layer around every
optimizer's internals, which trades our verifiable
compose-with-vanilla position for a compatibility chase that most
likely breaks Ferrite and makes things worse. If a user wants C2ME-,
Moonrise-, or Lithium-style worldgen acceleration, they should run
that mod instead of Ferrite for chunkgen, not both. Ferrite's lane
stays: vanilla worldgen, bit-exact validation, tick-time wins.

## 26.2 tick-time discovery: one live candidate (2026-07-13)

First discovery pass on 26.2 (the 26.1.x candidate list was measured
empty): interactive world, all monitors on, a spawned horde of 600+
zombies, skeletons, and creepers pressing the player. Monitors at
that load: entities 14.2 ms/tick (monster 11.5), nav-tick under 1 ms
(vanilla's PathTypeCache absorbing the horde), block entities and
items negligible, mob-spawn attempts ~0.5 ms into a saturated cap.

A 60 s JFR (settings=profile, 2433 server-thread samples) decomposed
the monster tick:

| slice | server thread |
|---|---|
| EntitySection#getEntities (spatial queries) | 20.0% (top leaf) |
| PalettedContainer#get | 13.0% |
| goal/AI selection | 8.2% |
| movement + collision shapes | 7.4% |
| sensing/targeting (overlaps getEntities) | 6.9% |
| pathfinding | 3.4% |

**The candidate: entity spatial queries.** Targeting, sensing, and
collision all ask "which entities are in this box"; vanilla answers
with a per-mob linear scan of section buckets, O(mobs x neighbors)
over data that barely changes between queries. Biggest single slice
any candidate has shown since cramming, and the same family: spatial
entity math over flat data (positions and AABBs), easy oracle
(compare returned sets).

Known risks before scoping, so the gates have targets: mid-tick
semantics (entities move while others query; an index must update
incrementally or accept snapshot staleness, and targeting may be
less forgiving than cramming was); gate six looks favorable for once
because the cost is algorithmic rather than dispatch, but the same
structural fix might be reachable in pure Java, which would be the
cheaper first probe; and this is Lithium's home turf, so any Rust
index has to beat their Java-side answer, not vanilla. Next step if
pursued: a query-count monitor (queries/tick, entities scanned per
query) to size the win, then the six-gate check.

Same day, the `[entity-query]` monitor (commit `dbd3bc2`, mixin on
both `EntitySectionStorage.getEntities` overloads) put numbers on
gate 3 and the six-gate check passed, the first candidate to clear
all gates since cramming: 1490-1560 queries/tick at the 600-mob
horde, 1.7-2.8 us per query steady-state (40x the 50 ns JIT death
line; the cost is candidate-set width, not dispatch), 2.5-4.1
ms/tick mean with p95 spikes to 16.8 ms when the horde clusters.
Win ceiling at that load is roughly 2-3 ms/tick average and most of
the spike windows, scaling superlinearly with mob count. Two design
centerpieces carried into scoping: mid-tick query semantics
(entities move while others query; incremental index updates vs
snapshot staleness) and the set-compare shadow oracle
(RedstoneOracle pattern, entity-id sets). One accounting note: JFR
leaf attribution said 20% of server thread, the outer-call monitor
says ~10% average; the difference is per-section inner-loop frames
and the clustered spike windows.

The superlinearity check landed the same hour: 1000+ zombies packed
on a flat world. Queries/tick fell to ~1172 (idle zombies scan
less), but per-query cost rose 5x to 9.5-9.6 us because every box
now holds hundreds of candidates, putting spatial queries at
11.1-11.3 ms/tick, 38% of a 29.7 ms monster tick, with the server
brushing the 50 ms budget. The cost driver is candidate-set width,
exactly what an index collapses, and it grows fastest in the packed
scenarios where servers actually die.

## The retro: what a straighter path would have looked like (2026-07-27)

With aarch64 landed and the first outside contributor in the log, we
stopped and asked a question the project had never asked directly:
knowing everything we know now, what would we have done differently
for performance? Not to beat ourselves up. To extract the ordering
rules while the memory is fresh, and to decide whether any dumped
plan deserves another look now that the ground has shifted.

The honest answer, ranked by wasted effort:

1. **Gate six should have existed on day one.** We ported the
   chunkgen math stack to bit-exactness before asking what vanilla's
   warm steady-state cost per call actually was. It was 20-50 ns.
   One JFR profile of a warmed-up server at the start would have
   shown a flat worker profile with no dominant leaf, and months of
   default-off kernels would have been scoped down or skipped. We
   measured our own kernel speed instead ("Rust 7x faster on
   equivalent work"), which was true and irrelevant. The tick thread
   never cared.

2. **Profile first, pick targets second.** Every win that is
   default-on today (cramming, redstone AC, the BE gates) came out
   of monitors on a realistic heavy world, sorted by ms/tick. We
   found that method in month three. Had the first month been
   instrumentation instead of "chunkgen looks like clean flat data,"
   cramming and redstone surface immediately and chunkgen never
   ranks. Boundary-first target picking optimizes for what is easy
   to port, not what costs ticks.

3. **A cheap Java probe before every Rust kernel.** The walkability
   cache burned six sessions before we learned vanilla 26.1 already
   fronts the same path with its own PathTypeCache. A 20-line
   counter mixin asking "how often does vanilla miss its own cache"
   would have answered in an hour. Hopper scans, chunk save, and
   mob spawn all died at gate 1 in a single evening each, but only
   because by then the probe habit existed.

4. **Aquifer parity chased the wrong metric.** 99.895% and still
   default-off indefinitely, because the perf case was never there
   and the artifact case never went away. The first "this will be
   default-off regardless" signal should end the work, not push it
   toward another nine.

What was not a mistake, and this matters for the revisit question:
the parity infrastructure. The validators are why the 26.1 and 26.2
ports took days, and why the C2ME conflict was caught as 35/50 hard
numbers instead of silent world corruption. Slow to build, kept
paying. Same for the closed threads themselves: each one carries its
measurement, which is what makes revisiting cheap.

**On revisiting dumped plans.** Coming this far changes the ground
under some closures, so the closures deserve a periodic look, with a
rule: a closed thread reopens on new evidence, never on hope. What
"new evidence" means concretely today:

- Every JIT-wall closure was measured on desktop x86 HotSpot. The
  Pi 4B report puts Ferrite on OpenJ9 and ARM in the field, where
  none of those numbers were taken. That is a real unknown, and we
  own no hardware to resolve it. The Pi perf lines requested in
  PR #8 are the data channel; if they arrive, we read them as gate
  measurements, not as an excuse.
- SIMD Perlin gets deader on ARM, not less. NEON is 128-bit.
- The DFC reopener keeps its price tag: ~8-15% of worker CPU
  addressable, multi-week cost, measured 2026-07-13. Nothing since
  has moved either number.
- The entity spatial query index is the one plan whose case got
  stronger, and it was never dumped. Its cost is algorithmic, so it
  matters most on exactly the low-end hardware that just showed up.
  Per lesson 3 above, it starts with the pure-Java probe.

The pattern for future revisits is the same one that closed the
threads: name what changed, find the cheapest measurement that the
change could have moved, run it, and let the number decide. This
whole section only works because each closure wrote its number down;
a closure without one would be a superstition we couldn't check.

## Biome 1999/2000, diagnosed and closed (2026-07-27)

The one biome miss from the 26.2 recon turned out to be an exact
distance tie, worked out by hand against 26.2's
OverworldBiomeBuilder. The failing sample is climate target
(t=8982, h=10081, c=8386, e=18516, d=5122, w=-13016), quantized
x10000. The two cave biomes differ in exactly one box each:
dripstone_caves constrains continentalness to [8000, 10000],
lush_caves constrains humidity to [7000, 10000]; everything else
they share. At this target, continentalness 8386 is inside both
boxes and humidity 10081 is 81 outside both (it also exceeds
FULL_RANGE). Per-axis distances come out identical on every axis:
81 (humidity), 8516 (erosion, outside every biome's box), 3016
(weirdness). Fitness ties at 81,625,073 on both sides. Vanilla's
tree search happens to reach dripstone first (registered first,
OverworldBiomeBuilder line 833); our R-tree reaches lush. Both are
nearest.

The reason only the validator ever sees it: the parity check
samples uniformly in plus-minus 20000 per axis, but real terrain
never produces erosion 1.85. Ties need a point that is outside
multiple boxes at once by matching margins, and the in-gamut
climate space has a strict nearest biome at every sampled point,
which is why 1999 of 2000 and every earlier 100-sample recon pass
cleanly.

Two side findings from the same session. The in-game
`/ferrite biome validate` command had been comparing our overworld
tree against whichever MultiNoiseBiomeSource was constructed last,
which is the nether's, producing a screaming 0/2000 with
crimson_forest answers; the validator now picks the captured source
whose biome set contains plains. And the deep-marker walk on 26.2
reports found=8 registered=0 because the walk dedup-returns on the
pre-seeded router roots without recursing into them; every consumer
of those fingerprints is default-off, so it degrades diagnostics
only. Fix queued, not urgent.

Verdict: no bug, no code change to the R-tree, closed. If a future
validator wants 2000/2000, the honest change is to count
equal-fitness answers as a pass, not to nudge sampling ranges.

## The aarch64 week: what one contributor unlocked (2026-07-29)

We got lucky, and it is worth writing down exactly what the luck
bought, because half of it is opportunities that were sitting in
stale sections of this file.

The sequence: cwright814 wanted Ferrite on a 2 GB Raspberry Pi 4B,
found no ARM native, compiled one, and sent PR #8, tested on their own
hardware. We merged it, released it in 0.6.7, and within two days their
field report handed us more new information than any session this
month produced on our own:

- **A hardware class we cannot buy data from otherwise.** Every gate
  and closure in this file was measured on desktop x86 HotSpot. His
  server runs ARM, OpenJ9, and a 2 GB heap, and it surfaced things
  our profile never could: entity tick at ~27 ms with only ~300
  mobs, misc-bucket spikes to 47.8 ms that our mob-farm-shaped test
  worlds never exercised, and a RAM comparison (Moonrise +400 MB vs
  our few) we did not know was a selling point.
- **Field answers to desk-level compat questions.** Their production
  stack runs C2ME, Lithium, FerriteCore, Krypton, and ZFastNoise
  beside Ferrite. Several Tier 3 unknowns in COMPATIBILITY.md now
  have a running-in-production answer.
- **A new instrument.** Chasing their numbers produced the pi-sim
  method (4-core affinity pin plus small heap), which immediately
  paid out three times: the G1-vs-ZGC stutter diagnosis, the
  1022-zombies-at-20-TPS-on-4-cores headline, and the inflight-cap
  re-validation that closed a lead the same day it was proposed.
- **Reopened stale questions on their merits.** The JIT-wall
  closures are HotSpot measurements; OpenJ9 in the field means they
  are unverified there, and the entity-query candidate got its
  strongest evidence yet from exactly the hardware that was not in
  the plan.

The durable lesson sits next to the retro's: our measurement
discipline made the luck usable. The monitors gave them something
worth sending, the parity validators made their stack safe to run, and
the hardware stamp and misc-top breakdown shipped within days so the
NEXT report answers the questions this one opened. Luck delivered
one contributor; the instrumentation turned them into a lab.

## Things not to re-investigate

Listed so that future us, having forgotten why, does not re-open them:

- **Chunkgen rustification (26.1.2 re-measure of the settled
  closure).** JFR profile 2026-07-12, 90 s of top-speed flight on
  26.1.2, settings=profile, 13.5K samples. Worker-Main pool 72.7%
  of CPU, render thread 20.8%, server tick thread 5.5% (TPS 20
  throughout). Inside the workers the profile is flat: hottest
  single method is PalettedContainer.get at 9.6% (palette reads,
  JVM pointer chasing, un-portable), density/noise family ~15%
  summed, biome lookup ~6.8%, surface rules ~4%, aquifer ~1.8%.
  The Rust-addressable math surface totals ~28% of worker CPU and
  is exactly the stack already ported bit-exact and measured
  losing to JIT per-call on 1.21.11. The closure previously rested
  on 1.21.11 numbers only; it now has 26.1.2-native confirmation.
  Chunkgen light stages measure 17-22 ms/chunk by pipeline latency
  but ~1 ms/chunk actual light-thread CPU (queue wait dominates;
  backlog peaks ~880 of the 1000 batch cap under heavy generation,
  thread still >90% idle). Same verdict, current evidence.
  Measured 2026-07-12 with the `[light]` monitor after a fresh
  26.1.2 source dive. The old "palette pointer chasing" shelving
  note turned out not to be the operative fact; the operative fact
  is that vanilla moved lighting off the tick thread entirely.
  `ThreadedLevelLightEngine.runLightUpdates` throws if called on
  the server thread; updates run on a `ConsecutiveExecutor` in
  batches of 1000, and the tick thread pays only task enqueues.
  Measured across idle, sustained flight, torch spam, and glowstone
  spam: light thread at 2-102 ms per 5 s window (0.06-2% duty),
  worst single pass 14.9 ms, backlog peak 358 of the 1000 batch
  cap, always drained within one window. Nothing tick-time to win;
  a Rust kernel would race a thread that is 98% idle. The
  propagation inner loop does still read block states per neighbor
  (BlockLightEngine.propagateIncrease), so a flat opacity/emission
  mirror is conceivable, but it would speed up a thread with no
  queue to speak of. Player-visible light lag is client render
  (mesh rebuilds on light change), out of a server mod's lane.
  Lucis-style engine replacement was considered and rejected on
  shape: it wins by replacing the architecture, which breaks the
  compose-with-everything scope. Monitor stays in tree. Re-entry
  only if a real server shows the light executor's backlog pinned
  at the batch cap across consecutive windows.
- **Mob spawning candidate sampling.** Measured 2026-07-12 with the
  `[mob-spawn]` monitor, two worlds: fresh "New World" idle at spawn
  and a flat world with 230 mobs (cap saturated, spawns=0). The
  census (`NaturalSpawner.createState`, once per tick) costs 0.08
  ms/tick fresh and 0.03 ms/tick at 230 mobs; it is bounded by entity
  count and never got hot. That census was the only portable slice,
  and the source dive had already shrunk it: `PotentialCalculator`
  only accumulates charges for mobs with a biome spawn cost, which
  vanilla uses in soul sand valley and warped forest, so in the
  overworld the "math" is a scan over an empty list. The attempt side
  (`spawnCategoryForChunk`) did measure hot-ish, ~0.93 ms/tick at
  ~1010 calls/tick on the saturated flat world, but each call is a
  2.3 us early-out built from world reads (nearest player, biome,
  block state, collision), the exact shape that killed fluid ticks
  and villager Brain on paper. A night flight over a regular
  overworld world, heavy live spawning (37-74 spawns per 5 s), came
  in at ~0.25 ms/tick combined,
  cheaper than the saturated case: successful attempts exit early,
  a full cap grinds every call for nothing. Prediction on record was
  "dies at gate 1" and it did. Monitor stays in tree. The one lead left is
  Java-side, not Rust: vanilla pays that ~1 ms/tick attempting into
  a saturated cap with zero spawns, so a cheaper pre-filter on
  categories already at local cap could exist; that is a gate in the
  BE-ticker family, a separate proposal if tick budget ever demands
  it.
- **Chunk save / serialize (palette bit-pack).** Measured 2026-07-01
  with the `[chunk-save]` monitor under top-speed flight (742-993
  saves per 5 s): the NBT encode + palette bit-pack (`write`) runs on
  `Util.backgroundExecutor()` in 26.1.x, avg 0.4-0.8 ms/chunk,
  off-thread. The only tick-thread piece is
  `SerializableChunkData.copyOf` at 0.3-0.5 ms/tick total under that
  worst-case pressure, and it is array copies plus block-entity NBT
  capture, not kernel math. Load-side decode also runs on the chunk
  pipeline workers. Vanilla moved the portable compute off the tick
  thread; there is nothing tick-time left to win. Monitor stays in
  tree. Occasional single-chunk copyOf spikes (14-26 ms max) exist;
  if tick hitches ever become a complaint, that is the lead, but the
  fix shape would be Java-side capture batching, not Rust.
- **Hopper item-entity scans.** Measured 2026-07-01 on a 92-hopper
  sorted mob farm: 45-48 scans/tick, 8-40 microseconds per tick total,
  itemsFound ~0. Half the hoppers never scan (sorter hoppers have
  containers above and take the transfer path); the scans that do run
  are empty AABB queries costing 0.2-0.9 us each. Fails gate 1 by
  three orders of magnitude. The hot hopper path in real builds is
  container transfer, already served by the extract hint. Re-entry
  only if a JFR ever shows getEntitiesOfClass hot in a hopper bucket.
- **Walkability cache (pathfinding PathType).** Six sessions, closed
  2026-07-01 by A/B at 294 chasing zombies. The session 6 build was
  healthy (84-87% hit rate, zero snapshot churn after lazy fill plus
  a 2-way associative 4096-slot cache) and still moved nav tick cost
  ~5% at best, inside noise. Root cause: vanilla 26.1.x already
  fronts `getPathTypeFromState` with its own per-position
  `PathTypeCache` (4096 entries, invalidated per block change), so
  our intercept only ever sees vanilla's misses, and HotSpot compiles
  the classification chain those misses hit to near-nothing. Same JIT
  wall as density and collisions, with a vanilla structural cache in
  front this time. Infrastructure stays in tree, default-off
  (`-Dferrite.nav.cache`). Re-entry requires a target vanilla does
  NOT already cache.
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
- **Owning the chunk-gen scheduler.** A multi-session scoping pass
  measured this end-to-end. Source dive in `26.1.2/decompiled/`
  produced the full stage-radius table, concurrency envelope, write
  surface, and ticket-gate model. A latency probe
  ([FerriteDispatcherProbeMixin](../src/main/java/me/apika/apikaprobe/mixin/FerriteDispatcherProbeMixin.java),
  default-off) captured per-priority queue-wait plus per-task run
  duration on the worldgen and light SCEs. Numbers from a 2-minute
  fast-flight session: dispatcher pollTask p999=6.29ms, max=14.83ms;
  worldgen task body p99=25.17ms, p999=50.33ms, max=81.75ms. Task
  body is 4-10× the dispatcher tail. Heavy compute already escapes
  the SCE via `Util.backgroundExecutor()` from inside `fillFromNoise`,
  so the parallelism win there is realized regardless of who owns
  the dispatcher. Beyond the throughput numbers, the deeper close is
  on shape: a scheduler we owned would deliver real wins (adaptive
  load shedding, player-aware prioritization, cancellation, vertical
  chunk priority, tick-budget back-pressure), but every one of those
  is control-flow work, not kernel work. Ferrite's pattern across
  every shipped port is "find a flat-data boundary where vanilla is
  algorithmically naive, swap in Rust math, retain vanilla's control
  flow." A scheduler inverts that, owning control flow plus the
  threading model plus the mod-compat surface. Even with the cleanest
  framing, building one makes Ferrite a different project. The probe
  stays in tree default-off for any future re-measurement; the
  scoping notes live in `LOCAL_DESIGN`. Closed on shape, not on
  numbers.
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

## The May 2026 audit pass

After cramming and AC redstone had been shipped a while, we did a
consolidation pass: deep-probe every default-on system, confirm
behavior against vanilla source, find inefficiencies, fix them, push.
Two patterns emerged worth remembering.

**Reading the source one more time before any edit caught the false
alarms.** Of seven concrete redstone findings written up after the
deep probe, two turned out wrong on closer reading. Finding #3 said
the `@Redirect` on `RedstoneController.update` was catching both the
experimental and default branches, opening a hidden footgun.
Re-reading javac's bytecode rules: the static type at the call site
is `ExperimentalRedstoneController`, not the parent class, so Mixin's
descriptor-based match never fires there. The redirect already
worked correctly. Finding #5 said the Rust path was redundantly
calling `findExternalPower` on every wire when AC-Java only calls it
on decreased wires. Tracing the lazy-resolution semantics in
`WireHandler` showed the per-wire call is necessary correctness
work, because Rust takes a static snapshot and can't reproduce AC's
deferred priority-queue resolution. Both findings would have shipped
regressions if we'd gone straight from "looks redundant" to a patch.
The discipline of writing it up, then re-reading the source once
more before editing, paid for itself twice in one session.

**Most of the actual win was deletion, not addition.**
`RedstoneRustDispatcher` had been superseded by AC's `runRustBatch`
in v0.4.0 and was ~500 lines of dead code by the time we looked at
it. Default-off, untested, sharing buffer layout with the live path,
which meant removing it required checking what was shared first.
The dispatcher class, its mixin, its command, and the `USE_RUST`
toggle field all came out in one commit. The codebase is smaller
and the next audit pass has less surface to traverse.

The cramming batch had the same shape: one real correctness fix
(a standalone vehicle's `root_vehicle_id` sentinel never matched
its passenger's parent reference, so vehicles were pushing their
own passengers), plus three perf fixes (FxHashMap reuse, small-N
brute-force fast path, monitor inject gated behind the `ENABLED`
flag). When you go looking for hot-loop inefficiencies after a
system has been live for months, the wins still on the table are
specific and small. The big ones got found and fixed at ship time.

**Fidelity audit confirmed AC matches upstream.** Space Walker's
repo at `89609e4` (March 2026) is what we ported from, and there
have been no commits to the `wire/` package since. Every
algorithmic touchpoint, from node lifecycle through neighbor/shape
updates, is preserved verbatim modulo correct yarn renames. The
size deltas (Ferrite's `WireHandler` 178 lines shorter,
`UpdateOrder` 75 lines shorter) all resolve to javadoc trimming
and constant extraction. No drift, no regressions, no missing
methods. We can stop second-guessing the adaptation.

The redstone audit also confirmed `MIN_NODES=1` is the right default
for the Rust BFS gate: aggregate wire-cost is lower with Rust
running on every cascade than with any threshold we tried, even on
workloads where the per-bucket numbers showed Rust losing on
specific cascade sizes. Per-cascade losses in narrow buckets are
real but get drowned out by the aggregate wins on the wider
distribution.

**Block-entity ticker hygiene fell out of the audit too.** A
chase-the-tick-cost question (Etho mentioned signs lagging on
Hermitcraft scale) opened a vein neither cramming nor redstone had
reached. Vanilla registers a `BlockEntityTicker` for every sign at
chunk load and ticks all of them every server tick to do, in 99.99%
of cases, a single null check on the `editor` field. The body work
is ~5-10 ns per sign per tick. The infrastructure around it (range
check, ticker map walk, lambda dispatch, profiler push/pop) costs
~120 ns per sign per tick regardless. At 961 placed signs that is
0.20 ms / tick of pure overhead doing nothing useful. Same shape we
had been finding everywhere else: vanilla maintains a flat data
structure (the per-chunk ticker map) and iterates all of it
unconditionally, so the win comes from pulling out the entries that
have nothing to do, not from making the inner work faster.

The fix is a single `@Redirect` on `BlockState.getBlockEntityTicker`
inside `WorldChunk.updateTicker`, returning null for vanilla
`SignBlockEntity` and `HangingSignBlockEntity` (strict-class check)
when the editor field is null. Vanilla's existing
`if (ticker == null) removeBlockEntityTicker(...)` branch handles
deregistration. `setEditor(UUID)` re-evaluates the gate via the
same `updateTicker` call when a player opens the edit screen. 961
placed signs measured: 0.20 ms / tick → 0.06 ms / tick, ~70%
reduction. Self-heals from persisted-editor state within two ticks
of chunk load.

The same pattern then applied to furnaces. `AbstractFurnaceBlockEntity`
ticks every furnace at chunk load, runs through ~6 ItemStack/field
reads, falls through every branch, and returns when the furnace is
idle (no fuel, no input, not burning, no recipe in progress). The
gate adds three more conditions on top of the sign check: redirect
to null when the strict-class is `FurnaceBlockEntity`,
`BlastFurnaceBlockEntity`, or `SmokerBlockEntity`, and all of
`litTimeRemaining == 0`, `cookingTimeSpent == 0`, slot 0 empty,
slot 1 empty hold. Re-registration via `@Inject RETURN` on
`setStack(int, ItemStack)`: the moment a hopper inserts fuel or
input, vanilla's ticker comes back through the same path. 500 idle
furnaces measured: zero measurable BE-tick increase versus
empty-area baseline, within the noise floor.

Two mistakes worth recording. First: the sign and furnace gates
started as two separate `@Redirect` mixins on the same
`BlockState.getBlockEntityTicker` INVOKE site. Mixin's redirect
machinery only allows one handler per call site, so it kept the
first-loaded one (sign) and silently skipped the second (furnace).
We caught it at the next dev boot via the `[Mixin/WARN]` line, not
because the fix had no effect on the bench. The lesson is that boot
warnings are part of the verification, not optional output to skim
past. Second: collapsing the two into one composite mixin
(`WorldChunkBlockEntityTickerGateMixin`) means future BE-type
gates add as additional `if` branches inside one handler, which
also avoids tripping the same conflict again.

The remaining audit question was whether the pattern has more
candidates. We checked the rest of vanilla's common block entities:
chest, barrel, bed, decorated pot, lectern, jukebox, comparator,
piston. Mojang already applied the dynamic-ticker pattern correctly
to all eight. Comparators and lecterns never tick and recompute
event-driven. Pistons only have a `BlockEntity` during the
extension/retraction animation and the BE is removed when motion
ends. Jukebox registers its ticker conditionally on the
`HAS_RECORD` blockstate property, which is exactly the pattern we
hand-rolled for signs and furnaces. Chest is client-only-tick (lid
animation). Barrel, bed, decorated pot have no `getTicker` at all.
Mojang knows this trick. They applied it consistently except for
signs and furnaces. Both gaps are now closed.

The cheap obvious targets are exhausted. Beacon, brewing stand,
campfire (unlit), sculk sensor still tick but each of them does at
least some load-bearing work per tick (beam scans, recipe-progress
drains, vibration listening) where suppression isn't a clean win.
From here the work is measurement-driven: the `[worldtick]`,
`[entity-tick]`, `[block-tick]`, `[sign-tick]`, `[redstone-bfs]`
log lines are the discovery layer. When a real server surfaces an
unexpected hot band, that becomes the next target. Source-scanning
vanilla for more sign-shaped wins has finished.

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

---

## Post-port revisit and the 0.6.3-alpha release

After the 50/50 DF parity result on 26.1.x, the natural next move
felt like "find the next gap." What the gap-finding session actually
surfaced was that there wasn't a gap, and the time was better spent
verifying what was already there.

The candidates the source audit walked into:

- **LegacyRandomSource**, java.util.Random's LCG, used by
  EndIslandDensityFunction's seed setup. The first reading said
  "this is missing on the Rust side, port it." It was already at
  `rust/mod/src/xoroshiro.rs:369`, added during the original DF
  parity push as a building block for `LazyEndIsland`. Same module
  as the Xoroshiro128++ implementation, just sitting in a different
  file from where habit said to look.
- **SimplexNoise**, 2D simplex used by EndIsland for the cone/cliffs
  term. Same story: already at `rust/mod/src/perlin.rs:119`,
  imported by `LazyEndIsland::get_or_init` line 220.
- **EndIslandDensityFunction**, the DF op that combines them.
  `OP_END_ISLAND` (opcode 0x19) at `rust/mod/src/density.rs:622`.
  Walker emits it, interpreter handles it via `LazyEndIsland`,
  parity validator at 50/50 already covers end-island terrain.

Three "gaps" were three things we had already shipped and just
hadn't internalized. Four commits got created scaffolding duplicate
ports before the duplication landed. `git reset --hard 4f2b845`
dropped them. The Java fixture capturers
(`LegacyRandomFixtureCapture`, `SimplexNoiseFixtureCapture`) had
real value as parity validators against the existing Rust side, so
those re-landed pointed at the existing implementations: commit
`3def5ce` adds three LegacyRandomSource parity tests plus one
SimplexNoise test, all bit-exact against captured vanilla fixtures.

Lesson, durable: if a port "feels missing," confirm it actually is
missing before scaffolding the replacement. The five questions
catch novel work; they do not catch duplicate work, because
duplicate work passes "is vanilla actually the bottleneck"
trivially. Add a sixth check before any new file:
`grep -rn "<thing>" rust/mod/src` first.

After the duplication recovery, the session shifted to release
prep. Five commits of cleanup, one tag:

- `efae14b` gates five dead-target mixins out of
  `ferrite.mixins.json` for 26.1.x. The targets either did not
  exist on 26.1.2 (AquiferMixin's hooks moved, MaterialRuleContext
  fields renamed) or wrapped methods that 26.1.2 deobf had
  restructured. The mixin loader was warning loudly on each, and
  warnings on every world load erode trust in the rest.
- `82b9294` fixes factual claims in `CHANGELOG.md` and
  `CURSEFORGE_DESCRIPTION.md`. The 26.1.x port did not carry the
  MaterialRuleContext 3 ms/chunk surface win to 26.1.2 (the
  underlying class was restructured); both docs claimed it did.
  Same commit fixes yarn → mojmap renames in the 0.6.2 entry:
  `cookingTimeSpent` → `cookingTimer`, `setStack` → `setItem`,
  `setEditor` → `setAllowedPlayerEditor`,
  `WorldChunk.updateTicker` → `LevelChunk.updateBlockEntityTicker`,
  `BlockState.getBlockEntityTicker` → `BlockState.getTicker`.
- `522b072` populates `fabric.mod.json` contact URLs. Modrinth and
  CurseForge surface these on the mod page; missing them looked
  unfinished.
- `2a798e1` bumps `mod_version` to `0.6.3-alpha+26.1.2`. The
  `+26.1.2` suffix is SemVer build metadata, encoding which MC
  version this jar targets without burning the version namespace
  on per-MC variants.
- `0266ae8` adds a measurement-scope disclaimer to
  [PIANO_STATUS.md](PIANO_STATUS.md) so readers do not assume the 1.21.11
  baseline numbers carried to 26.1.2 unchanged.

Tag pushed as `0.6.3-alpha+26.1.2`. CI picked up the tag and
published to Modrinth and CurseForge per the existing release flow.

The whole session was the opposite shape from a typical port arc.
No new Rust code, one targeted test commit, five small-but-honest
cleanup commits, one release. That is also the work, when the
work is verifying what shipped.

## Walkability cache: the pathfinding hotpath

### Framing the target

The question that opened this arc was simple: when a mob is
pathfinding, where does the time go? The answer from a brief
source read: `WalkNodeEvaluator.getPathTypeFromState(BlockGetter, BlockPos)`.
That static method is called by `PathTypeCache.getOrCompute` on every
cache miss. `PathTypeCache` is a 4096-slot direct-mapped cache keyed on
block position, so in a crowded area with many mobs covering distinct
positions, the miss rate is high. Each miss resolves to a `getBlockState`
call (chunk array read through the level-access chain) followed by a
50-line classification chain touching block properties, tags, and method
calls. Hundreds of nodes per path, many paths per tick, many ticks.

The five-question gate passed cleanly. Vanilla's steady-state cost per
call is not in the low-nanosecond range that kills a port (no HotSpot
memoization equivalent to `CacheOnce`). The classification logic is
pure-math against block properties with no world callbacks. JNI
overhead is affordable if we snapshot sections at fill time rather
than paying per-node. Parity is oracle-testable by comparing our
predicted `PathType` against `PathTypeCache`'s actual output. No
concurrent-chunkgen mod overlap.

### Sessions 1-3: infrastructure

Commit `37729e2` landed the full infrastructure stack in one shot, then
three follow-on commits (`8338427`, `d7905ad`, `09b5814`) hardened the
parity gate.

The Java side: `NavigationCacheBridge.java` holds an 18-category block
classifier (`encodeBlockKind`) that maps every `BlockState` to one of:
AIR, OPAQUE_FULL, SLAB_BOTTOM, SLAB_TOP, STAIRS, FENCE, WALL, DOOR,
FENCE_GATE, TRAPDOOR_OPEN, TRAPDOOR_CLOSED, LADDER, SCAFFOLDING,
CARPET, WATER, LAVA, LEAVES, OTHER. The classifier runs once per block
change, not per pathfinding query.

The Rust side: `rust/mod/src/nav_cache.rs` and
`rust/mod/src/nav_cache_storage.rs` store one `Vec<CellData>` (4096
bytes, flat array) per section, keyed by `SectionId`. The store evicts
on block change via the existing `LevelSetBlockMixin` dispatch path.
JNI surface: `navSnapshotSection` (fill a section from Java's
`BlockState` scan), `navOnBlockChanged` (single-cell eviction),
`navGetCellKind` (per-position lookup for the parity gate).

The parity gate: `checkPathParity` in `NavigationCacheBridge` runs
after every `PathFinder.findPath` call and compares our predicted
`PathType` category against `PathTypeCache`'s actual result.
`predictCategory(byte cellKind, byte floorKind)` encodes the
2-block-model logic: what kind of block is in the cell, and what kind
of block directly below provides or denies a floor. The gate logs any
mismatch with kind values and cell coordinates so regressions surface
immediately.

The parity gate required three fix passes before it stabilized:
- `8338427` restricted fluid kind to `LiquidBlock` only (flowing
  instances keyed differently in the registry, causing phantom WATER
  entries for non-liquid blocks).
- `d7905ad` fixed the aquatic filter (sea-grass, kelp, coral) that was
  falling into KIND_OPAQUE_FULL, corrected KIND_OTHER handling for
  blocks that have no unambiguous single category, fixed the water-floor
  case (standing on a water block's surface), and fixed dripleaf
  (small dripleaf has no collision but vanilla's classifier still
  returns WALKABLE via shape inspection).
- `09b5814` made the predictor floor-aware and restricted snapshot
  fill to the Y range actually requested by the path, avoiding
  snapshot work on sections that will never be queried.

Stable parity result: 6 structural mismatches per session, all
`predictCategory` limitations documented below.

### Session 4: the payoff

The infrastructure read: looks complete. But `navGetCellKind` crosses
the JNI boundary per call, at roughly 100 ns. Vanilla's
`getBlockState` + classification chain is roughly 50-100 ns too.
Replacing one 100 ns call with another 100 ns call is not a win.

The solution was a Java-side mirror of the section kind data.
`NavigationCacheBridge` gained a second direct-mapped cache alongside
the Rust store: 512 slots, `long[] JAVA_KEYS` and `byte[][] JAVA_KINDS`,
EMPTY_KEY = `Long.MIN_VALUE`. `kindAt(int x, int y, int z)` is a
pure Java method: key check, array index, byte read. No JNI, no
allocation, roughly 5-10 ns per call. The Java-side cache is filled in
`snapshotSection` alongside the Rust store and evicted in
`onBlockChanged` when `oldKind != newKind` (door state changes do not
need Java eviction because `kindToPathType(KIND_DOOR)` returns null,
falling through to vanilla regardless).

`kindToPathType(byte kind)` maps 14 of the 18 kinds unambiguously:
AIR/LADDER/SCAFFOLDING/CARPET to OPEN, OPAQUE_FULL/SLAB/STAIRS to
BLOCKED, FENCE/WALL to FENCE, TRAPDOOR to TRAPDOOR, WATER to WATER,
LAVA to LAVA, LEAVES to LEAVES. DOOR, FENCE_GATE, and OTHER return
null, falling through to vanilla's full classification.

The mixin: `WalkNodeEvaluatorMixin` injects at HEAD of the static
`getPathTypeFromState(BlockGetter, BlockPos)` method with
`cancellable = true`. If `kindAt` returns -1 (section not snapshotted)
or `kindToPathType` returns null (ambiguous kind), the handler returns
early and vanilla's path executes unchanged. Commit `fe44366`.

Five blocks were redirected to KIND_OTHER before the `canOcclude()`
check in `encodeBlockKind`: MAGMA_BLOCK, HONEY_BLOCK, POWDER_SNOW,
lit campfires, and LAVA_CAULDRON. Without those exceptions,
`canOcclude() == true` on those blocks would classify them as
KIND_OPAQUE_FULL, making `kindToPathType` return BLOCKED for blocks
that vanilla classifies as hazards (DAMAGE_OTHER, POWDER_SNOW,
DAMAGE_FIRE) rather than BLOCKED.

### Empirical water correction

The first test run showed `predicted=BLOCKED vanilla=WATER` for
KIND_WATER cells at five nodes in a path near water. The session 3
design had assumed `LiquidBlock.isPathfindable(LAND) = false` based
on general knowledge of how water interacts with land pathfinding.

Reading `26.1.2/decompiled/net/minecraft/world/level/block/LiquidBlock.java`
directly: `isPathfindable(LAND)` returns `true` in 26.1.x.
`getPathTypeFromState` calls `isPathfindable(LAND)` and returns
`PathType.WATER` when true. Fix: `kindToPathType(KIND_WATER)` changed
from BLOCKED to WATER, `predictCategory` cell-KIND_WATER case changed
to "WATER", floor-KIND_WATER case changed to "OPEN" (standing on
water surface gets OPEN from the floor lookup, not WATER).

Lesson, durable: block API behavior changes across MC versions. Do
not assume what `isPathfindable`, `isValidSpawn`, or similar returns.
Read the decompiled source for the version you are actually on.

### Structural parity limits

After the water fix, two families of persistent mismatches remained,
both pre-existing structural limits of the 2-block model:

**Deep-landing (18 OPEN→WALKABLE mismatches).** Vanilla's
`getPathTypeFromState` does not just look at the target block. For
certain non-solid blocks, it traces downward to find the actual
surface and classifies the block the entity would stand on. Our model
sees only the cell block and the immediately adjacent floor block.
Replicating vanilla's trace would require reading more blocks per
prediction, erasing the speed advantage.

**Neighbor-hazard (29 WALKABLE→OTHER mismatches).** Vanilla's higher
layers call `checkNeighbourBlocks`, which scans 26 surrounding
positions for cacti, fire, and lava and overrides the cell's type to
DAMAGE_CACTUS, DAMAGE_FIRE, or LAVA. The `getPathTypeFromState`
boundary we are patching is below that scan; our mixin fires before
the neighbor check runs. Replicating it would require reading 26
additional blocks per predicted node, which is worse than vanilla.

Both limits are documented in `LOCAL_DESIGN.md`. Neither is a
regression; both existed before session 4 and both produce false
negatives (we return OPEN when vanilla returns BLOCKED or OTHER),
meaning the mob takes a path that vanilla would reject as dangerous.
The frequency is low (under 50 mismatches across all paths checked
in a session) and the failure mode is a mob walking toward a cactus
rather than a mob getting stuck, which is the safer direction to
fail.

### State after sessions 1-4

The cache infrastructure is complete and default-on (the parity gate
is behind a runtime flag, the `WalkNodeEvaluatorMixin` is always
active for snapshotted sections). The open question is the actual
tick-time delta: how much does the mixin reduce server tick time with
100+ mobs pathfinding in a flat area? That measurement has not been
taken yet. Session 5 begins with a JFR run.

### Coupling hazards - what breaks silently

The nav-cache has more coupling surface than the earlier ports. The
cramming and redstone kernels are stateless per-call: hand data in,
get results back, nothing persists between calls. The nav-cache keeps
live state on both sides of the JNI boundary simultaneously. That
introduces failure modes that produce no compile error and no runtime
exception - just wrong behavior under specific world conditions.

Five things that look like safe cleanup targets but are load-bearing:

**Kind constant table.** The 18 discriminants (KIND_AIR through
KIND_OTHER) are defined identically in Java and Rust. One renumbering
or insertion on either side without matching the other produces silent
misclassification for every block at or after the changed value.
No compile error. The parity gate catches it, but only if run.

**Cell buffer layout.** `snapshotSection` packs 4 bytes per cell in
a specific order. The Rust side unpacks them by position, not by name.
Reordering the fields on the Java side without touching the Rust
`CellData` struct swaps which byte lands in which field. The section
fills without error; the cache returns wrong kinds.

**Section index formula.** `(ly<<8)|(lz<<4)|lx` appears in three
places: the Java fill loop, `kindAt`, and Rust's `cell_index`. All
three must agree. Changing one without the others produces wrong
lookups - the cache is populated but reads the wrong cell for every
position.

**OnceLock worldgen bootstrap timing.** The entire noise, biome, and
density stack initializes once from the world seed at load time via
`OnceLock`. Moving the bootstrap earlier, adding an async path, or
calling it before the seed is available produces either a panic or
a zeroed-seed worldgen that generates wrong terrain silently.

**Eviction contract.** Java and Rust must evict sections under the
same conditions. `onBlockChanged` evicts the Java-side direct-mapped
cache; `navOnBlockChanged` evicts the Rust HashMap. Both are gated on
`oldKind != newKind`. Adding a new block-change path that fires one
without the other lets the two caches diverge - the Java side serves
stale kinds for positions that Rust correctly evicted, or vice versa.

None of these are hypothetical. Each one is a real failure mode that
produces plausible-looking wrong behavior: mobs pathfinding through
recently-placed walls, wrong terrain at specific coordinates, hazard
blocks misclassified as walkable. The parity validators and oracle
gates are the only thing that catches them before they ship.

### Forward: what session 5 measures

Before session 5 scopes any further optimization, two measurements
are needed. The first is the timing delta under ideal conditions: a
flat area with 100+ hostile mobs and minimal block updates, comparing
pathfinding tick cost with the cache active versus disabled. The
second is the same measurement under block-update pressure: an active
redstone or piston array adjacent to the mob area, high eviction rate,
worst-case for the kind-diff filter.

The second scenario is the one that decides whether this stays
default-on. A piston array near pathfinding mobs is exactly
the workload that could flip the cache from a win to a regression if
the eviction rate outpaces the fill rate. The timing comparison under
ideal conditions confirms the win exists; the adversarial scenario
confirms it holds under production-realistic server conditions.

Session 5 also adds a hit-rate counter so the measurement is not just
"timing went up or down" but "here is how many calls were served from
cache versus fell through to vanilla." Without that, a timing
improvement could be noise. With it, the session produces a number
that can be cited in the release notes alongside the cramming and
redstone wins.

## The night the queries stopped: validating the index, then deleting the work

### Four checks in one evening

The bitset grid landed on 2026-07-29 with a 34% cut and a gate pass,
but one session is one session. Four things stood between it and any
default-on talk: a longer soak, a low-end A/B, the typed overload
question, and whether it could share the seam with other optimization
mods. All four got answered on 2026-08-05, and the last one changed
what we built next.

The soak was boring in the way you want. Forty-five minutes AFK at
the 1022-zombie farm, oracle sampling 1 in 16, and the counters never
moved off their line: per_query 9.7 to 9.9 us the whole run,
10,843,869 oracle checks, zero mismatches across 541 consecutive
windows. Two orders of magnitude more coverage than the landing-day
83k. No drift, no rebuild pathology, no spikes.

The pi-sim A/B was not boring. Same 4-core / 2G / ZGC recipe from the
July session, matched 12-minute windows, and the constrained cores
paid more for every candidate walk than the desktop did: per_query
fell from 14.0-15.1 us to 6.0, query tick-cost from 16.3-17.6 ms to
7.0, whole-server mspt from 41-44 to about 29.5. A 57% cut where the
desktop showed 34. The win grows exactly where the field reports
hurt, which is the best property a low-end optimization can have.

The typed overload closed itself. New counters on the 5s line showed
around 200 typed queries per tick and zero of them walking anything;
they all hit empty class-groups and return before the loop starts.
Per-class bits would have optimized a code path that already costs
nothing.

### The compat run that reframed the whole candidate

The fourth check was compatibility at the seam, tested by installing
a popular optimization mod beside the grid and watching for trouble.
There was no trouble. Both mods' mixins applied to the same classes,
zero injection failures, 105 clean windows, zero oracle mismatches.
The planned detect-and-yield logic turned out to be unnecessary.

The numbers were the interesting part. With that mod present, plain
query volume fell from about 1170 per tick to 82, and the farm ran at
15.3 mspt where our cache-only run sat at 32.7 under the same
conditions. Its approach never touches the query itself. It rewrites
the callers so most queries are never issued. Our grid makes each
query about 40% cheaper; caller rewrites made 93% of them disappear.
Avoidance beats acceleration, and we had just spent two weeks
building very good acceleration.

That could read as bad news. It is actually a map. If the queries
can be avoided, the question becomes which ones, and whether they
can be avoided without bending vanilla behavior, which is the line
we do not cross and the other mod happily does.

### One StackWalker probe, one number: 87.4%

A sampled caller-attribution probe (StackWalker on 1 in 16 queries,
bucketed by first frame outside the query plumbing) answered the
"which ones" question in five minutes of farm time. Entity#collide
issues 87.4% of all queries at the farm. Targeting scans are 7.1%.
Cramming's pushEntities, the thing everyone assumes dominates a
zombie pile, is 0.1%, because the cramming dispatcher already owns
that lane.

Then the source read turned the 87.4% into a gift. Entity#collide
goes through getEntityCollisions, and that walk's predicate only
ever accepts hard-collidable entities. In 26.2 the complete override
set for canBeCollidedWith is three families: boats, shulkers, and
the happy ghast. A zombie farm contains none of them. So 87% of the
farm's queries walk a thousand entities to accept zero, every tick,
and the correct result is known before the walk starts.

One wrinkle, caught in the same read: canCollideWith is
source-dependent. Boats and minecarts widen it to accept pushables,
so a boat's collision query genuinely needs the walk. The skip gate
therefore checks two things: the level holds zero hard-collidable
entities, and the source is not a boat or minecart. When in doubt,
walk.

### ColliderSkip: 150 lines, 91%

The build took under an hour because every piece already existed.
A per-level count of the three hard-collider families, maintained on
Fabric's entity load/unload events. An interface mixin on
EntityGetter.getEntityCollisions that returns the empty list when the
count is zero and the source predicate is the default. And the oracle
pattern, reused a third time: 1 in 16 eligible queries fall through
to the vanilla walk, and a non-empty result logs a mismatch.

A/B at the farm against the same night's baseline: storage queries
1171 to 209 per tick, query tick-cost 12.8 to 1.15 ms, whole-server
mspt from the high 30s to about 20.5, TPS flat at 20. The oracle ran
535,137 verification walks and found zero non-empty results. The
skip beats the grid it was meant to supplement, and they compose;
the grid still serves the 209 that remain.

The residual risk is documented rather than solved: a modded entity
that widens canCollideWith the way boats do would make the skip
wrong, and the oracle would catch it in any world where it runs. The
other open question is boats. One boat anywhere in the level zeroes
the whole-level skip. If field data shows real worlds always have a
boat loaded somewhere, the count moves per-section, which is the
same maintenance the grid already does.

Both features are opt-in and unreleased. The arc from "validate the
index" to "the index is the smaller half of the win" took one
evening, and the lesson is the same one the JIT wall taught in
April: measure what the system actually does before optimizing how
it does it.

## First night in the Nether (2026-08-08)

Everything Ferrite had been measured on so far lived in the
overworld. Discussion #11 promised Nether numbers, so this session
went and got some, starting with the nastiest workload the Nether
offers: the gold farm, hundreds of zombified piglins stuffed into a
one-block hole on purpose.

Nobody wants to build that by hand, so a datapack did it. One
function carves an obsidian rig into the nether at 0 60 0, turns
off natural spawning for clean numbers, and disables cramming
damage (the first pile of 800 suffocated in seconds, which was
funny once). Other functions dump 100, 300, or 800 persistent
piglins into the pit. The whole rig is five mcfunction files and a
/reload. Two dumb obstacles on the way in: PowerShell wrote the
files with a UTF-8 BOM, which mcfunction parsing rejects with a
useless "unknown function" error, and 26.2 renamed every gamerule
(doMobSpawning is now spawn_mobs; the registry rename ate the old
camelCase names).

### An idle pile is free, a dense pile is not

First surprise: 300 idle piglins cost almost nothing. Around 140
queries per tick, a tenth of a millisecond. Piglins are neutral;
they stand there. Movement is what drives collision queries, and a
calm pile barely moves. Overworld zombie numbers do not carry over
to a calm pile, exactly as #11 suspected they wouldn't.

Push the pile to ~914 and the picture flips. Vanilla baseline:
878 queries per tick at 19.5 us each, 17.2 ms of query cost per
tick, monster ticking at 24.6 ms average. That is worse per query
than the 1022-zombie farm, because the whole pile shares ONE entity
section. Every query walks all 900 neighbors.

### The grid's worst case is the skip's best case

That one-section detail set up a result we could not have staged
better. The bitset grid prunes candidates by cell; when everything
lives in the same cell, it prunes nothing, and the counters showed
exactly that, 0.0% filtered. The collider skip, though, answers
"any boats, shulkers, or minecarts here?" and a piglin pile has
none, so 93.7% of eligible queries returned empty without walking
anything.

Desktop A/B at ~914 piglins: query cost 17.2 to about 1.0 ms per
tick, monster tick 24.6 to 13.0 ms. Then the pi-sim pass, four
cores and a 2G heap, same pile: query cost 19.5 to 0.7 ms, monster
tick from roughly 27 down to 9. The win grows as the hardware
shrinks, same shape as the overworld zombie test, and that is the
number that matters for the Pi crowd #11 is aimed at. Oracles ran
the whole time on both features. Thousands of sampled verification
walks per window, zero mismatches, all night.

### The 80,000 pushes that weren't

One number nearly derailed the evening. The cramming dispatcher
reported ~150 to 300 pushes per window in every baseline run, then
~80,000 in the first run with the optimizations on. A 400x behavior
difference attached to the feature would have been disqualifying,
oracle or no oracle, because the oracle checks query answers, not
downstream motion.

So it got the hypothesis treatment instead of a shrug. Fresh pile
versus settled pile: no change. Survival versus creative: no
change. Then the matched test, fresh pile with the optimizations ON
under pi-sim: 256 to 295 pushes, completely normal. The feature was
innocent. The likely culprit is embarrassing in hindsight: that one
run began by teleporting the player directly onto the pit, and 900
piglins spent every tick shoving against a body standing in their
hole. The lesson filed next to the others: a scary correlation
deserves a controlled test before it deserves a panic.

### What the Nether said so far

The gold-farm shape is real, it is the collider skip's home turf,
and the win on small hardware is the largest Ferrite has measured
anywhere. Still open before this leaves the opt-in shelf: an
angered pile (group anger means pathing plus real movement),
fortress natural spawning, a mixed load with ghasts and magma
cubes, and one source question with teeth: if striders count as
hard-collidable, a single ambient strider in a lava ocean disarms
the whole-level skip, and the per-section count stops being an
upgrade path and becomes the design.

