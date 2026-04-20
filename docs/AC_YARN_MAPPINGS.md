# AC → Yarn 1.21.11 mapping reference

Scratch pad for the Alternate Current port. Every rename below was
verified against the yarn 1.21.11 sources bundled with loom
(`.gradle/loom-cache/.../minecraft-common-*-sources.jar`) during
session 1 prep — no guesses.

Delete this file once the port is complete and the mappings are
baked into the committed code.

---

## Package / class imports

| AC (Mojmap) | Yarn 1.21.11 |
| :-- | :-- |
| `net.minecraft.core.BlockPos` | `net.minecraft.util.math.BlockPos` |
| `net.minecraft.core.Direction` | `net.minecraft.util.math.Direction` |
| `net.minecraft.server.level.ServerLevel` | `net.minecraft.server.world.ServerWorld` |
| `net.minecraft.util.Mth` | `net.minecraft.util.math.MathHelper` |
| `net.minecraft.world.level.Level` | `net.minecraft.world.World` |
| `net.minecraft.world.level.block.Block` | `net.minecraft.block.Block` |
| `net.minecraft.world.level.block.Blocks` | `net.minecraft.block.Blocks` |
| `net.minecraft.world.level.block.RedStoneWireBlock` | `net.minecraft.block.RedstoneWireBlock` |
| `net.minecraft.world.level.block.state.BlockState` | `net.minecraft.block.BlockState` |
| `net.minecraft.world.level.chunk.ChunkAccess` | `net.minecraft.world.chunk.Chunk` |
| `net.minecraft.world.level.chunk.LevelChunkSection` | `net.minecraft.world.chunk.ChunkSection` |
| `net.minecraft.world.level.chunk.status.ChunkStatus` | `net.minecraft.world.chunk.ChunkStatus` |
| `net.minecraft.world.level.redstone.Redstone` | **no yarn equivalent** — inline `0` / `15` as `SIGNAL_MIN` / `SIGNAL_MAX` |

---

## Method renames

### On `BlockState` (actually `AbstractBlock.AbstractBlockState`)

| AC | Yarn |
| :-- | :-- |
| `state.is(Blocks.X)` | `state.isOf(Blocks.X)` |
| `state.isRedstoneConductor(level, pos)` | `state.isSolidBlock(world, pos)` |
| `state.isSignalSource()` | `state.emitsRedstonePower()` |
| `state.getValue(PROPERTY)` | `state.get(PROPERTY)` |
| `state.setValue(PROPERTY, v)` | `state.with(PROPERTY, v)` |
| `state.updateNeighbourShapes(level, pos, flags)` | `state.updateNeighbors(world, pos, flags)` |
| `state.updateIndirectNeighbourShapes(level, pos, flags)` | `state.prepare(world, pos, flags)` |

### On `BlockPos`

| AC | Yarn |
| :-- | :-- |
| `pos.immutable()` | `pos.toImmutable()` |

### On `World` / `ServerWorld` / `HeightLimitView`

| AC | Yarn |
| :-- | :-- |
| `level.getMinY()` | `world.getBottomY()` |
| `level.getMaxY()` | `world.getTopYInclusive()` |
| `level.getSectionIndex(y)` | `world.getSectionIndex(y)` (inherited from HeightLimitView) |
| `level.getChunk(x, z, status, create)` | `world.getChunk(x, z, status, create)` |
| `level.getChunkSource()` | `world.getChunkManager()` |
| `level.getChunkSource().blockChanged(pos)` | `world.getChunkManager().markForUpdate(pos)` |
| `level.setBlock(pos, state, flags)` | `world.setBlockState(pos, state, flags)` |

### On `Chunk` / `WorldChunk`

| AC | Yarn |
| :-- | :-- |
| `chunk.getSections()` | `chunk.getSectionArray()` |
| `chunk.markUnsaved()` | `chunk.markNeedsSaving()` |

### On `ChunkSection`

| AC | Yarn |
| :-- | :-- |
| `section.setBlockState(x, y, z, state)` | same (also exists in yarn, plus a 4-arg `(x, y, z, state, lock)` variant) |

### Static helpers

| AC | Yarn |
| :-- | :-- |
| `Block.dropResources(state, level, pos)` | `Block.dropStacks(state, world, pos)` |
| `Mth.clamp(v, min, max)` | `MathHelper.clamp(v, min, max)` |
| `Block.UPDATE_CLIENTS` | `Block.NOTIFY_LISTENERS` (both = 2) |
| `Redstone.SIGNAL_MIN` | inline `0` |
| `Redstone.SIGNAL_MAX` | inline `15` |

---

## Notes on specific AC file ports

### `LevelHelper.setWireState`

Yarn's `ChunkSection.setBlockState` and `Chunk.markNeedsSaving` are
near-exact replacements. The tricky pair is:

```
prevState.updateIndirectNeighbourShapes(level, pos, Block.UPDATE_CLIENTS);
state.updateNeighbourShapes(level, pos, Block.UPDATE_CLIENTS);
state.updateIndirectNeighbourShapes(level, pos, Block.UPDATE_CLIENTS);
```

Yarn equivalent:

```
prevState.prepare(world, pos, Block.NOTIFY_LISTENERS);
state.updateNeighbors(world, pos, Block.NOTIFY_LISTENERS);
state.prepare(world, pos, Block.NOTIFY_LISTENERS);
```

Both `updateNeighbors` and `prepare` live on `AbstractBlock.AbstractBlockState`
and are inherited by all `BlockState` instances.

### `WireNode` constants

AC uses `Redstone.SIGNAL_MIN` / `SIGNAL_MAX`. In yarn those aren't
exposed as named constants. Inline `0` / `15` where AC uses them,
or define them as package-private constants in the port's shared
constants file (e.g. `WireConstants.SIGNAL_MIN = 0`,
`SIGNAL_MAX = 15`) so future renames are one-place fixes.

### `WireHandler.Directions` + `NodeProvider` + lookup tables

AC nests these inside `WireHandler`. The port extracts them into
a separate `WireConstants.java` (or similar) so session 1 files
can import them without pulling in the 1,000-line algorithm class.
Session 2's ported `WireHandler` then references the shared
constants instead of owning them.
