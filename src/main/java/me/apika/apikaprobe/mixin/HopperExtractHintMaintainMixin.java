package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.hopper.ExtractHint;

import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

@Mixin(HopperBlockEntity.class)
public abstract class HopperExtractHintMaintainMixin implements Container, ExtractHint {

	@Inject(method = "setStack", at = @At("RETURN"))
	private void ferrite$onSetStack(int slot, ItemStack stack, CallbackInfo ci) {
		if (!stack.isEmpty() && slot < this.ferrite$getExtractHint()) {
			this.ferrite$setExtractHint(slot);
		}
	}

	@Inject(method = "removeStack(II)Lnet.minecraft.world.item.ItemStack;", at = @At("RETURN"))
	private void ferrite$onRemoveStack(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
		if (slot != this.ferrite$getExtractHint()) return;
		if (!this.getItem(slot).isEmpty()) return;
		int n = this.size();
		int next = n;
		for (int i = slot + 1; i < n; i++) {
			if (!this.getItem(i).isEmpty()) { next = i; break; }
		}
		this.ferrite$setExtractHint(next);
	}
}
