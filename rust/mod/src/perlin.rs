//! Bit-exact Rust port of vanilla Minecraft 1.21.11's
//! `ImprovedNoise` (single-octave 3D Perlin noise) plus the
//! `SimplexNoise.GRADIENT` table and dot helper that it depends on.
//!
//! Source files (mojmap, in `26.1.2/server/net/minecraft/`):
//!   - `world/level/levelgen/synth/ImprovedNoise.java`
//!   - `world/level/levelgen/synth/SimplexNoise.java`
//!   - `util/Mth.java` (smoothstep, smoothstepDerivative, lerp2, lerp3, floor)
//!
//! Pure math + a permutation table seeded from `XoroshiroRandomSource`.
//! No JNI, no global state. Intent: same seed → same sample, byte-for-byte.
//!
//! # Why this exists
//!
//! `ImprovedNoise` is the primitive every other Minecraft noise wraps.
//! `PerlinNoise` is N octaves of `ImprovedNoise`. `NormalNoise`
//! (`DoublePerlinNoiseSampler` in yarn) is two `PerlinNoise` instances
//! summed at slightly offset frequencies. Climate / surface / density
//! all bottom out here.
//!
//! Once this lands, the surface dispatcher (and any future seed-driven
//! Rust port — see `docs/SEED_DRIVEN_DISPATCH.md`) can sample noise
//! natively rather than reflecting per-column scalars from the JVM.

use crate::xoroshiro::XoroshiroRandomSource;

/// Vanilla `SimplexNoise.GRADIENT` — 16 fixed gradient vectors
/// indexed by `hash & 15`. Used for both ImprovedNoise (3D Perlin)
/// and SimplexNoise corner contributions.
///
/// Note rows 12-15 are intentional duplicates of rows 0/8/9/10/11
/// (vanilla pads the table to 16 entries from Ken Perlin's original
/// 12 so `& 15` is a free mask). Vanilla's `% 12` in `SimplexNoise`
/// avoids the duplicates; ImprovedNoise's `& 15` uses them.
pub const GRADIENT: [[i32; 3]; 16] = [
    [1, 1, 0],
    [-1, 1, 0],
    [1, -1, 0],
    [-1, -1, 0],
    [1, 0, 1],
    [-1, 0, 1],
    [1, 0, -1],
    [-1, 0, -1],
    [0, 1, 1],
    [0, -1, 1],
    [0, 1, -1],
    [0, -1, -1],
    [1, 1, 0],
    [0, -1, 1],
    [-1, 1, 0],
    [0, -1, -1],
];

/// Vanilla `SimplexNoise.dot(int[] g, double x, double y, double z)`.
#[inline]
pub fn grad_dot_raw(g: &[i32; 3], x: f64, y: f64, z: f64) -> f64 {
    g[0] as f64 * x + g[1] as f64 * y + g[2] as f64 * z
}

/// Vanilla `Mth.smoothstep(x) = x^3 * (x * (x * 6 - 15) + 10)`.
#[inline]
pub fn smoothstep(x: f64) -> f64 {
    x * x * x * (x * (x * 6.0 - 15.0) + 10.0)
}

/// Vanilla `Mth.smoothstepDerivative(x) = 30 * x^2 * (x - 1)^2`.
#[inline]
pub fn smoothstep_derivative(x: f64) -> f64 {
    30.0 * x * x * (x - 1.0) * (x - 1.0)
}

/// Vanilla `Mth.lerp(a, lo, hi)` — `lo + a * (hi - lo)`.
#[inline]
pub fn lerp(a: f64, lo: f64, hi: f64) -> f64 {
    lo + a * (hi - lo)
}

/// Vanilla `Mth.lerp2(...)`.
#[inline]
pub fn lerp2(a1: f64, a2: f64, x00: f64, x10: f64, x01: f64, x11: f64) -> f64 {
    lerp(a2, lerp(a1, x00, x10), lerp(a1, x01, x11))
}

/// Vanilla `Mth.lerp3(...)`.
#[inline]
#[allow(clippy::too_many_arguments)]
pub fn lerp3(
    a1: f64, a2: f64, a3: f64,
    x000: f64, x100: f64, x010: f64, x110: f64,
    x001: f64, x101: f64, x011: f64, x111: f64,
) -> f64 {
    lerp(a3, lerp2(a1, a2, x000, x100, x010, x110), lerp2(a1, a2, x001, x101, x011, x111))
}

/// Vanilla `Mth.floor(double)` — `(int) Math.floor(v)`.
/// Saturates on overflow; in practice worldgen inputs stay in range.
#[inline]
pub fn floor_to_i32(v: f64) -> i32 {
    let f = v.floor();
    if f >= i32::MAX as f64 {
        i32::MAX
    } else if f <= i32::MIN as f64 {
        i32::MIN
    } else {
        f as i32
    }
}

// ============================================================================
// SimplexNoise (used by EndIslandDensityFunction only)
// ============================================================================

/// Direct port of vanilla `SimplexNoise` from
/// `world/level/levelgen/synth/SimplexNoise.java`.
/// Initialized from a `LegacyRandomSource` (java.util.Random LCG).
/// Supports both 2D and 3D variants; the only caller in this crate is
/// `EndIslandDensityFunction` which uses the 2D version.
#[derive(Clone, Debug)]
pub struct SimplexNoise {
    pub xo: f64,
    pub yo: f64,
    pub zo: f64,
    /// Permutation of [0, 256), wrapped via `& 0xFF` on access.
    /// Vanilla declares `int[512]` but only fills the first 256; the
    /// extra 256 are zeros that get re-read after the `& 0xFF` mask.
    /// We mirror that exactly with a 256-entry array + the same mask.
    p: [i32; 256],
}

const SIMPLEX_F2: f64 = 0.5 * (1.732_050_807_568_877_2 - 1.0); // 0.5 * (sqrt(3) - 1)
const SIMPLEX_G2: f64 = (3.0 - 1.732_050_807_568_877_2) / 6.0;

impl SimplexNoise {
    pub fn new(random: &mut crate::xoroshiro::LegacyRandomSource) -> Self {
        let xo = random.next_double() * 256.0;
        let yo = random.next_double() * 256.0;
        let zo = random.next_double() * 256.0;
        let mut p = [0_i32; 256];
        for i in 0..256 { p[i] = i as i32; }
        for ix in 0..256 {
            let offset = random.next_int_bound((256 - ix) as i32) as usize;
            p.swap(ix, offset + ix);
        }
        Self { xo, yo, zo, p }
    }

    #[inline]
    fn p(&self, x: i32) -> i32 {
        self.p[(x as usize) & 0xFF]
    }

