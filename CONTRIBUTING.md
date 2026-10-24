# Contributing to Ferrite

You do not need Rust. Mixins, commands, logging, config and the build make up about two thirds of the code, all of it Java, and the Rust side only holds the hot loops. If a change does reach into Rust, say so in the issue or PR and I will take that part.

## Ways to help

**Test it.** Run Ferrite on your own hardware, a mob farm or a busy server for ten minutes, then search `latest.log` for `[ferrite]` and share those lines in an issue or in [Discussions](https://github.com/VoiceLessQ/Ferrite/discussions). Low-end machines are what I hear from least.

**Report a compatibility problem.** Ferrite and another mod not getting along is worth a bug report with both mod versions and the log. Finding the two mixins that collide, as in #19, saves a lot of time. Nobody expects it.

**Report an oracle mismatch.** Several features compare their result against vanilla while you play, and a log line containing `MISMATCH` means the fast path and vanilla disagreed. Use the "Oracle mismatch" issue form. Those reports decide whether a feature stays on by default.

**Change Java code.** Mixins, commands, config, logging. Look for issues labeled `good first issue`.

**Build and CI.** New platforms and launcher setups count too; Linux aarch64 support arrived that way, in #8.

## Building

You need JDK 25, [rustup](https://rustup.rs/) and a C linker: system GCC on Linux, Xcode tools on macOS, mingw-w64 on Windows ([docs/SETUP_MINGW.md](docs/SETUP_MINGW.md)). Rustup reads `rust-toolchain.toml` and installs the pinned toolchain on the first build.

```
./gradlew build
```

The jar lands in `build/libs/` with the native library for your platform. `./gradlew runClient` starts the game with your change.

Java-only work can skip Rust completely:

```
./gradlew build -x buildRustLib -x copyRustDll
```

That jar carries no native library. Ferrite notices at startup, logs that native support is off and sends every Rust-backed feature back to vanilla, so the Java side runs and can be tested as usual.

Touched Rust? Run the CI checks from the repo root before opening the PR:

```
cargo test --release
cargo clippy --release
```

## Rules for changes

Ferrite does not change gameplay. Each optimization has to give exactly the result vanilla would, so a new one goes through three steps:

- It starts off by default, behind a command toggle or a `-Dferrite.*` property.
- It gets an oracle, a check against vanilla, before it can turn on by default.
- Once on by default, it keeps a kill switch.

Open PRs against `26.3.x`. That branch tracks the current Minecraft version, and the older ones get fixes only on request. One change per PR, please. A performance change should say what was measured: ms per tick, how many mobs or chunks, and on which hardware.

## Questions

Ask in [Discussions](https://github.com/VoiceLessQ/Ferrite/discussions). Security problems go through [SECURITY.md](SECURITY.md) instead of a public issue.
