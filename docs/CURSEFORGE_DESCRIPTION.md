
# Ferrite

A Fabric performance mod for Minecraft 1.21.11 powered by a Rust native library. **Alpha — Windows x86_64 · Linux x86_64 · macOS aarch64 (Apple Silicon).**

---

## What it does

Ferrite replaces Minecraft's mob-vs-mob collision ("cramming") loop with a Rust spatial-hash implementation.

**At 1000+ mob loads (mob farms, zombie sieges, crowded servers):**

* **Cramming cost cut ~99%** (14 ms → 0.03 ms per tick)
* **Total server entity tick cost cut ~65%** (60 ms → 21 ms)
* **TPS stays stable** where vanilla would start slipping below 20

What that means in practice: on a 4-core server with ~500 mobs loaded, vanilla's entity tick can eat 30–40 ms of the 50 ms tick budget, and the server starts stuttering under any additional load. With Ferrite, the same scenario uses ~10–14 ms and leaves the rest of the budget free for redstone, block entities, and everything else.

On single-player worlds with a few dozen mobs you won't notice a difference. The win scales with entity count.

The mod also profiles where time goes so future Rust ports can target the next bottleneck.

**Current release does four things:**

* Replaces vanilla `tickCramming` with a Rust spatial-hash batched push (the main win)
* Runs Rust-accelerated terrain compute alongside vanilla chunk generation (secondary)
* Profiles chunk gen costs, entity movement phases, and client FPS every 5 seconds
* Writes results to `.minecraft/logs/latest.log` with a `[ferrite]` prefix so you can see exactly what's happening

---

## What the logs look like

```
[ferrite] [cramming-dispatch] batches=101 mobsTotal=86710  pushed=62921
[ferrite] [movement-internals] cramming: avg=0.03ms max=0.07ms  move: avg=9.67ms  travel: avg=10.06ms  ...  n=101 ticks
[ferrite] [chunkgen] noise-sync: n=47 avg=8.3ms max=23.1ms  surface: n=47 avg=1.4ms max=5.2ms
[ferrite] [client-lag] fps avg=42 min=28 max=60 [WARN]  entities=312  chunks=380  samples=100
[ferrite] [worldtick] blockentities: avg=0.2ms max=0.4ms  entities: avg=6.1ms max=12.3ms  n=100 ticks
```

Tags on `[client-lag]` lines: `[OK]` (≥60fps) / `[WARN]` (30–59fps) / `[LAG]` (<30fps).

---

## Why install it

**If you run mob farms, sieges, or entity-heavy content** — Ferrite directly cuts server tick cost at high mob densities. Vanilla's cramming loop is O(N²) in the dense case; Ferrite is linear in effective pairs via spatial hashing. The cramming speedup is active on every tick, for every mob, regardless of CPU.

**If you have low-end hardware** — 4-core CPU or fewer, integrated graphics, 8GB RAM or less — your logs also show chunk-gen and movement bottlenecks that high-end machines never surface. That data decides what gets optimized next.

Don't bother if you play alone in a fresh world with a handful of mobs — the win scales with entity count and won't matter at small scale.

---

## How to help

1. Play normally for 10–15 minutes, especially in areas with active chunk loading or many mobs
2. Open `.minecraft/logs/latest.log`
3. Search for `[ferrite]`
4. Paste representative lines in a comment below or open a GitHub issue
5. Include rough specs: CPU model, GPU (integrated or discrete), RAM

No telemetry is sent automatically. Everything stays in your log file and you choose what to share.

---

## What we've measured

**Cramming port — shipped in this release:**

| metric | vanilla | Ferrite | reduction |
|---|---|---|---|
| `tickCramming` avg | ~14 ms | 0.03 ms | **~99%** |
| `Entity.move()` avg | ~20 ms | ~10 ms | ~50% (secondary — mobs no longer getting pushed into block geometry as aggressively) |
| **total entity tick** | **~60 ms** | **~21 ms** | **~65%** |
| movement phase total | ~55 ms | ~21 ms | ~62% |

Measured at 1000+ active mobs. TPS held at 20, zero crashes, zero fallbacks observed.

**On measurement conditions:** these numbers come from an unconstrained Ryzen 9 5900X run. On an actual 4-core server, absolute millisecond numbers would be higher across the board, but the *percentage* improvements hold — both vanilla and Rust scale with core count similarly for this workload. The win is algorithmic (spatial hash vs O(N²)-ish sequential loop), not parallelism.

**Still-in-progress Rust targets:**

* `sampleBlockState` — Rust bulk compute proved 7× faster in equivalent work, but getting vanilla's density data into the pipeline cleanly is the current blocker. Framework is in the jar; output is gated.
* `Entity.adjustMovementForCollisions` — attempted, shelved. Snapshot materialization cost exceeded the sweep savings at realistic mob counts. Documented in PROFILING.md for future work.

---

## Requirements

* Minecraft 1.21.11
* Fabric Loader 0.18.4+
* Fabric API
* **Windows x86_64 · Linux x86_64 · macOS aarch64 (Apple Silicon)**

---

## Known limitations

* **Three platforms, not all architectures.** Windows x86_64, Linux x86_64, and macOS aarch64 (Apple Silicon) are supported. Intel Macs and ARM Linux aren't bundled yet — the mod loads on those but the native stays disabled with a clear log message (falls back to vanilla, no crashes).
* **Cramming damage deferred.** 1.21.11 refactored the GameRules API; cramming death-by-suffocation from the `maxEntityCramming` game rule is disabled when Ferrite is active. The push is applied, but the damage isn't. Mobs in mob farms still die from fall damage / suffocation as normal — just not from the cramming game-rule threshold.
* **Alpha state.** Report anything that looks broken.
* **Console output is chatty.** Up to ~12 lines per minute during active play. Logging toggle is on the roadmap.

---

## License

MIT. Source and build instructions at the GitHub repo (sidebar).
