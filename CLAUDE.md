# Ferrite: Claude session memory

Persistent preferences and conventions for this project. Read this at the
start of every session so we don't re-learn the same lessons.

## Git authorship

**Always commit as VoiceLessQ, not as Claude.** The sandbox's local git
config defaults to `Claude <noreply@anthropic.com>`, which is wrong for
this project. Use the `--author` flag on every commit:

```
git commit --author="VoiceLessQ <t12kaem@gmail.com>" -m "..."
```

Do not modify git config (system instructions forbid it). Use the flag.

If a commit accidentally lands under "Claude", do not force-push to fix
it without explicit confirmation. Force-pushing main is destructive and
the existing two-commit cosmetic miss is not worth that risk.

## Doc style

**No em dashes in any doc, commit message, or PR description.** The
user explicitly flagged this as AI-ish. Use commas, colons, semicolons,
parentheses, or regular hyphens with spaces instead. The existing docs
have legacy em dashes from earlier sessions; do not rewrite those just
to remove dashes, but never add new ones.

Other voice notes:
- Direct about mistakes ("I was wrong" beats hedging).
- Sentence length varied; one-line paragraphs are fine when they land.
- Grounded in specifics: file paths, line numbers, commit hashes,
  measured numbers.
- Don't write multi-paragraph docstrings or multi-line comment blocks.
  One short comment line max.

## Project worldview

Ferrite plays piano inside vanilla's orchestra. It does not replace
vanilla. Each Rust port is one instrument played faster, slotting into
vanilla's existing flow at a clean boundary. Read `JOURNEY.md` first.
The architectural philosophy doc is `docs/SEED_DRIVEN_DISPATCH.md`.

## Worldgen reference discipline

