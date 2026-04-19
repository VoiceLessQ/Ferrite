## Ferrite

**What you get:** A Rust-powered performance mod for Minecraft 1.21.11. The headline win in the current release is a Rust reimplementation of the mob-vs-mob cramming loop — on servers or worlds with 1000+ mobs it cuts the server's entity-tick cost by roughly 65%.

Every 5 seconds the mod also logs where your game is spending time, so the next Rust port can target the next real bottleneck.

---

## Measured results (1000+ active mobs)

| metric                | vanilla | Ferrite | reduction               |
| --------------------- | ------- | ------- | ----------------------- |
| `tickCramming` avg    | ~14 ms  | 0.03 ms | **~99%**                |
| `Entity.move()` avg   | ~20 ms  | ~10 ms  | ~50% (secondary effect) |
| total entity tick     | ~60 ms  | ~21 ms  | **~65%**                |

TPS held at 20 under the same load that was costing vanilla 60 ms/tick of entity work.

> **Note:** Results measured on a Ryzen 9 5900X limited to 4 active cores via CPU affinity (simulating a 4-core machine), with 1000+ zombies in a concentrated area. Improvement scales with mob count and density — smaller mob counts will see proportionally less benefit. Real 4-core hardware may show different absolute numbers but percentage improvements should be similar. Results may vary based on world setup, other mods, and hardware.

Measurement details in [CHANGELOG.md](CHANGELOG.md) and the full investigation path in [docs/PROFILING.md](docs/PROFILING.md).

---

## How it works

`LivingEntity.tickCramming` is intercepted with a Mixin. The first mob's tickCramming call in a given server tick triggers a batch: every mob's position and bounding box is packed into a direct ByteBuffer, Rust builds a 2-block spatial hash, iterates pairs with an array-index guard, applies the vanilla push formula (Chebyshev distance, exact bit-for-bit replica), and returns accumulated `(dx, dz)` velocity deltas. Java then applies each delta via `entity.addVelocity`. All subsequent tickCramming calls that tick are cancelled no-ops.

One JNI call per tick. No world state, no snapshot. The win is algorithmic — O(N·k) with spatial hashing where k is local density, instead of vanilla's per-mob `level.getEntities(bbox)` query-plus-iterate.

---

## What's still in progress

* **Chunk generation** — Rust bulk compute proved 7× faster than vanilla noise-sync in equivalent work, but piping vanilla's internal density-function data into the pipeline cleanly is the current blocker. Framework ships in the jar; output is gated.
* **`adjustMovementForCollisions` port** — attempted, shelved. The AABB sweep math runs correctly in Rust, but snapshot materialization cost exceeded the sweep savings at realistic mob counts. Retained as disabled infrastructure for a future invalidation-cache redesign.

---

## How to help

If you run mob farms, crowded servers, or entity-heavy worlds:

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
- **Platforms**: Windows 64-bit · Linux 64-bit · macOS (Intel + Apple Silicon)

> The native library is bundled for Windows, Linux, and macOS. If it fails to load on your platform, Ferrite falls back to vanilla behavior automatically — no crashes, no broken worlds. ARM Linux isn't bundled yet.

---

## Credits

Inspired by and originally forked from [Brayan-724/rust-mod-probe](https://github.com/Brayan-724/rust-mod-probe) — the original PoC that demonstrated calling Rust from Fabric via JNI.

---

## License

MIT
