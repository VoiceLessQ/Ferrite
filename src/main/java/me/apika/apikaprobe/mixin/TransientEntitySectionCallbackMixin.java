package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;

import me.apika.apikaprobe.spatial.CellHolder;
import me.apika.apikaprobe.spatial.EntityCellIndex;
import me.apika.apikaprobe.spatial.SectionExtents;

/**
 * Client-side twin of EntitySectionCallbackMixin: keeps the cached packed
 * position fresh for entities managed by TransientEntitySectionManager,
 * so client-thread spatial queries filter correctly too. No monitor
 * counters here; the interleave probe is a server-side question.
 */
@Mixin(targets = "net.minecraft.world.level.entity.TransientEntitySectionManager$Callback")
public abstract class TransientEntitySectionCallbackMixin {
	@Shadow @Final private EntityAccess entity;
	@Shadow private EntitySection<?> currentSection;

	@Inject(method = "onMove()V", at = @At("HEAD"))
	private void ferrite$updateCell(CallbackInfo ci) {
		if (!EntityCellIndex.ENABLED) return;
		if (entity instanceof CellHolder holder) {
			long oldPacked = holder.ferrite$packedPos();
			long newPacked = entity.blockPosition().asLong();
			holder.ferrite$setPackedPos(newPacked);
			SectionExtents section = (SectionExtents) currentSection;
			AABB bb = entity.getBoundingBox();
			section.ferrite$growExtents(
					(float) (Math.max(bb.getXsize(), bb.getZsize()) * 0.5), (float) bb.getYsize());
			me.apika.apikaprobe.spatial.SectionGrid grid = section.ferrite$grid();
			if (grid != null) {
				grid.onMove(entity, oldPacked, newPacked);
			}
		}
	}
}
