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

## What's opt-in

| Command | What it does |
|---|---|
| `/ferrite redstone ac on` | Alternate Current wire algorithm (~15× fewer cascades, contraptions ~6× faster at equivalent load) |
| `/ferrite hopper highway on` | Per-slot independent cooldowns + round-robin destination routing (~3× chain throughput) |
| `/ferrite surface dispatch on` | Rust surface evaluator (experimental, currently regresses vs vanilla baseline) |

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.18.4+
- Fabric API
- Server-side compatible (works without client install)

## Quick install

Download the latest release from [Modrinth](https://modrinth.com/mod/ferrite) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/ferrite). Drop the `.jar` into your `mods/` folder. Everything default-on ships immediately. Run `/ferrite cramming status` in-game to confirm it loaded.

Native libraries for Windows / Linux / macOS are bundled. If the native fails to load, Ferrite falls back to vanilla behavior automatically — no crashes.

## Full documentation

See [docs/GUIDE.md](docs/GUIDE.md) for measured results, full command reference, how each system works, what's still in progress, and platform notes.

For the cross-port retrospective on what worked and what didn't, see [docs/JOURNEY.md](docs/JOURNEY.md).

## Credits

- Redstone algorithm adapted from [Space Walker's Alternate Current](https://github.com/SpaceWalkerRS/alternate-current) (MIT). Full attribution in [LICENSES.md](LICENSES.md).
- JNI / native-loading scaffolding originally forked from [Brayan-724/rust-mod-probe](https://github.com/Brayan-724/rust-mod-probe).

## License

MIT
