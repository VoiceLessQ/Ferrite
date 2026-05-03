# Ferrite

Fabric performance mod for Minecraft 1.21.11. Java mixins + native Rust via JNI for the hot paths that matter.

## What ships default-on

| System | What it does | Measured win |
|---|---|---|
| Cramming | Rust spatial hash replaces O(N²) mob push loop | ~65% entity tick reduction at 1000+ mobs |
| Chunkgen baseline | `@Invoker` cleanup on surface phase | ~3 ms/chunk universal |
| Sign ticker | Suppress idle sign ticks | ~70% BE-tick reduction at 961 signs |
| Furnace ticker | Suppress idle furnace ticks | ~88% BE-tick reduction at 500 idle furnaces |
| Hopper extract hint | Skip empty leading slots on extract | 23-110 µs/call savings |

Strict-class checks on the ticker suppressions preserve mod subclass behavior.

## Big opt-in: Alternate Current redstone

`/ferrite redstone ac on`

Ferrite's biggest single algorithmic win. Adapts [Space Walker's Alternate Current](https://github.com/SpaceWalkerRS/alternate-current) (MIT) into a yarn-remapped port for 1.21.11, layered with a Rust BFS kernel that runs each cascade's power propagation in one batched JNI call.

| metric | vanilla default | Ferrite (AC + Rust BFS) | change |
|---|---|---|---|
| wire cascades / tick | ~127,000 | ~8,250 | **~15× fewer** |
| wire cost / gate tick | ~0.378 ms | ~0.062 ms | **~84% less** |
| effective TPS (CPU-bound) | ~4 | ~5.6 | **+40%** |
| oracle mismatches | — | 0 / 149,669 checked | bit-for-bit correct |

Two user-visible effects: contraptions animate **~6× faster at equivalent server load** (each cascade collapses into one settle, so the same per-tick budget processes more gate ticks), and on CPU-bound hardware **server TPS climbs ~40%** (wire savings convert directly into more completed ticks per second). Per-cascade Rust BFS adds another **~30% wire-cost reduction** once AC is on; disable with `/ferrite redstone bfs off` if a specific contraption regresses.

**Default-off because AC intentionally skips intermediate power-level updates** for efficiency. Contraptions relying on quasi-connectivity, 0-tick pulses, or instawire should leave it off. Gate tick speeds (repeaters, comparators, observers, torches) are vanilla-identical.

## Other opt-ins

| Command | What it does |
|---|---|
| `/ferrite hopper highway on` | Per-slot independent cooldowns + round-robin destination routing (~3× chain throughput on hopper-heavy storage) |
| `/ferrite surface dispatch on` | Rust surface evaluator (experimental, currently regresses vs vanilla baseline; useful for A/B measurement) |

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.18.4+
- Fabric API
- Server-side compatible (works without client install)

## Quick install

Download the latest release from [Modrinth](https://modrinth.com/mod/ferrite) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/ferrite). Drop the `.jar` into your `mods/` folder. Everything default-on ships immediately. Run `/ferrite cramming status` in-game to confirm it loaded.

Native libraries for Windows / Linux / macOS are bundled. If the native fails to load, Ferrite falls back to vanilla behavior automatically, no crashes.

## Full documentation

See [docs/GUIDE.md](docs/GUIDE.md) for full measured-results tables, complete command reference, how each system works, what's still in progress, and platform notes.

For the cross-port retrospective on what worked and what didn't, see [docs/JOURNEY.md](docs/JOURNEY.md).

## Credits

- Redstone algorithm adapted from [Space Walker's Alternate Current](https://github.com/SpaceWalkerRS/alternate-current) (MIT). The port is yarn-remapped for 1.21.11 and installed transparently as a `DefaultRedstoneController` subclass; design and algorithm remain entirely Space Walker's. Full attribution in [LICENSES.md](LICENSES.md).
- JNI / native-loading scaffolding originally forked from [Brayan-724/rust-mod-probe](https://github.com/Brayan-724/rust-mod-probe).

## License

MIT
