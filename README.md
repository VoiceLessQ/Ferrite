
## Ferrite

**What you get:** A Rust-powered mod that improves Minecraft's chunk generation pipeline and tells you exactly what it's doing. Chunk processing is faster, and every 5 seconds Ferrite logs where your game is spending time so you can see the difference.

Future versions will push the optimization further. Right now we need low-end hardware logs to confirm the improvements land where they matter most.

---

We built this because Minecraft's chunk generation is slow on low-end hardware. So we measured it, then started fixing it.

Using a Rust native library hooked into Minecraft via JNI, we identified that `sampleBlockState` — the per-block terrain decision loop — costs ~13ms per chunk and makes up 70% of chunk gen time on a 4-core machine.

We built a bulk handoff pipeline: Java hands Rust a buffer of terrain data, Rust processes all 98,304 blocks in one parallel pass using Rayon, and hands the result back in a single JNI call. No per-block overhead. Measured result: **7× faster** than vanilla Java on equivalent compute work.

The remaining blocker is getting vanilla's internal density data into that pipeline cleanly — solving that without rewriting Minecraft's noise pipeline is the current focus.

## How to help

If you have integrated graphics or 4 cores or less:

1. Install Ferrite + Fabric API
2. Play normally for 10 minutes, especially walking into unloaded terrain
3. Open `.minecraft/logs/latest.log`
4. Search for `[ferrite]` and paste those lines in the comments

That data drives the next version.

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.18.4+
- Fabric API
- **Windows 64-bit only** (Mac/Linux planned)

## License

MIT
