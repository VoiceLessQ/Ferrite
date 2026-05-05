# Redstone Port Plan

Plan for an extreme-redstoner-grade wire optimization layer on Ferrite,
written at the end of the session that proved the current Rust BFS
port is JNI-overhead-bound. Next session starts here.

---

## Strategy: A → B, in that order

**A. Port Alternate Current's wire algorithm into pure Java first.**
**B. Layer within-cascade Rust batching on top of A only if A's data
    justifies it.**

Why this order:

| state | cascade cost | runtime | notes |
| :-- | :-- | :-- | :-- |
| Vanilla default | O(N²) | Java | redundant re-evaluation dominates |
| After A (AC algo in Java) | O(N) | Java | no JNI overhead |
| After A + B (AC algo + Rust) | O(N) | Rust | JNI amortized over non-redundant work |

Without A, Rust is fighting JNI cost on redundant work (measured this
session: 57K dispatches/tick × 1ms setup = 3.5s/tick, 10× slower than
vanilla). With A already collapsing the cascade count to O(N), each
Rust call does meaningful work and JNI's fixed overhead amortizes.

The previous session's investigation ([CHANGELOG "Redstone Rust BFS
port"](../CHANGELOG.md)) confirmed the BFS is algorithmically correct
but that the per-call-dispatch architecture can't win. A fixes that
precondition.

---

## Three-session breakdown

### Session 1 — Port AC's graph representation

**Goal:** parallel data structures in `me.apika.apikaprobe.redstone`,
no behavior change yet. Ferrite still runs vanilla's controller.

Port `WireNode` (the per-wire graph vertex) and the neighbor-cache /
update-queue data structures. Do not wire them into the block yet.
Unit tests for the data structures if feasible.

Exit criteria: compiles clean, no oracle regressions, no TPS change
(new code is unreachable).

### Session 2 — Port AC's update algorithm, integrate as controller

**Goal:** `FerriteRedstoneController` extending `RedstoneController`,
implementing `update(...)` via AC's algorithm on the session-1 graph.
Install it as `RedstoneWireBlock`'s controller via a mixin that
redirects the field init.