    #[inline]
    fn dot2(g: [i32; 3], x: f64, y: f64, z: f64) -> f64 {
        (g[0] as f64) * x + (g[1] as f64) * y + (g[2] as f64) * z
    }

    #[inline]
    fn corner3d(&self, index: i32, x: f64, y: f64, z: f64, base: f64) -> f64 {
        let mut t = base - x * x - y * y - z * z;
        if t < 0.0 { return 0.0; }
        t *= t;
        let g = SIMPLEX_GRADIENT[(index as usize) & 0xF];
        t * t * Self::dot2(g, x, y, z)
    }

    /// Vanilla `getValue(double, double)` — 2D simplex noise.
    pub fn get_value_2d(&self, xin: f64, yin: f64) -> f64 {
        let s = (xin + yin) * SIMPLEX_F2;
        let i = floor_to_i32(xin + s);
        let j = floor_to_i32(yin + s);
        let t = ((i + j) as f64) * SIMPLEX_G2;
        let x0 = xin - (i as f64 - t);
        let y0 = yin - (j as f64 - t);
        let (i1, j1) = if x0 > y0 { (1, 0) } else { (0, 1) };
        let x1 = x0 - i1 as f64 + SIMPLEX_G2;
        let y1 = y0 - j1 as f64 + SIMPLEX_G2;
        let x2 = x0 - 1.0 + 2.0 * SIMPLEX_G2;
        let y2 = y0 - 1.0 + 2.0 * SIMPLEX_G2;
        let ii = i & 0xFF;
        let jj = j & 0xFF;
        let gi0 = (self.p(ii + self.p(jj))).rem_euclid(12);
        let gi1 = (self.p(ii + i1 + self.p(jj + j1))).rem_euclid(12);
        let gi2 = (self.p(ii + 1 + self.p(jj + 1))).rem_euclid(12);
        let n0 = self.corner3d(gi0, x0, y0, 0.0, 0.5);
        let n1 = self.corner3d(gi1, x1, y1, 0.0, 0.5);
        let n2 = self.corner3d(gi2, x2, y2, 0.0, 0.5);
        70.0 * (n0 + n1 + n2)
    }
}

/// Vanilla `SimplexNoise.GRADIENT` — same as the 16-row table used by
/// `ImprovedNoise` but extended with `% 12` indexing in the simplex
/// path.  Duplicated here as a self-contained `[[i32; 3]; 16]` for
/// convenience; values match `world/level/levelgen/synth/SimplexNoise.java`.
const SIMPLEX_GRADIENT: [[i32; 3]; 16] = [
    [1, 1, 0],   [-1, 1, 0],  [1, -1, 0],  [-1, -1, 0],
    [1, 0, 1],   [-1, 0, 1],  [1, 0, -1],  [-1, 0, -1],
    [0, 1, 1],   [0, -1, 1],  [0, 1, -1],  [0, -1, -1],
    [1, 1, 0],   [0, -1, 1],  [-1, 1, 0],  [0, -1, -1],
];

// ============================================================================
// ImprovedNoise
// ============================================================================

/// Direct port of vanilla `ImprovedNoise` (single-octave 3D Perlin).
///
/// Construction consumes 3 doubles + 256 bounded ints from the supplied
/// `XoroshiroRandomSource` in exactly the same order vanilla does, so
/// the resulting `(xo, yo, zo, p)` state is identical.
#[derive(Clone, Debug)]
pub struct ImprovedNoise {
    pub xo: f64,
    pub yo: f64,
    pub zo: f64,
    /// Permutation table — vanilla stores as `byte[]` (signed); we store
    /// as `u8` and mask on read, which gives the same `& 0xFF` semantics
    /// vanilla relies on.
    p: [u8; 256],
}

impl ImprovedNoise {
    /// Construct from a `XoroshiroRandomSource`, consuming PRNG values
    /// in vanilla's exact order:
    ///   1. `xo = nextDouble() * 256`
    ///   2. `yo = nextDouble() * 256`
    ///   3. `zo = nextDouble() * 256`
    ///   4. Identity-init `p[0..256]`
    ///   5. For i in 0..256: swap `p[i]` with `p[i + nextInt(256 - i)]`
    pub fn new(random: &mut XoroshiroRandomSource) -> Self {
        let xo = random.next_double() * 256.0;
        let yo = random.next_double() * 256.0;
        let zo = random.next_double() * 256.0;
        let mut p = [0u8; 256];
        for i in 0..256 {
            p[i] = i as u8;
        }
        for i in 0..256 {
            let bound = 256 - i as i32;
            let offset = random.next_int_bounded(bound) as usize;
            p.swap(i, i + offset);
        }
        Self { xo, yo, zo, p }
    }

    /// Vanilla `p(int x) = this.p[x & 0xFF] & 0xFF`.
    #[inline]
    fn p(&self, x: i32) -> i32 {
        self.p[(x & 0xFF) as usize] as i32
    }

    /// Vanilla `noise(x, y, z)` — calls the deprecated form with
    /// `yScale = 0`, `yFudge = 0`.
    #[inline]
    pub fn noise(&self, x: f64, y: f64, z: f64) -> f64 {
        self.noise_with_yfudge(x, y, z, 0.0, 0.0)
    }

    /// Vanilla `noise(x, y, z, yScale, yFudge)` — the @Deprecated form.
    /// `NormalNoise` calls this with non-zero yScale for slope-aware
    /// Y quantization.
    pub fn noise_with_yfudge(&self, x: f64, y: f64, z: f64, y_scale: f64, y_fudge: f64) -> f64 {
        let x = x + self.xo;
        let y = y + self.yo;
        let z = z + self.zo;
        let xf = floor_to_i32(x);
        let yf = floor_to_i32(y);
        let zf = floor_to_i32(z);
        let xr = x - xf as f64;
        let yr = y - yf as f64;
        let zr = z - zf as f64;
        let yr_fudge = if y_scale != 0.0 {
            // Vanilla: `fudgeLimit = (yFudge >= 0 && yFudge < yr) ? yFudge : yr`.
            let fudge_limit = if y_fudge >= 0.0 && y_fudge < yr { y_fudge } else { yr };
            // Vanilla casts the literal float `1.0E-7F` to double here;
            // mirror that exactly via `f32 -> f64`.
            let eps = 1.0e-7_f32 as f64;
            (floor_to_i32(fudge_limit / y_scale + eps) as f64) * y_scale
        } else {
            0.0
        };
        self.sample_and_lerp(xf, yf, zf, xr, yr - yr_fudge, zr, yr)
    }

