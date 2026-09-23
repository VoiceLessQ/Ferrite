package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.worldgen.OreKernel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.OreFeature;

// Replays vanilla place() up to the scan, then OreKernel's scan and doPlace; default on, once per placement.
@Mixin(OreFeature.class)
public abstract class OreFeatureMixin {

	@Unique
	private boolean[] ferrite$pure;

	@Shadow
	protected abstract boolean doPlace(WorldGenLevel level, RandomSource random, double x0, double x1, double z0,
			double z1, double y0, double y1, int xStart, int yStart, int zStart, int sizeXZ, int sizeY);

	@Inject(method = "place", at = @At("HEAD"), cancellable = true)
	private void ferrite$place(WorldGenLevel level, ChunkGenerator chunkGenerator, RandomSource random,
			BlockPos origin, CallbackInfoReturnable<Boolean> cir) {
		int mode = OreKernel.mode;
		if (mode == OreKernel.OFF || !(level instanceof WorldGenRegion region)
				|| ((Object) this).getClass() != OreFeature.class) {
			return;
		}
		OreFeature self = (OreFeature) (Object) this;
		int size = self.size();
		float dir = random.nextFloat() * (float) Math.PI;
		float spreadXY = size / 8.0F;
		int maxRadius = Mth.ceil((size / 16.0F * 2.0F + 1.0F) / 2.0F);
		double x0 = origin.getX() + Math.sin(dir) * spreadXY;
		double x1 = origin.getX() - Math.sin(dir) * spreadXY;
		double z0 = origin.getZ() + Math.cos(dir) * spreadXY;
		double z1 = origin.getZ() - Math.cos(dir) * spreadXY;
		double y0 = origin.getY() + random.nextInt(3) - 2;
		double y1 = origin.getY() + random.nextInt(3) - 2;
		int xStart = origin.getX() - Mth.ceil(spreadXY) - maxRadius;
		int yStart = origin.getY() - 2 - maxRadius;
		int zStart = origin.getZ() - Mth.ceil(spreadXY) - maxRadius;
		int sizeXZ = 2 * (Mth.ceil(spreadXY) + maxRadius);
		int sizeY = 2 * (2 + maxRadius);

		boolean oracle = OreKernel.sample();
		int scan = OreKernel.scan(region, xStart, zStart, sizeXZ, yStart);
		if (scan < 0 || oracle) {
			boolean hit = OreKernel.vanillaScan(level, xStart, zStart, sizeXZ, yStart);
			if (scan >= 0) OreKernel.checkScan(scan == 1, hit, origin);
			scan = hit ? 1 : 0;
		}
		if (scan == 0) {
			cir.setReturnValue(false);
			return;
		}
		if (mode == OreKernel.SCAN) {
			cir.setReturnValue(this.doPlace(level, random, x0, x1, z0, z1, y0, y1, xStart, yStart, zStart, sizeXZ, sizeY));
			return;
		}
		boolean[] pure = this.ferrite$pure;
		if (pure == null) {
			pure = OreKernel.purity(self.targetStates());
			this.ferrite$pure = pure;
		}
		float discard = self.discardChanceOnAirExposure();
		if (oracle) {
			OreKernel.Shadow shadow = OreKernel.shadow(region, random, self.targetStates(), pure, size, discard,
					x0, x1, z0, z1, y0, y1, xStart, yStart, zStart, sizeXZ, sizeY);
			boolean placed = this.doPlace(level, random, x0, x1, z0, z1, y0, y1, xStart, yStart, zStart, sizeXZ, sizeY);
			if (shadow != null) OreKernel.check(shadow, region, random, placed, origin);
			cir.setReturnValue(placed);
			return;
		}
		cir.setReturnValue(OreKernel.doPlace(region, random, self.targetStates(), pure, size, discard,
				x0, x1, z0, z1, y0, y1, xStart, yStart, zStart, sizeXZ, sizeY));
	}
}
