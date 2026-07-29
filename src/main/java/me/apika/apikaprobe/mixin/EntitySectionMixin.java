package me.apika.apikaprobe.mixin;

import java.util.Collection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import me.apika.apikaprobe.bridge.ExampleMod;
import me.apika.apikaprobe.spatial.CellHolder;
import me.apika.apikaprobe.spatial.EntityCellIndex;
import me.apika.apikaprobe.spatial.SectionExtents;

/**
 * Position filter for EntitySection.getEntities (both overloads), gated
 * by {@link EntityCellIndex#ENABLED}. Replicates vanilla's loop exactly
 * (same list, same order, same abort semantics) but skips entities whose
 * cached position cannot put their box inside the query box, padded by
 * the section's max observed entity extents plus one block of floor
 * slack. Entities with an unset cache (Long.MIN_VALUE) always pass.
 */
@Mixin(EntitySection.class)
public abstract class EntitySectionMixin implements SectionExtents {
	@Shadow private ClassInstanceMultiMap<EntityAccess> storage;

	@Unique private float ferrite$maxHalfXZ = 0.0f;
	@Unique private float ferrite$maxHeight = 0.0f;

	@Override
	public float ferrite$maxHalfXZ() {
		return ferrite$maxHalfXZ;
	}

	@Override
	public float ferrite$maxHeight() {
		return ferrite$maxHeight;
	}

	@Override
	public void ferrite$growExtents(float halfXZ, float height) {
		if (halfXZ > ferrite$maxHalfXZ) ferrite$maxHalfXZ = halfXZ;
		if (height > ferrite$maxHeight) ferrite$maxHeight = height;
	}

	@Inject(method = "add", at = @At("HEAD"))
	private void ferrite$onAdd(EntityAccess entity, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (entity instanceof CellHolder holder) {
			holder.ferrite$setPackedPos(entity.blockPosition().asLong());
		}
		AABB bb = entity.getBoundingBox();
		ferrite$growExtents((float) (Math.max(bb.getXsize(), bb.getZsize()) * 0.5), (float) bb.getYsize());
	}

	@Inject(method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
			at = @At("HEAD"), cancellable = true)
	private void ferrite$filteredPlain(AABB bb, AbortableIterationConsumer<EntityAccess> consumer,
			CallbackInfoReturnable<AbortableIterationConsumer.Continuation> cir) {
		if (!EntityCellIndex.ENABLED) return;
		cir.setReturnValue(ferrite$run(storage, null, bb, consumer));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
			at = @At("HEAD"), cancellable = true)
	private void ferrite$filteredTyped(EntityTypeTest type, AABB bb, AbortableIterationConsumer consumer,
			CallbackInfoReturnable<AbortableIterationConsumer.Continuation> cir) {
		if (!EntityCellIndex.ENABLED) return;
		Collection<? extends EntityAccess> found = storage.find(type.getBaseClass());
		if (found.isEmpty()) {
			cir.setReturnValue(AbortableIterationConsumer.Continuation.CONTINUE);
			return;
		}
		cir.setReturnValue(ferrite$run(found, type, bb, consumer));
	}

	/**
	 * One loop serving both overloads. type == null means the plain
	 * overload (no cast test). Oracle: on sampled queries, run the
	 * intersect test on filter-skipped entities too; a skipped entity
	 * that intersects is a MISMATCH (wrongly excluded).
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Unique
	private AbortableIterationConsumer.Continuation ferrite$run(Collection<? extends EntityAccess> list,
			EntityTypeTest type, AABB bb, AbortableIterationConsumer consumer) {
		boolean oracle = EntityCellIndex.ORACLE_RATE > 0
				&& ++EntityCellIndex.queryCounter % EntityCellIndex.ORACLE_RATE == 0;

		// Filter bounds: query box padded by the largest box any member
		// could project from its feet position, plus 1 block floor slack.
		int minX = Mth.floor(bb.minX - ferrite$maxHalfXZ) - 1;
		int maxX = Mth.floor(bb.maxX + ferrite$maxHalfXZ) + 1;
		int minZ = Mth.floor(bb.minZ - ferrite$maxHalfXZ) - 1;
		int maxZ = Mth.floor(bb.maxZ + ferrite$maxHalfXZ) + 1;
		int minY = Mth.floor(bb.minY - ferrite$maxHeight) - 1;
		int maxY = Mth.floor(bb.maxY) + 1;

		for (EntityAccess entity : list) {
			EntityCellIndex.scanned++;
			long packed = entity instanceof CellHolder holder ? holder.ferrite$packedPos() : Long.MIN_VALUE;
			boolean pass = true;
			if (packed != Long.MIN_VALUE) {
				int x = BlockPos.getX(packed);
				int y = BlockPos.getY(packed);
				int z = BlockPos.getZ(packed);
				pass = x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= minY && y <= maxY;
			}
			if (!pass) {
				EntityCellIndex.filteredOut++;
				if (oracle) {
					EntityCellIndex.oracleChecks++;
					if (entity.getBoundingBox().intersects(bb)
							&& (type == null || type.tryCast(entity) != null)) {
						EntityCellIndex.oracleMismatches++;
						ExampleMod.LOGGER.warn(
								"[entity-query-cache] MISMATCH: filter skipped intersecting entity {} pos={} box={}",
								entity, BlockPos.of(packed), bb);
					}
				}
				continue;
			}
			if (type != null) {
				Object cast = type.tryCast(entity);
				if (cast == null) continue;
				if (entity.getBoundingBox().intersects(bb)) {
					EntityCellIndex.delivered++;
					if (consumer.accept(cast).shouldAbort()) {
						return AbortableIterationConsumer.Continuation.ABORT;
					}
				}
			} else if (entity.getBoundingBox().intersects(bb)) {
				EntityCellIndex.delivered++;
				if (consumer.accept(entity).shouldAbort()) {
					return AbortableIterationConsumer.Continuation.ABORT;
				}
			}
		}
		return AbortableIterationConsumer.Continuation.CONTINUE;
	}
}
