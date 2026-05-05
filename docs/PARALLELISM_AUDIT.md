# Parallelism Audit (2026-04, Minecraft 1.21.11)

A retrospective on a parallelism investigation that pivoted twice before producing useful conclusions. Documents what was true, what was assumed, and where the reasoning broke down — so the next person reading this (future-me, or another contributor) doesn't replay the same mistakes.

## TL;DR

- **Vanilla already runs the heavy noise-fill compute on a multi-threaded ForkJoinPool.** "Parallelize chunkgen noise" was already done by Mojang. We almost built scaffolding to do it again.
- **The dispatcher tail (p999 = 100 ms) we measured is not noise-fill** — it's coordination overhead on a single-threaded `SimpleConsecutiveExecutor`, and the recoverable wallclock is much smaller than the headline number suggested.
- **One first-pass audit fabricated a thread-safety race** (`AquiferSampler` allegedly sharing state across chunks) that doesn't exist in source. Caught only by direct file reads.
- **The dispatcher latency probe is kept** as real measurement infrastructure. The "Path A: replace worker SCE with FJ pool" plan is reverted — premise was false.

## The pivot, in three steps

### Step 1 — What we initially claimed

> Real points of serialization in 1.21.11:
> - `ChunkTaskScheduler.dispatcher` 4-level priority queue
> - `ServerLightingProvider.pendingTasks` global queue + 1000-item batch
> - `ChunkGenerationContext.chunksToSave` LongLinkedOpenHashSet, allegedly unguarded

This came from a first-pass audit. **Three of those four claims were wrong or misframed:**

