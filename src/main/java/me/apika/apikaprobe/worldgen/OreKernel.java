package me.apika.apikaprobe.worldgen;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import me.apika.apikaprobe.bridge.ExampleMod;
import me.apika.apikaprobe.mixin.WorldGenRegionAccessor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.Xoroshiro128PlusPlus;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.AllOfRuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.AnyOfRuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.HeightMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.NotRuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;

// OreFeature.place scan and doPlace with vanilla's draws and writes; default full (-Dferrite.orekernel=off|scan).
public final class OreKernel {

	private OreKernel() {}

	public static final int OFF = 0;
	public static final int SCAN = 1;
	public static final int FULL = 2;

	public static volatile int mode = parseMode(System.getProperty("ferrite.orekernel", "full"));

	// 1 in N place calls rerun the vanilla scan and shadow doPlace on section copies; 0 disables.
	public static volatile int oracleRate = Integer.getInteger("ferrite.orekernel.oracle", 64);

	private static final Heightmap.Types SCAN_TYPE = Heightmap.Types.OCEAN_FLOOR_WG;
	// Section slots cover the box plus this many blocks, enough for the air check's neighbours.
	private static final int MARGIN = 2;
	private static final int[] DX = new int[6];
	private static final int[] DY = new int[6];
	private static final int[] DZ = new int[6];

	static {
		Direction[] dirs = Direction.values();
		for (int d = 0; d < 6; d++) {
			DX[d] = dirs[d].getStepX();
			DY[d] = dirs[d].getStepY();
			DZ[d] = dirs[d].getStepZ();
		}
	}

	private static final Field WORLDGEN_SOURCE = field(WorldgenRandom.class, "randomSource");
	private static final Field XOROSHIRO_GEN = field(XoroshiroRandomSource.class, "randomNumberGenerator");
	private static final Field SEED_LO = field(Xoroshiro128PlusPlus.class, "seedLo");
	private static final Field SEED_HI = field(Xoroshiro128PlusPlus.class, "seedHi");
	private static final Field ALL_OF_RULES = field(AllOfRuleTest.class, "rules");
	private static final Field ANY_OF_RULES = field(AnyOfRuleTest.class, "rules");
	private static final Field NOT_RULE = field(NotRuleTest.class, "rule");

	private static final LongAdder places = new LongAdder();
	private static final LongAdder scanMiss = new LongAdder();
	private static final LongAdder scanFallback = new LongAdder();
	private static final LongAdder kernelRuns = new LongAdder();
	private static final LongAdder zoneFallback = new LongAdder();
	private static final LongAdder overflow = new LongAdder();
	private static final LongAdder blockReads = new LongAdder();
	private static final LongAdder ruleCalls = new LongAdder();
	private static final LongAdder scanChecks = new LongAdder();
	private static final LongAdder scanMismatch = new LongAdder();
	private static final LongAdder placeChecks = new LongAdder();
	private static final LongAdder placeMismatch = new LongAdder();
	private static final LongAdder placeSkipped = new LongAdder();
	private static final AtomicInteger sampleCounter = new AtomicInteger();
	private static final AtomicInteger logged = new AtomicInteger();

	public static int parseMode(String s) {
		return switch (s) {
			case "scan" -> SCAN;
			case "full", "on", "true" -> FULL;
			default -> OFF;
		};
	}

	public static String modeName(int m) {
		return m == FULL ? "full" : m == SCAN ? "scan" : "off";
	}

	public static boolean sample() {
		int rate = oracleRate;
		return rate > 0 && sampleCounter.incrementAndGet() % rate == 0;
	}

