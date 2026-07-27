
## Ferrite

A performance mod for Minecraft 26.2 (Fabric, JDK 25). Java handles integration and mixins; native Rust does the heavy per-tick math where crossing the JNI boundary actually pays. Server-side compatible: install on a server, players don't need it.

**Headline numbers, all measured on real worlds:**

* **Cramming** (default on): ~65% entity-tick reduction at 1000+ packed mobs. Bit-for-bit vanilla push math.
* **Redstone** (`/ferrite redstone ac on`): Alternate Current algorithm plus a Rust BFS kernel. A lag machine that held vanilla at 1.4-1.8 TPS recovered to a flat 20.00 TPS. Zero mismatches across 150,000+ oracle checks. Off by default so contraptions tuned to vanilla update order keep working.
* **Hoppers** (default on): extract loops skip drained slots, up to ~85% cheaper on partially-emptied chests. Opt-in hopper highway multiplies chain throughput ~3x for storage systems.
* **Idle sign and furnace tickers suppressed** (default on): ~70% block-entity tick reduction at scale, self-healing, mod-subclass safe.
* **Pre-gen and predictive chunk forcing** (opt-in): 90-118 chunks/s spawn pre-generation with resume, and a generation ring that leads your flight path so you never see terrain loading.

Worldgen math (noise, biomes, density functions) is ported bit-exact and validated every release; parity checks run 63/63 noise and 50/50 density on 26.2.

**Every 5 seconds the mod logs where your tick time goes**, so optimization targets real bottlenecks. Runtime toggle: `/ferrite log monitors off`.

## Requirements

* Minecraft 26.2 (26.1.2 and 1.21.11 builds available as older releases)
* Java 25, Fabric Loader 0.19.3+, Fabric API 0.154.2+26.2
* Singleplayer and multiplayer

## Platforms

Natives bundled for Windows x86_64, Linux x86_64, Linux aarch64 (tested on a Raspberry Pi 4B), and macOS (universal). If the native fails to load, Ferrite falls back to vanilla behavior automatically: no crashes, no broken worlds.

## How to help

Play 10+ minutes with mob farms or crowded worlds, search `latest.log` for `[ferrite]`, and share the `[cramming-dispatch]` and `[entity-tick]` lines in a GitHub issue. Low-end hardware reports are especially useful.

Full measurement tables, methodology, and source: [github.com/VoiceLessQ/Ferrite](https://github.com/VoiceLessQ/Ferrite)

## Credits

* Redstone wire algorithm adapted from [Space Walker's Alternate Current](https://github.com/SpaceWalkerRS/alternate-current) (MIT); the design and algorithm remain entirely Space Walker's.
* Linux aarch64 support contributed and tested on real hardware by [cwright814](https://github.com/cwright814).
* JNI scaffolding originally forked from [Brayan-724/rust-mod-probe](https://github.com/Brayan-724/rust-mod-probe).

MIT licensed.
