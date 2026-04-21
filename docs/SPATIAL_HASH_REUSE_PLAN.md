# Spatial hash reuse — research plan

Audit performed at the end of the 0.3.0-alpha cycle to scope whether
Ferrite's cramming spatial hash could be extended to cover other
entity-scan hot paths.

**Headline finding:** zero in-Ferrite call sites would benefit. The
cramming mixin already cancels the per-mob `level.getEntities(bbox)`
that would otherwise dominate. Any further win has to come from
redirecting **vanilla** call sites through the hash via new mixins,
which is a deliberate design exercise — not a refactor.

This document captures the audit so the next session starts with the
shape of the problem already mapped.

---

## What the cramming hash currently covers

- `CrammingDispatcher` (`src/main/java/me/apika/apikaprobe/CrammingDispatcher.java`)
- Built **once per server tick**, on the first `LivingEntity.tickCramming`
  call observed that tick. All subsequent `tickCramming` calls that tick
  are cancelled.
- Source set: `world.iterateEntities()` filtered to `MobEntity` (or the
  cramming-relevant subset — see dispatcher for exact predicate).
- Structure: **2D** spatial hash, **2-block cells** (matches vanilla's
  cramming push radius). Built in Java, packed into a direct ByteBuffer,
  handed to Rust via one JNI call.
- Lifetime: discarded at the end of the tick. No reuse across ticks; no
  invalidation problem.
- Cost: one O(N) iterate + one O(N) bucket insert per tick. Currently
  amortised by being the only world-wide entity scan in the per-tick
  path.

---

## Vanilla call sites to investigate

Yarn 1.21.11 names confirmed in the audit:

```
getOtherEntities       (Entity, Box, Predicate)        -> List
getEntitiesByClass     (Class, Box, Predicate)         -> List
getNonSpectatingEntities (Class, Box)                  -> List
iterateEntities        ()                              -> Iterable
```

All declared on `EntityView` (`class_1924`), inherited by `World` and
`ServerWorld`.

Candidate scans, ranked by intuition (needs profiler confirmation):

| Candidate | Entity type | Frequency (est.) | Read/mutate | Hash-eligible? |
|---|---|---|---|---|
| `ItemEntity.tryMerge` neighbour scan | `ItemEntity` | per-item-tick (very hot in farms) | mutates (merges stacks) | yes — separate 2D hash, item-only |
| `ExperienceOrbEntity` clump scan | `ExperienceOrbEntity` | per-orb-tick (bursts after farms kill) | mutates (merges value) | yes — separate hash, orb-only |
| `MobEntity.checkDespawn` mob-cap count | `MobEntity` | per-mob, every ~20 ticks | read-only | **reuse cramming hash** (same source set, same cell size works) |
| AI target scan (`Brain` / `LookTargetUtil` / `EntitySelector`) | various living | per-mob-tick for some AI types | read-only | partially — many use small Boxes that already chunk-prune well |
| `Entity.tickInVoid` / pushable neighbour lookup | `Entity` (broad) | per-entity-tick for pushable types | mutates (apply push) | yes — but entity set is broad; new hash needed |
| Boat/minecart collision scans | broad | per-vehicle-tick | mutates | low priority — small N |

### Read-only vs mutating

Read-only scans are trivially redirectable: build the hash once, hand
out lookups against it. Mutating scans are trickier because the entity
the scan returns may be deleted/merged/teleported during the same tick,
which would invalidate hash entries the rest of the tick still depends
on. For the ItemEntity case specifically, vanilla merges happen inside
the iteration, so the hash would need to either (a) be rebuilt after
each merge, or (b) treat merge as a tombstone and skip stale entries.

---

## What a second hash would look like

If `ItemEntity` + `ExperienceOrbEntity` turn out to dominate (likely on
mob-farm-heavy servers), the cleanest fit is a **separate** hash:

- Same 2D structure, same 2-block cell size (or maybe 1-block for items
  — they merge at distance ≤ 0.5).
- Built once per tick at the same dispatch point as the cramming hash.
- Source: `world.iterateEntities()` filtered to `ItemEntity | ExperienceOrbEntity`.
- Stays in Java — no JNI. Item merges aren't expensive enough per pair
  to justify crossing the boundary; the win is purely the O(1) lookup
  replacing O(N) per-item scans.

**Cost to budget:** rough back-of-envelope is one extra
`world.iterateEntities()` pass + N inserts. On the cramming benchmark
load (1000+ mobs) this added ~0.1ms; for items the typical N is much
smaller except in farms where it's the whole point. Should stay well
under 0.5ms/tick worst case.

A **single shared hash over all entities** is *not* attractive — the
cell size that's right for cramming (2 blocks) is wrong for item merge
(0.5 blocks), and walking the wrong cell size costs more than just
maintaining two hashes.

### Reuse vs new hash decision matrix

- `MobEntity` scans (mob-cap, AI targeting): **reuse cramming hash**.
- `ItemEntity` + `XP orb`: **new hash**, item-tuned cells.
- Broad `Entity` scans: **case-by-case**; usually not worth a third hash.

---

## Open questions before next session

1. **Which vanilla scans actually dominate?** The candidate list above
   is intuition. Need a profiler run on a mob-farm world (lots of items,
   lots of XP orbs, mob cap pressure) with the existing
   `[movement-internals]` / `[entity-tick]` monitors to see which call
   path the time is actually in. Pivot scope based on data, not guess.
2. **Per-tick cost of building a second hash.** Measure on the same
   4-core baseline as cramming. If a second hash costs more than the
   scans it would replace on a typical world, the whole pivot is moot.
3. **Mutation safety for `ItemEntity.tryMerge`.** Decide between
   tombstone-on-delete vs rebuild-after-merge before writing any code.
   Probably tombstone — merges are point-deletions, not mass mutations,
   and a stale entry just costs one wasted lookup.
4. **Mixin target choice.** `@Redirect` on `getEntitiesByClass` /
   `getOtherEntities` is the obvious shape, but may collide with other
   mods doing the same redirect. Consider `@Inject(HEAD, cancellable)`
   with a fallback to vanilla on hash miss instead — slower per call,
   safer in mixed-mod environments.
5. **Does the `MobEntity` mob-cap scan still happen if cramming is
   active?** It's a separate vanilla call path from `tickCramming` so
   probably yes, but worth confirming before claiming it as a win — if
   it only fires every ~20 ticks the win is tiny.

---

## Out of scope for now

- AI pathfinder spatial queries — these use targeted small Boxes that
  vanilla's `EntityManager` chunk-prunes effectively. Win is unclear
  without profiler data.
- Boat/minecart collision scans — N is too small to matter.
- Anything that requires invalidating the hash mid-tick — defer until
  the read-only cases are shipped and measured.
