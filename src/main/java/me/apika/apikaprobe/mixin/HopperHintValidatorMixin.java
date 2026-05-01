package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.hopper.ExtractHint;
import me.apika.apikaprobe.monitor.HopperHintMonitor;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

@Mixin(HopperBlockEntity.class)
public abstract class HopperHintValidatorMixin {

	@Inject(
		method = "extract(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/Hopper;)Z",
		at = @At("HEAD")
	)
	private static void ferrite$validateHint(Level world, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
		if (!HopperHintMonitor.VALIDATE) return;

		BlockPos pos = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY() + 1.0, hopper.getLevelZ());
		BlockState state = world.getBlockState(pos);
		Container inventory = HopperBlockEntityInvoker.ferrite$invokeGetInputInventory(world, hopper, pos, state);

		if (inventory == null) {
			HopperHintMonitor.onValidationNoInv();
			return;
		}
		if (!(inventory instanceof ExtractHint h)) {
			HopperHintMonitor.onValidationUnsupported();
			return;
		}

		int hint = h.ferrite$getExtractHint();
		int n = inventory.getContainerSize();
		if (hint <= 0) {
			HopperHintMonitor.onValidation(false, pos, hint, -1);
			return;
		}
		if (hint > n) {
			HopperHintMonitor.onValidation(true, pos, hint, -1);
			return;
		}

		int firstNonEmpty = -1;
		for (int i = 0; i < hint; i++) {
			if (!inventory.getItem(i).isEmpty()) { firstNonEmpty = i; break; }
		}
		HopperHintMonitor.onValidation(firstNonEmpty >= 0, pos, hint, firstNonEmpty);
	}
}
