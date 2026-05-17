## Ferrite

**A server diagnostics tool for Minecraft, with native Rust kernels for the bottlenecks it finds.**

Install Ferrite and your server starts telling you where tick time is going. Per-bucket breakdowns log every 5 seconds across 24 instrumented hot paths: cramming, entity movement, redstone cascades, hopper extracts, chunk generation, lighting, AI controllers, surface rule dispatch, density-function evaluation, and more. The data stays on disk. Nothing phones home. You read `latest.log` for `[ferrite]` lines and you know what's slow.

The native kernels that ship are proof the approach works. Each one was written because the monitors first showed vanilla as the bottleneck on a real workload, then the port was scoped against a parity oracle, then it landed default-on (or default-off, when surface artifacts ruled it out). The 65% cramming win and the redstone tables further down are the receipts, not the pitch.

A Fabric (Java) mod for Minecraft 26.1.2 (mojmap, JDK 25). Built natively against 26.1's deobfuscated source rather than recompiled from 1.21.11, so the parity and tick-cost numbers are measured on 26.1.2 directly. Java handles Minecraft integration and mixins. Rust does the heavy per-tick math where the win is big enough to justify the JNI boundary. The 1.21.11 line continues separately on `main`.

---

## What you get out of the box

- **Tick breakdown logs every 5 seconds** across 24 instrumented buckets. No external profiler required, nothing to set up.
- **`/ferrite log monitors on|off|status`** runtime toggle. Default on, about 5 lines/sec in normal play. Silence it on long sessions or I/O-bound hardware without losing the underlying counters.
- **JFR-compatible profile zones** for operators who want to overlay Ferrite's data with external tooling.
- **Parity oracles** for every native kernel. Any divergence from vanilla surfaces in `[*-oracle]` log lines so you see drift immediately, not a week later when a redstone contraption breaks.
- **Server-side compatible.** Install on a server, players don't need it.
- **Automatic vanilla fallback** if the native library fails to load. No crashes, no broken worlds.

---

## Native kernels currently shipping