	// 1 if a box column's OCEAN_FLOOR_WG height reaches yStart, 0 if none, -1 when vanilla must scan.
	public static int scan(WorldGenRegion level, int xStart, int zStart, int sizeXZ, int yStart) {
		places.increment();
		int cx0 = xStart >> 4;
		int cz0 = zStart >> 4;
		int cx1 = (xStart + sizeXZ) >> 4;
		int cz1 = (zStart + sizeXZ) >> 4;
		WorldGenRegionAccessor zone = (WorldGenRegionAccessor) level;
		if (!zone.ferrite$isWithinWriteZone(cx0, cz0) || !zone.ferrite$isWithinWriteZone(cx1, cz1)) {
			scanFallback.increment();
			return -1;
		}
		int ncz = cz1 - cz0 + 1;
		Heightmap[] maps = new Heightmap[(cx1 - cx0 + 1) * ncz];
		for (int x = xStart; x <= xStart + sizeXZ; x++) {
			for (int z = zStart; z <= zStart + sizeXZ; z++) {
				int slot = ((x >> 4) - cx0) * ncz + ((z >> 4) - cz0);
				Heightmap map = maps[slot];
				if (map == null) {
					ChunkAccess chunk = level.getChunk(x >> 4, z >> 4);
					// Imposter chunks remap the type and unprimed maps get primed: both stay vanilla's.
					if (chunk.getClass() != ProtoChunk.class || !chunk.hasPrimedHeightmap(SCAN_TYPE)) {
						scanFallback.increment();
						return -1;
					}
					map = chunk.getOrCreateHeightmapUnprimed(SCAN_TYPE);
					maps[slot] = map;
				}
				if (yStart <= map.getFirstAvailable(x & 15, z & 15)) return 1;
			}
		}
		scanMiss.increment();
		return 0;
	}

	// Vanilla's probe loop from OreFeature.place.
	public static boolean vanillaScan(WorldGenLevel level, int xStart, int zStart, int sizeXZ, int yStart) {
		for (int xprobe = xStart; xprobe <= xStart + sizeXZ; xprobe++) {
			for (int zprobe = zStart; zprobe <= zStart + sizeXZ; zprobe++) {
				if (yStart <= level.getHeight(SCAN_TYPE, xprobe, zprobe)) return true;
			}
		}
		return false;
	}

	public static boolean doPlace(WorldGenRegion level, RandomSource random, List<BlockReplacement> targets,
			boolean[] pure, int size, float discard, double x0, double x1, double z0, double z1, double y0, double y1,
			int xStart, int yStart, int zStart, int sizeXZ, int sizeY) {
		kernelRuns.increment();
		Sections sections = new Sections(level, false, xStart, yStart, zStart, sizeXZ, sizeY);
		try {
			return place(level, random, targets, pure, size, discard, x0, x1, z0, z1, y0, y1,
					xStart, yStart, zStart, sizeXZ, sizeY, sections);
		} finally {
			sections.release();
		}
	}

