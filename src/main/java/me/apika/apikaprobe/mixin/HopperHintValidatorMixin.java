package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.hopper.ExtractHint;
import me.apika.apikaprobe.monitor.HopperHintMonitor;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.Hopper;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

@Mixin(HopperBlockEntity.class)
public abstract class HopperHintValidatorMixin {

	@Inject(
		method = "extract(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Z",
		at = @At("HEAD")
	)
	private static void ferrite$validateHint(World world, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
		if (!HopperHintMonitor.VALIDATE) return;

		BlockPos pos = BlockPos.ofFloored(hopper.getHopperX(), hopper.getHopperY() + 1.0, hopper.getHopperZ());
		BlockState state = world.getBlockState(pos);
		Inventory inventory = HopperBlockEntityInvoker.ferrite$invokeGetInputInventory(world, hopper, pos, state);

		if (inventory == null) {
			HopperHintMonitor.onValidationNoInv();
			return;
		}
		if (!(inventory instanceof ExtractHint h)) {
			HopperHintMonitor.onValidationUnsupported();
			return;
		}

		int hint = h.ferrite$getExtractHint();
		int n = inventory.size();
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
			if (!inventory.getStack(i).isEmpty()) { firstNonEmpty = i; break; }
		}
		HopperHintMonitor.onValidation(firstNonEmpty >= 0, pos, hint, firstNonEmpty);
	}
}
