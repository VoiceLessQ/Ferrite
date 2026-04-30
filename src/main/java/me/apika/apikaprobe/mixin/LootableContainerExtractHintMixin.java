package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.hopper.ExtractHint;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

@Mixin(LootableContainerBlockEntity.class)
public abstract class LootableContainerExtractHintMixin implements Inventory, ExtractHint {

	@Unique
	private int ferrite$extractHint = 0;

	@Override
	public int ferrite$getExtractHint() {
		return this.ferrite$extractHint;
	}

	@Override
	public void ferrite$setExtractHint(int hint) {
		this.ferrite$extractHint = hint < 0 ? 0 : hint;
	}

	@Unique
	private void ferrite$advanceHintFrom(int from) {
		int n = this.size();
		int next = n;
		for (int i = from; i < n; i++) {
			if (!this.getStack(i).isEmpty()) { next = i; break; }
		}
		this.ferrite$extractHint = next;
	}

	@Inject(method = "setStack", at = @At("RETURN"))
	private void ferrite$onSetStack(int slot, ItemStack stack, CallbackInfo ci) {
		if (!stack.isEmpty() && slot < this.ferrite$extractHint) {
			this.ferrite$extractHint = slot;
		}
	}

	@Inject(method = "removeStack(II)Lnet/minecraft/item/ItemStack;", at = @At("RETURN"))
	private void ferrite$onRemoveStackAmount(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
		if (slot == this.ferrite$extractHint && this.getStack(slot).isEmpty()) {
			this.ferrite$advanceHintFrom(slot + 1);
		}
	}

	@Inject(method = "removeStack(I)Lnet/minecraft/item/ItemStack;", at = @At("RETURN"))
	private void ferrite$onRemoveStackAll(int slot, CallbackInfoReturnable<ItemStack> cir) {
		if (slot == this.ferrite$extractHint && this.getStack(slot).isEmpty()) {
			this.ferrite$advanceHintFrom(slot + 1);
		}
	}
}