1. The dispatcher is a `PrioritizedConsecutiveExecutor` — single-threaded by *design*, not "queue contention." The 4 levels are priority ordering inside one thread, not parallelism. ([ChunkTaskScheduler.java:28](../1.21.11/common/net/minecraft/server/world/ChunkTaskScheduler.java#L28))
2. `pendingTasks` is also single-threaded (the producer side runs through the same dispatcher). No lock contention. ([ServerLightingProvider.java:131-136](../1.21.11/common/net/minecraft/server/world/ServerLightingProvider.java#L131-L136))
3. `chunksToSave` lives in `ServerChunkLoadingManager`, not `ChunkGenerationContext` (which is a 6-line immutable record with no fields like that). The audit confused two classes. ([ServerChunkLoadingManager.java:152](../1.21.11/common/net/minecraft/server/world/ServerChunkLoadingManager.java#L152))

### Step 2 — What we then planned

> Build a dispatcher latency probe to measure whether the gate is actually saturated. If yes, build "concurrent in-flight entries" — let entry N+1 schedule before entry N's tasks complete.

The probe was a good move. Results from one 85-second flight session:

| scope     | n      | p50     | p99    | p999     | max     |
|-----------|--------|---------|--------|----------|---------|
| worldgen  | 147362 | 24.6us  | 6.29ms | 100.66ms | 171.63ms|
| light     | 211078 | 12.3us  | 3.15ms | 50.33ms  | 92.19ms |
| ticket    | 197572 | 786.4us | 25.17ms| 25.17ms  | 31.50ms |

Real numbers, real bursty tail. Looked actionable. Plan was to bypass the wait-for-completion barrier in [`ChunkTaskScheduler.schedule`](../1.21.11/common/net/minecraft/server/world/ChunkTaskScheduler.java#L88-L93) so the dispatcher could pull entry N+1 while entry N's tasks were still running.

### Step 3 — What turned out to be true

Then we read the actual file `schedule()` calls into:

```java
this.executor.executeAsync(future -> { runnable.run(); future.complete(...); })
```

The `executor` field is `SimpleConsecutiveExecutor` — *also* single-threaded. So the supposed "fan out to a worker pool" doesn't actually fan out. Fine, that means the parallelism plan needs a different shape.

Then we audited noise-fill thread-safety to validate Path A (replace the worker SCE for noise-fill with a real parallel executor). The audit came back clean — every component is per-chunk-instantiated or concurrent-safe. Path A looked viable.

Then we read where `populateNoise` actually gets submitted:

```java
// NoiseChunkGenerator.java:362
return ... CompletableFuture.supplyAsync(() -> {
    ...
}, Util.getMainWorkerExecutor().named("wgen_fill_noise"));
```

`Util.getMainWorkerExecutor()` is a real multi-threaded `ForkJoinPool` ([Util.java:210](../1.21.11/common/net/minecraft/util/Util.java#L210)). `NameableExecutor.named(...)` is a thin name/Tracy wrapper, not a serializer ([NameableExecutor.java:11-31](../1.21.11/common/net/minecraft/util/thread/NameableExecutor.java#L11-L31)).

**Vanilla noise-fill bypasses the SCE entirely and runs on a real parallel pool.** The "parallelize noise-fill" plan was solving a problem Mojang already solved.

The dispatcher tail we measured was real, but it represents coordination overhead on the orchestration SCE — not the heavy compute. Recoverable wallclock from fixing it is bounded by orchestration cost, which is much smaller than I had implied (~5–10 % wallclock at best, vs. the 28 % I had floated).

## What we got right

- **Building the probe before the parallelism scaffolding.** Without the probe data, we'd have built a fix and shipped it, then watched it produce no measurable speedup. The probe's measurement-first discipline caught the "noise-fill is already parallel" issue *before* writing the parallel executor — at the audit stage, not the post-deploy stage.
- **`FerriteDispatcherProbe` is genuine reusable infrastructure.** Per-scope log-bucket histograms with p50/p99/p999 for any `PrioritizedConsecutiveExecutor`. Future questions like "is this scheduler saturated?" now have a one-line answer instead of a half-day investigation.
- **The 1.21.11 source extraction (`1.21.11/`) was the right move.** Every claim above gets a `file:line` reference. None of this works without local source-of-truth.
- **The thread-safety audit conclusion is correct on its own terms.** ChunkNoiseSampler, AquiferSampler.Impl, Blender, MultiNoiseSampler are all per-chunk-instantiated or record-immutable. NoiseConfig's hot maps are `ConcurrentHashMap`. That's a useful piece of documentation even though it doesn't unlock new code — it confirms vanilla's parallel design is sound.
- **The dispatcher-tail finding (p999 = 100 ms) is a real signal.** It shrinks in importance once you understand it's coordination not compute, but it's still a real cost worth a follow-up.

## What we got wrong

### Wrong-1: first-pass audit (the AquiferSampler "race")

The first-pass audit claimed:

> AquiferSampler is **shared across chunks** via the aquifer cache (water level and block position grid). Two nearby chunks' sampler regions overlap by padding (...). When `apply()` mutates `blockPositions[ac]` or `waterLevels[i]` concurrently, data races occur.

This is wrong by direct source inspection:

- [ChunkNoiseSampler.java:47](../1.21.11/common/net/minecraft/world/gen/chunk/ChunkNoiseSampler.java#L47) — `private final AquiferSampler aquiferSampler;` (instance field)
- [ChunkNoiseSampler.java:168](../1.21.11/common/net/minecraft/world/gen/chunk/ChunkNoiseSampler.java#L168) — `this.aquiferSampler = AquiferSampler.aquifer(...)` (constructed inside ctor)
- [AquiferSampler.java:30](../1.21.11/common/net/minecraft/world/gen/chunk/AquiferSampler.java#L30) — `return new AquiferSampler.Impl(...)` (fresh per call)

`AquiferSampler.Impl` is per-chunk-instantiated. Its `blockPositions` and `waterLevels` arrays are per-instance. Two chunks have two `Impl` objects with two different arrays. The audit confused **spatial overlap** (chunk A's sampler can read positions also covered by chunk B) with **memory sharing** (writes to the same array). They are not the same.

If we'd taken that audit at face value, the next step would have been "build a synchronization layer around AquiferSampler" — solving a non-problem, adding overhead, leaving the actual situation undocumented.

### Wrong-2: assuming SCE serialization extended to the heavy compute

Reading [ChunkTaskScheduler.schedule](../1.21.11/common/net/minecraft/server/world/ChunkTaskScheduler.java#L88-L93) and noting the `executor` field is a `SimpleConsecutiveExecutor`, we concluded "the worker pool is single-threaded too, so vanilla chunkgen is end-to-end serial." That conclusion is partially correct — the *orchestration tasks* routed through that executor are serial — but it's wrong about the *heavy compute*. `populateNoise` doesn't go through that executor at all. It uses `Util.getMainWorkerExecutor()` directly via `supplyAsync`, sidestepping both the dispatcher and the worker SCE.

The mistake was extrapolating from the type of `ChunkTaskScheduler.executor` to "everything chunkgen does goes through it." The right move would have been: as soon as the executor type was identified, grep `populateNoise` and `populateBiomes` to see *what executor they actually submit to*. That grep would have surfaced `Util.getMainWorkerExecutor()` immediately and saved the rest of the detour.

### Wrong-3: estimating recoverable wallclock from p999 alone

Mid-audit I floated "the dispatcher tail is ~28 % of wallclock during sustained flight, derived from p999 × event count." That number was bait. p999 latency on a queue is not the same as recoverable wallclock — many of those waits overlap with other work the system is doing on other threads, and the work-completing event that ends the wait is *also* the event that unblocks 100 other tasks at once. Treating it as additive cost double-counts. The realistic ceiling is much lower.

Lesson: **don't multiply tail-latency by event count to get wallclock.** Do an A/B (probe with vs without proposed fix) before claiming a number.

## The first-pass superficiality pattern

Both first-pass audits in this investigation produced reports that:

- Looked authoritative (file:line references, structured verdicts, professional tone)
- Stated conclusions confidently (`VERDICT: NO — UNSAFE RACES`)
- **Were materially wrong on the load-bearing claim**

Specific failure modes observed:

1. **Class-name confusion.** Confusing `ChunkGenerationContext` (an immutable record) with `ServerChunkLoadingManager` (the class with the actual mutable state). The first-pass report cited "line 152" — that line exists in the manager, not the context, but the report attributed it to the context.
2. **Conceptual-vs-physical confusion.** Equating spatial overlap of sampler regions with memory sharing of arrays. These are different things; one implies the other only if you assume a shared instance, which the audit did without checking.
3. **Verdict before proof.** Reports led with `VERDICT: ...` followed by reasoning, when the reasoning didn't actually support the verdict. The structure invites the reader to trust the verdict without re-deriving it.

This isn't a complaint about first-pass audits being useless — they're great for breadth (`grep across N files in parallel`) and for enumeration. It's about not letting their output reach a decision without a direct-source verification step in between.

### Operational rule for this codebase going forward

**Any first-pass audit that asserts a race condition, a missing field, or a "hidden bug" must be followed by a manual `Read` on the cited file before that claim influences a code change.** The cost of one Read is ~5 seconds. The cost of building scaffolding around a fabricated race is hours of code we throw away — or worse, hours we don't throw away because we ship it without realizing the premise was wrong.

For this investigation specifically:

- The first-pass audit was disregarded the moment a single Read of `ChunkNoiseSampler.java` showed the per-chunk instantiation. ~30 seconds of direct-source verification saved a real wrong turn.

## What's kept vs reverted

### Kept in tree

| Artifact | File | Why kept |
|---|---|---|
| Dispatcher latency probe | [`FerriteDispatcherProbe.java`](../src/main/java/me/apika/apikaprobe/FerriteDispatcherProbe.java), [`FerriteDispatcherProbeMixin.java`](../src/main/java/me/apika/apikaprobe/mixin/FerriteDispatcherProbeMixin.java) | Reusable measurement infra; `/ferrite probe dispatcher on/off/status/reset` works for any future "is this scheduler saturated" question |
| 1.21.11 source extraction | `1.21.11/` (gitignored) | Single source of truth for every audit, mixin verification, future cross-version reasoning |
| Wrapping.Type CamelCase anchor comment | [`BulkChunkDensityMixin.java:84-99`](../src/main/java/me/apika/apikaprobe/mixin/BulkChunkDensityMixin.java#L84-L99) | Load-bearing runtime-vs-source drift documentation, unrelated to the parallelism arc but discovered alongside it |
| Source audit doc | [`SOURCE_AUDIT.md`](SOURCE_AUDIT.md) | 42-mixin verification against 1.21.11; all clear, no drift |

### Not built (correctly aborted before code)

- "Concurrent in-flight entries" mixin — would have produced ~zero gain because the supposed worker pool is the SCE and serialized anyway.
- "Replace worker SCE with FJ pool for noise-fill" mixin — would have been a no-op because vanilla doesn't route noise-fill through the worker SCE in the first place.
- Synchronization wrapper around `AquiferSampler` — would have solved a non-problem and added overhead.

### Not built (still on the table)

- **Run-duration probe** — extending `FerriteDispatcherProbe` to also capture `runEnd - runStart` per task, so we can see *which* lambda is taking the long tail. ~30 lines, would identify the real serialization wedge if there is one.
- **Aquifer port to Rust** — vanilla's aquifer is per-chunk and not parallelized internally; current cost ~22 ms/chunk per existing diagnostic. Concrete, achievable.
- **Lighting port to Rust** — similar story; ~14 ms/chunk init + ~16 ms/chunk steady, serial through the `light` SCE in vanilla.

## Methodology rules carried forward

1. **Probe before plan.** The dispatcher probe was the only thing in this arc that had no false positives — it just measured what was true. Build measurement before parallelism scaffolding, every time.
2. **Read the call chain to the executor, not just the class.** Knowing a field's type is `SimpleConsecutiveExecutor` tells you what tasks routed *through that field* do. It tells you nothing about where other tasks (`populateNoise`, `populateBiomes`) submit, because they may bypass the field entirely.
3. **First-pass audits are enumeration, not decisions.** Verify any load-bearing claim with a direct Read before changing code based on it.
4. **Don't multiply tail latency by event count to estimate wallclock recovery.** A/B with a probe-only fix first, or stay quiet about the number.
5. **`getMainWorkerExecutor()` exists and is parallel.** When something is on a hot critical path and *should* be parallel for vanilla to be reasonable, check whether it already is. Mojang has been at this for a decade; "obvious" parallelizations are often already done.

## Affected files inventory

Every file touched during this arc, with a verdict on whether it survives the pivot.

### Added (kept)

| File | Purpose | Verdict | Why safe to keep |
|---|---|---|---|
| [`src/main/java/me/apika/apikaprobe/FerriteDispatcherProbe.java`](../src/main/java/me/apika/apikaprobe/FerriteDispatcherProbe.java) | Per-scope log-bucket histogram, `wrap()` instrumenting `Runnable` queue-wait | **Keep** | Independent of any false premise; just measures latency on `PrioritizedConsecutiveExecutor.send` paths. Useful for any future "is this scheduler saturated" question. |
| [`src/main/java/me/apika/apikaprobe/mixin/FerriteDispatcherProbeMixin.java`](../src/main/java/me/apika/apikaprobe/mixin/FerriteDispatcherProbeMixin.java) | `@ModifyArg` on the four `dispatcher.send(new PrioritizedTask(N, ...))` sites in `ChunkTaskScheduler` | **Keep** | Mixin targets verified against [ChunkTaskScheduler.java](../1.21.11/common/net/minecraft/server/world/ChunkTaskScheduler.java) line 38/50/63/78. Default off; no behavior change when `ENABLED=false`. |
| [`docs/PARALLELISM_AUDIT.md`](PARALLELISM_AUDIT.md) | This document | **Keep** | The documentation is the artifact. |

### Modified (kept; localized changes)

| File | Change | Verdict | Notes |
|---|---|---|---|
| [`src/main/resources/ferrite.mixins.json`](../src/main/resources/ferrite.mixins.json) | Added `FerriteDispatcherProbeMixin` to the mixin list | **Keep** | One-line addition; load-bearing only when probe is enabled. |
| [`src/main/java/me/apika/apikaprobe/FerriteCommand.java`](../src/main/java/me/apika/apikaprobe/FerriteCommand.java) | Added `/ferrite probe dispatcher {on,off,status,reset}` subcommand and four handler methods | **Keep** | Pure additive subcommand path; doesn't alter existing commands. |

### Considered, never written (correctly aborted)

These are the files we *would have* created if the disproved premises had stood. None exist in tree; this section is a paper trail so the next person doesn't independently propose them.

| Hypothetical file | Purpose under the wrong premise | Why not written |
|---|---|---|
| `FerriteParallelChunkgen.java` | Toggle + lazily-created parallel `Executor` to substitute for the worldgen `SimpleConsecutiveExecutor` | The SCE doesn't carry the heavy work; substituting it gains nothing. |
| `FerriteParallelChunkgenMixin.java` | `@Inject` on `ChunkTaskScheduler.schedule` to enable concurrent in-flight entries | The "fan-out" inside `schedule()` already routes to a single-threaded SCE; concurrent entries don't unlock parallelism. |
| `FerriteAquiferGuardMixin.java` (or similar) | `synchronized` wrapper around `AquiferSampler.Impl.apply` to fix the first-pass-claimed race | The race doesn't exist — `Impl` is per-chunk-instantiated. Adding synchronization would be pure overhead. |
| `FerriteNoiseFillExecutorMixin.java` | Replace `Util.getMainWorkerExecutor().named("wgen_fill_noise")` with a custom executor at the `populateNoise` `supplyAsync` call | Vanilla is already on the FJ pool; substitution would give us a worse (or equivalent) parallel executor. |

### Adjacent files verified still accurate

Statements about threading exist elsewhere in the repo. Spot-checked the load-bearing ones; none required correction:

| File:line | Claim | Status |
|---|---|---|
| [`docs/JOURNEY.md:321`](JOURNEY.md#L321) | "Server tick is single-threaded for anything that touches world state." | **Still accurate.** This is about the *server tick thread* (entity ticking, block updates, redstone), which genuinely is single-threaded. Distinct from the chunkgen worker pool. The two are separate executors. |
| [`docs/SOURCE_AUDIT.md`](SOURCE_AUDIT.md) | 42-mixin verification | **Still accurate.** None of the audited mixins make threading claims; all targets are structural (method/field existence). |
| [`docs/VANILLA_WORLDGEN_REFERENCE.md`](VANILLA_WORLDGEN_REFERENCE.md) | Single-source-of-truth audit of seed/PRNG/noise/math | **Still accurate.** Doesn't make claims about *which executor* runs the work, only what the work computes. |
| [`src/main/java/me/apika/apikaprobe/mixin/ChunkPhaseMixin.java`](../src/main/java/me/apika/apikaprobe/mixin/ChunkPhaseMixin.java) | Comments mention "the public populateNoise (the real noise work)" without claiming threading | **Still accurate.** No threading claim. The mixin targets HEAD/RETURN of `populateNoise` for monitoring; works regardless of which thread populateNoise runs on. |
| [`src/main/java/me/apika/apikaprobe/ChunkGenMonitor.java`](../src/main/java/me/apika/apikaprobe/ChunkGenMonitor.java) | Logs noise-sync timings per call | **Still accurate.** Per-thread instrumentation; the existence of multiple `Worker-Main-N` threads in its log output is itself evidence vanilla noise-fill is already parallel — which we missed reading our own logs. |

### Worth re-reading after this arc (for context, not correction)

- [`docs/WORLDGEN_ARCHITECTURE.md`](WORLDGEN_ARCHITECTURE.md) — written before the executor topology was clarified; still accurate on the math, but a follow-up commit could add a section on "where vanilla parallelizes vs. serializes" using the call-chain findings here.
- [`docs/CACHE_FILL_PLAN.md`](CACHE_FILL_PLAN.md) — pre-existing density-buffer plan; unaffected by this arc but worth a re-read to make sure no statement implicitly assumes the SCE is the worker pool.

## Addendum: Ferrite-side worldgen code audit

Triggered by the realization that vanilla `populateNoise` runs on a multi-threaded `ForkJoinPool` — every Ferrite class that hooks into noise-fill or biome-lookup is touched by multiple `Worker-Main-N` threads concurrently. We re-audited the worldgen surface with that lens.

### TL;DR for the addendum

- **No correctness bugs.** Every Ferrite class on the chunkgen path is either pure, per-chunk-instantiated, set-once-then-read with `volatile`, or already uses thread-safe primitives (`AtomicLong`, `ConcurrentHashMap`, `OnceLock`, `ThreadLocal`).
- **Three performance gotchas.** Hot-path `AtomicLong.incrementAndGet()` calls in `RustBlendedNoiseWrapper`, `RustFlatCache`, and `RustBiomeRouter` are correct but cache-line-ping-pong across `Worker-Main-N` threads. Worse under multi-threaded chunkgen than they were when they were written. Same pattern we already fixed in `RustFinalDensityBufferWrapper.sample` for JIT inlining.

### Per-class audit

| Class / file | Multi-thread surface | Verdict | Notes |
|---|---|---|---|
| [`WorldgenStateBootstrap`](../src/main/java/me/apika/apikaprobe/WorldgenStateBootstrap.java) | All published state read by chunkgen workers | **Safe** | Fields are `volatile` + `Collections.unmodifiable*`. Init gated by `AtomicBoolean.getAndSet(true)`. Set-once-then-read pattern is textbook. |
| [`RustBridge`](../src/main/java/me/apika/apikaprobe/RustBridge.java) | All native calls; multiple workers can call simultaneously | **Safe** | Java side has no mutable state beyond `static final NATIVE_AVAILABLE`. JNI thread-safety pushed to Rust. |
| Rust `worldgen_state.rs` | Concurrent `WORLDGEN_STATE.get()` reads | **Safe** | `Mutex<Option<...>>` for builder (init-only); `OnceLock<WorldgenState>` for published state; concurrent reads lock-free. Type system enforces no other shared mutability. |
| Rust `density.rs` `LazyBlendedNoise` | Lazy noise-table init | **Safe** | `Arc<OnceLock<BlendedNoise>>`; `get_or_init` is race-free by design. |
| [`BulkChunkDensityFill`](../src/main/java/me/apika/apikaprobe/BulkChunkDensityFill.java) | Counters + per-typename log | **Safe** | All counters `AtomicLong`. `seenTypeNames.putIfAbsent` ensures the log fires exactly once even under concurrent calls. |
| [`RustFinalDensityBufferWrapper`](../src/main/java/me/apika/apikaprobe/RustFinalDensityBufferWrapper.java) | One instance per chunk; `sample()` called per-block | **Safe + Optimal** | Per-chunk instance, double-checked locking on `ensureBuffer`. Hot path is **atomic-free** (cold-path counters only) — already optimized for multi-threaded chunkgen even though it was done for JIT reasons. |
| [`RustBlendedNoiseWrapper`](../src/main/java/me/apika/apikaprobe/RustBlendedNoiseWrapper.java) | One instance per chunk; `sample()` called per-block | **Safe but suboptimal** | Same per-chunk + double-checked locking pattern. **But:** 4 hot-path `AtomicLong.incrementAndGet()` calls per `sample()`. Under N concurrent workers this thrashes one cache line N-ways. See "perf gotcha" below. |
| [`RustFlatCache`](../src/main/java/me/apika/apikaprobe/RustFlatCache.java) | Per-chunk instance; per-block sample | **Safe but suboptimal** | Same pattern. 2 hot-path atomic increments per sample. |
| [`RustBiomeRouter`](../src/main/java/me/apika/apikaprobe/RustBiomeRouter.java) | Static singleton, called from every chunkgen worker | **Safe but suboptimal** | Already uses `ThreadLocal<SlabCache>` — past code recognized multi-threaded chunkgen and built a per-thread cache for the slab. **But:** the diagnostic counters (`hitCount`, `missCount`, `totalNs`) are still hot-path atomics. The slab cache was the big win; the counters are leftover suboptimality. |
| [`DensityFunctionWalker`](../src/main/java/me/apika/apikaprobe/DensityFunctionWalker.java) | `fingerprint()`/`encode()` called from BulkChunkDensityMixin during chunkgen | **Safe** | Pure functions over read-only DF tree. Local `ByteArrayOutputStream` / `StringBuilder` per call; no shared mutable state. `seenUnknown` is `ConcurrentHashMap` for the unknown-once log. |
| [`BulkChunkDensityMixin`](../src/main/java/me/apika/apikaprobe/mixin/BulkChunkDensityMixin.java) | Fires per `getActualDensityFunctionImpl` call across workers | **Safe** | Stateless mixin body; only mutates static `AtomicLong` counters and reads from the thread-safe `WorldgenStateBootstrap.fingerprintToName()`. |
| [`FerriteDispatcherProbe`](../src/main/java/me/apika/apikaprobe/FerriteDispatcherProbe.java) (this arc) | Called from any executor | **Safe** | `ConcurrentHashMap<String, Stats>` keyed by scope; `Stats` uses `AtomicLong` + `AtomicLongArray`. By design. |

### The perf gotcha — hot-path atomics under multi-threaded chunkgen

Under single-threaded chunkgen (what we used to assume), `AtomicLong.incrementAndGet()` costs a few nanoseconds — a `LOCK CMPXCHG` on the local cache line, no contention.

Under multi-threaded chunkgen (the actual reality), the same call from N worker threads causes the cache line containing that `AtomicLong` to **bounce between N cores**. Each increment from one core invalidates the line on every other core via the cache coherency protocol. Throughput collapses from "a few ns per increment" to "tens of ns per increment plus pipeline stalls."

This is the "false sharing on a hot atomic" anti-pattern. It's correct (atomics still serialize properly), just slow.

Three places have it on what is now provably a hot multi-threaded path:

1. **`RustBlendedNoiseWrapper.sample`** ([line 89](../src/main/java/me/apika/apikaprobe/RustBlendedNoiseWrapper.java#L89), [95-96](../src/main/java/me/apika/apikaprobe/RustBlendedNoiseWrapper.java#L95-L96), [149](../src/main/java/me/apika/apikaprobe/RustBlendedNoiseWrapper.java#L149)) — vanilla calls `sample()` 2,425 corner samples × N chunks concurrently × N workers.
2. **`RustFlatCache.sample`** ([line 54](../src/main/java/me/apika/apikaprobe/RustFlatCache.java#L54), [67](../src/main/java/me/apika/apikaprobe/RustFlatCache.java#L67)) — same pattern at smaller per-chunk count.
3. **`RustBiomeRouter.tryRoute`** ([line 82, 123, 106, 120, 104](../src/main/java/me/apika/apikaprobe/RustBiomeRouter.java)) — vanilla calls biome supplier ~256 times per chunk × N chunks × N workers.

We already fixed exactly this pattern in `RustFinalDensityBufferWrapper.sample`. The fix template is documented [there](../src/main/java/me/apika/apikaprobe/RustFinalDensityBufferWrapper.java#L36-L40):

> Diagnostic counters live on the COLD path only (per-chunk JNI fill, wrapper construction, fallbacks). Per-block sample is JIT-critical and atomic operations there nuke pipelining.

Applying the same template to the three suboptimal classes:

- **Drop the hot-path counter** entirely if it's purely diagnostic (the simplest fix; matches `RustFinalDensityBufferWrapper`'s approach).
- **Or move the hot-path counters to `ThreadLocal<long[]>`** — each thread writes lock-free to its own array; `diagSummary()` aggregates by walking all known TLS instances (use a `Collection<long[]>` registered in TLS init). This preserves the per-call counts at near-zero per-call cost.

The fix is small (~10 lines per class). The gain depends on how loaded the chunkgen pool gets — likely material under sustained flight load, possibly negligible at idle.

### What this addendum changes about the parent retrospective

Strengthens the original verdict: **noise-fill is thread-safe per-chunk** — and not just on the vanilla side, but on the Ferrite-extended side too. Every wrapper, router, and walker we've added is structurally correct under multi-threaded chunkgen. The only remaining issue is the perf-gotcha pattern above, which is a tunable, not a redesign.

Also strengthens the methodology rule: **when you discover a path is multi-threaded that you previously thought was single-threaded, re-audit your own code on that path with the new lens.** This addendum is the application of that rule. We didn't find correctness bugs, but we did find suboptimality that's already fixable using a template we'd already developed.

### Suggested next moves (still informed by the parent doc)

The hot-path atomic fix is small and concrete. Ranked by likely impact:

1. **`RustBiomeRouter` counter strip** (one line per atomic; counters are pure diagnostic). Highest-traffic hot path.
2. **`RustBlendedNoiseWrapper` counter strip** (same approach). Second-highest hot path.
3. **`RustFlatCache` counter strip** (same approach). Lower volume but trivial.

Each can ship behind an "untested → tested" gate by running `runClient` and confirming chunk-gen wallclock didn't regress (and ideally improved at high parallelism). All three together are < 30 lines of change.

After that, the parent doc's "next moves" still apply: extend `FerriteDispatcherProbe` to capture run-duration of the slow tail, then decide between aquifer-port and lighting-port for the next major track.

## Aftermath

The investigation cost ~one session of context. Of that, the genuinely-productive output is:

- The probe (kept, useful)
- This document (so we don't replay it)
- A clearer mental model of vanilla's chunkgen executor topology

The discarded output is:

- An aborted parallelism scaffolding plan
- A retracted "28 % wallclock recoverable" claim
- One first-pass audit's fabricated race condition

That ratio is acceptable for a speculative perf investigation, but it would have been better with the methodology rules above applied from the start. They're applied now.