Single oracle for worldgen ports: Mojang's `26.1.2/server/` source
(unobfuscated, on the user's local machine).

**Do not cross-reference other Rust Minecraft projects** (Pumpkin,
Valence, FerrumC, etc.) when porting. They solve a different problem
(replace vanilla rather than accelerate it) and may be pre-parity or
approximate. Anchoring our correctness judgments to their code muddies
the clean-room story. Captured in JOURNEY.md "Things not to
re-investigate" but worth re-reading when the temptation comes up.

## Measurement discipline

Before accepting "the gate says stop," ask "did we measure the right
thing?" The redstone Phase 2 arc would have stopped at a false negative
without the activation counter. The five questions before any new port
live in `JOURNEY.md`; read them before scoping.

## Live state, last refreshed 2026-05-02

**The 26.1.x port is on its own branch.** Anyone reading "let's bump
to 26.1.2" or "port the worldgen to mojmap" as a next move is reading
stale context. The 26.1.x branch is at parity, ahead of main by ~36
commits, and has bit-exact noise + biome + density carry-over (see
`docs/JOURNEY.md` "26.1.x port: parity carry-over").

**Track B is shipped, not pending.** The full noise + climate +
density stack is in:

- `rust/mod/src/worldgen_state.rs` (seed-derived state, `OnceLock`)
- `rust/mod/src/perlin.rs` (`ImprovedNoise`, `PerlinNoise`, `NormalNoise` aka yarn `DoublePerlinNoiseSampler`, plus `BlendedNoise` and `SimplexNoise` for end-island)
- `rust/mod/src/climate.rs` (R-tree biome lookup, 1000/1000 sample tests)
- `rust/mod/src/density.rs` (50/50 bit-exact on 26.1.x as of 2026-05-02; 41/42 on 1.21.11 main)
- `rust/mod/src/aquifer.rs` (99.895% parity, default-off, see below)
- `rust/mod/src/xoroshiro.rs` (Xoroshiro128PlusPlus, XoroshiroPositionalRandomFactory, plus `LegacyRandomSource` for end-island seeding)
- `rust/mod/src/worldgen_jni.rs` (seed handoff, registration entries)
- `WorldgenStateBootstrap.java` (NoiseConfig population at world load; exposes `capturedWorldSeed()` for the validator)
- `NoiseConfigCaptureMixin` (live-instance handle for parity validator)

**Current shipping state:** v0.5.1-alpha. Cramming, AC redstone, and
Rust BFS ship default-on as before. A new chunkgen baseline win
(`@Invoker`/`@Accessor` on `MaterialRuleContext` + diagnostic gating
of `CacheRouteCaptureMixin` and `AquiferMonitor`) saves ~3 ms/chunk on
surface phase plus ~8-10 ms/chunk on noise-sync, both default-on. See
`docs/PIANO_STATUS.md` for the JFR-driven session that surfaced these.

**Open with structural floor (not actively blocked, just hard):**

- Surface rule dispatcher: 13.4 ms ON vs 6.4 ms vanilla (~7 ms gap),
  default-off. Closing it requires bypassing vanilla's chunk API,
  which conflicts with the mod-compatibility scope. Documented in
  `docs/PIANO_STATUS.md`.
- Aquifer Rust port: 99.895% parity, visible surface-grid artifacts
  at chunk boundaries. Default-off indefinitely. See
  `docs/AQUIFER_PORT.md`.

**Closed threads (do not re-open without a fundamentally new approach):**

- **Bulk-chunk-density.** Vanilla's per-cell DF cost is ~20 ns
  amortized via `Marker(CacheOnce, X)` + C2 inlining. Our Rust DF
  interpreter is ~5 ns/cell BUT pays a ~50-56 ms JNI fill, net
  +25-50 ms regression. The Rayon-strip hypothesis was falsified
  (made it worse, then catastrophic to 1000-3000 ms under sustained
  flight). Closing this gap requires a Rust DF *compiler*, not an
  interpreter. Multi-week investment with diminishing returns.
  Infrastructure stays in tree, default-off.
- **Physics dispatcher.** Same JIT wall reproduced at 1000-mob scale.
  Parity-validated, ENABLED stays false.

**Forward-looking targets** (unmeasured, in
`docs/FUTURE_PLANS.md`): fluid ticks, villager Brain, mob spawning
candidate sampling, hopper item-entity scans, chunk save/serialize.
None of these are picked yet. The four checks (vanilla actually the
bottleneck, pure-math slice, >2x after JNI, oracle-testable) gate
each one.

## Lesson the JIT wall taught us (durable)

Two threads closed in 2026-04-28 by the same finding: **don't propose
porting subsystems vanilla has already JIT-optimized to within an inch
of their life.** Density functions and entity collision both showed
the same pattern: vanilla's HotSpot inlining + structural memoization
(`CacheOnce` for DF, type-stability for collisions) yields per-cell or
per-call costs in the low nanoseconds. Rust's compute is faster, but
the JNI handoff plus our lack of equivalent memoization gives back
more than the compute saves.

This adds a sixth implicit port-discipline question to the five in
JOURNEY.md: **has HotSpot already won this fight?** If vanilla's
steady-state cost is <50 ns per call after warmup, the port is dead
on arrival. Profile *steady-state* cost, not first-call cost; JIT
warmup masks this if you measure cold.

## Branch and push conventions

Default development happens on `main` for doc-only changes (matches
the user's pattern). Code changes that touch the runtime should go on
a feature branch and PR, matching how the surface dispatcher arc landed
via PR #4.

Never push to `main` without confirmation. Never force-push to `main`
under any circumstance unless explicitly asked.

The `26.1.x` branch carries the port to MC 26.1.2 (mojmap, JDK 25,
architectury-loom). It runs ahead of main; main stays on 1.21.11.
Until that branch is merged or shipped, do not "modernize" main to
mojmap or update its gradle.properties / fabric.mod.json to 26.1.x
versions. Doc-style additions on main are fine; runtime fixes on
26.1.x should land there.

## runClient defaults

Plain `./gradlew runClient` boots to the **title screen**. This is the
right default: you do not always know which world you want, and a fresh
boot is the safe starting point.

The autovalidate flow is opt-in via a gradle property:

```
./gradlew runClient -Pferrite.autovalidate=2000
```

When that property is set, `build.gradle` injects two args into the run:

- `-Dferrite.autovalidate=<n>` (vmArg) which `WorldgenStateBootstrap`
  reads and runs the noise + biome + density parity validators at
  sample count `<n>` after worldgen state finalize.
- `--quickPlaySingleplayer "<world-name>"` (programArg) which is a
  Mojang launch arg that skips the title screen, "Singleplayer" menu,
  and world list, loading the named world directly.

World defaults to `"New World"`. Override with
`-Pferrite.world="My Test World"`. The folder must exist under
`run/saves/`; if it doesn't, MC silently falls back to title screen.

Use the autovalidate flow when you want a headless validator dump in
under a minute. Use plain runClient when you want to actually click
through MC, build something, or test a specific scenario.