    /// Vanilla `noiseWithDerivative(x, y, z, derivativeOut)`.
    /// `derivativeOut` is a 3-element array that vanilla *adds* into
    /// (uses `+=`, not assignment) — same semantics here.
    pub fn noise_with_derivative(&self, x: f64, y: f64, z: f64, derivative_out: &mut [f64; 3]) -> f64 {
        let x = x + self.xo;
        let y = y + self.yo;
        let z = z + self.zo;
        let xf = floor_to_i32(x);
        let yf = floor_to_i32(y);
        let zf = floor_to_i32(z);
        let xr = x - xf as f64;
        let yr = y - yf as f64;
        let zr = z - zf as f64;
        self.sample_with_derivative(xf, yf, zf, xr, yr, zr, derivative_out)
    }

    #[inline]
    fn grad_dot(hash: i32, x: f64, y: f64, z: f64) -> f64 {
        grad_dot_raw(&GRADIENT[(hash & 15) as usize], x, y, z)
    }

    #[allow(clippy::too_many_arguments)]
    fn sample_and_lerp(
        &self,
        x: i32, y: i32, z: i32,
        xr: f64, yr: f64, zr: f64,
        yr_original: f64,
    ) -> f64 {
        let x0 = self.p(x);
        let x1 = self.p(x + 1);
        let xy00 = self.p(x0 + y);
        let xy01 = self.p(x0 + y + 1);
        let xy10 = self.p(x1 + y);
        let xy11 = self.p(x1 + y + 1);
        let d000 = Self::grad_dot(self.p(xy00 + z), xr, yr, zr);
        let d100 = Self::grad_dot(self.p(xy10 + z), xr - 1.0, yr, zr);
        let d010 = Self::grad_dot(self.p(xy01 + z), xr, yr - 1.0, zr);
        let d110 = Self::grad_dot(self.p(xy11 + z), xr - 1.0, yr - 1.0, zr);
        let d001 = Self::grad_dot(self.p(xy00 + z + 1), xr, yr, zr - 1.0);
        let d101 = Self::grad_dot(self.p(xy10 + z + 1), xr - 1.0, yr, zr - 1.0);
        let d011 = Self::grad_dot(self.p(xy01 + z + 1), xr, yr - 1.0, zr - 1.0);
        let d111 = Self::grad_dot(self.p(xy11 + z + 1), xr - 1.0, yr - 1.0, zr - 1.0);
        let xa = smoothstep(xr);
        let ya = smoothstep(yr_original);
        let za = smoothstep(zr);
        lerp3(xa, ya, za, d000, d100, d010, d110, d001, d101, d011, d111)
    }

    #[allow(clippy::too_many_arguments)]
    fn sample_with_derivative(
        &self,
        x: i32, y: i32, z: i32,
        xr: f64, yr: f64, zr: f64,
        derivative_out: &mut [f64; 3],
    ) -> f64 {
        let x0 = self.p(x);
        let x1 = self.p(x + 1);
        let xy00 = self.p(x0 + y);
        let xy01 = self.p(x0 + y + 1);
        let xy10 = self.p(x1 + y);
        let xy11 = self.p(x1 + y + 1);
        let p000 = self.p(xy00 + z);
        let p100 = self.p(xy10 + z);
        let p010 = self.p(xy01 + z);
        let p110 = self.p(xy11 + z);
        let p001 = self.p(xy00 + z + 1);
        let p101 = self.p(xy10 + z + 1);
        let p011 = self.p(xy01 + z + 1);
        let p111 = self.p(xy11 + z + 1);
        let g000 = &GRADIENT[(p000 & 15) as usize];
        let g100 = &GRADIENT[(p100 & 15) as usize];
        let g010 = &GRADIENT[(p010 & 15) as usize];
        let g110 = &GRADIENT[(p110 & 15) as usize];
        let g001 = &GRADIENT[(p001 & 15) as usize];
        let g101 = &GRADIENT[(p101 & 15) as usize];
        let g011 = &GRADIENT[(p011 & 15) as usize];
        let g111 = &GRADIENT[(p111 & 15) as usize];
        let d000 = grad_dot_raw(g000, xr, yr, zr);
        let d100 = grad_dot_raw(g100, xr - 1.0, yr, zr);
        let d010 = grad_dot_raw(g010, xr, yr - 1.0, zr);
        let d110 = grad_dot_raw(g110, xr - 1.0, yr - 1.0, zr);
        let d001 = grad_dot_raw(g001, xr, yr, zr - 1.0);
        let d101 = grad_dot_raw(g101, xr - 1.0, yr, zr - 1.0);
        let d011 = grad_dot_raw(g011, xr, yr - 1.0, zr - 1.0);
        let d111 = grad_dot_raw(g111, xr - 1.0, yr - 1.0, zr - 1.0);
        let xa = smoothstep(xr);
        let ya = smoothstep(yr);
        let za = smoothstep(zr);
        let d1x = lerp3(xa, ya, za,
            g000[0] as f64, g100[0] as f64, g010[0] as f64, g110[0] as f64,
            g001[0] as f64, g101[0] as f64, g011[0] as f64, g111[0] as f64);
        let d1y = lerp3(xa, ya, za,
            g000[1] as f64, g100[1] as f64, g010[1] as f64, g110[1] as f64,
            g001[1] as f64, g101[1] as f64, g011[1] as f64, g111[1] as f64);
        let d1z = lerp3(xa, ya, za,
            g000[2] as f64, g100[2] as f64, g010[2] as f64, g110[2] as f64,
            g001[2] as f64, g101[2] as f64, g011[2] as f64, g111[2] as f64);
        let d2x = lerp2(ya, za, d100 - d000, d110 - d010, d101 - d001, d111 - d011);
        let d2y = lerp2(za, xa, d010 - d000, d011 - d001, d110 - d100, d111 - d101);
        let d2z = lerp2(xa, ya, d001 - d000, d101 - d100, d011 - d010, d111 - d110);
        let xsd = smoothstep_derivative(xr);
        let ysd = smoothstep_derivative(yr);
        let zsd = smoothstep_derivative(zr);
        derivative_out[0] += d1x + xsd * d2x;
        derivative_out[1] += d1y + ysd * d2y;
        derivative_out[2] += d1z + zsd * d2z;
        lerp3(xa, ya, za, d000, d100, d010, d110, d001, d101, d011, d111)
    }

    /// Read-only access to the permutation table — for parity dumps
    /// against vanilla's `parityConfigString`.
    pub fn permutation_table(&self) -> &[u8; 256] {
        &self.p
    }
}

// ============================================================================
// PerlinNoise — N-octave wrapper around ImprovedNoise
// ============================================================================

