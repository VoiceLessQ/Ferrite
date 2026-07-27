## Ferrite

**What you get:** a performance mod for Minecraft 26.1.2. It is a Fabric (Java) mod that calls into native Rust via JNI for the hot paths. Java handles Minecraft integration and mixins; Rust does the heavy per-tick math where the win is big enough to justify crossing the JNI boundary.

**Working today:**

- **Cramming (active by default, toggle with `/ferrite cramming on|off|status`).** A Rust reimplementation of the mob-vs-mob cramming loop. Cuts the server's entity-tick cost by roughly 65% at high mob density. It applies to every MobEntity subclass, so a villager trading hall with 50+ villagers gets the same spatial-hash win as a mob farm, and you keep stable TPS standing next to a 1000+ mob farm. Vanilla 1:1 parity: same push math, same `isPassengerOfSameVehicle` skip, same cramming-damage application (gated by `maxEntityCramming` gamerule and per-entity 1-in-4 random, identical to vanilla). For unbounded farms, set `/gamerule maxEntityCramming 0` like you would in vanilla; Ferrite keeps that scenario at 20 TPS. To A/B the perf claim, `/ferrite cramming off` falls back to vanilla without a restart.
- **Redstone (`/ferrite redstone ac on`, default off).** Adapts [Space Walker's Alternate Current](https://github.com/SpaceWalkerRS/alternate-current) algorithm into Ferrite. On the reference lag machine: ~15x fewer wire cascades and ~4x gate throughput at equivalent server load, with zero mismatches across ~150,000 oracle checks. No world migration needed; turn it on per world with the command. It stays off by default so contraptions tuned to vanilla wire-update order behave identically out of the box. While AC is on, each cascade's power propagation also runs through a Rust kernel (one batched JNI call per cascade) for another ~30% wire-cost reduction on heavy contraptions; `/ferrite redstone bfs off` disables that part.
- **Sign + furnace ticker hygiene (active by default, no toggle).** Vanilla registers a `BlockEntityTicker` for every sign and every furnace at chunk load and ticks all of them every server tick, even though the body does nothing useful 99%+ of the time (no one is editing the sign, the furnace is empty). Ferrite suppresses the ticker for vanilla `SignBlockEntity` / `HangingSignBlockEntity` with no active editor (re-registers the moment a player opens the edit screen) and for vanilla `FurnaceBlockEntity` / `BlastFurnaceBlockEntity` / `SmokerBlockEntity` when empty, not burning, and with no recipe in progress (re-registers on `setStack`, so a hopper insert wakes the furnace immediately). Measured: ~70% BE-tick cost reduction at 961 placed signs (0.20 ms to 0.06 ms / tick), and zero measurable BE-tick cost at 500 idle furnaces within the measurement noise floor. A strict-class check preserves mod subclass behavior, so modded sign or furnace types with non-trivial tick bodies keep their tickers. Self-heals from persisted-editor state within 2 ticks of chunk load.
- **Hopper extract hint (active by default, no toggle).** Skip-empty-prefix on extract: when a hopper pulls from a partially-drained source (a chest with the front slots emptied), the loop starts at the first known non-empty slot instead of slot 0. Saves ~23 µs/call at avgStartIdx=16, up to ~110 µs/call at avgStartIdx=53 (~85% reduction). The vanilla 1-item-per-fire contract is preserved: same cooldown=8, same comparator output formula, same hopper-to-hopper chain timing. Parity was proven across 450+ validator-checked extracts before release; opt-in validator at `-Dferrite.hopper.extract.validate=true`. See [docs/HOPPER_HIGHWAY.md](docs/HOPPER_HIGHWAY.md).
- **Hopper highway (opt-in, default off).** `/ferrite hopper highway on` activates per-slot independent cooldowns plus round-robin destination routing in `transfer()`. Each of a hopper's 5 slots fires at vanilla 8-tick speed, but staggered, so a single hopper moves up to 5 items per 8 ticks instead of 1. Measured ~3.1x chain throughput under steady-state flow with `tickViolations=0` and `staggerCollapses=0` across 20K+ validator-checked fires. Items distribute across all 5 destination slots instead of always landing in slot 0, so chain hoppers visibly use lanes 1-4 in their UIs. It is default off because aggregate throughput is a 5x rate change that downstream redstone tuned to vanilla pace can saturate; turn it on for hopper-heavy storage where speed wins, leave it off for sorters timed to vanilla 8-tick clocks.
- **Surface rule dispatcher (opt-in, default off).** `/ferrite surface dispatch on` runs surface rule evaluation in Rust with a batched per-column heightmap update. It currently measures ~13.4 ms ON vs ~6.4 ms vanilla baseline, a ~7 ms structural gap; useful for A/B measurement but not recommended for production until the gap closes. Parity-clean (100% match across 23K+ chunks). See [Commands](#commands) below for the full setup sequence.

Every 5 seconds the mod also logs where your game is spending time, so the next optimization can target the next real bottleneck.

---

## Status: consolidation cycle

Six features are live across cramming, redstone, hoppers, world-creation pre-gen, chunkgen baselines, and density functions. The next stretch is not adding more, it is deepening what already works. Some internals may change as we revisit the assumptions baked in during their first ports, and a few default-off paths exist because their current shape did not beat vanilla and want a structural rethink. User-facing toggles and parity validators stay; the implementations underneath get better.

The hopper highway and world-creation pre-gen are default-off opt-ins. They work, and their oracles and shadow-validators show parity, but they need real-server validation across player setups before flipping default-on. Operators who enable them are helping validate the current shape, not guinea-pigging an unknown.

---

## Measured results

### Cramming (1000+ active mobs)

| metric                | vanilla | Ferrite | reduction               |
| --------------------- | ------- | ------- | ----------------------- |
| `tickCramming` avg    | ~14 ms  | 0.03 ms | **~99%**                |
| `Entity.move()` avg   | ~20 ms  | ~10 ms  | ~50% (secondary effect) |
| total entity tick     | ~60 ms  | ~21 ms  | **~65%**                |

TPS held at 20 under the same load that was costing vanilla 60 ms/tick of entity work.

### 254-mob steady-state baseline

Fully instrumented profiling session with 254 hostile mobs loaded and active:

| metric              | value                                    |
| ------------------- | ---------------------------------------- |
| tick time (ms/tick) | avg ~9-10ms, max spikes ~11-19ms         |
| entity tick         | avg ~4.7ms (all 254 mobs, full movement) |
| TPS                 | 20/20 (50ms budget, well clear)          |

Breakdown of the 4.7ms entity tick: travel ~1.63ms, goal selectors + controls ~1.87ms, collision math ~1.07ms, block collision ~0.31ms, cramming 0.01ms. Numbers stable across all measurement windows. The entity tick seam is fully characterized, with no remaining mystery buckets. See [docs/FUTURE_PLANS.md](docs/FUTURE_PLANS.md) for port-verdict details per bucket.

### Redstone (lag-machine benchmark, AC algorithm enabled)

| metric                 | vanilla default     | Ferrite (AC)         | change                |
| ---------------------- | ------------------- | -------------------- | --------------------- |
| cascades per tick      | ~127,000            | ~8,250               | **~15x fewer**        |
| gate ticks per tick    | ~663                | ~2,780               | **~4x more**          |
| wire cost / gate tick  | ~0.378 ms           | ~0.062 ms            | ~84% less             |
| effective TPS          | ~4                  | ~5.6                 | **+40%**              |
| oracle mismatches      | n/a                 | 0 / 149,669 checked  | bit-for-bit correct   |

Re-tested on MC 26.1.2 with a heavier lag machine (full burst, view
distance 16, simulation 12): the vanilla path held 1.4-1.8 TPS at up to
716 ms per tick; enabling AC mid-choke recovered to a flat 20.00 TPS in
about 40 seconds, with roughly 13x fewer cascades and 6x cheaper gate
updates on the same build.

Two user-visible effects combine:
- Contraptions animate about 4x faster at equivalent server load. Each wire cascade now collapses into a single network settle (~84% less wire time per gate tick), so the same per-tick budget processes more gate ticks.
- Server TPS climbs about 40% on CPU-bound hardware. When the server is saturated (as in this 4-core baseline), wire savings convert directly into more completed ticks per second. On unconstrained hardware with headroom, TPS stays flat but the gate-throughput win persists.

**Vanilla compatibility note.** Gate tick speeds (repeaters, comparators, observers, torches) are vanilla-identical. Wire update ordering intentionally differs from vanilla: AC skips intermediate power-level updates for efficiency. Contraptions relying on quasi-connectivity, 0-tick pulses, or instawire should leave `/ferrite redstone ac` off (the default).

AC wins big on dense contraptions with feedback amplifiers (like the lag machine above) and takes slight overhead on small clean builds. A single repeater clock plus a 64-block wire run measured ~0.083 ms / tick on AC vs ~0.026 ms / tick on vanilla, because AC's per-cascade setup cost only beats vanilla when there is enough redundancy to amortize it. Both effects stay well under 1 ms / tick on realistic setups, so the small-build overhead is imperceptible.

> **Per-cascade Rust BFS (0.4.0-alpha, active whenever AC is on).** With AC enabled, each wire cascade's power propagation runs in a Rust kernel via one batched JNI call (Java still emits the resulting block/shape updates). It adds another ~30% wire-cost reduction on heavy contraptions (1.3 to 2.1x per cascade across measured size buckets) on top of the AC numbers above. The regression on small cold workloads is imperceptible (~20µs / cascade); disable per-world with `/ferrite redstone bfs off` if a specific contraption misbehaves. See [docs/REDSTONE_PORT_PLAN.md](docs/REDSTONE_PORT_PLAN.md) for per-bucket measurements.

> **Your results will vary.** Both tables are single data points on one CPU (Ryzen 9 5900X limited to 4 active cores via affinity) and specific worst-case workloads (a concentrated zombie pile for cramming, a clock-based lag machine for redstone). Real numbers depend on your hardware, the size and density of your mob farms or contraptions, other mods you run, and the specific redstone patterns you use. On CPU-bound hardware you will likely see both the cascade reduction and the TPS improvement; on unconstrained hardware the gate-throughput win (contraptions running visibly faster) persists but the TPS delta can disappear entirely, because vanilla was not the bottleneck.

Measurement details in [CHANGELOG.md](CHANGELOG.md), the full investigation path in [docs/PROFILING.md](docs/PROFILING.md), and a cross-port retrospective in [docs/JOURNEY.md](docs/JOURNEY.md).

---

## How it works

### Cramming

`LivingEntity.tickCramming` is intercepted with a Mixin. The first mob's tickCramming call in a given server tick triggers a batch: every mob's position and bounding box is packed into a direct ByteBuffer, Rust builds a 2-block spatial hash, iterates pairs with an array-index guard, applies the vanilla push formula (Chebyshev distance, exact bit-for-bit replica), and returns accumulated `(dx, dz)` velocity deltas. Java then applies each delta via `entity.addVelocity`. All subsequent tickCramming calls that tick are cancelled no-ops.

One JNI call per tick. No world state, no snapshot. The win is algorithmic: O(N·k) with spatial hashing where k is local density, instead of vanilla's per-mob `level.getEntities(bbox)` query-plus-iterate.

### Redstone

A `@Redirect(NEW)` mixin swaps `RedstoneWireBlock`'s `redstoneController` field from `DefaultRedstoneController` to `FerriteRedstoneController` (a subclass) at construction time. With `/ferrite redstone ac on`, the Ferrite controller routes wire updates through the ported Alternate Current algorithm: build the connected wire network as a graph, find power sources, do one BFS-style settle that touches each wire at most twice, write all power changes in one pass via a chunk-section bypass that skips lighting/heightmap/block-entity bookkeeping. With AC off, the controller delegates to `super.update(...)` and is byte-for-byte equivalent to vanilla.

Pure Java, no JNI. The win is algorithmic: replacing vanilla's per-wire recursive re-evaluation (which can revisit the same wire dozens of times per cascade) with one settle per cascade, plus skipping the redundant block updates a wire would normally emit between intermediate power levels.

A shadow-compute `RedstoneOracle` validates every sampled cascade against vanilla's own `calculateWirePowerAt`, so any algorithm divergence surfaces immediately in `[redstone-oracle]` log lines.

---

## Commands

All Ferrite toggles live under `/ferrite`. Default state is in the rightmost column. Settings are volatile: they persist for the running server session, not across restarts.

### User-facing toggles

| Command | Effect | Default |
|---|---|---|
| `/ferrite cramming on\|off\|status` | Rust spatial-hash mob-vs-mob cramming. Vanilla parity, A/B switchable. | **on** |
| `/ferrite hopper highway on\|off\|status` | Per-slot independent cooldowns + round-robin destination routing. ~3x chain throughput. Each slot still vanilla 8-tick speed; aggregate is 5 lanes in parallel. Turn off for sorters tuned to vanilla pacing. Extract hint stays default-on regardless. | off |
| `/ferrite redstone ac on\|off\|status` | Alternate Current wire algorithm (~15x fewer cascades). Turn on for performance; turn off if a contraption relies on quasi-connectivity, 0-tick pulses, or instawire. | off |
| `/ferrite redstone bfs on\|off\|status` | Per-cascade Rust BFS for power propagation (~30% additional wire-cost reduction). Only effective when AC is on. | on (unreachable until AC is enabled via `/ferrite redstone ac on`) |
| `/ferrite redstone bfs-min <int>` | Minimum cascade size (in wires) before dispatching through Rust. Raise this to skip small cascades where JNI overhead exceeds the win. | 1 |
| `/ferrite redstone bench` | Run a built-in lag-machine benchmark in the current world. | n/a |

### Surface dispatcher (opt-in, debug / measurement)

The surface rule dispatcher runs vanilla's `BlockStateRule.tryApply` in a Rust evaluator with a batched heightmap update. Default OFF, currently ~7 ms above vanilla baseline (see lead). Useful for A/B measurement and as a foundation for future architectural work. Setup sequence:

```
/ferrite surface validate            # compile this world's surface rule into a bytecode tree
/ferrite surface dispatch on         # turn the dispatcher on (requires a tree from validate)
... fly through fresh chunks ...
/ferrite surface validate-stats      # print rolling parity + perf statistics
/ferrite surface dispatch off        # back to vanilla
/ferrite surface validate-off        # release the tree
```

Full reference:

| Command | Effect |
|---|---|
| `/ferrite surface validate` | Compile the active world's surface rule into a bytecode tree. Required before `dispatch on` can do anything. |
| `/ferrite surface validate-off` | Clear the installed tree. |
| `/ferrite surface validate-stats` | Print rolling validator stats (sample count, vanilla-vs-eval match %, java-vs-rust agreement %). |
| `/ferrite surface dispatch on\|off\|status` | Toggle the batched dispatcher. |
| `/ferrite surface heightmap-parity on\|off\|stats\|reset` | Diff the batched heightmap update against vanilla's per-write `trackUpdate` reference. Regression check; ~1 ms/chunk overhead when on. Validated 100% match across 23K+ chunks; turn on if you have changed surface rules and want to confirm the predicate-preserving assumption still holds. |

### Other opt-ins (default off, measurement / experimental)

| Command / flag | Effect |
|---|---|
| `/ferrite aquifer rust on\|off\|status` | Toggle the Rust aquifer port. Currently disabled, with a fine-grain parity gap against vanilla unresolved. |
| `-Dferrite.bulkChunkDensity=true` (JVM flag) | Enable the bulk chunk density Rust kernel for benchmarking. Confirmed JIT-wall regression at realistic load; use for measurement only. |

### What runs invisibly (no toggle needed)

- **Diagnostic gating.** `CacheRouteCaptureMixin` and `AquiferMonitor` are gated off by default, removing ~8-10 ms/chunk of instrumentation overhead the early profiling sessions used.

This runs for everyone with no opt-in required, because it purely reduces overhead in a code path that runs regardless of other Ferrite settings.

---

## What's still in progress

* **Chunk generation.** The Rust bulk-compute kernel measured ~7x faster than vanilla's noise-sync on equivalent work. The speedup is real but blocked at the density-function layer: vanilla evaluates DFs interleaved with interpolation inside `NoiseChunkGenerator` (marked `final`), so there is no clean intermediate cell-corner grid to hand to Rust without reimplementing the full DF tree. We pivoted to surface rule batch evaluation, which runs after density resolves with a clean boundary and still captures a realistic end-to-end chunkgen win.
* **`adjustMovementForCollisions` port.** Attempted, then shelved. The AABB sweep math runs correctly in Rust, but snapshot materialization cost exceeded the sweep savings at realistic mob counts. Retained as disabled infrastructure for a future invalidation-cache redesign.

---

## How to help

If you run mob farms, crowded multiplayer servers, or singleplayer worlds with lots of mobs or animals:

1. Install Ferrite + Fabric API
2. Play normally for 10+ minutes
3. Open `.minecraft/logs/latest.log`, search for `[ferrite]`
4. Share representative `[cramming-dispatch]` and `[movement-internals]` lines in a GitHub issue or CurseForge comment

Low-end hardware (4-core CPU, integrated graphics) is especially useful: the `[chunkgen]` and `[client-lag]` logs on that profile decide what gets optimized next.

---

## Requirements

- Minecraft 26.1.2 (JDK 25 required, provided automatically with most modern launchers)
- Fabric Loader 0.18.4+
- Fabric API 0.147.0+26.1.2 or newer
- Works in **singleplayer and multiplayer**
- **Server-side compatible**, can be installed on a server without requiring players to have the mod

---

## Platform verification

| platform | status |
|---|---|
| Windows x86_64 | Developed and tested throughout |
| Linux x86_64 | Verified on WSL Ubuntu 24.04, OpenJDK 21: server loads `/tmp/rust_mod_*.so`, initEngine returns Rayon pool size, reaches "Done" with no errors |
| Linux aarch64 | Verified: server loads `/tmp/rust_mod_*.so`, native engine initializes and performs well (tested on Raspberry Pi 4B) |
| macOS (universal) | Partial: binary confirmed structurally correct (`lipo -info` shows both x86_64 + arm64 slices); runtime load not yet verified on real Apple hardware |

The macOS `.dylib` is a fat binary produced by `lipo -create` on the CI `macos-latest` runner. Happy to mark it verified once a Mac user confirms `System.load` succeeds; a log snippet showing `Loaded rust_mod from /tmp/rust_mod_*.dylib` is enough.

The native library is bundled for Windows, Linux (x86_64 and aarch64), and macOS. If it fails to load on your platform, Ferrite falls back to vanilla behavior automatically, with no crashes and no broken worlds.

---

## Credits

- The redstone wire algorithm is adapted from [Space Walker's Alternate Current](https://github.com/SpaceWalkerRS/alternate-current) (MIT). Full attribution in [LICENSES.md](LICENSES.md). The port targets 26.1.2 mojmap and installs transparently as a `DefaultRedstoneController` subclass; the design and algorithm remain entirely Space Walker's.
- The JNI / native-loading scaffolding was originally forked from [Brayan-724/rust-mod-probe](https://github.com/Brayan-724/rust-mod-probe), the proof of concept that demonstrated calling Rust from Fabric.

---

## License

MIT
