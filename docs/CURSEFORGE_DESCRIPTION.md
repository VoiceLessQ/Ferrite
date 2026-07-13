
## Ferrite

**What you get:** A performance mod for Minecraft 26.1.2 (mojmap, JDK 25). Built natively against 26.1's deobfuscated source rather than recompiled from the 1.21.11 codebase, so the parity and tick-cost numbers below are measured on 26.1.2 directly. It's a Fabric (Java) mod that calls into native Rust via JNI for the hot paths. Java handles Minecraft integration and mixins, Rust does the heavy per-tick math where the win is big enough to justify crossing the JNI boundary. The 1.21.11 line continues separately on the `main` branch.

**Live now:**

* **Cramming** (`/ferrite cramming on|off|status`, default on). Rust port of the mob-vs-mob cramming loop. **~65% entity-tick reduction at high mob density.** Every MobEntity subclass (villager halls, mob farms). Vanilla 1:1 parity: same push math, same passenger-of-same-vehicle skip, same `maxEntityCramming` damage (gamerule + 1-in-4 random). `/gamerule maxEntityCramming 0` stays at 20 TPS for unbounded farms.
* **Redstone** (`/ferrite redstone ac on`). [Space Walker&#39;s Alternate Current](https://github.com/SpaceWalkerRS/alternate-current). **~10× fewer cascades, ~6× faster contraptions at same load.** Bit-correct on 150,000+ oracle checks, works on existing worlds. Per-cascade Rust BFS adds another **~30%** wire-cost cut on heavy builds. Re-tested on 26.1.2 against a full lag machine: vanilla held **1.4-1.8 TPS**, enabling AC mid-choke recovered to a flat **20.00 TPS** in about 40 seconds on the same build.
* **Hopper extract hint (default on).** Per-source-inventory hint tracks the first non-empty slot; extract loops start there instead of iterating from slot 0 every fire. **~23 µs/call at avgStartIdx=16 (~60% reduction), ~110 µs/call at avgStartIdx=53 (~85%)** on partially-drained chests. Validator shadow-runs reported 0 stale events across 450+ extracts.
* **Hopper highway** (`/ferrite hopper highway on`, default off). Per-slot independent cooldowns + round-robin destination routing. Aggregate per-hopper throughput climbs from vanilla 1/(8 ticks) to up to 5/(8 ticks). **3.1× chain throughput** under back-pressure on a 100-hopper test chain. Per-tick item count stays ≤ 1 so comparator transition rate is preserved. For hopper-heavy storage; leave off for sorters tuned to vanilla 8-tick clocks.
* **World creation pre-gen** (toggle on Create World "More" tab, default off). Pre-generates a configurable 5-50 chunk radius around spawn before the player loads in, runs through Ferrite's optimized chunkgen pipeline. Cancel writes a snapshot, next world load auto-resumes. Boss bar reports progress to the host. Dedicated servers: `-Dferrite.pregen.radius=N` first-launch only. Also available any time via `/ferrite pregen <radius>`. Validated **90-118 chunks/sec** on virgin terrain (inflight cap raised to 200 in 0.6.6, +25% over the old default; tunable with `/ferrite pregen inflight <n>`), TPS 20 holding throughout.
* **Predictive chunk forcing** (`/ferrite chunkforce on`, default off). Force-generates terrain ahead of moving players: the generation ring leads your flight path by up to 12 chunks, so at top speed you only ever see your render distance, never the generation front. Stationary players get a plain radial buffer.
* **Density function port: 50/50 bit-exact on 26.1.2** (vs the 41/42 baseline on 1.21.11). Building blocks for the future Rust DF compiler are now in tree.
* **Logging gate** (`/ferrite log monitors on|off|status`). Runtime toggle for the periodic monitor reports. About 5 lines/sec across 24 buckets in normal play; turn off on long sessions or I/O-bound hardware to cut log volume without losing the counters.

Logs tick breakdowns every 5s so the next port targets real bottlenecks.

## Measured results

### Cramming (1000+ active mobs)

| metric                | vanilla | Ferrite | reduction               |
| --------------------- | ------- | ------- | ----------------------- |
| `tickCramming` avg  | ~14 ms  | 0.03 ms | **~99%**          |
| `Entity.move()` avg | ~20 ms  | ~10 ms  | ~50% (secondary effect) |
| total entity tick     | ~60 ms  | ~21 ms  | **~65%**          |

### Redstone (lag machine)

| metric                | vanilla default | Ferrite (AC)        | change                |
| --------------------- | --------------- | ------------------- | --------------------- |
| cascades per tick     | ~127,000        | ~8,250              | **~15× fewer** |
| gate ticks per tick   | ~663            | ~2,780              | **~4× more**   |
| wire cost / gate tick | ~0.378 ms       | ~0.062 ms           | ~84% less             |
| effective TPS         | ~4              | ~5.6                | **+40%**        |
| oracle mismatches     | —              | 0 / 149,669 checked | bit-for-bit correct   |
| full lag machine (26.1.2) | 1.4-1.8 TPS | 20.00 TPS           | **choke eliminated** |

Measurement details in [CHANGELOG.md](https://github.com/VoiceLessQ/Ferrite/blob/main/CHANGELOG.md) and the full investigation path in [docs/PROFILING.md](https://github.com/VoiceLessQ/Ferrite/blob/main/docs/PROFILING.md) in github.

---

## How it works

### Cramming

`LivingEntity.tickCramming` is intercepted with a Mixin. The first mob's tickCramming call in a given server tick triggers a batch: every mob's position and bounding box is packed into a direct ByteBuffer, Rust builds a 2-block spatial hash, iterates pairs with an array-index guard, applies the vanilla push formula (Chebyshev distance, exact bit-for-bit replica), and returns accumulated `(dx, dz)` velocity deltas. Java then applies each delta via `entity.addVelocity`. All subsequent tickCramming calls that tick are cancelled no-ops.

One JNI call per tick. No world state, no snapshot. The win is algorithmic — O(N·k) with spatial hashing where k is local density, instead of vanilla's per-mob `level.getEntities(bbox)` query-plus-iterate.

### Redstone

A `@Redirect(NEW)` mixin swaps `RedstoneWireBlock`'s `redstoneController` field from `DefaultRedstoneController` to `FerriteRedstoneController` (a subclass) at construction time. With `/ferrite redstone ac on`, the Ferrite controller routes wire updates through the ported Alternate Current algorithm: build the connected wire network as a graph, find power sources, do one BFS-style settle that touches each wire at most twice, write all power changes in one pass via a chunk-section bypass that skips lighting/heightmap/block-entity bookkeeping. With AC off, the controller delegates to `super.update(...)` and is byte-for-byte equivalent to vanilla.

Pure Java; no JNI. The win is algorithmic — replacing vanilla's per-wire recursive re-evaluation (which can revisit the same wire dozens of times per cascade) with one settle per cascade, plus skipping the redundant block updates a wire would normally emit between intermediate power levels.

A shadow-compute `RedstoneOracle` validates every sampled cascade against vanilla's own `calculateWirePowerAt`, so any algorithm divergence surfaces immediately in `[redstone-oracle]` log lines.

## How to help

If you run mob farms, crowded multiplayer servers, or singleplayer worlds with lots of mobs or animals:

1. Install Ferrite + Fabric API
2. Play normally for 10+ minutes
3. Open `.minecraft/logs/latest.log`, search for `[ferrite]`
4. Share representative `[cramming-dispatch]` and `[movement-internals]` lines in a GitHub issue or CurseForge comment

Low-end hardware (4-core CPU, integrated graphics) is especially useful — the `[chunkgen]` and `[client-lag]` logs on that profile decide what gets optimized next.

---

## Requirements

* Minecraft 26.1.2 (this build) / Minecraft 1.21.11 (separate `main` branch builds)
* Java 25 (Temurin recommended; CI builds against JDK 25)
* Fabric Loader 0.18.4+
* Fabric API 0.147.0+26.1.2
* Works in **singleplayer and multiplayer**
* **Server-side compatible** , can be installed on a server without requiring players to have the mod

---

## Platform verification

| platform          | status                                                                                                                                             |
| ----------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| Windows x86_64    | ✅ Developed and tested throughout                                                                                                                 |
| Linux x86_64      | ✅ Verified — WSL Ubuntu 24.04, OpenJDK 21, server loads`/tmp/rust_mod_*.so`, initEngine returns Rayon pool size, reaches "Done" with no errors |
| macOS (universal) | ⚠️ Binary confirmed structurally correct (`lipo -info` shows both x86_64 + arm64 slices); runtime load not yet verified on real Apple hardware |

The macOS `.dylib` is a fat binary produced by `lipo -create` on the CI `macos-latest` runner. Happy to mark it verified once a Mac user confirms `System.load` succeeds — a log snippet showing `Loaded rust_mod from /tmp/rust_mod_*.dylib` is enough.

The native library is bundled for Windows, Linux, and macOS. If it fails to load on your platform, Ferrite falls back to vanilla behavior automatically — no crashes, no broken worlds. ARM Linux isn't bundled yet.

---

## Credits

* The redstone wire algorithm is adapted from [Space Walker&#39;s Alternate Current](https://github.com/SpaceWalkerRS/alternate-current) (MIT). Full attribution in [LICENSES.md](https://github.com/VoiceLessQ/Ferrite/blob/main/LICENSES.md). The port targets mojmap 26.1.2 on this branch (the 1.21.11 line continues on `main`) and installs transparently as a `DefaultRedstoneController` subclass; design and algorithm remain entirely Space Walker's.
* The JNI / native-loading scaffolding was originally forked from [Brayan-724/rust-mod-probe](https://github.com/Brayan-724/rust-mod-probe) — the PoC that demonstrated calling Rust from Fabric.

---

## License

MIT
