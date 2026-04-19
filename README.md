# Ferrite

A Fabric performance research mod for Minecraft 1.21.11.

Ferrite uses a Rust native library (via JNI) to profile and accelerate
Minecraft's chunk generation and entity rendering pipelines. The current
release is instrumentation-only — it measures where time goes and logs
it to console. Future versions will use that data to ship targeted
optimizations.

## Why

Low-end hardware users (4-core CPUs, integrated graphics, 8GB RAM)
experience chunk loading stutters and entity lag that high-end machines
never see. Ferrite collects real performance data from real hardware so we
can fix the right things.

## What it does right now

- Logs chunk generation phase costs every 5 seconds during active play
- Logs client FPS, entity count, and chunk count every 5 seconds
- Logs entity render cost per frame (sampled)
- All logging uses the `[ferrite]` prefix — searchable in your log file

## What the logs look like

```
[ferrite] [chunkgen] noise-sync: n=47 avg=8.3ms max=23.1ms  surface: n=47 avg=1.4ms max=5.2ms
[ferrite] [client-lag] fps avg=42 min=28 max=60 [WARN]  entities=312  chunks=380  samples=100
[ferrite] [entity-render] calls=18000 sampled=180 avg=210ns  frame≈6.5ms
```

## Platform support

Windows only (64-bit). The Rust native library is compiled for
x86_64-pc-windows-gnu. Mac and Linux support is planned for a future
release.

## How to help

If you have low-end hardware (integrated graphics, 4 cores or fewer,
8GB RAM or less):

1. Install the mod
2. Play normally for 10–15 minutes, especially in areas with many
   entities or active chunk loading
3. Find your `latest.log` file
4. Search for `[ferrite]` lines and paste them in a CurseForge comment
   or open a GitHub issue

That data directly drives what gets optimized next.

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.18.4+
- Fabric API

## Building from source

Requires Rust (nightly-2025-08-29), MSYS2 with mingw-w64-x86_64-gcc,
and JDK 21.

```
./gradlew build
```

The build process compiles the Rust library and bundles it into the jar
automatically.

## License

MIT — see [LICENSE](LICENSE).