	// Vanilla OreFeature.doPlace; writes go to the sections given, real or copied.
	private static boolean place(WorldGenRegion level, RandomSource random, List<BlockReplacement> targets,
			boolean[] pure, int size, float discard, double x0, double x1, double z0, double z1, double y0, double y1,
			int xStart, int yStart, int zStart, int sizeXZ, int sizeY, Sections sections) {
		int placed = 0;
		int reads = 0;
		int calls = 0;
		BitSet tested = new BitSet(sizeXZ * sizeY * sizeXZ);
		BlockPos.MutableBlockPos orePos = new BlockPos.MutableBlockPos();
		double[] data = new double[size * 4];

		for (int i = 0; i < size; i++) {
			float step = (float) i / size;
			double xx = Mth.lerp((double) step, x0, x1);
			double yy = Mth.lerp((double) step, y0, y1);
			double zz = Mth.lerp((double) step, z0, z1);
			double ss = random.nextDouble() * size / 16.0;
			double r = ((Mth.sin((float) Math.PI * step) + 1.0F) * ss + 1.0) / 2.0;
			data[i * 4 + 0] = xx;
			data[i * 4 + 1] = yy;
			data[i * 4 + 2] = zz;
			data[i * 4 + 3] = r;
		}

		for (int i1 = 0; i1 < size - 1; i1++) {
			if (!(data[i1 * 4 + 3] <= 0.0)) {
				for (int i2 = i1 + 1; i2 < size; i2++) {
					if (!(data[i2 * 4 + 3] <= 0.0)) {
						double dx = data[i1 * 4 + 0] - data[i2 * 4 + 0];
						double dy = data[i1 * 4 + 1] - data[i2 * 4 + 1];
						double dz = data[i1 * 4 + 2] - data[i2 * 4 + 2];
						double dr = data[i1 * 4 + 3] - data[i2 * 4 + 3];
						if (dr * dr > dx * dx + dy * dy + dz * dz) {
							if (dr > 0.0) {
								data[i2 * 4 + 3] = -1.0;
							} else {
								data[i1 * 4 + 3] = -1.0;
							}
						}
					}
				}
			}
		}

		int count = targets.size();
		RuleTest[] tests = new RuleTest[count];
		BlockState[] ores = new BlockState[count];
		for (int t = 0; t < count; t++) {
			tests[t] = targets.get(t).target();
			ores[t] = targets.get(t).state();
		}
		// Two most recent (state, answer) pairs per pure target and box row.
		int rows = sizeY + 1;
		BlockState[] memoState = new BlockState[count * rows * 2];
		boolean[] memoOk = new boolean[count * rows * 2];
		int minY = level.getMinY();
		int maxY = level.getMaxY();
		WorldGenRegionAccessor region = (WorldGenRegionAccessor) level;
		// ensureCanWrite is true for every box block when the box sits in the write zone of a non-upgrading chunk.
		boolean zoneAll = !region.ferrite$center().isUpgrading()
				&& region.ferrite$isWithinWriteZone(xStart >> 4, zStart >> 4)
				&& region.ferrite$isWithinWriteZone((xStart + sizeXZ) >> 4, (zStart + sizeXZ) >> 4);
		if (!zoneAll) zoneFallback.increment();

		for (int i = 0; i < size; i++) {
			double r = data[i * 4 + 3];
			if (!(r < 0.0)) {
				double xx = data[i * 4 + 0];
				double yy = data[i * 4 + 1];
				double zz = data[i * 4 + 2];
				int xMin = Math.max(Mth.floor(xx - r), xStart);
				int yMin = Math.max(Mth.floor(yy - r), yStart);
				int zMin = Math.max(Mth.floor(zz - r), zStart);
				int xMax = Math.max(Mth.floor(xx + r), xMin);
				int yMax = Math.max(Mth.floor(yy + r), yMin);
				int zMax = Math.max(Mth.floor(zz + r), zMin);

				for (int x = xMin; x <= xMax; x++) {
					double xd = (x + 0.5 - xx) / r;
					if (xd * xd < 1.0) {
						for (int y = yMin; y <= yMax; y++) {
							double yd = (y + 0.5 - yy) / r;
							if (xd * xd + yd * yd < 1.0) {
								for (int z = zMin; z <= zMax; z++) {
									double zd = (z + 0.5 - zz) / r;
									if (xd * xd + yd * yd + zd * zd < 1.0 && y >= minY && y <= maxY) {
										int bitSetIndex = x - xStart + (y - yStart) * sizeXZ + (z - zStart) * sizeXZ * sizeY;
										if (!tested.get(bitSetIndex)) {
											tested.set(bitSetIndex);
											orePos.set(x, y, z);
											if (zoneAll || level.ensureCanWrite(orePos)) {
												LevelChunkSection section = sections.get(x, y, z);
												if (section != null) {
													int rx = x & 15;
													int ry = y & 15;
													int rz = z & 15;
													BlockState state = section.getBlockState(rx, ry, rz);
													reads++;
													for (int t = 0; t < count; t++) {
														boolean ok;
														int m = (t * rows + (y - yStart)) * 2;
														if (!pure[t] || y - yStart >= rows) {
															ok = tests[t].test(state, orePos, random);
															calls++;
														} else if (state == memoState[m]) {
															ok = memoOk[m];
														} else if (state == memoState[m + 1]) {
															ok = memoOk[m + 1];
														} else {
															ok = tests[t].test(state, orePos, random);
															calls++;
															memoState[m + 1] = memoState[m];
															memoOk[m + 1] = memoOk[m];
															memoState[m] = state;
															memoOk[m] = ok;
														}
														// canPlaceOre's air check: same draw, same short circuit.
														if (ok && discard > 0.0F
																&& (discard >= 1.0F || random.nextFloat() < discard)
																&& adjacentToAir(sections, x, y, z)) {
															ok = false;
														}
														if (ok) {
															section.setBlockState(rx, ry, rz, ores[t], false);
															placed++;
															break;
														}
													}
												}
											}
										}
									}
								}
							}
						}
					}
				}
			}
		}

		blockReads.add(reads);
		ruleCalls.add(calls);
		return placed > 0;
	}

