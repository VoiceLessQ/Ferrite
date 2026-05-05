# Hopper Highway

The story of how Ferrite ended up with a per-slot hopper highway, what
got built, what got rejected, and what shipped where.

Two things ship in this work:

1. **Extract hint, default-on.** Skip-empty-prefix on extract from
   partially-drained chests and barrels. Pure vanilla parity, just
   faster. Lives at commit `662b125`.
2. **Highway, opt-in via `/ferrite hopper highway on`.** Per-slot
   cooldowns + round-robin destination routing. Same vanilla speed
   per slot, multiple slots in parallel, 5 lanes instead of 1.
   Lives at commits `74fabc9` (Phase 1), `fba4ed6` (Phase 2-3),
   `6ae2dea` (gate fix).

The rest of this doc is the discovery path.

## Where the friction lives

The diagnostic probe `[hopper-slot]` (commit `c34b05c`) instruments
`HopperBlockEntity.insert` and `HopperBlockEntity.extract`, counting
attempts per call and partitioning by where success landed:

```
extract calls=25  succ@0=0  succ>0=25  avgAtt=9.00  wasted=200  (8.00/call)  usPerCall=21.86
```

That's a chest hopper draining a partially-emptied source. **Every
extract call iterates 9 slots before finding the first non-empty
one.** The first 8 are wasted: vanilla calls `inventory.getStack(slot)`,
short-circuits on empty, advances. ~2.4 µs of work per probe, summed
across 8 wasted probes per call, summed across hundreds of calls per
second. Real cost.

Insert side, same probe:
```
insert calls=875  succ@0=875  succ>0=0  fail=0  avgAtt=1.00  wasted=0  (0.00/call)
```

Zero waste. Slot 0 is always the right answer for insert in chains.
That ruled out smart-routing-by-type as a candidate fix.

## What the contract is

Before any code, we re-read [HopperBlockEntity.java](../1.21.11/common/net/minecraft/block/entity/HopperBlockEntity.java)
and mapped what's load-bearing:

- The `return true` break at L157 (insert) and L225 (extract) caps
  each fired tick at exactly 1 item moved. That's the
  vanilla-redstone contract.
- `setTransferCooldown(8)` at L121 after success caps each hopper at
  1 fire per 8 ticks.
- The chain-feed trick at L323-L331 phases hopper-to-hopper handoff:
  receiver gets cooldown=7 (instead of 8) when the feeder fires into
  an empty receiver, so the receiver drains 1 tick before the feeder
  fires next.
- [ScreenHandler.calculateComparatorOutput](../1.21.11/common/net/minecraft/screen/ScreenHandler.java)
  sums `count/maxCount` across slots and averages. **Slot
  distribution doesn't change comparator strength.** Only total count
  matters. This is a load-bearing fact for what we shipped: spreading
  items across 5 slots changes nothing about the redstone signal a
  comparator emits.

What ships preserves all five.

## What got rejected