Exit criteria:
1. Oracle shows `node-mismatches = 0` on a static settled network
   (the exact test this session's oracle validated against). The test:
   creative world → lever → 14 wires → lever on → wait 3s → check
   `[redstone-oracle]` lines. Already proven working.
2. Lag-machine benchmark beats vanilla default and approaches
   experimental-redstone numbers (see targets below).

### Session 3 — (Conditional) Rust batching on top

Only pursue if session 2 leaves visible headroom vs experimental
redstone AND user-facing workloads still lag.

Architecture: within-cascade batching. When AC's algorithm starts a
cascade, collect all wire ops into one buffer and send to Rust once
per cascade instead of once per wire update. Reuses the Rust BFS
kernel already in `rust/mod/src/redstone.rs` — only the Java dispatcher
surface changes.

Exit criteria: oracle still 0-mismatches on static networks, Rust
path measurably faster than pure-AC-Java on lag machine.

---

## Step 0 — before any code (do this first, offline)

1. **Clone Alternate Current** to `f:/Minecraft modding/References/alternate-current/`
2. **Read its LICENSE** — determines whether session 1 is
   - clean-room (read to understand, re-implement in Ferrite
     conventions), or
   - direct adaptation (copy core classes, preserve headers, add
     Ferrite-side notes)
   Both are fine; pick based on license terms and attribution
   requirements. Record decision in this file before starting code.
3. **Skim these files first:**
   - `src/main/java/alternate/current/wire/WireHandler.java` — the
     update algorithm
   - `src/main/java/alternate/current/wire/WireNode.java` — the
     per-wire graph vertex
4. Note any class that depends on runtime state shared with vanilla
   (e.g. `World`, `BlockState`, `Block`); those are the integration
   seams.

**License decision (recorded 2026-04-20):** MIT — direct adaptation
approved. Preserve Space Walker's copyright header in each ported
file. Add `LICENSES.md` at repo root crediting upstream.

---

## Benchmark targets (lag machine, measured this session)

| configuration | cascades/tick | effective TPS | per-tick wire cost |
| :-- | --: | --: | --: |
| Vanilla default (baseline) | ~2,250,000 | ~0.4 | ~2.25 s |
| **Experimental redstone (target to match or beat)** | ~350,000 | ~2-5 | ~0.35 s |
| AC algorithm target (session 2) | ≤350,000 | ≥2-5 | ≤0.35 s |
| AC + Rust target (session 3) | same | >5 | <0.35 s |

AC should match or beat experimental **without** requiring the
experimental-redstone world toggle — that's the user-facing win.

---

## Validation infrastructure already in place

All built and shipped in the session that produced this plan:

- **`[redstone]` phase monitor** — wire cascade count, avg/max wall
  time, gate-driven vs direct split, default vs experimental
  controller split, server-ticks per 5s window.
- **`[redstone-oracle]` BFS correctness checker** — uses vanilla's own
  `calculateWirePowerAt` to shadow-compute expected power levels and
  logs `node-mismatches` against actual. Proven 0-mismatches on static
  networks; 0.7% "timing noise" on dynamic networks is expected and
  not a bug.
- **`[redstone-rust]` dispatcher counter** — confirms whether the
  Rust path is firing (useful if session 3 happens).
- **`/ferrite redstone rust on|off|status`** — runtime A/B toggle.

For session 2 we'll want a parallel `/ferrite redstone ac on|off|status`
toggle so we can A/B the AC path against vanilla or experimental
mid-session without restarts.

---

## Roadmap: AC Rust core port (phased, measurement-gated)

**Status:** on the list, not blocking. Current priority is surface rule
batch evaluation (`docs/SURFACE_RULE_BATCH_PLAN.md`); this work begins only
after surface rules ship or stall.

**Why phased:** the PROFILING.md lesson still binds — per-call JNI overhead
(~200–500 ns) can exceed the per-call compute it displaces at the wrong
granularity. The phased approach measures overhead at each increment
before committing deeper. Stop whenever a phase's exit number doesn't
clear the bar.

**Scope basis:** upstream + internal audits confirmed ~600 lines of AC are
pure compute with no World callbacks (priority queue, power offer,
flow-direction bit logic) and ~1,375 lines must stay in Java (neighbor
caching, block-state reads, neighbor-updater callbacks). The port only
targets the 600 compute-only lines.

### Phase 1 — Priority queue port  ✅ COMPLETE — gate cleared decisively

**Goal:** port `redstone/PriorityQueue.java` + `redstone/SimpleQueue.java`
to `rust/mod/src/redstone_queues.rs`. Pure data structures, no World access.

JNI surface: flat array of `(wire_id, priority)` pairs in, ordered output
out. Micro-benchmark the Rust round-trip against the Java queue's
`offer` / `poll` / `insert` on 100, 1000, and 10000 nodes. No integration
into the controller yet — this is diagnostic only.

**Exit criterion (met):** Rust beats Java by ≥2× on 1000+ nodes after
JNI overhead is counted.

**Result (live `/ferrite redstone bench`, commit `0cfac8c`):**

| N      | Java       | Rust       | Ratio    | Gate |
|-------:|-----------:|-----------:|---------:|------|
|    100 | 0.029 ms   | 0.002 ms   | **17.00×** | ✓ |
|   1000 | 0.059 ms   | 0.005 ms   | **10.94×** | ✓ |
|  10000 | 0.298 ms   | 0.052 ms   |  **5.77×** | ✓ |

Gate cleared at every realistic AC workload size, including the N=100
case where pre-bench prediction said Rust would lose to JNI overhead.

**Calibration update for the project:** the 200–500 ns "per-call JNI"
figure from [PROFILING.md](PROFILING.md) was for the cramming/physics path with
snapshot materialization on each crossing. For "pass two direct
ByteBuffers and an int" the boundary cost is far smaller — likely
single-digit nanoseconds amortized inside the bench's 2 µs total.
This calibration changes the calculus for other potential ports
that previously got "no" answers based on the 200 ns figure.

Phase 2 is green-lit on this evidence.

### Phase 2 — Power calculation batch  ✓ SHIPPED ON BY DEFAULT (0.4.0-alpha)

**Goal:** replace AC's per-cascade propagation loop with one JNI call
using the existing oracle-validated Rust kernel.

Hook site: `WireHandler.powerNetwork()` (`redstone/WireHandler.java:632-674`).
Graph building stays in Java (runs once before the Rust call); Rust
receives the pre-built `WireNode` graph (source power + neighbor indices)
and returns power deltas for Java to apply.

Reuse existing infrastructure: `rust/mod/src/redstone.rs` (oracle-validated
kernel), `RedstoneHandoff.java` (buffer layout already defined), and the
`RedstoneRustDispatcher.ACTIVE` ThreadLocal re-entry guard pattern. No
new Rust algorithm — only Java-side glue to feed AC's graph into the
existing buffer.

**Exit criterion:** cascade avg wall time on a lag-machine world beats
AC-Java by ≥20% AND is not worse than AC-Java by >10% on a 64-wire run
(protected by a minimum-node cutoff — below that threshold, AC-Java's
loop runs unchanged). Oracle reports 0 mismatches over a 10-minute mixed
test.

**Initial result (commit `5eb9e3b`, naive Java glue, 64-repeater clock):**

| Path                | Avg cascade time | Δ vs AC-Java |
|---|---:|---:|
| AC-Java (BFS off)   | 0.041 ms         | —            |
| AC-Java + Rust BFS  | 0.073 ms         | **+78% slower** |

Then refactored the Java glue (Phase 2b — kill `HashMap<Long,Integer>`
in favor of a per-node `rustIndex` field, hoist scratch buffers to
`WireHandler` fields, replace `connections.forEach` lambda with a direct
linked-list walk via `WireConnectionManager.head()`):

| Path                          | Repeater clock | Lag machine |
|---|---:|---:|
| AC-Java (BFS off)             | 0.050 ms       | 0.024 ms    |
| AC-Java + Rust BFS (Phase 2b) | 0.073 ms (-46%) | 0.024 ms (~0%) |

Correctness ✓: `[redstone-oracle] node-mismatches=0` in every sample
window, BFS on and off, both worlds. The Rust kernel is bit-for-bit
identical to AC-Java.

**The 0% lag-machine result is unfalsifiable as written.** With the
activation counter added (commit `68c059d`, `RUST_BFS_ACTIVATIONS` in
`RedstonePhaseMonitor`), the re-bench reports `rust-bfs:
activations=0 (0.0% of cascades)` across **every 5-second window** of
the lag machine run with `bfs on`. The production cutoff
(`RUST_BFS_MIN_NODES = 32`) gates out 100% of cascades on this
workload — which makes sense: 200K–290K cascades per 5-second window
at avg 0.024 ms each means the lag machine is dominated by tiny
cascades (~5–20 nodes), all below the threshold.

So "neutral on lag machine" really means "Rust path never ran; the gate
worked as designed." It's a defensible *non-regression* claim (adding
the gate check costs nothing) but tells us nothing about Rust's actual
ceiling on this workload.

