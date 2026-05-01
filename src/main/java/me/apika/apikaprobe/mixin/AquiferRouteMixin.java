package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;

import me.apika.apikaprobe.bridge.ExampleMod;
import me.apika.apikaprobe.worldgen.RustAquiferDispatch;
import me.apika.apikaprobe.worldgen.RustAquiferSampler;

/**
 * Redirects the {@link Aquifer#aquifer(NoiseChunk,
 * ChunkPos, NoiseRouter, PositionalRandomFactory, int, int,
 * Aquifer.FluidLevelSampler)} factory call inside
 * {@link NoiseChunk}'s constructor. When
 * {@link RustAquiferDispatch#ENABLED} is true, wraps the vanilla
 * sampler with {@link RustAquiferSampler} so per-block
 * {@code apply()} calls go through the Rust port.
 *
 * <p>When the toggle is off, the redirect transparently returns the
 * vanilla sampler unchanged.
 */
@Mixin(NoiseChunk.class)
public abstract class AquiferRouteMixin {

    /** Vanilla per-column surface-height estimator. Used to populate
     *  the surface-height grid that Rust queries during
     *  `get_fluid_level_for`. */
    @Shadow
    abstract int estimateSurfaceHeight(int x, int z);

    /** Vanilla rectangle-max surface-height estimator. Vanilla's
     *  `Aquifer.Impl` constructor (line 140-141) feeds this
     *  into the `field_61452` high-Y cap calculation. Mirroring the
     *  exact call site is needed for parity — approximating with
     *  `max` over our sparse surface grid drifts by a few blocks and
     *  produces the dominant Pattern-1 mismatches documented in
     *  {@code docs/AQUIFER_PORT.md}. */
    @Shadow
    abstract int estimateHighestSurfaceLevel(int minX, int minZ, int maxX, int maxZ);

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/Aquifer;"
                            + "aquifer(Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                            + "Lnet/minecraft/world/level/ChunkPos;"
                            + "Lnet/minecraft/world/level/levelgen/NoiseRouter;"
                            + "Lnet/minecraft/util/PositionalRandomFactory;"
                            + "II"
                            + "Lnet/minecraft/world/level/levelgen/Aquifer$FluidLevelSampler;)"
                            + "Lnet/minecraft/world/level/levelgen/Aquifer;"))
    private Aquifer ferrite$wrapAquifer(
            NoiseChunk chunkNoiseSampler,
            ChunkPos chunkPos,
            NoiseRouter noiseRouter,
            PositionalRandomFactory randomSplitter,
            int minimumY,
            int height,
            Aquifer.FluidLevelSampler fluidLevelSampler) {
        // Always build the vanilla sampler — used as fallback when
        // Rust init fails AND keeps vanilla's lazy state consistent
        // with the wrapper's. Cost: one extra Impl alloc per chunk
        // (~16 KB), worth it for the simpler fallback path.
        Aquifer vanilla = Aquifer.aquifer(
                chunkNoiseSampler, chunkPos, noiseRouter, randomSplitter,
                minimumY, height, fluidLevelSampler);

        if (!RustAquiferDispatch.ENABLED) {
            return vanilla;
        }

        try {
            int chunkMinBlockX = chunkPos.getStartX();
            int chunkMinBlockZ = chunkPos.getStartZ();
            int chunkMaxBlockX = chunkPos.getEndX();
            int chunkMaxBlockZ = chunkPos.getEndZ();

            // Pre-compute surface-height grid via vanilla's per-column
            // method — exposed via the @Shadow above. The estimator
            // closure is called from the grid builder before we
            // construct the wrapper.
            RustAquiferSampler.GridResult grid = RustAquiferSampler.buildSurfaceGrid(
                    chunkMinBlockX, chunkMinBlockZ, this::estimateSurfaceHeight);

            // High surface estimate — replicate vanilla's exact call
            // from `Aquifer.Impl` constructor (line 140-141).
            // Vanilla feeds this through `field_61452` math to
            // determine the high-Y bailout cap; bit-exact agreement
            // here closes the dominant Pattern-1 mismatch documented
            // in `docs/AQUIFER_PORT.md`.
            //
            // Vanilla cell-padded extents — match `AquiferImpl::new`:
            //   start_x = (chunkMinBlockX - 5) >> 4
            //   end_x_cell = ((chunkMaxBlockX - 5) >> 4) + 1
            //   query rect x: [start_x << 4, (end_x_cell << 4) + 9]
            int aqStartXCell = (chunkMinBlockX - 5) >> 4;
            int aqEndXCell = ((chunkMaxBlockX - 5) >> 4) + 1;
            int aqStartZCell = (chunkMinBlockZ - 5) >> 4;
            int aqEndZCell = ((chunkMaxBlockZ - 5) >> 4) + 1;
            int rectMinX = aqStartXCell << 4;
            int rectMinZ = aqStartZCell << 4;
            int rectMaxX = (aqEndXCell << 4) + 9;
            int rectMaxZ = (aqEndZCell << 4) + 9;
            int highEstimate = this.estimateHighestSurfaceLevel(
                    rectMinX, rectMinZ, rectMaxX, rectMaxZ);

            // sea_level — vanilla derives this from settings; the
            // FluidLevelSampler's getFluidLevel above sea-level
            // returns a `FluidLevel(seaLevel, defaultFluid)`. For
            // overworld this is settings.seaLevel() = 63. Pull it
            // by sampling at a high y where we know we'll get the
            // sea-level water entry.
            int seaLevel = fluidLevelSampler.getFluidLevel(0, 256, 0).y();

            return new RustAquiferSampler(
                    seaLevel,
                    chunkMinBlockX, chunkMinBlockZ,
                    chunkMaxBlockX, chunkMaxBlockZ,
                    minimumY, height,
                    highEstimate,
                    grid.buf(),
                    grid.originX(), grid.originZ(),
                    vanilla);
        } catch (Throwable t) {
            // Any failure in the wrapping path = fall back to vanilla
            // so we never break chunkgen.
            ExampleMod.LOGGER.warn(
                    "[aquifer-rust] wrapper construction failed for chunk ({},{}): {}",
                    chunkPos.x, chunkPos.z, t.toString());
            return vanilla;
        }
    }
}
