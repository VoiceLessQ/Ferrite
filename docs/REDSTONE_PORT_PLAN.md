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
figure from `PROFILING.md` was for the cramming/physics path with
snapshot materialization on each crossing. For "pass two direct
ByteBuffers and an int" the boundary cost is far smaller — likely
single-digit nanoseconds amortized inside the bench's 2 µs total.
This calibration changes the calculus for other potential ports
that previously got "no" answers based on the 200 ns figure.

Phase 2 is green-lit on this evidence.

### Phase 2 — Power calculation batch

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

### Decision gate after Phase 2

If both phases pass, Phase 3 is the full integration: promote the Rust
path to default (behind a toggle or opt-out), update CHANGELOG, bump to
`v0.4.0-alpha`, ship.

If either phase shows neutral or negative measurement, document the
number in this doc, leave the Rust code in place as library-only for
future revisit, and ship nothing. The existing `[redstone-rust]` counter
infrastructure stays in the jar gated off, ready for re-measurement if
Minecraft internals or JNI costs shift.

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

## Out of scope

- **Batch-all-redstone-per-tick.** Semantically broken for Java
  redstone — wire cascades are synchronous and observable mid-tick
  (observers, comparators reading containers, 0-tick pulses, BUD
  tricks). Don't revisit. Detail in CHANGELOG's Unreleased section.
- **Fully custom Ferrite wire block.** Breaks contraption interop with
  vanilla redstone dust; doesn't match project goals.
- **Replacing vanilla redstone dust via registry swap.** High
  mod-interop risk for small additional gain over Option A/B.