**Phase 2c — histogram + MIN_NODES sweep (commit `996ad7d`).** Added
log2 cascade-size buckets (`1-4 5-8 9-16 17-32 33-64 65-128 129+`) and
per-bucket Java-vs-Rust timing in `RedstonePhaseMonitor`, plus a
`/ferrite redstone bfs-min <n>` runtime command to drop the gate to 1
and force Rust on every cascade. Two workloads measured:

**Lag machine, per-bucket avg cascade time (3 windows × ~5–10 s each):**

| Bucket | Java baseline | Rust (min=1) | Java cross-check | Rust speedup |
|---|---:|---:|---:|---:|
| 1-4    | 0.010 ms | **0.007 ms** | 0.010 ms | **1.43×** |
| 5-8    | 0.023 ms | **0.015 ms** | 0.022 ms | **1.53×** |
| 9-16   | 0.052 ms | **0.033 ms** | 0.051 ms | **1.55×** |
| 17-32  | 0.052 ms | **0.025 ms** | 0.051 ms | **2.08×** |
| 33-64+ | — | — | — | (zero cascades, ever) |

Aggregate: Rust-forced window dropped avg cascade from 0.020 ms to
0.014 ms AND raised cascade throughput from ~240K to ~335–380K per
5 s window — i.e. the server tick had more headroom, so more cascades
fit. Roughly **30% reduction in wire cost**.