/// Vanilla `PerlinNoise.wrap(x) = x - lfloor(x / 3.3554432E7 + 0.5) * 3.3554432E7`.
/// Divisor is `2 * ROUND_OFF` where `ROUND_OFF = 1 << 25 = 33554432`.
/// Snaps coordinates onto a band wide enough to avoid float-precision
/// loss in the gradient index lookups at large world coordinates.
#[inline]
pub fn wrap(x: f64) -> f64 {
    let div = 3.3554432e7_f64;
    x - (x / div + 0.5).floor() * div
}

/// Direct port of vanilla `PerlinNoise` — N-octave fractal Perlin.
///
/// `noise_levels[i]` is `None` if its amplitude is 0 (vanilla skips
/// constructing the `ImprovedNoise` in that case to save random draws,
/// modulo the legacy-init `skipOctave` accounting).
#[derive(Debug)]
pub struct PerlinNoise {
    pub first_octave: i32,
    pub amplitudes: Vec<f64>,
    pub noise_levels: Vec<Option<ImprovedNoise>>,
    pub lowest_freq_input_factor: f64,
    pub lowest_freq_value_factor: f64,
    pub max_value: f64,
}

impl PerlinNoise {
    /// Vanilla `PerlinNoise.makeAmplitudes(IntSortedSet)`:
    /// builds a contiguous amplitude vector covering
    /// `[firstOctave, lastOctave]` with `1.0` at every octave in the
    /// set and `0.0` elsewhere.
    pub fn make_amplitudes(octave_set: &[i32]) -> (i32, Vec<f64>) {
        assert!(!octave_set.is_empty(), "Need some octaves!");
        let mut sorted: Vec<i32> = octave_set.to_vec();
        sorted.sort_unstable();
        sorted.dedup();
        let low_freq_octaves = -sorted[0];
        let high_freq_octaves = *sorted.last().unwrap();
        let octaves = low_freq_octaves + high_freq_octaves + 1;
        assert!(octaves >= 1, "Total number of octaves needs to be >= 1");
        let mut amplitudes = vec![0.0; octaves as usize];
        for &o in &sorted {
            amplitudes[(o + low_freq_octaves) as usize] = 1.0;
        }
        (-low_freq_octaves, amplitudes)
    }

    /// Vanilla `PerlinNoise.create(random, octaves)` — the main path
    /// `Noises` uses. Builds amplitudes from the octave set, then
    /// initializes each octave via `forkPositional().fromHashOf("octave_" + i)`.
    pub fn create_from_octaves(random: &mut XoroshiroRandomSource, octave_set: &[i32]) -> Self {
        let (first_octave, amplitudes) = Self::make_amplitudes(octave_set);
        Self::new(random, first_octave, amplitudes, true)
    }

    /// Vanilla `PerlinNoise.create(random, firstOctave, amplitudes)` —
    /// the explicit-amplitudes form (used by `NormalNoise` /
    /// `NoiseParameters`).
    pub fn create_from_first_octave_and_amplitudes(
        random: &mut XoroshiroRandomSource,
        first_octave: i32,
        amplitudes: Vec<f64>,
    ) -> Self {
        Self::new(random, first_octave, amplitudes, true)
    }

    /// Vanilla `createLegacyForBlendedNoise(random, octaves)` —
    /// `useNewInitialization=false`. Used by `BlendedNoise` for
    /// vanilla-compatible legacy worldgen.
    pub fn create_legacy_for_blended_noise(
        random: &mut XoroshiroRandomSource,
        octave_set: &[i32],
    ) -> Self {
        let (first_octave, amplitudes) = Self::make_amplitudes(octave_set);
        Self::new(random, first_octave, amplitudes, false)
    }

    /// Vanilla protected constructor `PerlinNoise(random, Pair, useNewInitialization)`.
    pub fn new(
        random: &mut XoroshiroRandomSource,
        first_octave: i32,
        amplitudes: Vec<f64>,
        use_new_initialization: bool,
    ) -> Self {
        let octaves = amplitudes.len();
        let zero_octave_index = -first_octave;
        let mut noise_levels: Vec<Option<ImprovedNoise>> = (0..octaves).map(|_| None).collect();

        if use_new_initialization {
            let positional = random.fork_positional();
            for i in 0..octaves {
                if amplitudes[i] != 0.0 {
                    let octave = first_octave + i as i32;
                    let name = format!("octave_{}", octave);
                    let mut child = positional.from_hash_of(&name);
                    noise_levels[i] = Some(ImprovedNoise::new(&mut child));
                }
            }
        } else {
            // Legacy path: build the zero-octave first, then walk down to
            // octave 0, consuming `skipOctave` (262 longs) for each absent
            // slot to keep the PRNG stream aligned with vanilla.
            let zero_octave = ImprovedNoise::new(random);
            let octaves_i32 = octaves as i32;
            if zero_octave_index >= 0 && zero_octave_index < octaves_i32 {
                let zero_amp = amplitudes[zero_octave_index as usize];
                if zero_amp != 0.0 {
                    noise_levels[zero_octave_index as usize] = Some(zero_octave);
                }
            }
            let mut ix = zero_octave_index - 1;
            while ix >= 0 {
                if ix < octaves_i32 {
                    let amp = amplitudes[ix as usize];
                    if amp != 0.0 {
                        noise_levels[ix as usize] = Some(ImprovedNoise::new(random));
                    } else {
                        Self::skip_octave(random);
                    }
                } else {
                    Self::skip_octave(random);
                }
                ix -= 1;
            }
            assert!(
                zero_octave_index >= octaves_i32 - 1,
                "Positive octaves are temporarily disabled"
            );
        }

        let lowest_freq_input_factor = 2.0_f64.powi(-zero_octave_index);
        let lowest_freq_value_factor =
            2.0_f64.powi(octaves as i32 - 1) / (2.0_f64.powi(octaves as i32) - 1.0);

        let mut this = Self {
            first_octave,
            amplitudes,
            noise_levels,
            lowest_freq_input_factor,
            lowest_freq_value_factor,
            max_value: 0.0,
        };
        this.max_value = this.edge_value(2.0);
        this
    }

    #[inline]
    fn skip_octave(random: &mut XoroshiroRandomSource) {
        random.consume_count(262);
    }

    /// Vanilla `getValue(x, y, z)`.
    #[inline]
    pub fn get_value(&self, x: f64, y: f64, z: f64) -> f64 {
        self.get_value_with_yfudge(x, y, z, 0.0, 0.0)
    }