	private static boolean adjacentToAir(Sections sections, int x, int y, int z) {
		for (int d = 0; d < 6; d++) {
			int nx = x + DX[d];
			int ny = y + DY[d];
			int nz = z + DZ[d];
			LevelChunkSection section = sections.get(nx, ny, nz);
			if (section == null || section.getBlockState(nx & 15, ny & 15, nz & 15).isAir()) return true;
		}
		return false;
	}

	// Per target: true when the rule reads only the state and y and draws nothing, so a (state, y) memo is exact.
	public static boolean[] purity(List<BlockReplacement> targets) {
		boolean[] pure = new boolean[targets.size()];
		for (int t = 0; t < pure.length; t++) pure[t] = pure(targets.get(t).target());
		return pure;
	}

	@SuppressWarnings("unchecked")
	private static boolean pure(RuleTest test) {
		Class<?> c = test.getClass();
		if (c == TagMatchTest.class || c == BlockMatchTest.class || c == BlockStateMatchTest.class
				|| c == HeightMatchTest.class || c == AlwaysTrueTest.class) {
			return true;
		}
		try {
			if (c == AllOfRuleTest.class) return allPure((List<RuleTest>) ALL_OF_RULES.get(test));
			if (c == AnyOfRuleTest.class) return allPure((List<RuleTest>) ANY_OF_RULES.get(test));
			if (c == NotRuleTest.class) return pure((RuleTest) NOT_RULE.get(test));
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
		return false;
	}

	private static boolean allPure(List<RuleTest> rules) {
		for (RuleTest rule : rules) {
			if (!pure(rule)) return false;
		}
		return true;
	}

	// Oracle, first half: the kernel on section copies with a cloned random, before vanilla writes.
	public static Shadow shadow(WorldGenRegion level, RandomSource random, List<BlockReplacement> targets,
			boolean[] pure, int size, float discard, double x0, double x1, double z0, double z1, double y0, double y1,
			int xStart, int yStart, int zStart, int sizeXZ, int sizeY) {
		long[] seed = rngState(random);
		if (seed == null) {
			placeSkipped.increment();
			return null;
		}
		RandomSource clone = new WorldgenRandom(new XoroshiroRandomSource(seed[0], seed[1]));
		Sections copies = new Sections(level, true, xStart, yStart, zStart, sizeXZ, sizeY);
		boolean placed = place(level, clone, targets, pure, size, discard, x0, x1, z0, z1, y0, y1,
				xStart, yStart, zStart, sizeXZ, sizeY, copies);
		copies.resolveAll();
		return new Shadow(copies, placed, rngState(clone), xStart, yStart, zStart, sizeXZ, sizeY);
	}

	public record Shadow(Sections copies, boolean placed, long[] rngEnd,
			int xStart, int yStart, int zStart, int sizeXZ, int sizeY) {}

	// Oracle, second half: vanilla has written; compare the box, the result and the random's end state.
	public static void check(Shadow shadow, WorldGenRegion level, RandomSource random, boolean placed, BlockPos origin) {
		placeChecks.increment();
		Sections c = shadow.copies();
		int xLo = shadow.xStart() - MARGIN;
		int xHi = shadow.xStart() + shadow.sizeXZ() + MARGIN;
		int yLo = shadow.yStart() - MARGIN;
		int yHi = shadow.yStart() + shadow.sizeY() + MARGIN;
		int zLo = shadow.zStart() - MARGIN;
		int zHi = shadow.zStart() + shadow.sizeXZ() + MARGIN;
		int diff = 0;
		String first = null;
		for (int ix = 0; ix < c.nsx; ix++) {
			for (int iz = 0; iz < c.nsz; iz++) {
				for (int iy = 0; iy < c.nsy; iy++) {
					LevelChunkSection copy = c.slots[(ix * c.nsz + iz) * c.nsy + iy];
					if (copy == null) continue;
					int sx = c.sx0 + ix;
					int sy = c.sy0 + iy;
					int sz = c.sz0 + iz;
					LevelChunkSection real = level.getChunk(sx, sz).getSection(sy - c.minSectionY);
					for (int x = Math.max(sx << 4, xLo); x <= Math.min((sx << 4) + 15, xHi); x++) {
						for (int y = Math.max(sy << 4, yLo); y <= Math.min((sy << 4) + 15, yHi); y++) {
							for (int z = Math.max(sz << 4, zLo); z <= Math.min((sz << 4) + 15, zHi); z++) {
								BlockState want = real.getBlockState(x & 15, y & 15, z & 15);
								BlockState got = copy.getBlockState(x & 15, y & 15, z & 15);
								if (want != got) {
									diff++;
									if (first == null) first = x + "," + y + "," + z + " vanilla=" + want + " kernel=" + got;
								}
							}
						}
					}
				}
			}
		}
		boolean sameRng = Arrays.equals(rngState(random), shadow.rngEnd());
		if (diff > 0 || placed != shadow.placed() || !sameRng) {
			placeMismatch.increment();
			if (logged.incrementAndGet() <= 8) {
				ExampleMod.LOGGER.warn("[ferrite/ore] MISMATCH doPlace at {}: blocks={} first={} placed vanilla={} kernel={} rng {}",
						origin, diff, first, placed, shadow.placed(), sameRng ? "same" : "differs");
			}
		}
	}

	public static void checkScan(boolean kernel, boolean vanilla, BlockPos origin) {
		scanChecks.increment();
		if (kernel != vanilla) {
			scanMismatch.increment();
			if (logged.incrementAndGet() <= 8) {
				ExampleMod.LOGGER.warn("[ferrite/ore] MISMATCH scan at {}: kernel={} vanilla={}", origin, kernel, vanilla);
			}
		}
	}

	// Xoroshiro {lo, hi} behind a WorldgenRandom, or null for any other source.
	private static long[] rngState(RandomSource random) {
		if (!(random instanceof WorldgenRandom) || WORLDGEN_SOURCE == null || XOROSHIRO_GEN == null
				|| SEED_LO == null || SEED_HI == null) {
			return null;
		}
		try {
			Object source = WORLDGEN_SOURCE.get(random);
			if (!(source instanceof XoroshiroRandomSource)) return null;
			Object gen = XOROSHIRO_GEN.get(source);
			return new long[] { SEED_LO.getLong(gen), SEED_HI.getLong(gen) };
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	private static Field field(Class<?> owner, String name) {
		try {
			Field f = owner.getDeclaredField(name);
			f.setAccessible(true);
			return f;
		} catch (ReflectiveOperationException | RuntimeException e) {
			ExampleMod.LOGGER.warn("[ferrite/ore] {}.{} unreachable: {}", owner.getSimpleName(), name, e.toString());
			return null;
		}
	}

	public static String report() {
		return String.format("[ferrite/ore] mode=%s oracle=1/%d place=%d scanMiss=%d scanFallback=%d kernel=%d"
				+ " zoneFallback=%d overflow=%d reads=%d ruleCalls=%d | oracle: scan %d checks %d mismatch, doPlace %d checks %d mismatch %d skipped",
				modeName(mode), oracleRate, places.sum(), scanMiss.sum(), scanFallback.sum(), kernelRuns.sum(),
				zoneFallback.sum(), overflow.sum(), blockReads.sum(), ruleCalls.sum(), scanChecks.sum(), scanMismatch.sum(),
				placeChecks.sum(), placeMismatch.sum(), placeSkipped.sum());
	}

	public static void reset() {
		places.reset();
		scanMiss.reset();
		scanFallback.reset();
		kernelRuns.reset();
		zoneFallback.reset();
		overflow.reset();
		blockReads.reset();
		ruleCalls.reset();
		scanChecks.reset();
		scanMismatch.reset();
		placeChecks.reset();
		placeMismatch.reset();
		placeSkipped.reset();
		logged.set(0);
	}

	// Sections over the box plus MARGIN, acquired and released like BulkSectionAccess, or copied for the oracle.
	static final class Sections {
		private final WorldGenRegion level;
		private final boolean copy;
		final int minSectionY;
		private final int sectionCount;
		final int sx0;
		final int sy0;
		final int sz0;
		final int nsx;
		final int nsy;
		final int nsz;
		final LevelChunkSection[] slots;
		private final boolean[] resolved;
		private Long2ObjectOpenHashMap<LevelChunkSection> spill;

		Sections(WorldGenRegion level, boolean copy, int xStart, int yStart, int zStart, int sizeXZ, int sizeY) {
			this.level = level;
			this.copy = copy;
			this.minSectionY = level.getMinSectionY();
			this.sectionCount = level.getSectionsCount();
			this.sx0 = (xStart - MARGIN) >> 4;
			this.sy0 = (yStart - MARGIN) >> 4;
			this.sz0 = (zStart - MARGIN) >> 4;
			this.nsx = ((xStart + sizeXZ + MARGIN) >> 4) - this.sx0 + 1;
			this.nsy = ((yStart + sizeY + MARGIN) >> 4) - this.sy0 + 1;
			this.nsz = ((zStart + sizeXZ + MARGIN) >> 4) - this.sz0 + 1;
			this.slots = new LevelChunkSection[this.nsx * this.nsy * this.nsz];
			this.resolved = new boolean[this.slots.length];
		}

		LevelChunkSection get(int x, int y, int z) {
			int ix = (x >> 4) - this.sx0;
			int iy = (y >> 4) - this.sy0;
			int iz = (z >> 4) - this.sz0;
			if (ix < 0 || ix >= this.nsx || iy < 0 || iy >= this.nsy || iz < 0 || iz >= this.nsz) {
				return this.spilled(x >> 4, y >> 4, z >> 4);
			}
			int slot = (ix * this.nsz + iz) * this.nsy + iy;
			if (!this.resolved[slot]) {
				this.slots[slot] = this.resolve(x >> 4, y >> 4, z >> 4);
				this.resolved[slot] = true;
			}
			return this.slots[slot];
		}

		private LevelChunkSection spilled(int sx, int sy, int sz) {
			overflow.increment();
			if (this.spill == null) this.spill = new Long2ObjectOpenHashMap<>();
			long key = SectionPos.asLong(sx, sy, sz);
			if (this.spill.containsKey(key)) return this.spill.get(key);
			LevelChunkSection section = this.resolve(sx, sy, sz);
			this.spill.put(key, section);
			return section;
		}

		private LevelChunkSection resolve(int sx, int sy, int sz) {
			int index = sy - this.minSectionY;
			if (index < 0 || index >= this.sectionCount) return null;
			LevelChunkSection section = this.level.getChunk(sx, sz).getSection(index);
			if (this.copy) return section.copy();
			section.acquire();
			return section;
		}

		// Oracle only: copy the slots the shadow never touched so vanilla's writes there still compare.
		void resolveAll() {
			for (int ix = 0; ix < this.nsx; ix++) {
				for (int iz = 0; iz < this.nsz; iz++) {
					for (int iy = 0; iy < this.nsy; iy++) {
						int slot = (ix * this.nsz + iz) * this.nsy + iy;
						if (!this.resolved[slot]) {
							this.slots[slot] = this.resolve(this.sx0 + ix, this.sy0 + iy, this.sz0 + iz);
							this.resolved[slot] = true;
						}
					}
				}
			}
		}

		void release() {
			if (this.copy) return;
			for (LevelChunkSection section : this.slots) {
				if (section != null) section.release();
			}
			if (this.spill != null) {
				for (LevelChunkSection section : this.spill.values()) {
					if (section != null) section.release();
				}
			}
		}
	}
}
