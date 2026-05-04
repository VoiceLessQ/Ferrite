# 26.1.x merge plan

Operational doc for forward-merging `main` into `26.1.x`. Delete after the
merge lands. Written 2026-05-04 from the state captured below.

## Branch state

- Merge base: `4eb6a41` "docs: hopper highway" (2026-04-30)
- `main` tip: `7019ca6` "release: 0.6.3-alpha -- AC offer-based Rust kernel"
- `26.1.x` tip: `585567b` "docs: consolidation cycle framing, README + CHANGELOG direction block"
- 26.1.x is 50 ahead, 24 behind. Both branches have moved since the base; this
  is a real merge, not a fast-forward replay.

## What's being ported (24 commits on main)

Sorted by recommended apply order, oldest first per cluster.

### Cluster A: pure docs (5 commits, cherry-pick clean)

```
deba4af docs: JOURNEY retrospective for the May 2026 audit pass
b26dd17 docs: extend JOURNEY audit pass with ticker hygiene story
ed52169 docs: README -- add sign + furnace ticker hygiene to Shipped wins
1779735 docs: split README into short overview + docs/GUIDE.md
fa1b3dd docs: README -- promote Alternate Current redstone to its own section
```

No code touched. Apply via `git cherry-pick --author="VoiceLessQ <t12kaem@gmail.com>"`.

### Cluster B: tooling / chore (3 commits, cherry-pick clean)

```
923c763 chore: untrack CLAUDE.md, add to .gitignore
3759e2e docs: trim CURSEFORGE_DESCRIPTION for Modrinth review
6b02d7d feat(monitor): MonitorLog gate + /ferrite log monitors command
```

The monitor commit touches Java but no vanilla API surface. The `MonitorLog`
gate is internal. Apply directly.

### Cluster C: audit-pass cleanup (5 commits)

```
c934e5a cramming: correctness + perf fixes (audit batch 1)         [Java + Rust]
2554e88 docs: fix redstone default-state claims in project memory  [docs]
30e0ca1 redstone: audit batch 1 -- experimental warning + buffer   [Java + Rust]
5590afd redstone: delete RedstoneRustDispatcher dead code (#13)    [deletion]
2454e23 docs: redstone audit complete                              [docs]
```

### Cluster D: ticker hygiene (6 commits)

```
b6c5e30 sign-tick: suppress ticker for signs with no active editor
dd55d0a docs: changelog entry for sign-tick suppression
2fe6d8e release: 0.6.1-alpha
daaa404 sign+furnace: unified block entity ticker gate
a320aa4 docs: changelog + future-plans entries for furnace ticker gate
c51859a release: 0.6.2-alpha
```

### Cluster E: AC kernel (5 commits, headline release)

```
0c55ee0 cramming: project-specific cell hash mix function          [Rust]
0c1b49b redstone-ac: offer-based propagation kernel (Phase 1)      [Rust]
0aa508a redstone-ac: wire Java side to AC kernel (Phase 2)         [Java + Rust]
2be0d1e redstone-ac: pull outside-cascade offers via findPower(true) [Java]
7019ca6 release: 0.6.3-alpha -- AC offer-based Rust kernel
```

## Yarn -> mojmap rename map (verified 2026-05-04 against 26.1.2 decompiled)

Single consolidated table covering every name shift the merge will hit. Source
oracle: `26.1.2/decompiled/`. Each row was verified by reading the mojmap class
file directly.

### Class renames

