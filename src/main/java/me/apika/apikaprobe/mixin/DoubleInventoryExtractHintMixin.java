package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import me.apika.apikaprobe.hopper.ExtractHint;

import net.minecraft.inventory.DoubleInventory;
import net.minecraft.inventory.Inventory;

@Mixin(DoubleInventory.class)
public abstract class DoubleInventoryExtractHintMixin implements Inventory, ExtractHint {

	@Shadow @Final private Inventory first;
	@Shadow @Final private Inventory second;

	@Override
	public int ferrite$getExtractHint() {
		int firstSize = this.first.size();
		if (this.first instanceof ExtractHint h1) {
			int h = h1.ferrite$getExtractHint();
			if (h < firstSize) return h;
		} else {
			return 0;
		}
		if (this.second instanceof ExtractHint h2) {
			return firstSize + h2.ferrite$getExtractHint();
		}
		return firstSize;
	}

	@Override
	public void ferrite$setExtractHint(int hint) {
	}
}
