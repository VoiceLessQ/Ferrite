# Documentation map

Index of what each doc in `docs/` is for and when to read it. Kept short
on purpose. If you want to know what the project is, why it exists, or
how it thinks, start with JOURNEY. Everything else is reference.

## Start here

- **[JOURNEY.md](JOURNEY.md)** — the narrative arc. What shipped, what
  did not, and why. The five questions before any port, the JIT-wall
  finding, the version-durability frame. If you only read one doc,
  read this one.
- **[SEED_DRIVEN_DISPATCH.md](SEED_DRIVEN_DISPATCH.md)** — the
  architectural philosophy. The "piano in vanilla's orchestra" model,
  the single-i64 seed handoff at the JNI boundary, why state-derivation
  on the Rust side is the cheapest possible boundary.

## Forward-looking

- **[FUTURE_PLANS.md](FUTURE_PLANS.md)** — the candidate list. Ports
  that pass the five questions, ports that failed them, and parked
  candidates waiting on external signal. Updated whenever a candidate
  is opened, closed, or shipped.
- **[COMPATIBILITY.md](COMPATIBILITY.md)** — threading-model audit
  across three tiers (structurally safe, latent risk, unknown).
  Documents what holds today, what would fail under hypothetical
  parallel entity ticking, and what test gates close the unknowns.
- **[PIANO_STATUS.md](PIANO_STATUS.md)** — current shipping status
  per subsystem. What is default-on, what is opt-in, what is
  default-off and why. Updated each release.

## Per-subsystem writeups

These are the deep dives behind individual ports. Read when you need
the full context on a specific subsystem; skip when you do not.

- **[AQUIFER_PORT.md](AQUIFER_PORT.md)** — aquifer Rust port at
  99.895% parity, surface-grid artifact at chunk boundaries,
  default-off indefinitely.
- **[HOPPER_HIGHWAY.md](HOPPER_HIGHWAY.md)** — per-slot cooldown
  design notes for the hopper highway concept.
- **[REDSTONE_PORT_PLAN.md](REDSTONE_PORT_PLAN.md)** — the Phase 1
  through Phase 2 arc on AC redstone, the offer-based kernel, the
  per-cascade Rust BFS.
- **[SURFACE_RULE_STATUS.md](SURFACE_RULE_STATUS.md)** —
  current state of the surface rule dispatcher, the structural
  ~7 ms floor, default-off ship.
- **[SURFACE_RULE_BATCH_PLAN.md](SURFACE_RULE_BATCH_PLAN.md)** /
  **[SURFACE_RULE_BUFFER_SPEC.md](SURFACE_RULE_BUFFER_SPEC.md)** —
  batched evaluation design and the buffer format for the JNI
  handoff.
- **[WORLDGEN_ARCHITECTURE.md](WORLDGEN_ARCHITECTURE.md)** —
  walkthrough of the noise / climate / density-function stack on
  the Rust side and how it mirrors vanilla's RandomState.
- **[WORLDGEN_SPEEDUP_SESSION.md](WORLDGEN_SPEEDUP_SESSION.md)** —
  the chunkgen baseline win session (MaterialRuleContext accessor
  + diagnostic gating).
- **[CACHE_FILL_PLAN.md](CACHE_FILL_PLAN.md)** /
  **[SPATIAL_HASH_REUSE_PLAN.md](SPATIAL_HASH_REUSE_PLAN.md)** —
  design notes from earlier optimization arcs.

## Reference

Lookup material, not narrative.

- **[VANILLA_WORLDGEN_REFERENCE.md](VANILLA_WORLDGEN_REFERENCE.md)** —
  vanilla's worldgen pipeline annotated with the points where Rust
  hooks in. Keep open when reading any worldgen-related code.
- **[AC_YARN_MAPPINGS.md](AC_YARN_MAPPINGS.md)** — the yarn ↔
  Alternate Current symbol mapping table used during the AC port.
- **[SOURCE_AUDIT.md](SOURCE_AUDIT.md)** — vanilla source audit
  notes (what is hot, what is cold, where the JIT has already won).
- **[PARALLELISM_AUDIT.md](PARALLELISM_AUDIT.md)** — earlier
  threading-context audit, predates the COMPATIBILITY doc.
- **[PROFILING.md](PROFILING.md)** — the JFR investigation arcs,
  measurement-discipline lessons, and the original chunkgen PoC
  scope assessment.

## Setup and release

- **[SETUP_MINGW.md](SETUP_MINGW.md)** — Windows toolchain notes for
  building the Rust crate alongside the Java mod.
- **[CURSEFORGE_DESCRIPTION.md](CURSEFORGE_DESCRIPTION.md)** — the
  text body that ships to CurseForge with each release. Edit before
  tagging.

## How this map stays current

When a new doc lands, add a one-line entry under the right section.
When a doc is retired or merged, delete the entry. Keep the map
short; readers should be able to scan the whole thing in under a
minute and know where to go next.