**Multi-item-per-fire** ("remove the return-true break, move 5 items
in one call, 5× throughput"). Looked tempting. Killed by the contract
mapping: per-tick comparator transitions go from ≤1 step to ≤5 steps,
which breaks every redstone sorter built around "1 item per 8 ticks
per hopper." Walked back in conversation.

**Smart routing by item type** ("if slot 0's item won't fit downstream,
try slots 1-4 first"). The data said no. `succ>0 = 0` on insert across
thousands of calls. Slot 0 is always the right insert slot in chain
workloads. Ship would have added overhead with no win.

**Intake distribution** ("spread items across slots on arrival, fill
all 5 lanes always"). This defeats `isFull()` as a backpressure
signal. With items spread thin across slots, `isFull` rarely fires;
upstream extract keeps trying full destinations and pays the
~21 µs/call extract loop cost we just measured. Net regression.

## What shipped - Phase by phase

### Phase 0: Extract hint (default-on)

The empty-prefix waste from `[hopper-slot]` data. Per-`Inventory` hint
field tracks the first known non-empty slot. Maintenance hooks on
`setStack` and `removeStack` keep the invariant correct. Extract loop
starts at the hint instead of slot 0.

**Files:**

- [src/main/java/me/apika/apikaprobe/hopper/ExtractHint.java](../src/main/java/me/apika/apikaprobe/hopper/ExtractHint.java) - interface
- [src/main/java/me/apika/apikaprobe/mixin/LootableContainerExtractHintMixin.java](../src/main/java/me/apika/apikaprobe/mixin/LootableContainerExtractHintMixin.java) - `@Unique` field + maintenance hooks
- [src/main/java/me/apika/apikaprobe/mixin/HopperExtractHintMaintainMixin.java](../src/main/java/me/apika/apikaprobe/mixin/HopperExtractHintMaintainMixin.java) - same hooks for hopper's `setStack`/`removeStack` overrides
- [src/main/java/me/apika/apikaprobe/mixin/DoubleInventoryExtractHintMixin.java](../src/main/java/me/apika/apikaprobe/mixin/DoubleInventoryExtractHintMixin.java) - delegates hint to underlying chests
- [src/main/java/me/apika/apikaprobe/mixin/HopperHintExtractRouteMixin.java](../src/main/java/me/apika/apikaprobe/mixin/HopperHintExtractRouteMixin.java) - `@Inject` at `INVOKE_ASSIGN` of `getInputInventory`, captures the local, short-circuits when the inventory is hint-supported (no duplicate lookup)
- [src/main/java/me/apika/apikaprobe/mixin/HopperHintValidatorMixin.java](../src/main/java/me/apika/apikaprobe/mixin/HopperHintValidatorMixin.java) - opt-in shadow validator

**Phase outcome:**

- avgStartIdx=16 mid-drain: ~23 µs/call savings, ~60% reduction
- avgStartIdx=53 (one stack left at slot 53 of a double chest): ~110 µs/call, ~85% reduction
- 100% hit rate, 0 wraparounds, 0 stale events across 450 validator-checked extracts
- Default-on, no toggle. Ships at commit `662b125`.

### Phase 1: Per-slot cooldown infrastructure (default-off, dormant)

Replaces vanilla's single `int transferCooldown` with a parallel
`int[5] slotCooldowns` array. The vanilla field stays untouched as
the source-of-truth in this phase; the array is maintained in sync.
NBT migration shim reads legacy `TransferCooldown` and broadcasts to
all 5 slots so existing worlds load identically.

This phase has zero observable behavior change on its own.
`setTransferCooldown(n)` broadcasts to all 5 slots when ENABLE is
false, so vanilla code paths see the same monolithic cooldown they
always did.

**Files:**

- [src/main/java/me/apika/apikaprobe/hopper/SlotCooldownAccess.java](../src/main/java/me/apika/apikaprobe/hopper/SlotCooldownAccess.java) - interface with default helpers (decrementAll, broadcast, allOnCooldown, max)
- [src/main/java/me/apika/apikaprobe/hopper/PerSlotFireConfig.java](../src/main/java/me/apika/apikaprobe/hopper/PerSlotFireConfig.java) - ENABLE / VALIDATE flags, default false
- [src/main/java/me/apika/apikaprobe/mixin/HopperPerSlotCooldownMixin.java](../src/main/java/me/apika/apikaprobe/mixin/HopperPerSlotCooldownMixin.java) - `@Unique` field, NBT read/write hooks, decrement on `serverTick`, broadcast on `setTransferCooldown`, override `needsCooldown` to read the array

Ships at commit `74fabc9`.

### Phase 2: Per-slot fire (still default-off)

Replaces the single fire body in `insertAndExtract` with a round-robin
per-slot fire. Each tick, the round-robin pointer advances by one
slot. If the slot at the pointer has cooldown ≤ 0, vanilla's fire
logic runs once (1 item moved per the contract). On success, only
that slot's cooldown is reset to 8.

Round-robin enforces at most 1 fire per tick per hopper, which
preserves the per-tick comparator transition rate ≤ 1. The 5x
aggregate throughput comes from each slot firing on a different
tick within the 8-tick window.

Chain feed: when a hopper-to-hopper transfer lands in a slot,
the receiving slot's cooldown is set to 7 (or 8 depending on tick
timing) - same `8 - j` math vanilla uses, but applied per slot
instead of per hopper.

**Files:**

- [src/main/java/me/apika/apikaprobe/mixin/HopperPerSlotFireMixin.java](../src/main/java/me/apika/apikaprobe/mixin/HopperPerSlotFireMixin.java) - `@Inject(HEAD, cancellable=true)` on `insertAndExtract`, round-robin pointer, calls vanilla `insert` + `extract` per slot, sets per-slot cooldown on success
- [src/main/java/me/apika/apikaprobe/mixin/HopperPerSlotChainFeedMixin.java](../src/main/java/me/apika/apikaprobe/mixin/HopperPerSlotChainFeedMixin.java) - `@Inject` after the `setTransferCooldown` call inside the private 5-arg `transfer`, sets per-slot cooldown on the receiving slot

**Phase 2 measurement** (validator on):
```
fires=4174  items=4174  noReady=0  tickViolations=0  staggerCollapses=0
```
Hit rate 1:1 (one item per fire), zero per-tick comparator violations,
zero stagger collapses. ~3.1× chain throughput vs vanilla's
1-fire-per-8-ticks.

### Phase 3: Slot-to-slot routing in transfer()

Phase 2 alone gives 3× throughput but items still pile in slot 0 of
each receiving hopper because vanilla `transfer()` iterates destination
slots starting at 0 and fills the first non-full one. Phase 3 routes
incoming items round-robin across destination slots so they actually
distribute.

**First attempt: lane preservation** (source slot K → destination
slot K). Failed visibly. In a chain where every source hopper has
items only in slot 0, every transfer routes "slot 0 → slot 0" and
distribution doesn't happen. 99% lane hit rate but lanes 1-4 never
used.

**Second attempt: round-robin destination** (each hopper tracks
`lastInsertSlot`; next item goes to `(last+1) % 5`). This works.
Items rotate through slots 0 → 1 → 2 → 3 → 4 → 0 in each receiving
hopper.

**Files:**

- [src/main/java/me/apika/apikaprobe/hopper/HopperLaneRouteConfig.java](../src/main/java/me/apika/apikaprobe/hopper/HopperLaneRouteConfig.java) - ENABLE flag
- [src/main/java/me/apika/apikaprobe/mixin/HopperLaneRouteMixin.java](../src/main/java/me/apika/apikaprobe/mixin/HopperLaneRouteMixin.java) - `@Inject(HEAD, cancellable=true)` on the public 4-arg `transfer`, reads `lastInsertSlot` from the destination hopper, iterates dest slots starting at `(last+1) % size`, falls back to vanilla iteration if the round-robin slot is full or wrong-item, advances `lastInsertSlot` on success

Ships (default-off) at commit `fba4ed6` together with Phase 2.

### Commit 3: Gate the per-slot decrement and NBT write

Phase 1's mixin decremented all 5 cooldowns and wrote
`FerriteSlotCooldowns: int[5]` to NBT every tick - even when
ENABLE was false. Cosmetic and lightweight, but a correctness
miss: users who don't opt in shouldn't pay any cost.

Both gated behind `if (!PerSlotFireConfig.ENABLE) return;`.
Default-off users now run vanilla hopper code with zero per-tick
overhead from this mod and zero NBT bloat.

Ships at commit `6ae2dea`.

## Per-slot speed verification

Question that came up after Phase 3 landed: "is each slot actually
running at vanilla 8-tick speed, or is it somehow faster?"

Added inter-fire interval tracking per slot. Records `world.getTime()`
delta between consecutive fires of the same slot. At steady state:

```
interval avg=8.00  min=8  max=8  cooldownViolations=0
```

`min=8, max=8` across 4400 samples per 5s window. Each slot fires
exactly every 8 ticks. The early `min=7` lines come from chain-feed
(receiver gets cooldown=7 when the feeder lands in an empty receiver
slot - same `8 - j` adjustment vanilla uses). Not a regression, and
not faster than vanilla single-hopper pace.

## What this is and isn't

**It is:** five lanes of vanilla speed running in parallel. Each lane
is bit-equivalent to a vanilla hopper running solo. The aggregate
movement rate per hopper is 5×.

**It isn't:** "faster hoppers." A single slot moves items at vanilla
pace, no faster. The per-tick comparator transition stays bounded at
1 step.

**It also isn't:** universally safe. Many vanilla redstone designs
are tuned around "1 item per 8 ticks per hopper" as a clock signal.
Item sorters that count comparator pulses, lossless sorter timing
windows, and most clock-based contraptions assume that rate. Highway
runs at 5× that rate, which can saturate clock-following gear. That's
why it's opt-in.

For pure storage: highway helps. For sorters: turn it off.

## Knobs

| flag / command | effect | default |
|---|---|---|
| `-Dferrite.hopper.extract.useHint` | extract hint | **true** |
| `-Dferrite.hopper.extract.validate` | shadow validator on extract hint | false |
| `-Dferrite.hopper.perslot.enable` | per-slot fire (Phase 2) | false |
| `-Dferrite.hopper.perslot.validate` | comparator-safety + stagger validator | false |
| `-Dferrite.hopper.lane.enable` | round-robin destination routing (Phase 3) | false |
| `/ferrite hopper highway on` | toggles all three above to true at runtime | - |
| `/ferrite hopper highway off` | toggles all three to false (vanilla paths) | - |
| `/ferrite hopper highway status` | prints flag state | - |

## Where the validators live

- `[hopper-slot]` log lines - slot-attempt distribution per call,
  wall-time per call and per-attempt. Active when ferrite is loaded.
  Used to spot regressions if anyone disables the extract hint.
- `[hopper-hint]` log lines - hint hits/wraps/fails plus shadow
  validator (when enabled) checking the invariant "slots [0..hint-1]
  are empty." Reports stale-hint events with world coordinates,
  capped at 20 warns per session.
- `[hopper-perslot]` log lines - fires, items, lane hits/fallbacks,
  per-tick item-count violations, stagger-collapse events, per-slot
  fire intervals (avg/min/max for each slot 0-4 separately).

## Things not to re-investigate

- Multi-item per fire. Already rejected. See "What got rejected"
  above. Comparator + chain-feed contract.
- Smart routing by item type on insert. `succ>0 = 0` across thousands
  of measured insert calls in chain workloads. No win to be had.
- Intake distribution that defeats `isFull()`. Causes extract
  upstream to spin on full destinations.

If a future contributor proposes "make hoppers move 5 items per fire",
that proposal is working from stale context. Stop and re-read this doc.
