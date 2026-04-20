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

## Out of scope

- **Batch-all-redstone-per-tick.** Semantically broken for Java
  redstone — wire cascades are synchronous and observable mid-tick
  (observers, comparators reading containers, 0-tick pulses, BUD
  tricks). Don't revisit. Detail in CHANGELOG's Unreleased section.
- **Fully custom Ferrite wire block.** Breaks contraption interop with
  vanilla redstone dust; doesn't match project goals.
- **Replacing vanilla redstone dust via registry swap.** High
  mod-interop risk for small additional gain over Option A/B.
