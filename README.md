## Ferrite

A performance mod for Minecraft 26.3. It is a Fabric mod that calls into native Rust over JNI for the hot paths. Java handles the Minecraft integration and mixins; Rust does the per-tick math where the win is large enough to pay for the JNI crossing.

Every port keeps vanilla behavior bit for bit and comes with an oracle that checks it against vanilla while you play. Anything that failed to beat vanilla is off by default.

## What it does

- **Cramming** (on by default, `/ferrite cramming on|off|status`). The mob-vs-mob cramming loop runs in Rust with a spatial hash, one JNI call per tick. Same push math, same `maxEntityCramming` damage rule as vanilla. Cuts entity-tick cost by roughly 65% next to a 1000+ mob farm.
- **Entity query index and collider skip** (on by default since 0.7.3). A spatial index answers entity lookups in front of vanilla, and the collider skip fires only when the answer is provably empty. A 1022-zombie farm on a 4-core server went from 15.5 TPS at 64 ms/tick to 20 TPS at 31 ms, zero mismatches across 10.8M oracle checks. Kill switches: `-Dferrite.entityquery.cache=false` and `-Dferrite.entityquery.colliderskip=false`.
- **Redstone** (`/ferrite redstone ac on`, off by default). An adaptation of [Space Walker's Alternate Current](https://github.com/SpaceWalkerRS/alternate-current). On the reference lag machine: ~15x fewer wire cascades, ~4x gate throughput, zero mismatches across ~150,000 oracle checks. Off by default so contraptions that depend on vanilla wire-update order (quasi-connectivity, 0-tick pulses, instawire) work untouched. With AC on, each cascade also runs through a Rust BFS kernel for another ~30% off wire cost; `/ferrite redstone bfs off` disables that part.
- **Sign and furnace ticker gates** (on by default, no toggle). Vanilla ticks every sign and every furnace each tick. Ferrite drops the ticker for idle vanilla signs and empty vanilla furnaces and puts it back the moment they are used. About 70% less block-entity tick cost at 961 signs. Mod subclasses keep their tickers.
- **Hopper extract hint** (on by default, no toggle). Extraction starts at the first known non-empty slot instead of slot 0. Up to ~85% less per call on drained chests, same one-item-per-fire contract as vanilla.
- **Hopper highway** (`/ferrite hopper highway on`, off by default). Per-slot cooldowns and round-robin routing, about 3.1x chain throughput. Off by default because sorters timed to the vanilla 8-tick clock can saturate.
- **World-creation pre-gen** (`/ferrite pregen <radius>`). Generates a border on a strong machine so a weak server reads chunks from disk. Re-running skips finished chunks.

The mod also logs where the server spends its time every 5 seconds. The `[hw]` line at boot describes the machine, so log excerpts in an issue self-describe.

## Measured results

Both tables come from one CPU (Ryzen 9 5900X pinned to 4 cores) on worst-case worlds: a zombie pile for cramming, a clock-based lag machine for redstone. Your numbers will differ. On CPU-bound hardware you should see both the reduction and the TPS gain; with headroom, the TPS delta can vanish because vanilla was never the bottleneck.

Cramming, 1000+ active mobs:

| metric              | vanilla | Ferrite | reduction |
| ------------------- | ------- | ------- | --------- |
| `tickCramming` avg  | ~14 ms  | 0.03 ms | ~99%      |
| total entity tick   | ~60 ms  | ~21 ms  | ~65%      |

Redstone, lag machine with AC on:

| metric                | vanilla   | Ferrite (AC)        | change      |
| --------------------- | --------- | ------------------- | ----------- |
| cascades per tick     | ~127,000  | ~8,250              | ~15x fewer  |
| gate ticks per tick   | ~663      | ~2,780              | ~4x more    |
| wire cost / gate tick | ~0.378 ms | ~0.062 ms           | ~84% less   |
| oracle mismatches     | n/a       | 0 / 149,669 checked | bit-exact   |

AC takes a small overhead on tiny builds (a single clock and a 64-block wire measured ~0.083 ms/tick against ~0.026 ms vanilla) and wins on dense contraptions with feedback. Gate speeds are vanilla-identical either way.

Measurement details are in [CHANGELOG.md](CHANGELOG.md), the investigation path in [docs/PROFILING.md](docs/PROFILING.md), and the retrospective in [docs/JOURNEY.md](docs/JOURNEY.md).

## Commands

Everything lives under `/ferrite`. Settings hold for the running session, not across restarts.

| Command | Effect | Default |
|---|---|---|
| `/ferrite cramming on\|off\|status` | Rust cramming. A/B switchable without a restart. | on |
| `/ferrite hopper highway on\|off\|status` | Per-slot cooldowns and round-robin routing. | off |
| `/ferrite redstone ac on\|off\|status` | Alternate Current wire algorithm. | off |
| `/ferrite redstone bfs on\|off\|status` | Rust BFS for power propagation. Only active while AC is on. | on |
| `/ferrite redstone bfs-min <int>` | Smallest cascade (in wires) sent to Rust. | 1 |
| `/ferrite redstone bench` | Built-in lag-machine benchmark in the current world. | n/a |
| `/ferrite pregen <radius>` | Pre-generate chunks around spawn. `pregen inflight <n>` caps concurrency. | n/a |

`/ferrite surface` and `/ferrite aquifer rust` still register on 26.x builds but change nothing. The surface dispatcher lost its hooks in the 26.1 port, and the density and aquifer ports are out of the 26.3 build until they are redone for the rewritten density code. All of them were slower than vanilla in a real chunk, because the JNI handoff costs more than the compute saves, so none were on by default anyway.

## Requirements

- Minecraft 26.3 with JDK 25 (most launchers provide it). 26.2, 26.1.2 and 1.21.11 builds exist as older releases.
- Fabric Loader 0.19.5 or newer, Fabric API 0.161.0+26.3 or newer.
- Works in singleplayer and multiplayer. Server-side only: players do not need the mod.

## Running on low-end hardware

A recipe from a production setup on a 2 GB Raspberry Pi 4B with two players and ~200 MB to spare:

1. Pre-generate the world on a stronger machine with `/ferrite pregen <radius>` and copy the world folder over. If you must pre-gen with players online, lower concurrency with `/ferrite pregen inflight 50`.
2. Use a small heap and a lean JVM. On HotSpot prefer ZGC (`-XX:+UseZGC`): in a 4-core, 2 GB test at 1022 zombies, G1 froze for up to 640 ms per collection while ZGC held 20 TPS. On heaps of 3 GB or less, Ferrite silences its periodic monitor log so SD-card I/O does not pay for it; `/ferrite log monitors on` turns it back on.
3. Watch entities, not chunks. On a weak CPU the tick budget goes to mobs first, and that is what the default-on features target. The `[entity-tick]` log line shows where the rest goes.

Ferrite itself costs a few megabytes: about 3.4 KB of live Java objects on a loaded world, plus the Rust worldgen state and per-tick native buffers of roughly 50 KB per 1000 mobs.

## Platforms

| platform | status |
|---|---|
| Windows x86_64 | Developed and tested throughout |
| Linux x86_64 | Verified (Ubuntu 24.04) |
| Linux aarch64 | Verified on a Raspberry Pi 4B |
| macOS universal | Fat binary built in CI; runtime load not yet confirmed on real Apple hardware. A log line showing `Loaded rust_mod from /tmp/rust_mod_*.dylib` is enough to mark it verified. |

If the native library fails to load on your platform, Ferrite falls back to vanilla behavior with no crash.

## Building from source

Needs JDK 25, stable Rust via [rustup](https://rustup.rs/), and a C linker (mingw-w64 on Windows, see [docs/SETUP_MINGW.md](docs/SETUP_MINGW.md); system GCC on Linux; Xcode tools on macOS).

```
git clone https://github.com/VoiceLessQ/Ferrite.git
cd Ferrite
./gradlew build
```

The `buildRustLib` task compiles the native library for your host and copies it into the jar, which lands in `build/libs/`. A local jar bundles only your platform; release jars bundle all four. `cargo test` and `cargo clippy --release` from the repo root match the CI gates.

## How to help

Run it on a mob farm or a crowded server for ten minutes, then search `latest.log` for `[ferrite]` and share the `[cramming-dispatch]` and `[entity-tick]` lines in a GitHub issue or discussion. Low-end hardware reports are the most useful ones.

## Credits

- Redstone wire algorithm adapted from [Space Walker's Alternate Current](https://github.com/SpaceWalkerRS/alternate-current) (MIT); full attribution in [LICENSES.md](LICENSES.md).
- JNI scaffolding forked from [Brayan-724/rust-mod-probe](https://github.com/Brayan-724/rust-mod-probe).
- Linux aarch64 support contributed and tested on real hardware by [cwright814](https://github.com/cwright814) in PR #8.

## License

MIT