- **Cramming** (`/ferrite cramming on|off|status`, default on). Rust port of the mob-vs-mob cramming loop. Vanilla 1:1 parity: same push math, same passenger-of-same-vehicle skip, same `maxEntityCramming` damage (gamerule + 1-in-4 random). `/gamerule maxEntityCramming 0` stays at 20 TPS for unbounded farms.
- **Redstone** (`/ferrite redstone ac on`, default off). [Space Walker's Alternate Current](https://github.com/SpaceWalkerRS/alternate-current) port. Bit-correct on 150,000+ oracle checks, works on existing worlds. Per-cascade Rust BFS adds another wire-cost cut on heavy builds.
- **Hopper extract hint** (default on). Per-source-inventory hint tracks the first non-empty slot; extract loops start there instead of iterating from slot 0 every fire. Validator shadow-runs reported 0 stale events across 450+ extracts.
- **Hopper highway** (`/ferrite hopper highway on`, default off). Per-slot independent cooldowns + round-robin destination routing. Per-tick item count stays at 1 so comparator transition rate is preserved. For hopper-heavy storage; leave off for sorters tuned to vanilla 8-tick clocks.
- **World creation pre-gen** (toggle on Create World "More" tab, default off). Pre-generates a 5-50 chunk radius around spawn before the player loads in. Cancel writes a snapshot, next world load auto-resumes. Boss bar reports progress to the host. Dedicated servers: `-Dferrite.pregen.radius=N` first-launch only.
- **Density function port: 50/50 bit-exact on 26.1.2** (vs the 41/42 baseline on 1.21.11). Building blocks for the future Rust DF compiler are in tree.

---

## Where Ferrite is going

The JNI scaffolding, monitor framework, parity-oracle tooling, and Rust kernel infrastructure are paid for once. Every bottleneck the diagnostics find can become its own kernel without rebuilding the foundation. Active areas being scoped from current monitor data: chunk save serialization, mob AI visibility batching, walkability caching, percentile reporting in the monitor framework itself. A third-party kernel extension API and a Prometheus/Grafana endpoint are under exploration. Nothing on this list is promised, but the platform is built to absorb them when the structural argument holds up.

---

## Measured results

Receipts for the kernels above. Measurements taken on 26.1.2 directly.

### Cramming (1000+ active mobs)

| metric                | vanilla | Ferrite | reduction               |
| --------------------- | ------- | ------- | ----------------------- |
| `tickCramming` avg    | ~14 ms  | 0.03 ms | **~99%**                |
| `Entity.move()` avg   | ~20 ms  | ~10 ms  | ~50% (secondary effect) |
| total entity tick     | ~60 ms  | ~21 ms  | **~65%**                |

### Redstone (lag machine)

| metric                | vanilla default | Ferrite (AC)        | change              |
| --------------------- | --------------- | ------------------- | ------------------- |
| cascades per tick     | ~127,000        | ~8,250              | **~15x fewer**      |
| gate ticks per tick   | ~663            | ~2,780              | **~4x more**        |
| wire cost / gate tick | ~0.378 ms       | ~0.062 ms           | ~84% less           |
| effective TPS         | ~4              | ~5.6                | **+40%**            |
| oracle mismatches     | n/a             | 0 / 149,669 checked | bit-for-bit correct |

### Hopper extract hint

~23 µs/call at avgStartIdx=16 (~60% reduction), ~110 µs/call at avgStartIdx=53 (~85%) on partially-drained chests.

### Hopper highway

Aggregate per-hopper throughput climbs from vanilla 1/(8 ticks) to up to 5/(8 ticks). 3.1x chain throughput under back-pressure on a 100-hopper test chain.

### Pre-gen

53-104 chunks/sec depending on server load (steady ~80/s, ~50/s when competing with active player), TPS 20 holding under flight.

Measurement details in [CHANGELOG.md](https://github.com/VoiceLessQ/Ferrite/blob/main/CHANGELOG.md) and the full investigation path in [docs/PROFILING.md](https://github.com/VoiceLessQ/Ferrite/blob/main/docs/PROFILING.md).

---

## How it works

### Cramming

`LivingEntity.tickCramming` is intercepted with a Mixin. The first mob's tickCramming call in a given server tick triggers a batch: every mob's position and bounding box is packed into a direct ByteBuffer, Rust builds a 2-block spatial hash, iterates pairs with an array-index guard, applies the vanilla push formula (Chebyshev distance, exact bit-for-bit replica), and returns accumulated `(dx, dz)` velocity deltas. Java then applies each delta via `entity.addVelocity`. All subsequent tickCramming calls that tick are cancelled no-ops.

One JNI call per tick. No world state, no snapshot. The win is algorithmic: O(N·k) with spatial hashing where k is local density, instead of vanilla's per-mob `level.getEntities(bbox)` query-plus-iterate.

### Redstone

A `@Redirect(NEW)` mixin swaps `RedstoneWireBlock`'s `redstoneController` field from `DefaultRedstoneController` to `FerriteRedstoneController` (a subclass) at construction time. With `/ferrite redstone ac on`, the Ferrite controller routes wire updates through the ported Alternate Current algorithm: build the connected wire network as a graph, find power sources, do one BFS-style settle that touches each wire at most twice, write all power changes in one pass via a chunk-section bypass that skips lighting/heightmap/block-entity bookkeeping. With AC off, the controller delegates to `super.update(...)` and is byte-for-byte equivalent to vanilla.

Pure Java; no JNI. The win is algorithmic: replacing vanilla's per-wire recursive re-evaluation (which can revisit the same wire dozens of times per cascade) with one settle per cascade, plus skipping the redundant block updates a wire would normally emit between intermediate power levels.

A shadow-compute `RedstoneOracle` validates every sampled cascade against vanilla's own `calculateWirePowerAt`, so any algorithm divergence surfaces in `[redstone-oracle]` log lines.

## How to help

If you run mob farms, crowded multiplayer servers, or singleplayer worlds with lots of mobs or animals:

1. Install Ferrite + Fabric API
2. Play normally for 10+ minutes
3. Open `.minecraft/logs/latest.log`, search for `[ferrite]`
4. Share representative `[cramming-dispatch]` and `[movement-internals]` lines in a GitHub issue or CurseForge comment

Low-end hardware (4-core CPU, integrated graphics) is especially useful: the `[chunkgen]` and `[client-lag]` logs on that profile decide what gets optimized next.

---

## Requirements

- Minecraft 26.1.2 (this build) / Minecraft 1.21.11 (separate `main` branch builds)
- Java 25 (Temurin recommended; CI builds against JDK 25)
- Fabric Loader 0.18.4+
- Fabric API 0.147.0+26.1.2
- Works in **singleplayer and multiplayer**
- **Server-side compatible**, can be installed on a server without requiring players to have the mod

---

## Platform verification

| platform          | status                                                                                                                                              |
| ----------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| Windows x86_64    | Developed and tested throughout                                                                                                                     |
| Linux x86_64      | Verified, WSL Ubuntu 24.04, OpenJDK 21, server loads `/tmp/rust_mod_*.so`, initEngine returns Rayon pool size, reaches "Done" with no errors        |
| macOS (universal) | Binary confirmed structurally correct (`lipo -info` shows both x86_64 + arm64 slices); runtime load not yet verified on real Apple hardware         |

The macOS `.dylib` is a fat binary produced by `lipo -create` on the CI `macos-latest` runner. Happy to mark it verified once a Mac user confirms `System.load` succeeds: a log snippet showing `Loaded rust_mod from /tmp/rust_mod_*.dylib` is enough.

The native library is bundled for Windows, Linux, and macOS. If it fails to load on your platform, Ferrite falls back to vanilla behavior automatically: no crashes, no broken worlds. ARM Linux isn't bundled yet.

---

## Credits

- The redstone wire algorithm is adapted from [Space Walker's Alternate Current](https://github.com/SpaceWalkerRS/alternate-current) (MIT). Full attribution in [LICENSES.md](https://github.com/VoiceLessQ/Ferrite/blob/main/LICENSES.md). The port is Yarn-remapped for 1.21.11 and installed transparently as a `DefaultRedstoneController` subclass; design and algorithm remain entirely Space Walker's.
- The JNI / native-loading scaffolding was originally forked from [Brayan-724/rust-mod-probe](https://github.com/Brayan-724/rust-mod-probe), the PoC that demonstrated calling Rust from Fabric.

---

## License

MIT