    /// Vanilla `getValue(x, y, z, yScale, yFudge)` — the @Deprecated form.
    /// `NormalNoise` calls this with `yScale = 0` (so it just folds back
    /// to the simple form), but blended noise uses non-zero values.
    pub fn get_value_with_yfudge(
        &self,
        x: f64, y: f64, z: f64,
        y_scale: f64, y_fudge: f64,
    ) -> f64 {
        let mut value = 0.0;
        let mut factor = self.lowest_freq_input_factor;
        let mut value_factor = self.lowest_freq_value_factor;
        for (i, slot) in self.noise_levels.iter().enumerate() {
            if let Some(noise) = slot {
                let n = noise.noise_with_yfudge(
                    wrap(x * factor),
                    wrap(y * factor),
                    wrap(z * factor),
                    y_scale * factor,
                    y_fudge * factor,
                );
                value += self.amplitudes[i] * n * value_factor;
            }
            factor *= 2.0;
            value_factor /= 2.0;
        }
        value
    }

    /// Vanilla `maxValue()` — precomputed peak amplitude.
    #[inline]
    pub fn max_value(&self) -> f64 {
        self.max_value
    }

    /// Vanilla `maxBrokenValue(yScale) = edgeValue(yScale + 2.0)`.
    #[inline]
    pub fn max_broken_value(&self, y_scale: f64) -> f64 {
        self.edge_value(y_scale + 2.0)
    }

    fn edge_value(&self, noise_value: f64) -> f64 {
        let mut value = 0.0;
        let mut value_factor = self.lowest_freq_value_factor;
        for (i, slot) in self.noise_levels.iter().enumerate() {
            if slot.is_some() {
                value += self.amplitudes[i] * noise_value * value_factor;
            }
            value_factor /= 2.0;
        }
        value
    }

    /// Vanilla `getOctaveNoise(i)` — indexed from highest-frequency
    /// (smallest-scale) end. Returns `None` if that octave was elided.
    pub fn get_octave_noise(&self, i: usize) -> Option<&ImprovedNoise> {
        let idx = self.noise_levels.len() - 1 - i;
        self.noise_levels[idx].as_ref()
    }
}

// ============================================================================
// NormalNoise — two PerlinNoise instances at offset frequencies
// (mojmap `NormalNoise`, yarn `DoublePerlinNoiseSampler`)
// ============================================================================

/// Vanilla `NormalNoise.INPUT_FACTOR` — the second sampler's frequency
/// is offset by this multiplier so the two octave stacks alias slightly
/// out of phase, suppressing zero-crossings at the gradient lattice.
const INPUT_FACTOR: f64 = 1.0181268882175227;

/// Vanilla `NormalNoise.expectedDeviation(octaveSpan)`.
#[inline]
fn expected_deviation(octave_span: i32) -> f64 {
    0.1 * (1.0 + 1.0 / (octave_span as f64 + 1.0))
}

/// `NormalNoise.NoiseParameters` — the (firstOctave, amplitudes) tuple
/// every named noise channel is configured with in `Noises`.
#[derive(Clone, Debug)]
pub struct NoiseParameters {
    pub first_octave: i32,
    pub amplitudes: Vec<f64>,
}

impl NoiseParameters {
    pub fn new(first_octave: i32, amplitudes: Vec<f64>) -> Self {
        Self { first_octave, amplitudes }
    }
}

/// Direct port of vanilla `NormalNoise` (yarn `DoublePerlinNoiseSampler`).
/// Two `PerlinNoise` instances sampled at frequencies offset by
/// `INPUT_FACTOR`, summed and rescaled to unit-ish output.
pub struct NormalNoise {
    pub first: PerlinNoise,
    pub second: PerlinNoise,
    pub value_factor: f64,
    pub max_value: f64,
    pub parameters: NoiseParameters,
}

impl NormalNoise {
    /// Vanilla `NormalNoise.create(random, parameters)` — the production
    /// path (`useNewInitialization=true`). Each `PerlinNoise.create`
    /// internally calls `random.forkPositional()`, so the two children
    /// receive distinct positional factories drawn from the same root.
    pub fn create(random: &mut XoroshiroRandomSource, parameters: NoiseParameters) -> Self {
        Self::new(random, parameters, true)
    }

    /// Vanilla `createLegacyNetherBiome(random, parameters)` —
    /// `useNewInitialization=false`. Used only by the legacy nether
    /// biome generator.
    pub fn create_legacy_nether_biome(
        random: &mut XoroshiroRandomSource,
        parameters: NoiseParameters,
    ) -> Self {
        Self::new(random, parameters, false)
    }

    fn new(
        random: &mut XoroshiroRandomSource,
        parameters: NoiseParameters,
        use_new_initialization: bool,
    ) -> Self {
        let first_octave = parameters.first_octave;
        let amplitudes = parameters.amplitudes.clone();

        let (first, second) = if use_new_initialization {
            let f = PerlinNoise::create_from_first_octave_and_amplitudes(
                random, first_octave, amplitudes.clone(),
            );
            let s = PerlinNoise::create_from_first_octave_and_amplitudes(
                random, first_octave, amplitudes.clone(),
            );
            (f, s)
        } else {
            // Both children take the deprecated explicit-amplitudes
            // path with useNewInitialization=false.
            let f = PerlinNoise::new(random, first_octave, amplitudes.clone(), false);
            let s = PerlinNoise::new(random, first_octave, amplitudes.clone(), false);
            (f, s)
        };

        // octave span = max_index_with_nonzero_amp - min_index_with_nonzero_amp
        let mut min_octave = i32::MAX;
        let mut max_octave = i32::MIN;
        for (i, &a) in amplitudes.iter().enumerate() {
            if a != 0.0 {
                let i = i as i32;
                if i < min_octave { min_octave = i; }
                if i > max_octave { max_octave = i; }
            }
        }
        // If all amplitudes are zero, vanilla's max - min underflows from
        // (MIN_VALUE - MAX_VALUE); we mirror by leaving it as a sentinel
        // rather than guarding (real Noises configs always have at least
        // one non-zero amplitude).
        let octave_span = max_octave.wrapping_sub(min_octave);
        let value_factor = (1.0 / 6.0) / expected_deviation(octave_span);
        let max_value = (first.max_value() + second.max_value()) * value_factor;

        Self { first, second, value_factor, max_value, parameters }
    }

    /// Vanilla `getValue(x, y, z)`.
    #[inline]
    pub fn get_value(&self, x: f64, y: f64, z: f64) -> f64 {
        let x2 = x * INPUT_FACTOR;
        let y2 = y * INPUT_FACTOR;
        let z2 = z * INPUT_FACTOR;
        (self.first.get_value(x, y, z) + self.second.get_value(x2, y2, z2)) * self.value_factor
    }

