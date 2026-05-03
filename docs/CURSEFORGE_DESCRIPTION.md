## Ferrite

**What you get:** A performance mod for Minecraft 1.21.11. It's a Fabric (Java) mod that calls into native Rust via JNI for the hot paths — Java handles Minecraft integration and mixins, Rust does the heavy per-tick math where the win is big enough to justify crossing the JNI boundary.

**Live now:**

- **Cramming** (`/ferrite cramming on|off|status`, default on) — Rust port of the mob-vs-mob cramming loop. **~65% entity-tick reduction at high mob density.** Every MobEntity subclass (villager halls, mob farms). Vanilla 1:1 parity: same push math, same `isPassengerOfSameVehicle` skip, same `maxEntityCramming` damage (gamerule + 1-in-4 random). `/gamerule maxEntityCramming 0` stays at 20 TPS for unbounded farms.
- **Redstone** (`/ferrite redstone ac on`) — [Space Walker's Alternate Current](https://github.com/SpaceWalkerRS/alternate-current). **~10× fewer cascades, ~6× faster contraptions at same load.** Bit-correct on 150,000+ oracle checks, works on existing worlds. Per-cascade Rust BFS adds another **~30%** wire-cost cut on heavy builds.
- **Chunkgen baseline** (no toggle) — `@Invoker`/`@Accessor` mixins replace reflection on `MaterialRules.MaterialRuleContext`. **~3 ms/chunk** off vanilla's surface phase, every chunk. Diagnostic instrumentation (~8-10 ms/chunk) also gated off by default.
- **Surface rule dispatcher** (`/ferrite surface dispatch on`, default off) — Rust evaluation + batched column heightmap. **~13.4 ms ON vs ~6.4 ms vanilla**, ~7 ms structural gap remains. A/B only, not production-ready. Parity-clean (100% across 23,000+ chunks).

Logs tick breakdowns every 5s so the next port targets real bottlenecks.

---

## What's new in 0.6.1

Consolidation release. Builds on 0.6.0's hopper highway and pre-gen with audit-driven correctness, perf cleanups, and a sign-tick fix.

- **Sign-tick suppression** (default-on). Vanilla ticks every placed sign every server tick to do a no-op null check 99.99% of the time. Ferrite suppresses the ticker when no player is editing the sign and re-registers immediately when someone opens the edit screen. **~70% BE-tick cost reduction at 961 placed signs (0.20 ms → 0.06 ms / tick).** Strict-class check preserves mod subclass behavior.
- **Cramming correctness fix.** Standalone vehicles were pushing their own passengers because the sentinel-based `root_vehicle_id` check never matched. Java side now uses `e.getId()` for unmounted entities, mirroring vanilla's `getRootVehicle() == self`. New unit test confirms vehicle⇄passenger pairs are now correctly skipped.
- **Cramming + redstone Rust kernels: thread-local buffer reuse.** Spatial-hash structures and relaxation buffers now persist across ticks instead of allocating per call. Plus a small-N brute-force fast path on the cramming side for sparse mob counts.
- **Experimental-redstone warning.** Once-per-world warning when `/ferrite redstone ac on` is active but the world has the experimental redstone feature flag set; AC is silently bypassed on those worlds since vanilla's experimental controller doesn't route through Ferrite's installed controller. The warning surfaces what was previously a silent no-op.
- **`RedstoneRustDispatcher` and its mixin deleted (~500 LOC).** Superseded by AC's `runRustBatch` in 0.4.0; default-off `USE_RUST` toggle since. The codebase is smaller and the next audit pass has less surface to traverse.
- **AC fidelity audit.** Confirmed Ferrite's Alternate Current port matches [Space Walker's upstream](https://github.com/SpaceWalkerRS/alternate-current) at commit `89609e4` (2026-03-23). Full algorithmic parity, three Ferrite-side improvements (`rustIndex`, no-lambda walk, scratch buffer pre-alloc) verified present.

See [CHANGELOG.md](https://github.com/VoiceLessQ/Ferrite/blob/main/CHANGELOG.md) for the full per-change detail and [docs/JOURNEY.md](https://github.com/VoiceLessQ/Ferrite/blob/main/docs/JOURNEY.md) "The May 2026 audit pass" for the retrospective on what we tried, what stuck, and the two findings that turned out to be false alarms on closer reading.

---

## Measured results

### Cramming (1000+ active mobs)

| metric                | vanilla | Ferrite | reduction               |
| --------------------- | ------- | ------- | ----------------------- |
| `tickCramming` avg    | ~14 ms  | 0.03 ms | **~99%**                |
| `Entity.move()` avg   | ~20 ms  | ~10 ms  | ~50% (secondary effect) |
| total entity tick     | ~60 ms  | ~21 ms  | **~65%**                |

TPS held at 20 under the same load that was costing vanilla 60 ms/tick of entity work.

> **Isolated cramming-math sub-budget:** ~0.06 ms with Ferrite vs ~18.81 ms vanilla on the same pile — roughly **310× reduction in the cramming calculation itself**, stripped of all other entity costs. The 65% total entity-tick reduction is the user-facing number; 310× is what Rust is actually doing to the specific bottleneck.

### Redstone (lag machine)

| metric                 | vanilla default     | Ferrite (AC)         | change                |
| ---------------------- | ------------------- | -------------------- | --------------------- |
| cascades per tick      | ~127,000            | ~8,250               | **~15× fewer**        |
| gate ticks per tick    | ~663                | ~2,780               | **~4× more**          |
| wire cost / gate tick  | ~0.378 ms           | ~0.062 ms            | ~84% less             |
| effective TPS          | ~4                  | ~5.6                 | **+40%**              |
| oracle mismatches      | —                   | 0 / 149,669 checked  | bit-for-bit correct   |

Two effects:
- **~4× faster contraptions** at the same server load — each cascade collapses into one settle (~84% less wire time per gate tick).
- **~40% TPS** on CPU-bound hardware. Unconstrained hardware: TPS flat, contraptions still faster.

**Compat:** gate tick speeds (repeaters, comparators, observers, torches) are vanilla-identical. Wire ordering differs — AC skips intermediate power-level updates. QC / 0-tick / instawire builds: `/ferrite redstone ac off`.

**Feast-or-famine:** big wins on dense contraptions with feedback amplifiers, slight overhead on small clean builds (~0.083 vs ~0.026 ms/tick on a single clock + 64-block wire). Both stay well under 1 ms/tick — small-build overhead imperceptible.

> **Per-cascade Rust BFS** (default on with AC, since 0.4.0-alpha). Each cascade's power propagation runs in a Rust kernel via one batched JNI call. **+~30% wire-cost cut** on heavy contraptions (1.3–2.1× per cascade) on top of AC. ~20µs/cascade overhead on small cold workloads. `/ferrite redstone bfs off` if a contraption misbehaves.

> **YMMV.** Single CPU (Ryzen 9 5900X, 4 cores via affinity), worst-case workloads (zombie pile / clock-based lag machine). Real numbers depend on hardware, contraption density, other mods. CPU-bound hardware sees both cascade-reduction and TPS gains; unconstrained hardware: throughput wins persist, TPS delta can vanish.

Measurement details in [CHANGELOG.md](https://github.com/VoiceLessQ/Ferrite/blob/main/CHANGELOG.md) and the full investigation path in [docs/PROFILING.md](https://github.com/VoiceLessQ/Ferrite/blob/main/docs/PROFILING.md).

---

## How it works

### Cramming

`LivingEntity.tickCramming` is intercepted with a Mixin. The first mob's tickCramming call in a given server tick triggers a batch: every mob's position and bounding box is packed into a direct ByteBuffer, Rust builds a 2-block spatial hash, iterates pairs with an array-index guard, applies the vanilla push formula (Chebyshev distance, exact bit-for-bit replica), and returns accumulated `(dx, dz)` velocity deltas. Java then applies each delta via `entity.addVelocity`. All subsequent tickCramming calls that tick are cancelled no-ops.

One JNI call per tick. No world state, no snapshot. The win is algorithmic — O(N·k) with spatial hashing where k is local density, instead of vanilla's per-mob `level.getEntities(bbox)` query-plus-iterate.

### Redstone

A `@Redirect(NEW)` mixin swaps `RedstoneWireBlock`'s `redstoneController` field from `DefaultRedstoneController` to `FerriteRedstoneController` (a subclass) at construction time. With `/ferrite redstone ac on`, the Ferrite controller routes wire updates through the ported Alternate Current algorithm: build the connected wire network as a graph, find power sources, do one BFS-style settle that touches each wire at most twice, write all power changes in one pass via a chunk-section bypass that skips lighting/heightmap/block-entity bookkeeping. With AC off, the controller delegates to `super.update(...)` and is byte-for-byte equivalent to vanilla.

Pure Java; no JNI. The win is algorithmic — replacing vanilla's per-wire recursive re-evaluation (which can revisit the same wire dozens of times per cascade) with one settle per cascade, plus skipping the redundant block updates a wire would normally emit between intermediate power levels.

A shadow-compute `RedstoneOracle` validates every sampled cascade against vanilla's own `calculateWirePowerAt`, so any algorithm divergence surfaces immediately in `[redstone-oracle]` log lines.

---

## In progress

* **Surface rule dispatcher** (`/ferrite surface dispatch on`) — opt-in in 0.5.1. ~7 ms structural gap above vanilla; closing it needs architectural work that bypasses palette writes or the biome supplier chain. Default-off. Parity validator: `/ferrite surface heightmap-parity`.
* **Density-function port** — Rust kernel ~7× faster than vanilla's noise-sync in equivalent work, but blocked at the DF layer. Vanilla interleaves DFs with interpolation inside `final NoiseChunkGenerator`; no clean cell-corner grid without reimplementing the DF tree. Per-call JIT-vs-JNI wall is the recurring pattern across density/aquifer/chunkgen targets. See [PIANO_STATUS.md](https://github.com/VoiceLessQ/Ferrite/blob/main/docs/PIANO_STATUS.md).
* **Aquifer port** (`/ferrite aquifer rust on`) — 99.895% parity, surface-grid artifacts at chunk boundaries. In tree, default-off. Revisit needs a new surface-grid approach.
* **`adjustMovementForCollisions`** — shelved. AABB sweep correct in Rust, but snapshot materialization cost exceeded sweep savings at realistic mob counts. `PhysicsOracle` validator in tree (100% across 700K+ dispatches) for future revisit. Dispatcher disabled.

---

## How to help

If you run mob farms, crowded multiplayer servers, or singleplayer worlds with lots of mobs or animals:

1. Install Ferrite + Fabric API
2. Play normally for 10+ minutes
3. Open `.minecraft/logs/latest.log`, search for `[ferrite]`
4. Share representative `[cramming-dispatch]` and `[movement-internals]` lines in a GitHub issue or CurseForge comment

Low-end hardware (4-core CPU, integrated graphics) is especially useful — the `[chunkgen]` and `[client-lag]` logs on that profile decide what gets optimized next.

---

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.18.4+
- Fabric API
- Works in **singleplayer and multiplayer**
- **Server-side compatible** — can be installed on a server without requiring players to have the mod

---

## Platform verification

| platform | status |
|---|---|
| Windows x86_64 | ✅ Developed and tested throughout |
| Linux x86_64 | ✅ Verified — WSL Ubuntu 24.04, OpenJDK 21, server loads `/tmp/rust_mod_*.so`, initEngine returns Rayon pool size, reaches "Done" with no errors |
| macOS (universal) | ⚠️ Binary confirmed structurally correct (`lipo -info` shows both x86_64 + arm64 slices); runtime load not yet verified on real Apple hardware |

The macOS `.dylib` is a fat binary produced by `lipo -create` on the CI `macos-latest` runner. Happy to mark it verified once a Mac user confirms `System.load` succeeds — a log snippet showing `Loaded rust_mod from /tmp/rust_mod_*.dylib` is enough.

The native library is bundled for Windows, Linux, and macOS. If it fails to load on your platform, Ferrite falls back to vanilla behavior automatically — no crashes, no broken worlds. ARM Linux isn't bundled yet.

---

## Credits

- The redstone wire algorithm is adapted from [Space Walker's Alternate Current](https://github.com/SpaceWalkerRS/alternate-current) (MIT). Full attribution in [LICENSES.md](https://github.com/VoiceLessQ/Ferrite/blob/main/LICENSES.md). The port is Yarn-remapped for 1.21.11 and installed transparently as a `DefaultRedstoneController` subclass; design and algorithm remain entirely Space Walker's.
- The JNI / native-loading scaffolding was originally forked from [Brayan-724/rust-mod-probe](https://github.com/Brayan-724/rust-mod-probe) — the PoC that demonstrated calling Rust from Fabric.

---

## License

MIT