| Yarn 1.21.11 | Mojmap 26.1.2 |
|---|---|
| `net.minecraft.world.chunk.WorldChunk` | `net.minecraft.world.level.chunk.LevelChunk` |
| `net.minecraft.world.World` | `net.minecraft.world.level.Level` |
| `net.minecraft.entity.LivingEntity` | `net.minecraft.world.entity.LivingEntity` |
| `net.minecraft.entity.Entity` | `net.minecraft.world.entity.Entity` |
| `net.minecraft.entity.mob.MobEntity` | `net.minecraft.world.entity.Mob` |
| `net.minecraft.block.BlockState` | `net.minecraft.world.level.block.state.BlockState` |
| `net.minecraft.block.entity.BlockEntity` | `net.minecraft.world.level.block.entity.BlockEntity` |
| `net.minecraft.block.entity.SignBlockEntity` | `net.minecraft.world.level.block.entity.SignBlockEntity` |
| `net.minecraft.block.entity.AbstractFurnaceBlockEntity` | `net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity` |
| `net.minecraft.block.entity.BlockEntityType` | `net.minecraft.world.level.block.entity.BlockEntityType` |
| `net.minecraft.block.entity.BlockEntityTicker` | `net.minecraft.world.level.block.entity.BlockEntityTicker` |
| `net.minecraft.item.ItemStack` | `net.minecraft.world.item.ItemStack` |
| `net.minecraft.util.math.BlockPos` | `net.minecraft.core.BlockPos` |
| `net.minecraft.util.math.Direction` | `net.minecraft.core.Direction` |
| `net.minecraft.block.RedstoneWireBlock` | `net.minecraft.world.level.block.RedStoneWireBlock` (capital S) |
| `net.minecraft.world.DefaultRedstoneController` | `net.minecraft.world.level.redstone.DefaultRedstoneWireEvaluator` |
| `net.minecraft.world.ExperimentalRedstoneController` | `net.minecraft.world.level.redstone.ExperimentalRedstoneWireEvaluator` |
| `net.minecraft.world.block.WireOrientation` | `net.minecraft.world.level.redstone.Orientation` |
| `net.minecraft.world.block.NeighborUpdater` | `net.minecraft.world.level.redstone.NeighborUpdater` |
| `net.minecraft.world.block.SimpleNeighborUpdater` | `net.minecraft.world.level.redstone.InstantNeighborUpdater` |

### Method / field renames (callsites and mixin descriptors)

| Yarn 1.21.11 | Mojmap 26.1.2 | Surface |
|---|---|---|
| `WorldChunk.updateTicker(BlockEntity)` | `LevelChunk.updateBlockEntityTicker(T)` (private) | mixin descriptor |
| `BlockState.getBlockEntityTicker(World, Type)` | `BlockState.getTicker(Level, Type)` | redirect target |
| `SignBlockEntity.getEditor()` returning Player | `SignBlockEntity.getPlayerWhoMayEdit()` returning UUID | callsite, null check stays equivalent |
| `SignBlockEntity.setEditor(UUID)` | `SignBlockEntity.setAllowedPlayerEditor(UUID)` | mixin descriptor |
| `AbstractFurnaceBlockEntity.cookingTimeSpent` (field) | `AbstractFurnaceBlockEntity.cookingTimer` (field) | accessor target |
| `AbstractFurnaceBlockEntity.litTimeRemaining` | unchanged | accessor target |
| `AbstractFurnaceBlockEntity.setStack(int, ItemStack)` | `AbstractFurnaceBlockEntity.setItem(int, ItemStack)` | mixin descriptor |
| `BlockEntity.getWorld()` | `BlockEntity.getLevel()` | callsite |
| `BlockEntity.getPos()` | `BlockEntity.getBlockPos()` | callsite |
| `World.getWorldChunk(BlockPos)` | `Level.getChunkAt(BlockPos)` | callsite |
| `World.isClient()` | `Level.isClientSide()` | callsite |
| `LivingEntity.tickCramming()` | `LivingEntity.pushEntities()` | mixin descriptor |
| `Entity.hasVehicle()` | `Entity.isPassenger()` | callsite |
| `Entity.getRootVehicle()` | unchanged | callsite |
| `Entity.getId()` | unchanged | callsite |
| `Orientation.getFront()` / `getUp()` | unchanged on mojmap `Orientation` | callsite |

### Cosmetic flag

`RedstoneWireBlock` becomes `RedStoneWireBlock` (capital S in middle). Easy to
miss in greps. Yarn flattens this Mojang quirk.

## Per-file change list (the harder commits)

### Ticker hygiene cluster

| File | Required change |
|---|---|
| `WorldChunkBlockEntityTickerGateMixin.java` | `@Mixin` target -> `LevelChunk`; method -> `updateBlockEntityTicker`; redirect target -> `BlockState.getTicker(Level, Type)`; `getEditor()` -> `getPlayerWhoMayEdit()`; imports |
| `SignEditorChangeMixin.java` | method target -> `setAllowedPlayerEditor`; imports |
| `AbstractFurnaceBlockEntityAccessor.java` | `@Accessor("cookingTimeSpent")` -> `@Accessor("cookingTimer")`; imports |
| `FurnaceStackChangeMixin.java` | method target -> `setItem`; ItemStack package; `getWorld()` -> `getLevel()`; `getPos()` -> `getBlockPos()`; `getWorldChunk()` -> `getChunkAt()`; `WorldChunk` -> `LevelChunk`; invoker `apikaprobe$updateBlockEntityTicker`; imports |
| `SignTickProbeMixin.java` | tick descriptor -> `Level`; `BlockPos` package; `isClient()` -> `isClientSide()`; imports |
| `WorldChunkInvoker.java` | `@Mixin` -> `LevelChunk`; `@Invoker("updateBlockEntityTicker")`; imports |

