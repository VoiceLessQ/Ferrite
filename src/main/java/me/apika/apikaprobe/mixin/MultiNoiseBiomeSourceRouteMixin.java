package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.Climate;

import me.apika.apikaprobe.worldgen.RustBiomeRouter;

/**
 * Routes per-cell biome lookups to {@link RustBiomeRouter}. Targets the
 * (qx, qy, qz, sampler) overload — the one chunk gen calls per quart
 * cell. Yarn descriptor: {@code (IIIL.../MultiNoiseSampler;)L.../Holder;}.
 *
 * <p>HEAD inject with cancellable: when {@link RustBiomeRouter#ENABLED}
 * is true and rust resolves the biome, return the cached holder. On any
 * miss (router not installed, ID -1, ID out of table) we fall through
 * to vanilla unchanged.
 *
 * <p>Coords are vanilla's quart-pos units (block >> 2). The router calls
 * {@code findBiomeAtBlockRust(blockX, blockY, blockZ)} which snaps to
 * quart internally — we pre-multiply back to block coords here.
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceRouteMixin {

	@Inject(
		method = "getBiome(IIILnet/minecraft/world/level/biome/Climate$MultiNoiseSampler;)Lnet/minecraft/core/Holder;",
		at = @At("HEAD"),
		cancellable = true,
		require = 0  // optional — yarn descriptor may shift across versions
	)
	private void ferrite$routeBiome(int qx, int qy, int qz,
			Climate.Sampler sampler,
			CallbackInfoReturnable<Holder<Biome>> cir) {
		Holder<Biome> rust = RustBiomeRouter.tryRoute(qx << 2, qy << 2, qz << 2);
		if (rust != null) {
			cir.setReturnValue(rust);
		}
	}
}
