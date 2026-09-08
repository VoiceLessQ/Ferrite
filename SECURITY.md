# Security

Ferrite is a Fabric mod that bundles a native library written in Rust.
That is unusual for a Minecraft mod, so this page explains what the
native does, what it does not do, and how to check the jar yourself.

## What the native library does

The jar contains one prebuilt library per platform under
`assets/ferrite/natives/` (`rust_mod.dll`, `librust_mod.so`,
`librust_mod.dylib`). At startup `RustBridge.java` copies the one
matching your OS to a temporary file and loads it with `System.load`.
The temp file is marked for deletion on exit. If no native exists for
your platform, the mod logs that and runs without it.

Everything the native does is compute. Noise, biome lookup, density
functions, entity cramming, redstone graph search: numbers in, numbers
out. The Rust code under `rust/` has no network, file, or process
access, and a search for `std::net`, `std::fs`, `std::process`, and
`Command::new` across the Rust sources returns nothing at all. The Java
side makes no network calls either.

Direct dependencies of the native are `jni`, `serde`, `bitflags`,
`rayon`, `md-5`, and `fxhash`, plus the crates in this repo. The full
locked tree is in `Cargo.lock`.

## How the jar is built

Every release jar is assembled by GitHub Actions from the tagged
commit, and the natives inside it are compiled on GitHub-hosted runners
by the `build.yml` workflow, one job per platform. Nothing is built on
a developer machine and uploaded by hand. Nothing.

The Rust toolchain is pinned in `rust-toolchain.toml` and every crate
version in `Cargo.lock`. Builds are not byte-for-byte reproducible
across runner images, so a locally built native may differ from the
released one while doing the same thing.

## Checking it yourself

Every GitHub Release carries a `SHA256SUMS` file and a signed build
provenance attestation. With the GitHub CLI installed:

```
gh attestation verify ferrite-<version>.jar --owner VoiceLessQ
```

A pass means that exact jar was produced by `build.yml` in this
repository at the tagged commit. Nothing hand-built passes.

Build from source and compare behaviour:

```
cargo build --release --manifest-path=rust/mod/Cargo.toml
./gradlew build
```

Or inspect a release jar: unzip it, and `strings` or a disassembler on
the native will show JNI symbols and math, nothing that resolves
hostnames or opens sockets.

Modrinth records a SHA-512 hash for each uploaded file. Compare it
against the
GitHub Release asset if you got the jar from somewhere else.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting on this repository
(Security tab, "Report a vulnerability"). Do not open a public issue
for anything exploitable. You will get a reply within a week; a fix
lands in the newest supported Minecraft lane first.

## Supported versions

Only the latest release on each Minecraft version lane receives fixes.
Older releases on the same lane are not patched.