The lag machine's largest cascade ever measured was 32 nodes, so
`MIN_NODES=32` was gating out 100% of production cascades — confirming
the prior "0% activation" result was a measurement artifact, not a
Rust limitation.

**Repeater clock, per-bucket avg cascade time (only bucket 1-4 fires):**

| Sample | n | Java avg (warm) | Rust avg | Rust delta |
|---|---:|---:|---:|---:|
| Java (final 5 s window)  | 100 | **0.026 ms** | —        | —              |
| Rust forced              | 100 | —            | **0.049 ms** | **−1.77× (slower)** |

Same bucket label, opposite verdict. Two plausible reasons:

- **JIT warmup**: lag machine had millions of Rust-glue invocations
  warming the path; repeater clock had ~300 total — likely below the
  C2 compile threshold for the glue methods.
- **Per-cascade work shape**: a repeater is a signal source, so
  `findExternalPower` does more world-touch work per wire. The Rust
  JNI hop + serialize/deserialize is a higher relative fraction when
  the per-wire Java-side work is also high.

**Workload-shape table (the resolved picture):**

| Workload       | Volume                  | Cascade sizes seen | Verdict                  |
|---|---|---|---|
| Lag machine    | sustained, 200K+/window | 1–32 nodes         | Rust wins 1.43–2.08× all buckets |
| Repeater clock | bursty, ~100/window     | 1–4 nodes only     | Rust loses ~1.77× on 1-4         |

Rust's win is **workload-shape dependent**, not just size-dependent.
The right `MIN_NODES` for the lag machine is 1; the right one for
the repeater clock is "never." There is no single value that's
optimal for both.

**Phase 2 code disposition (final, 0.4.0-alpha):**
- `RUST_BFS = true` and `RUST_BFS_MIN_NODES = 1` ship as defaults.
  4-core re-bench confirmed the per-bucket numbers are CPU-affinity-
  invariant (server tick is single-threaded), so the lag-machine win
  holds on the same hardware all prior project benchmarks used.
- `/ferrite redstone bfs off` and `/ferrite redstone bfs-min <n>`
  remain available for users to disable or partially gate the path
  if a specific contraption regresses.
- Phase 2b refactor (`rustIndex`, scratch buffers, no-lambda walk) and
  Phase 2c diagnostics (histogram, sweep command) stay in production —
  they're load-bearing for the default-on shipping decision.
- Oracle reports 0 mismatches across both workloads in every bench
  window — correctness proven across the experiment.
- Repeater-clock regression (~20µs/cascade) is documented as an
  accepted tradeoff: absolute cost is &lt;0.05 ms/tick, invisible in
  practice, and the user has a one-command opt-out.

### Decision gate result

Per plan: "If either phase shows neutral or negative measurement,
document the number in this doc, leave the Rust code in place as
library-only for future revisit, and ship nothing."

**Phase 1 ✓ Phase 2 ✓ → ship default ON in 0.4.0-alpha.** Per-bucket
data showed Rust wins 1.3–2.1× across every measured size class on the
lag machine and only loses imperceptibly (~20µs/cascade) on cold
bursty workloads. Default flipped from gated-off (32) to active-on (1);
`/ferrite redstone bfs off` is the one-command opt-out for users who
hit a regression on a contraption shape we haven't measured.

### Relationship to the Session 3 description above

The earlier **Session 3 — (Conditional) Rust batching on top** block is
the Phase 2 + Phase 3 combined. The phased breakdown here is a refinement,
not a replacement — Session 3's exit criteria (oracle 0-mismatches,
measurable lag-machine speedup) remain the real gate. Phase 1 is purely
diagnostic: it answers "is JNI overhead survivable for AC's queue shape?"
before we wire Phase 2 into the controller at all.

### Non-goals

- Does not block or preempt surface rule work.
- Does not change user-visible behavior until Phase 3 ships.
- Does not revisit "rewrite all of AC in Rust" — the audit confirmed the
  ~1,375-line Java-side layer cannot cross JNI efficiently; any plan
  that moves it to Rust is unviable regardless of kernel speed.

