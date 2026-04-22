## Ferrite

**What you get:** A performance mod for Minecraft 1.21.11. It's a Fabric (Java) mod that calls into native Rust via JNI for the hot paths — Java handles Minecraft integration and mixins, Rust does the heavy per-tick math where the win is big enough to justify crossing the JNI boundary. Two opt-in optimizations:

- **Cramming (active by default)** — a Rust reimplementation of the mob-vs-mob cramming loop. In any world with 1000+ mobs — singleplayer or multiplayer — it cuts the server's entity-tick cost by roughly 65%.
- **Redstone (`/ferrite redstone ac on`)** — adapts [Space Walker's Alternate Current](https://github.com/SpaceWalkerRS/alternate-current) algorithm into Ferrite. **~10× fewer wire cascades, contraptions run ~6× faster at equivalent server load.** Bit-for-bit correct vs vanilla on 150,000+ oracle checks. Works on existing worlds without toggles or migration.

Every 5 seconds the mod also logs where your game is spending time, so the next optimization can target the next real bottleneck.

---

## Measured results

### Cramming (1000+ active mobs)

| metric                | vanilla | Ferrite | reduction               |
| --------------------- | ------- | ------- | ----------------------- |
| `tickCramming` avg    | ~14 ms  | 0.03 ms | **~99%**                |
| `Entity.move()` avg   | ~20 ms  | ~10 ms  | ~50% (secondary effect) |
| total entity tick     | ~60 ms  | ~21 ms  | **~65%**                |

TPS held at 20 under the same load that was costing vanilla 60 ms/tick of entity work.

### Redstone (lag-machine benchmark, AC algorithm enabled)

| metric                 | vanilla default     | Ferrite (AC)         | change                |
| ---------------------- | ------------------- | -------------------- | --------------------- |
| cascades per tick      | ~127,000            | ~8,250               | **~15× fewer**        |
| gate ticks per tick    | ~663                | ~2,780               | **~4× more**          |
| wire cost / gate tick  | ~0.378 ms           | ~0.062 ms            | ~84% less             |
| effective TPS          | ~4                  | ~5.6                 | **+40%**              |
| oracle mismatches      | —                   | 0 / 149,669 checked  | bit-for-bit correct   |

Two user-visible effects combine:
- **Contraptions animate ~4× faster at equivalent server load** — each wire cascade now collapses into a single network settle (~84% less wire time per gate tick), so the same per-tick budget processes more gate ticks.
- **Server TPS climbs ~40%** on CPU-bound hardware — when the server is saturated (as in this 4-core baseline), wire savings convert directly into more completed ticks per second. On unconstrained hardware with headroom, TPS stays flat but the gate-throughput win persists.

**Vanilla compatibility note.** Gate tick speeds (repeaters, comparators, observers, torches) are vanilla-identical. Wire update ordering intentionally differs from vanilla — AC skips intermediate power-level updates for efficiency. Contraptions relying on quasi-connectivity, 0-tick pulses, or instawire should use `/ferrite redstone ac off`.

**AC is a feast-or-famine algorithm** — significant wins on dense contraptions with feedback amplifiers (like the lag machine above), slight overhead on small clean builds (e.g. a single repeater clock + 64-block wire run measured ~0.083 ms / tick on AC vs ~0.026 ms / tick on vanilla — AC's per-cascade setup cost beats vanilla only when there's enough redundancy to amortize). Both effects stay well under 1 ms / tick on realistic setups, so the small-build overhead is imperceptible.

> **Per-cascade Rust BFS — enabled by default in 0.4.0-alpha.** Once AC is on, each wire cascade's power propagation runs in a Rust kernel via one batched JNI call (Java still emits the resulting block/shape updates). Adds another **~30% wire-cost reduction** on heavy contraptions (1.3–2.1× per cascade across measured size buckets) on top of the AC numbers above. Imperceptible regression (~20µs / cascade) on small cold workloads — disable per-world with `/ferrite redstone bfs off` if a specific contraption misbehaves. See [docs/REDSTONE_PORT_PLAN.md](docs/REDSTONE_PORT_PLAN.md) for per-bucket measurements.

> **Your results will vary.** Both tables are single data points on one CPU (Ryzen 9 5900X limited to 4 active cores via affinity) and specific worst-case workloads (concentrated zombie pile for cramming; clock-based lag machine for redstone). Real numbers depend on your hardware, the size and density of your mob farms or contraptions, other mods you run, and the specific redstone patterns you use. On CPU-bound hardware you'll likely see both the cascade-reduction and the TPS improvement; on unconstrained hardware the gate-throughput win (contraptions running visibly faster) persists but the TPS delta can disappear entirely because vanilla wasn't the bottleneck.

Measurement details in [CHANGELOG.md](CHANGELOG.md) and the full investigation path in [docs/PROFILING.md](docs/PROFILING.md).

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

## What's still in progress

* **Chunk generation** — Rust bulk-compute kernel measured ~7× faster than vanilla's noise-sync in equivalent work. The speedup is real but blocked from shipping at the density-function layer: vanilla evaluates DFs interleaved with interpolation inside `NoiseChunkGenerator` (marked `final`), so there's no clean intermediate cell-corner grid to hand to Rust without reimplementing the full DF tree. Pivoting to surface rule batch evaluation, which runs after density resolves with a clean boundary and still captures a realistic end-to-end chunkgen win.
* **`adjustMovementForCollisions` port** — attempted, shelved. The AABB sweep math runs correctly in Rust, but snapshot materialization cost exceeded the sweep savings at realistic mob counts. Retained as disabled infrastructure for a future invalidation-cache redesign.

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

- The redstone wire algorithm is adapted from [Space Walker's Alternate Current](https://github.com/SpaceWalkerRS/alternate-current) (MIT). Full attribution in [LICENSES.md](LICENSES.md). The port is Yarn-remapped for 1.21.11 and installed transparently as a `DefaultRedstoneController` subclass; design and algorithm remain entirely Space Walker's.
- The JNI / native-loading scaffolding was originally forked from [Brayan-724/rust-mod-probe](https://github.com/Brayan-724/rust-mod-probe) — the PoC that demonstrated calling Rust from Fabric.

---

## License

MIT