    #[inline]
    pub fn max_value(&self) -> f64 {
        self.max_value
    }
}

// ============================================================================
// BlendedNoise — vanilla's main 3D terrain density (yarn InterpolatedNoiseSampler)
// ============================================================================

/// Direct port of vanilla `BlendedNoise` (yarn `InterpolatedNoiseSampler`).
///
/// <p>Three legacy-Perlin chains drive the sample:
/// - `min_limit_noise` — 16 octaves over `(-15..=0)`
/// - `max_limit_noise` — 16 octaves over `(-15..=0)`
/// - `main_noise`      — 8 octaves over `(-7..=0)`
///
/// The chains are seeded sequentially from the same `XoroshiroRandomSource`,
/// so order is significant. `compute(x, y, z)` interpolates between the
/// min and max noise sums by a factor derived from the main noise.
///
/// Constructor parameters mirror vanilla's record:
/// - `xz_scale` / `y_scale` — multiplied by `684.412` to get the limit
///   sample multipliers.
/// - `xz_factor` / `y_factor` — the main noise samples at limit
///   coords divided by these.
/// - `smear_scale_multiplier` — y-scale smearing factor for
///   `ImprovedNoise.noise(x,y,z, yScale, yLimit)` calls.
#[derive(Debug)]
pub struct BlendedNoise {
    pub min_limit: PerlinNoise,
    pub max_limit: PerlinNoise,
    pub main: PerlinNoise,
    xz_multiplier: f64,
    y_multiplier: f64,
    xz_factor: f64,
    y_factor: f64,
    smear_scale_multiplier: f64,
    pub max_value: f64,
}

impl BlendedNoise {
    /// Vanilla `new BlendedNoise(random, xzScale, yScale, xzFactor, yFactor, smearScaleMultiplier)` —
    /// the public constructor that consumes a `XoroshiroRandomSource`
    /// to seed the three legacy-Perlin chains in order.
    pub fn new(
        random: &mut XoroshiroRandomSource,
        xz_scale: f64,
        y_scale: f64,
        xz_factor: f64,
        y_factor: f64,
        smear_scale_multiplier: f64,
    ) -> Self {
        // Vanilla `IntStream.rangeClosed(-15, 0)` → 16 octaves.
        let limit_octaves: Vec<i32> = (-15..=0).collect();
        // Vanilla `IntStream.rangeClosed(-7, 0)` → 8 octaves.
        let main_octaves: Vec<i32> = (-7..=0).collect();

        let min_limit = PerlinNoise::create_legacy_for_blended_noise(random, &limit_octaves);
        let max_limit = PerlinNoise::create_legacy_for_blended_noise(random, &limit_octaves);
        let main = PerlinNoise::create_legacy_for_blended_noise(random, &main_octaves);

        let xz_multiplier = 684.412 * xz_scale;
        let y_multiplier = 684.412 * y_scale;
        let max_value = min_limit.max_broken_value(y_multiplier);

        Self {
            min_limit,
            max_limit,
            main,
            xz_multiplier,
            y_multiplier,
            xz_factor,
            y_factor,
            smear_scale_multiplier,
            max_value,
        }
    }

    /// Vanilla `compute(FunctionContext)` — samples at integer block
    /// coords. Returns the interpolated terrain density.
    ///
    /// Block coords are passed as `f64` so the caller can pre-cast
    /// (vanilla casts `(double) blockX` etc. before multiplying).
    pub fn sample(&self, x: f64, y: f64, z: f64) -> f64 {
        let limit_x = x * self.xz_multiplier;
        let limit_y = y * self.y_multiplier;
        let limit_z = z * self.xz_multiplier;
        let main_x = limit_x / self.xz_factor;
        let main_y = limit_y / self.y_factor;
        let main_z = limit_z / self.xz_factor;
        let limit_smear = self.y_multiplier * self.smear_scale_multiplier;
        let main_smear = limit_smear / self.y_factor;

        // Pass 1: 8 octaves of mainNoise to compute the blend factor.
        let mut main_noise_value = 0.0_f64;
        let mut pow = 1.0_f64;
        for i in 0..8 {
            if let Some(noise) = self.main.get_octave_noise(i) {
                main_noise_value += noise.noise_with_yfudge(
                    wrap(main_x * pow),
                    wrap(main_y * pow),
                    wrap(main_z * pow),
                    main_smear * pow,
                    main_y * pow,
                ) / pow;
            }
            pow /= 2.0;
        }

        let factor = (main_noise_value / 10.0 + 1.0) / 2.0;
        let is_max = factor >= 1.0;
        let is_min = factor <= 0.0;

        // Pass 2: 16 octaves each of min/max limit noises, skipping the
        // chain that won't contribute (when factor is at an extreme).
        let mut blend_min = 0.0_f64;
        let mut blend_max = 0.0_f64;
        let mut pow = 1.0_f64;
        for i in 0..16 {
            let wx = wrap(limit_x * pow);
            let wy = wrap(limit_y * pow);
            let wz = wrap(limit_z * pow);
            let y_scale_pow = limit_smear * pow;
            if !is_max {
                if let Some(min_noise) = self.min_limit.get_octave_noise(i) {
                    blend_min +=
                        min_noise.noise_with_yfudge(wx, wy, wz, y_scale_pow, limit_y * pow) / pow;
                }
            }
            if !is_min {
                if let Some(max_noise) = self.max_limit.get_octave_noise(i) {
                    blend_max +=
                        max_noise.noise_with_yfudge(wx, wy, wz, y_scale_pow, limit_y * pow) / pow;
                }
            }
            pow /= 2.0;
        }

        clamped_lerp(factor, blend_min / 512.0, blend_max / 512.0) / 128.0
    }
}

