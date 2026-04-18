# High-Performance Worldgen Architecture

> Design notes for building a "no-lag" worldgen mod on top of rust-mod-probe
> (a proof-of-concept by Brayan-724). Targets Fabric to stay lightweight while
> using Rust for the heavy math.

## Phase 1: Preparation

Dual-compiler environment:

- **Java 21 JDK** — required for modern Fabric (1.20.6+). Ensure `JAVA_HOME` is set.
- **Rust toolchain** — install via `rustup.rs`.
- **Cross-compilation targets**:
  - `rustup target add x86_64-pc-windows-msvc` (Windows)
  - `rustup target add x86_64-unknown-linux-gnu` (Linux)
  - `rustup target add aarch64-apple-darwin` (Mac M1/M2/M3)
- Clone `Brayan-724/rust-mod-probe`.

## Phase 2: Architecture

Two halves:

### Rust "engine" (`/rust/src/lib.rs`)

Use the `jni` crate (or Project Panama) to expose functions. Use the `bind!` macro provided by the repository for ergonomics.

Essential crates:

- `rayon` — 2–6 thread worldgen pool
- `sysinfo` / `num_cpus` — auto-detect CPU cores
- `noise` or `bracket-noise` — terrain algorithms

### Java "bridge" (`/src/main/java/...`)

Keep it thin. Its only jobs:

1. Identify the user's OS.
2. Load the correct `.dll`, `.so`, or `.dylib`.
3. Call Rust functions and pass a **direct ByteBuffer**.

## Phase 3: Implementation

### Step 1: Hardware detection (auto-scaling)

Don't hardcode thread counts. Detect at `onInitialize`:

```rust
pub fn init_engine() {
    let cores = num_cpus::get();
    let thread_count = match cores {
        0..=4 => 2,
        5..=8 => 4,
        _     => 6,
    };
    rayon::ThreadPoolBuilder::new()
        .num_threads(thread_count)
        .build_global()
        .unwrap();
}
```

### Step 2: Bulk handoff (zero-copy)

Never pass blocks one by one. Allocate a native buffer in Java, send its address to Rust, let Rust fill it, Java reads it.

- **Java:** `ByteBuffer.allocateDirect(16 * 16 * 384)`
- **Rust:** receives the pointer to that buffer.
- **Threading:** `par_iter()` from Rayon — calculate noise for all 98,304 blocks in the chunk across the thread pool.
- **Completion:** Rust returns. Data is already in Minecraft's memory space — no copy.

### Step 3: TPS-awareness (throttle)

Pass current MSPT (milliseconds per tick) from Fabric to Rust.

- If `MSPT > 40`, tell the Rust worker threads to sleep/yield for ~5ms.
- Keeps FPS high while the world generates in the background.

## Phase 4: Packaging

Users shouldn't have to install Rust. Embed the binaries.

1. Build all targets: `cargo build --release` for Windows, Mac, Linux.
2. Place outputs in Fabric mod resources:
   - `resources/natives/windows/engine.dll`
   - `resources/natives/linux/libengine.so`
   - `resources/natives/macos/libengine.dylib`
3. Write a small Java loader: extract the right file to `.minecraft/temp` and call `System.load()`.

## Later additions

- **Project Panama** — switch from JNI to the Foreign Function & Memory API (Java 21) to reduce bridge tax further.
- **SIMD intrinsics** — use `std::arch` in the noise code so each thread calculates 4 blocks at a time with AVX2.

## Checklist

- [ ] Foundation: `rust-mod-probe` for JNI/Gradle boilerplate
- [ ] Logic: 100% of terrain math in Rust
- [ ] Threading: Rayon for parallel chunk generation
- [ ] Interface: `DirectByteBuffer` for zero-copy data movement
- [ ] Packaging: cross-compiled, embedded natives for one-click install