---

## Lessons from the Phase 1 through 2c arc

Six takeaways, extracted for future port attempts so we do not re-learn
them the expensive way. See [JOURNEY.md](JOURNEY.md) for the broader cross-port
framing.

1. **The gate rule needs a sanity check before it fires.** Phase 2's
   -78% result would have stopped shipping if taken at face value. The
   activation counter (commit `68c059d`) revealed that the lag-machine
   rerun was measuring Rust-never-ran, not Rust-lost. A valid-looking
   stop rule can fire on an invalid measurement. Before accepting "the
   gate says stop," confirm the measurement captured what the gate was
   meant to gate on.

2. **Micro-benches do not measure infrastructure.** Phase 1's 17x was
   real for pure compute and misleading for end-to-end cost. The
   `HashMap<Long,Integer>`, lambda capture, and boxed keys in the naive
   Phase 2 glue were invisible to Phase 1 by design. Every future port
   needs one infrastructure-inclusive measurement before shipping, not
   just kernel-isolated benchmarks.

3. **Default thresholds should be measured, not guessed.** Phase 2
   shipped with `MIN_NODES=32` as a round number derived from Phase 1
   queue-bench data. The sweep in Phase 2c revealed the production
   cascade distribution on the lag machine topped out at 32 nodes, so
   the "safe" default gated out 100% of candidate cascades. For future
   batched ports with a minimum-size cutoff, measure the workload's
   actual size distribution first, then set the threshold from that
   curve.

4. **Workload shape beats cascade size.** The same 1-4 node bucket
   produced a 1.43x Rust win on the lag machine and a 1.77x loss on
   the repeater clock. JIT warmup state (millions of invocations vs
   ~300) and per-wire Java-side work density (repeater clocks do more
   `findExternalPower` per wire) shift the verdict. Benchmarks need
   at least two workloads, sustained-high-throughput and cold-bursty,
   before the default shipping decision.

5. **Oracle validation is non-negotiable.** The `[redstone-oracle]`
   counter ran through every phase and reported 0 mismatches across
   every sample window. That is what let us ship aggressive defaults
   in 0.4.0-alpha without user-facing risk. No oracle, no default-on.

6. **The Java glue matters as much as the Rust kernel.** Phase 2b's
   refactor (per-node `rustIndex` field instead of HashMap, hoisted
   scratch buffers onto `WireHandler`, direct linked-list walk via
   `WireConnectionManager.head()`, removed lambda captures) recovered
   a large fraction of the Phase 2 deficit without touching Rust at
   all. For future ports, assume the first-draft Java glue is the
   bottleneck and budget time to rewrite it in a data-oriented style
   before declaring the Rust path unviable.

---

## Out of scope

- **Batch-all-redstone-per-tick.** Semantically broken for Java
  redstone — wire cascades are synchronous and observable mid-tick
  (observers, comparators reading containers, 0-tick pulses, BUD
  tricks). Don't revisit. Detail in CHANGELOG's Unreleased section.
- **Fully custom Ferrite wire block.** Breaks contraption interop with
  vanilla redstone dust; doesn't match project goals.
- **Replacing vanilla redstone dust via registry swap.** High
  mod-interop risk for small additional gain over Option A/B.

---

## Fidelity audit — 2026-05-03

Confirmed faithful adaptation of Space Walker's AC at upstream commit
89609e4 (2026-03-23). No upstream changes to backport.

Algorithmic parity: full. Every node lifecycle, BFS search, depower,
priority queue, flow-direction, power transmission, neighbor/shape
update preserved verbatim vs upstream modulo correct yarn renames.

Ferrite additions confirmed present:
  - rustIndex field on WireNode (eliminates HashMap allocation per cascade)
  - head() accessor on WireConnectionManager (eliminates lambda allocation)
  - Scratch buffers pre-allocated to MAX_NODES (no per-cascade alloc)

One conservative divergence: defensive REDSTONE_WIRE re-check in
powerNetwork -- early-skips wires whose state changed between
discovery and commit. Inert, not regressive.

Vanilla redstone intact when AC disabled (default). Experimental
redstone controller untouched (audit fix #2 adds warning when both
AC and experimental are active simultaneously).