/// Vanilla `Mth.clampedLerp(t, lo, hi)` — t outside `[0,1]` returns the
/// nearest endpoint instead of extrapolating.
#[inline]
pub fn clamped_lerp(t: f64, lo: f64, hi: f64) -> f64 {
    if t < 0.0 {
        lo
    } else if t > 1.0 {
        hi
    } else {
        lerp(t, lo, hi)
    }
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::xoroshiro::XoroshiroRandomSource;

    /// smoothstep boundary values: 0 → 0, 0.5 → 0.5, 1 → 1.
    #[test]
    fn smoothstep_endpoints() {
        assert_eq!(smoothstep(0.0), 0.0);
        assert!((smoothstep(0.5) - 0.5).abs() < 1e-12);
        assert_eq!(smoothstep(1.0), 1.0);
    }

    /// smoothstepDerivative endpoints are 0 (zero slope at both ends).
    #[test]
    fn smoothstep_derivative_endpoints() {
        assert_eq!(smoothstep_derivative(0.0), 0.0);
        assert_eq!(smoothstep_derivative(1.0), 0.0);
        // Peak at x=0.5: 30 * 0.25 * 0.25 = 1.875
        assert!((smoothstep_derivative(0.5) - 1.875).abs() < 1e-12);
    }

    /// lerp / lerp2 / lerp3 sanity at endpoints.
    #[test]
    fn lerp_endpoints() {
        assert_eq!(lerp(0.0, 3.0, 7.0), 3.0);
        assert_eq!(lerp(1.0, 3.0, 7.0), 7.0);
        assert_eq!(lerp(0.5, 3.0, 7.0), 5.0);
    }

    /// Vanilla `Mth.floor((double) -0.5) == -1`.
    #[test]
    fn floor_negative_half() {
        assert_eq!(floor_to_i32(-0.5), -1);
        assert_eq!(floor_to_i32(0.5), 0);
        assert_eq!(floor_to_i32(-1.0), -1);
    }

    /// GRADIENT table size and a couple known entries (from vanilla).
    #[test]
    fn gradient_table() {
        assert_eq!(GRADIENT.len(), 16);
        assert_eq!(GRADIENT[0], [1, 1, 0]);
        assert_eq!(GRADIENT[15], [0, -1, -1]);
        // Row 12 is intentional duplicate of row 0
        assert_eq!(GRADIENT[12], GRADIENT[0]);
    }

    /// grad_dot_raw: hand-checkable.
    #[test]
    fn grad_dot_basic() {
        // GRADIENT[0] = (1, 1, 0); dot with (1, 2, 5) = 1 + 2 + 0 = 3
        assert_eq!(grad_dot_raw(&GRADIENT[0], 1.0, 2.0, 5.0), 3.0);
        // GRADIENT[3] = (-1, -1, 0); dot with (2, 3, 100) = -2 -3 + 0 = -5
        assert_eq!(grad_dot_raw(&GRADIENT[3], 2.0, 3.0, 100.0), -5.0);
    }

    /// Permutation table after construction is a permutation of 0..256
    /// (each byte present exactly once — Fisher-Yates invariant).
    #[test]
    fn permutation_is_a_bijection() {
        let mut r = XoroshiroRandomSource::from_legacy_seed(0xCAFEBABE);
        let n = ImprovedNoise::new(&mut r);
        let p = n.permutation_table();
        let mut seen = [false; 256];
        for &b in p.iter() {
            assert!(!seen[b as usize], "duplicate byte {} in permutation", b);
            seen[b as usize] = true;
        }
        assert!(seen.iter().all(|&s| s));
    }

    /// Construction is deterministic for a given seed.
    #[test]
    fn construction_is_deterministic() {
        let mut r1 = XoroshiroRandomSource::from_legacy_seed(42);
        let mut r2 = XoroshiroRandomSource::from_legacy_seed(42);
        let n1 = ImprovedNoise::new(&mut r1);
        let n2 = ImprovedNoise::new(&mut r2);
        assert_eq!(n1.xo, n2.xo);
        assert_eq!(n1.yo, n2.yo);
        assert_eq!(n1.zo, n2.zo);
        assert_eq!(n1.permutation_table(), n2.permutation_table());
    }

    /// xo, yo, zo are in [0, 256) per `nextDouble() * 256`.
    #[test]
    fn offsets_in_range() {
        let mut r = XoroshiroRandomSource::from_legacy_seed(7);
        let n = ImprovedNoise::new(&mut r);
        for v in [n.xo, n.yo, n.zo] {
            assert!((0.0..256.0).contains(&v), "offset {} out of [0, 256)", v);
        }
    }

    /// Sampling at the same point twice gives the same value.
    #[test]
    fn noise_is_deterministic() {
        let mut r = XoroshiroRandomSource::from_legacy_seed(99);
        let n = ImprovedNoise::new(&mut r);
        let a = n.noise(1.5, 2.5, 3.5);
        let b = n.noise(1.5, 2.5, 3.5);
        assert_eq!(a, b);
    }

    /// noise output is bounded — Perlin in [-1, 1] roughly. We give
    /// generous slack since the exact bound is sqrt(N/4) but vanilla's
    /// gradient table puts magnitudes well under 2.
    #[test]
    fn noise_is_bounded() {
        let mut r = XoroshiroRandomSource::from_legacy_seed(2026);
        let n = ImprovedNoise::new(&mut r);
        for x in -10..10 {
            for z in -10..10 {
                let v = n.noise(x as f64 * 0.31, 0.0, z as f64 * 0.31);
                assert!(v.abs() < 2.0, "noise out of bound: {}", v);
            }
        }
    }

    /// Spot-check the noiseWithDerivative path: returns a finite value
    /// and writes finite derivatives. (Bit-exact vs vanilla is covered
    /// by the planned cross-validator; here we just guard against NaN.)
    #[test]
    fn derivative_path_is_finite() {
        let mut r = XoroshiroRandomSource::from_legacy_seed(2027);
        let n = ImprovedNoise::new(&mut r);
        let mut d = [0.0; 3];
        let v = n.noise_with_derivative(0.7, 1.3, -2.1, &mut d);
        assert!(v.is_finite());
        for x in d {
            assert!(x.is_finite());
        }
    }

    /// wrap is the identity for small inputs, and folds large inputs
    /// onto a band of width 2*ROUND_OFF (=3.3554432e7).
    #[test]
    fn wrap_basics() {
        assert_eq!(wrap(0.0), 0.0);
        assert_eq!(wrap(100.0), 100.0);
        assert_eq!(wrap(-100.0), -100.0);
        // |x| > 1.6777216e7 starts folding
        let big = 1.0e9;
        let w = wrap(big);
        assert!(w.abs() <= 3.3554432e7 / 2.0 + 1.0);
    }

    /// makeAmplitudes for a single octave at 0 → first_octave=0, [1.0].
    #[test]
    fn make_amplitudes_single_zero_octave() {
        let (first, amps) = PerlinNoise::make_amplitudes(&[0]);
        assert_eq!(first, 0);
        assert_eq!(amps, vec![1.0]);
    }

    /// makeAmplitudes for octaves [-2, 0]: low=2, high=0, span=3,
    /// amplitudes = [1, 0, 1], first_octave = -2.
    #[test]
    fn make_amplitudes_with_gap() {
        let (first, amps) = PerlinNoise::make_amplitudes(&[-2, 0]);
        assert_eq!(first, -2);
        assert_eq!(amps, vec![1.0, 0.0, 1.0]);
    }

    /// PerlinNoise construction is deterministic for a given seed and
    /// octave set.
    #[test]
    fn perlin_construction_is_deterministic() {
        let mut r1 = XoroshiroRandomSource::from_legacy_seed(2026);
        let mut r2 = XoroshiroRandomSource::from_legacy_seed(2026);
        let p1 = PerlinNoise::create_from_octaves(&mut r1, &[-3, -2, -1, 0]);
        let p2 = PerlinNoise::create_from_octaves(&mut r2, &[-3, -2, -1, 0]);
        assert_eq!(p1.first_octave, p2.first_octave);
        assert_eq!(p1.amplitudes, p2.amplitudes);
        assert_eq!(p1.max_value, p2.max_value);
        assert_eq!(p1.get_value(1.5, 2.5, 3.5), p2.get_value(1.5, 2.5, 3.5));
    }

    /// max_value > 0 and finite for a typical octave set.
    #[test]
    fn perlin_max_value_positive_finite() {
        let mut r = XoroshiroRandomSource::from_legacy_seed(99);
        let p = PerlinNoise::create_from_octaves(&mut r, &[-3, -2, -1, 0]);
        assert!(p.max_value.is_finite());
        assert!(p.max_value > 0.0);
    }

    /// Sampled values are finite and bounded by max_value (with slack).
    #[test]
    fn perlin_get_value_bounded() {
        let mut r = XoroshiroRandomSource::from_legacy_seed(2027);
        let p = PerlinNoise::create_from_octaves(&mut r, &[-2, -1, 0]);
        for x in -5..5 {
            for z in -5..5 {
                let v = p.get_value(x as f64 * 1.7, 0.0, z as f64 * 1.7);
                assert!(v.is_finite());
                assert!(v.abs() <= p.max_value * 1.5, "v={} > max={}", v, p.max_value);
            }
        }
    }

    /// get_octave_noise is a length-aware reverse index into noise_levels.
    #[test]
    fn get_octave_noise_indexing() {
        let mut r = XoroshiroRandomSource::from_legacy_seed(1);
        let p = PerlinNoise::create_from_octaves(&mut r, &[-2, -1, 0]);
        // 3 octaves; index 0 → noise_levels[2]
        let last = p.get_octave_noise(0).unwrap();
        let stored = p.noise_levels[2].as_ref().unwrap();
        assert_eq!(last.xo, stored.xo);
    }

    /// Legacy init must produce a stream-aligned result — building a
    /// PerlinNoise should not panic and the noise_levels array length
    /// must equal amplitudes.
    #[test]
    fn legacy_init_constructs() {
        let mut r = XoroshiroRandomSource::from_legacy_seed(42);
        let p = PerlinNoise::create_legacy_for_blended_noise(&mut r, &[-3, -2, -1, 0]);
        assert_eq!(p.noise_levels.len(), p.amplitudes.len());
    }

    /// expected_deviation matches vanilla: 0 → 0.2, 1 → 0.15, etc.
    #[test]
    fn expected_deviation_known() {
        assert!((expected_deviation(0) - 0.2).abs() < 1e-12);
        assert!((expected_deviation(1) - 0.15).abs() < 1e-12);
        assert!((expected_deviation(3) - 0.125).abs() < 1e-12);
    }

    /// NormalNoise construction is deterministic.
    #[test]
    fn normal_noise_is_deterministic() {
        let mut r1 = XoroshiroRandomSource::from_legacy_seed(2026);
        let mut r2 = XoroshiroRandomSource::from_legacy_seed(2026);
        let p1 = NormalNoise::create(
            &mut r1,
            NoiseParameters::new(-3, vec![1.0, 1.0, 1.0, 1.0]),
        );
        let p2 = NormalNoise::create(
            &mut r2,
            NoiseParameters::new(-3, vec![1.0, 1.0, 1.0, 1.0]),
        );
        assert_eq!(p1.value_factor, p2.value_factor);
        assert_eq!(p1.max_value, p2.max_value);
        assert_eq!(p1.get_value(1.5, 2.5, 3.5), p2.get_value(1.5, 2.5, 3.5));
    }

    /// max_value > 0 and finite.
    #[test]
    fn normal_noise_max_value_positive() {
        let mut r = XoroshiroRandomSource::from_legacy_seed(99);
        let n = NormalNoise::create(
            &mut r,
            NoiseParameters::new(-3, vec![1.0, 1.0, 1.0, 1.0]),
        );
        assert!(n.max_value.is_finite());
        assert!(n.max_value > 0.0);
    }

    /// Sampled values are finite and bounded by max_value (with slack).
    #[test]
    fn normal_noise_get_value_bounded() {
        let mut r = XoroshiroRandomSource::from_legacy_seed(2027);
        let n = NormalNoise::create(
            &mut r,
            NoiseParameters::new(-2, vec![1.0, 1.0, 1.0]),
        );
        for x in -5..5 {
            for z in -5..5 {
                let v = n.get_value(x as f64 * 1.7, 0.0, z as f64 * 1.7);
                assert!(v.is_finite());
                assert!(v.abs() <= n.max_value * 1.5, "v={} > max={}", v, n.max_value);
            }
        }
    }

    /// NormalNoise consumes the same random stream every time — i.e.
    /// drawing two NormalNoises from one source matches drawing them
    /// in two separate sources advanced to the same point.
    #[test]
    fn normal_noise_stream_alignment() {
        let params = NoiseParameters::new(-2, vec![1.0, 1.0, 1.0]);
        // Draw two NormalNoises from r_a; record sampled value of #2.
        let mut r_a = XoroshiroRandomSource::from_legacy_seed(7);
        let _n1 = NormalNoise::create(&mut r_a, params.clone());
        let n2_a = NormalNoise::create(&mut r_a, params.clone());

        // Repeat from a fresh source.
        let mut r_b = XoroshiroRandomSource::from_legacy_seed(7);
        let _n1 = NormalNoise::create(&mut r_b, params.clone());
        let n2_b = NormalNoise::create(&mut r_b, params.clone());

        assert_eq!(n2_a.get_value(0.5, 0.5, 0.5), n2_b.get_value(0.5, 0.5, 0.5));
    }

    /// noise_with_yfudge with yScale=0 must equal noise(...).
    #[test]
    fn yfudge_zero_matches_default() {
        let mut r = XoroshiroRandomSource::from_legacy_seed(11);
        let n = ImprovedNoise::new(&mut r);
        for &(x, y, z) in &[(0.0, 0.0, 0.0), (1.5, -2.25, 7.125), (-100.5, 60.5, 33.5)] {
            assert_eq!(n.noise(x, y, z), n.noise_with_yfudge(x, y, z, 0.0, 0.0));
        }
    }
}