### Audit-pass cleanup cluster

| File | Required change |
|---|---|
| `CrammingMixin.java` | method target -> `pushEntities()V`; `MobEntity` -> `Mob`; LivingEntity package; imports |
| `CrammingHandoff.java` | `e.hasVehicle()` -> `e.isPassenger()`; Entity package; imports |
| `ExperimentalRedstoneControllerMixin.java` | `@Mixin` -> `ExperimentalRedstoneWireEvaluator`; class name in mixin file may want renaming for consistency; imports |
| `RedstoneRustDispatcher.java` (if present on 26.1.x) | delete |
| `RedstoneRustMixin.java` (if present on 26.1.x) | delete |

### AC kernel cluster

| File | Required change |
|---|---|
| `WireHandler.java` | `WorldChunk` removed (not used); `SimpleNeighborUpdater` -> `InstantNeighborUpdater`; `WireOrientation` -> `Orientation` (callsites unchanged, only type); imports for redstone package; `World` -> `Level`; `Direction` package |
| `FerriteRedstoneController.java` | `DefaultRedstoneController` -> `DefaultRedstoneWireEvaluator`; `WireOrientation` -> `Orientation`; `RedstoneWireBlock` -> `RedStoneWireBlock`; `World` -> `Level`; imports |
| `RedstoneOracle.java` | `WireOrientation` -> `Orientation`; `World` -> `Level`; imports |
| `DefaultRedstoneControllerMixin.java` | `@Mixin` -> `DefaultRedstoneWireEvaluator`; method target may need updating; `World` -> `Level`; `WireOrientation` -> `Orientation`; imports |
| `ExperimentalRedstoneControllerMixin.java` | already covered in audit cluster row above |
| `RedstoneOracleMixin.java` | `World` -> `Level`; `WireOrientation` -> `Orientation`; imports |
| `RedstoneWireMixin.java` | `@Mixin` -> `RedStoneWireBlock`; `World` -> `Level`; `WireOrientation` -> `Orientation`; imports |
| `FerriteWireConfig.java` | check for any vanilla type references; imports |

## Verification gates

Apply between cluster boundaries, not between every commit (build cost adds up):

1. **After Cluster A (docs)**: no build needed. Visual check the README/JOURNEY/changelog files render.
2. **After Cluster B (tooling)**: `./gradlew compileJava`.
3. **After Cluster C (audit-pass)**: `./gradlew compileJava` plus `./gradlew test` for redstone unit tests if they exist on 26.1.x.
4. **After Cluster D (ticker hygiene)**: `./gradlew compileJava`. Then `./gradlew runClient`, place a sign, type once, walk away, return: ticker should re-suppress. Place a furnace, drop fuel + input via hopper, ticker should re-fire.
5. **After Cluster E (AC kernel)**: `./gradlew compileJava` and `./gradlew buildRustLib`. Then `./gradlew runClient -Pferrite.autovalidate=2000` to confirm noise + biome + density parity holds end-to-end. Manual redstone test: build a small AC contraption (16 wires + lever + lamp), confirm correct propagation with `/ferrite redstone ac on`.

If any gate fails, the cluster's commits stay on a tracked branch (do not force-push) so we can diff against this doc to find the missed rename.

## Rollback

If the merge breaks at a cluster boundary and we want to back out:

```
git reset --hard 585567b   # back to 26.1.x's pre-merge tip
```

The destructive nature of this command means: confirm with user before running.
The 24 main commits stay on main untouched, so we can always retry with a
better understanding of what hit.

## Authorship

Every commit in this merge series is authored as VoiceLessQ. No coauthor, no
contributor. Either cherry-pick (which preserves the original author since
it's already correct on main) or apply with
`--author="VoiceLessQ <t12kaem@gmail.com>"` for any new commits this merge
introduces (typically merge commits or the rename-fix patches).

No AI references in commit messages, doc updates, or code comments produced
by this merge.
