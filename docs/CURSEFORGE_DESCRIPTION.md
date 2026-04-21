# Ferrite

A Fabric performance mod for Minecraft 1.21.11. **Alpha — Windows 64-bit · Linux 64-bit · macOS (Intel + Apple Silicon).**

---

## What it does

Two opt-in optimizations target different bottlenecks:

**1. Cramming (active by default).** Replaces Minecraft's mob-vs-mob collision ("cramming") loop with a Rust spatial-hash implementation.

* At 1000+ mob loads: cramming cost cut ~99% (14 ms → 0.03 ms per tick); total server entity tick cost cut ~65% (60 ms → 21 ms).
* TPS stays stable where vanilla would start slipping below 20.

**2. Redstone (opt-in: `/ferrite redstone ac on`).** Adapts [Alternate Current](https://github.com/SpaceWalkerRS/alternate-current)'s wire algorithm into Ferrite. Installed transparently as a `DefaultRedstoneController` subclass — no world migration, no experimental-toggle required.

* On a clock-based lag machine, 4-core baseline: ~15× fewer wire cascades per tick, contraptions animate ~4× faster, TPS climbs ~40% on CPU-bound hardware.
* Bit-for-bit correct vs vanilla — validated against vanilla's own `calculateWirePowerAt` on 149,000+ sampled wire checks, zero mismatches.
* Default OFF; enable with `/ferrite redstone ac on` (op-level 2).

The mod also profiles where server time goes — chunk gen, movement phases, redstone, client FPS — so future optimizations can target the next real bottleneck.

---

## Why install it

**Mob farms, sieges, entity-heavy content** — the cramming speedup is active on every tick, for every mob, regardless of CPU. Vanilla's cramming loop is O(N²) in the dense case; Ferrite is linear in effective pairs via spatial hashing.

**Redstone-heavy builds** — enable the AC path with `/ferrite redstone ac on`. Your clocks, observers, and pistons will animate noticeably faster at the same server load. On constrained hardware (4-core, dedicated servers) you'll also see a real TPS uplift; on headroom-rich machines the TPS delta may vanish but the contraption-speed win persists.

**Low-end hardware** — 4-core CPU or fewer, integrated graphics, 8 GB RAM or less — your logs surface bottlenecks that high-end machines never reveal. That data decides what gets optimized next.

Don't bother if you play alone in a fresh world with a handful of mobs and no redstone — both wins scale with load.

---

## What the logs look like

```
[ferrite] [cramming-dispatch] batches=101 mobsTotal=86710 pushed=62921
[ferrite] [movement-internals] cramming: avg=0.03ms max=0.07ms  move: avg=9.67ms  ... n=101 ticks
[ferrite] [redstone] wire: avg=0.062ms max=10.499ms cascades=230919 (gate-driven=230851 direct=68, default=0 exp=0) gates: avg=0.062ms ticks=77836  n=28 server-ticks
[ferrite] [redstone-oracle] bfs-runs=2347 sampled-out=228572 node-checks=149669 node-mismatches=0
[ferrite] [chunkgen] noise-sync: n=47 avg=8.3ms max=23.1ms  surface: n=47 avg=1.4ms max=5.2ms
[ferrite] [client-lag] fps avg=42 min=28 max=60 [WARN]  entities=312  chunks=380  samples=100
```

Tags on `[client-lag]` lines: `[OK]` (≥60 fps) / `[WARN]` (30–59 fps) / `[LAG]` (<30 fps).

When AC is enabled, `[redstone]` shows `default=0` confirming vanilla's controller is fully bypassed, and `[redstone-oracle] node-mismatches=0` confirms algorithm correctness — any divergence from vanilla surfaces immediately.

---

## How to help

1. Play normally for 10–15 minutes, especially in areas with active chunk loading, many mobs, or active redstone.
2. If you have redstone builds, try `/ferrite redstone ac on` and see whether your contraptions feel faster. `/ferrite redstone ac off` reverts at runtime with no restart.
3. Open `.minecraft/logs/latest.log`, search for `[ferrite]`.
4. Paste representative lines in a comment below or open a GitHub issue.
5. Include rough specs: CPU model, GPU (integrated or discrete), RAM.

No telemetry is sent automatically. Everything stays in your log file and you choose what to share.

---

## What we've measured

### Cramming (shipped)

| metric | vanilla | Ferrite | reduction |
|---|---|---|---|
| `tickCramming` avg | ~14 ms | 0.03 ms | **~99%** |
| `Entity.move()` avg | ~20 ms | ~10 ms | ~50% (secondary effect) |
| **total entity tick** | **~60 ms** | **~21 ms** | **~65%** |

Measured at 1000+ active mobs. TPS held at 20, zero crashes, zero fallbacks observed.

### Redstone (shipped, opt-in via `/ferrite redstone ac on`)

Reference lag machine, Ryzen 9 5900X limited to 4 active cores:

| metric | vanilla default | Ferrite (AC) | change |
|---|---|---|---|
| cascades per tick | ~127,000 | ~8,250 | **~15× fewer** |
| gate throughput per tick | ~663 | ~2,780 | **~4× more** |
| wire cost per gate tick | ~0.378 ms | ~0.062 ms | ~84% less |
| effective TPS | ~4 | ~5.6 | **+40%** |
| oracle mismatches | — | 0 / 149,669 checked | bit-for-bit correct |

**Your results will vary.** Both tables are single data points on one CPU and specific worst-case workloads. Real numbers depend on your hardware, mob-farm / contraption density, other mods, and the specific redstone patterns you use. On CPU-bound hardware you'll likely see both the cascade reduction and the TPS gain; on unconstrained hardware the gate-throughput win (contraptions running visibly faster) persists but the TPS delta can disappear because vanilla wasn't the bottleneck.

### Still-in-progress targets

* `sampleBlockState` — Rust bulk compute proved 7× faster in isolation, but getting vanilla's density data into the pipeline cleanly is the current blocker. Framework ships in the jar; output is gated.
* `Entity.adjustMovementForCollisions` — attempted, shelved. Snapshot materialization cost exceeded the sweep savings at realistic mob counts. Documented for future work.

---

## Requirements

* Minecraft 1.21.11
* Fabric Loader 0.18.4+
* Fabric API
* **Windows 64-bit · Linux 64-bit · macOS (Intel + Apple Silicon)**

---

## Known limitations

* The native library is bundled for Windows, Linux, and macOS. If it fails to load on your platform, Ferrite falls back to vanilla behavior automatically — no crashes, no broken worlds. ARM Linux isn't bundled yet.
* **Cramming damage deferred.** 1.21.11 refactored the GameRules API; cramming death-by-suffocation from the `maxEntityCramming` game rule is disabled when the Rust cramming path is active. The push is applied, but the damage isn't.
* **AC redstone is opt-in (`ENABLED=false`) this release.** Enable explicitly with `/ferrite redstone ac on`. Default-on is planned for a later release after broader user-reports.
* **Alpha state.** Report anything that looks broken.
* **Console output is chatty.** Up to ~12 lines per minute during active play. Logging toggle is on the roadmap.

---

## Credits

Wire propagation algorithm adapted from [Alternate Current](https://github.com/SpaceWalkerRS/alternate-current) by Space Walker (MIT). Ferrite's port is yarn-remapped for 1.21.11 and installed transparently as a `DefaultRedstoneController` subclass; design and algorithm remain entirely Space Walker's work. Full attribution in [LICENSES.md](https://github.com/VoiceLessQ/Ferrite/blob/main/LICENSES.md).

---

## License

MIT. Source and build instructions at the GitHub repo (sidebar).
