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

## Live state at start of next session

The current blocker is `PerlinNoiseSampler` port (the first piece of
Track B's noise stack). Foundation `xoroshiro.rs` is in. Surface
dispatcher works at 2.5x vanilla, gated off, waiting on Track B. See
`docs/SURFACE_RULE_STATUS.md` and `docs/VANILLA_WORLDGEN_REFERENCE.md`
for the dependency-ordered port list and per-class effort estimates.

The next concrete commit is the `WorldgenState` skeleton plus the
`initWorldgenState(seed)` JNI entry, before any noise code. That gives
PerlinNoise a home to slot into.

## Branch and push conventions

Default development happens on `main` for doc-only changes (matches
the user's pattern). Code changes that touch the runtime should go on
a feature branch and PR, matching how the surface dispatcher arc landed
via PR #4.

Never push to `main` without confirmation. Never force-push to `main`
under any circumstance unless explicitly asked.
