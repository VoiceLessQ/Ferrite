package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.entity.EntityAccess;

import me.apika.apikaprobe.monitor.EntityQueryMonitor;

/**
 * Counts entity position updates at the section-callback level, split
 * into same-section moves (list untouched, but any position-keyed index
 * goes stale) and section-crossing moves (list mutates too).
 *
 * Probe for the entity-spatial-query-index design (LOCAL_DESIGN): the
 * move/query interleave ratio decides lazy per-section rebuild vs
 * incremental cell update.
 */
@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback")
public abstract class EntitySectionCallbackMixin {
	@Shadow @Final private EntityAccess entity;
	@Shadow private long currentSectionKey;
	@Shadow private net.minecraft.world.level.entity.EntitySection<?> currentSection;

	@Inject(method = "onMove()V", at = @At("HEAD"))
	private void ferrite$countMove(CallbackInfo ci) {
		net.minecraft.core.BlockPos pos = entity.blockPosition();
		long newKey = SectionPos.asLong(pos);
		EntityQueryMonitor.onMoveEvent(newKey != currentSectionKey);
		if (me.apika.apikaprobe.spatial.EntityCellIndex.ENABLED
				&& entity instanceof me.apika.apikaprobe.spatial.CellHolder holder) {
			holder.ferrite$setPackedPos(pos.asLong());
			// Refresh extents so entities that grew (slimes) stay covered.
			net.minecraft.world.phys.AABB bb = entity.getBoundingBox();
			((me.apika.apikaprobe.spatial.SectionExtents) currentSection).ferrite$growExtents(
					(float) (Math.max(bb.getXsize(), bb.getZsize()) * 0.5), (float) bb.getYsize());
		}
	}
}
