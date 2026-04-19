# Rusty

A Fabric performance research mod for Minecraft 1.21.11. **Alpha — Windows 64-bit only.**

---

## What it is

Rusty is a diagnostic mod. It uses a Rust native library (via JNI) to measure where Minecraft spends time during chunk generation and entity rendering, and writes that data to your log file.

It does **not** change worldgen, entities, blocks, biomes, or anything visible in-game. The current release is instrumentation only. Future versions will use the collected data to ship targeted optimizations — which specific optimizations get built depends on what the real-user logs show.

## What you actually see

Nothing visual. No new items, no new structures, no particle effects, no menu screens.

Every 5 seconds during active play, Rusty appends lines to `.minecraft/logs/latest.log` prefixed with `[rusty]`. Example:

```
[rusty] [chunkgen] noise-sync: n=47 avg=8.3ms max=23.1ms  surface: n=47 avg=1.4ms max=5.2ms
[rusty] [client-lag] fps avg=42 min=28 max=60 [WARN]  entities=312  chunks=380  samples=100
[rusty] [entity-render] calls=18000 sampled=180 avg=210ns  frame≈6.5ms  low-end≈11ms (est)
```

That's the entire player-facing output.

## Why install it

If you have low-end hardware (4-core CPU or fewer, integrated graphics, 8 GB RAM or less), your log data is directly useful for deciding what gets optimized next. High-end machines generate data too, but the bottlenecks we're trying to study don't show up there.

**Install if:** you regularly see chunk-loading stutters, FPS drops near mobs or villages, or the "loading terrain" moment drags past a few seconds.

**Don't bother if:** the game runs smoothly for you. The mod won't hurt anything, but your logs won't tell us what we need.

## What the logs mean

Each `[rusty]` line reports a specific metric averaged over the last 5 seconds.

- **`[chunkgen]`** — how long chunk generation's noise and surface phases take, in milliseconds per chunk.
- **`[client-lag]`** — current FPS (avg, min, max), number of loaded entities, number of loaded chunks. Tagged `[OK]`, `[WARN]`, or `[LAG]` based on average FPS (60+/30+/<30).
- **`[entity-render]`** — average nanoseconds per entity render call, plus estimated per-frame cost on your hardware and on an Intel HD 620 reference.
- **`[rust-engine]`** — fires once at startup, reports the Rust worker pool size for your CPU.

Every number is measured. The only estimate is the "low-end" figure, labeled as such.

## How to submit feedback

If you see consistent `[LAG]` or `[WARN]` tags, or numbers that look interesting:

1. Open `.minecraft/logs/latest.log`
2. Search for `[rusty]`
3. Paste a chunk of representative lines into a CurseForge comment on this page, or open an issue on the GitHub repo (linked in the project sidebar)
4. Include rough specs: CPU model, GPU (integrated or discrete + model), RAM

That's all the data we need. No personal info, no telemetry sent automatically — everything is in your log file and you choose what to share.

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.18.4 or newer
- Fabric API
- **Windows 64-bit**

## Known limitations

- **Windows only.** The Rust native library is compiled for `x86_64-pc-windows-gnu`. On Mac or Linux the mod will load cleanly but Rust-side features stay disabled (you'll see a message in the log). Cross-platform builds are planned.
- **No gameplay changes.** This is intentional. The mod is there to measure, not to modify. Future releases may add optimizations.
- **Alpha state.** First public release. Load order conflicts with other mods are possible. Logs may spam harder than expected during very busy sessions. Please report anything that looks broken.
- **Console output is chatty.** The mod writes up to ~12 lines per minute during active play. If that's a problem, a logging toggle is on the roadmap.

## License

MIT. Source and issues at the GitHub repo (see sidebar).
