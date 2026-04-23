//! Bit-exact Rust port of vanilla Minecraft 1.21.11's
//! `Xoroshiro128PlusPlus`, `XoroshiroRandomSource`, and
//! `XoroshiroPositionalRandomFactory`.
//!
//! Source files (mojmap, in `26.1.2/server/net/minecraft/`):
//!   - `world/level/levelgen/Xoroshiro128PlusPlus.java`
//!   - `world/level/levelgen/XoroshiroRandomSource.java`
//!   - `world/level/levelgen/RandomSupport.java`
//!   - `util/Mth.java` (getSeed)
//!
//! This module is pure math — no JNI, no global state. Every function
//! is a direct translation of vanilla's. The intent is that
//! `XoroshiroPositionalRandomFactory::new(seed_lo, seed_hi).at(x, y, z).next_float()`
//! returns IDENTICAL values to vanilla, byte-for-byte.
//!
//! # Why this exists
//!
//! Two problems, one port:
//!
//! 1. Surface-rule `OP_VERT_GRADIENT` (bedrock floor + deepslate
//!    transition) currently uses a midpoint-cutoff placeholder in the
//!    Rust evaluator (`surface.rs:216`). Validator measures Java=Rust
//!    at 97.5% because of this. With a correct Xoroshiro Rust port,
//!    the evaluator can do the per-block PRNG roll and match Java
//!    100%.
//!
//! 2. Track B of the surface dispatcher: instead of reflecting per-
//!    column noise/random/biome state from the JVM, Rust will compute
//!    everything from the world's seed once at world load. Xoroshiro
//!    is the foundation — every other deterministic noise/random
//!    sampler in vanilla wraps `Xoroshiro128PlusPlus`.
//!
//! # Subtleties
//!
//! - Java's `>>>` is unsigned right shift. Rust uses `u64` arithmetic
//!   for the same effect, then casts back to i64 where needed for
//!   parity with Java's signed long return type. `nextLong` returns
//!   `i64` to match vanilla; clients that want `u64` can cast.
//! - `rotateLeft(value, bits)` in Java preserves all bits — Rust's
//!   `u64::rotate_left` is equivalent.
//! - `mixStafford13` uses two specific magic constants
//!   (`-4658895280553007687` and `-7723592293110705685`) — Java
//!   negative literals; Rust needs the unsigned bit pattern via
//!   `i64::wrapping_mul` or `u64` arithmetic. We keep everything
//!   in `i64` to mirror vanilla exactly (signed multiply is
//!   wrapping in two's complement, which is what we want).
//! - `Mth.getSeed(x, y, z)` does signed integer multiplications
//!   that overflow in Java by design. Rust uses `wrapping_mul`
//!   on `i64` to preserve the wrap.

// ============================================================================
// Constants from vanilla RandomSupport
// ============================================================================

/// Vanilla `RandomSupport.GOLDEN_RATIO_64` (Java literal `-7046029254386353131L`).
pub const GOLDEN_RATIO_64: i64 = -7046029254386353131_i64;
/// Vanilla `RandomSupport.SILVER_RATIO_64` (Java literal `7640891576956012809L`).
pub const SILVER_RATIO_64: i64 = 7640891576956012809_i64;

/// Vanilla `XoroshiroRandomSource.FLOAT_UNIT` = `5.9604645e-8f` =
/// `1.0f / (1 << 24)`. Used by `next_float`.
const FLOAT_UNIT_F32: f32 = 5.9604645e-8_f32;
/// Vanilla `XoroshiroRandomSource.DOUBLE_UNIT` = `1.110223e-16f` (note:
/// declared as `float` in vanilla but used as double — matches IEEE exactly
/// for `1.0 / (1 << 53)`).
const DOUBLE_UNIT_F32: f32 = 1.110223e-16_f32;

// ============================================================================
// Mixing primitives
// ============================================================================

/// Vanilla `RandomSupport.mixStafford13(long z)` — three rounds of
/// xor-shift-multiply. Used to scramble a raw long seed before feeding
/// into Xoroshiro.
#[inline]
pub fn mix_stafford_13(z: i64) -> i64 {
    let z = (z ^ ((z as u64 >> 30) as i64)).wrapping_mul(-4658895280553007687_i64);
    let z = (z ^ ((z as u64 >> 27) as i64)).wrapping_mul(-7723592293110705685_i64);
    z ^ ((z as u64 >> 31) as i64)
}

/// Vanilla `RandomSupport.upgradeSeedTo128bitUnmixed(long legacySeed)`.
#[inline]
pub fn upgrade_seed_to_128bit_unmixed(legacy_seed: i64) -> (i64, i64) {
    let lo = legacy_seed ^ SILVER_RATIO_64;
    let hi = lo.wrapping_add(GOLDEN_RATIO_64);
    (lo, hi)
}

/// Vanilla `RandomSupport.upgradeSeedTo128bit(long legacySeed)` —
/// unmixed then mixStafford13'd in both halves.
#[inline]
pub fn upgrade_seed_to_128bit(legacy_seed: i64) -> (i64, i64) {
    let (lo, hi) = upgrade_seed_to_128bit_unmixed(legacy_seed);
    (mix_stafford_13(lo), mix_stafford_13(hi))
}

/// Vanilla `Mth.getSeed(int x, int y, int z)` — derives a per-position
/// long seed. Used by `PositionalRandomFactory.at(x, y, z)`.
///
/// Vanilla's literal computation:
/// ```text
/// long seed = (long)(x * 3129871) ^ (long)z * 116129781L ^ (long)y;
/// seed = seed * seed * 42317861L + seed * 11L;
/// return seed >> 16;
/// ```
///
/// Note: `x * 3129871` is an `int * int` multiply in Java, which
/// overflows to `int` THEN is sign-extended to `long`. Rust must mirror
/// that: do the multiply as `i32::wrapping_mul`, then cast.
#[inline]
pub fn get_seed(x: i32, y: i32, z: i32) -> i64 {
    let xs: i64 = (x.wrapping_mul(3129871) as i64) ^ ((z as i64).wrapping_mul(116129781)) ^ (y as i64);
    let mixed = xs.wrapping_mul(xs).wrapping_mul(42317861).wrapping_add(xs.wrapping_mul(11));
    mixed >> 16
}

// ============================================================================
// Xoroshiro128PlusPlus — the core PRNG
// ============================================================================

/// Direct port of vanilla's `Xoroshiro128PlusPlus`. Holds two i64 seeds
/// and advances them on every `next_long` call. Mutable.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Xoroshiro128PlusPlus {
    pub seed_lo: i64,
    pub seed_hi: i64,
}

impl Xoroshiro128PlusPlus {
    /// Vanilla constructor: zero-zero seed is replaced by the golden+silver
    /// constants (otherwise xoroshiro would emit zeros forever).
    pub fn new(mut seed_lo: i64, mut seed_hi: i64) -> Self {
        if (seed_lo | seed_hi) == 0 {
            seed_lo = GOLDEN_RATIO_64;
            seed_hi = SILVER_RATIO_64;
        }
        Self { seed_lo, seed_hi }
    }

    /// Vanilla `nextLong()`. Bit-exact with the Java implementation:
    /// ```text
    /// long s0 = seedLo, s1 = seedHi;
    /// long result = Long.rotateLeft(s0 + s1, 17) + s0;
    /// s1 ^= s0;
    /// seedLo = Long.rotateLeft(s0, 49) ^ s1 ^ (s1 << 21);
    /// seedHi = Long.rotateLeft(s1, 28);
    /// return result;
    /// ```
    #[inline]
    pub fn next_long(&mut self) -> i64 {
        let s0 = self.seed_lo;
        let s1 = self.seed_hi;
        let sum = s0.wrapping_add(s1);
        // i64 rotate is sign-preserving in Java; we go through u64 to mirror
        // bit-for-bit.
        let result = (sum as u64).rotate_left(17) as i64;
        let result = result.wrapping_add(s0);

        let s1_xor_s0 = s1 ^ s0;
        let new_lo = ((s0 as u64).rotate_left(49) as i64) ^ s1_xor_s0 ^ (s1_xor_s0.wrapping_shl(21));
        let new_hi = (s1_xor_s0 as u64).rotate_left(28) as i64;
        self.seed_lo = new_lo;
        self.seed_hi = new_hi;
        result
    }
}

// ============================================================================
// XoroshiroRandomSource — Random-style API on top of the PRNG
// ============================================================================

/// Direct port of vanilla's `XoroshiroRandomSource`. Wraps
/// `Xoroshiro128PlusPlus` with the convenience methods vanilla rules
/// call (`nextFloat`, `nextDouble`, `nextInt`, etc.).
///
/// The Gaussian source from vanilla (`MarsagliaPolarGaussian`) is NOT
/// ported here — surface rules don't use it. Add later if a downstream
/// use needs it.
#[derive(Clone, Copy, Debug)]
pub struct XoroshiroRandomSource {
    pub generator: Xoroshiro128PlusPlus,
}

impl XoroshiroRandomSource {
    /// Vanilla `XoroshiroRandomSource(long seed)`: upgrades the legacy
    /// 64-bit seed via `upgradeSeedTo128bit` (golden+silver xor +
    /// mixStafford13).
    pub fn from_legacy_seed(legacy_seed: i64) -> Self {
        let (lo, hi) = upgrade_seed_to_128bit(legacy_seed);
        Self { generator: Xoroshiro128PlusPlus::new(lo, hi) }
    }

    /// Vanilla `XoroshiroRandomSource(long seedLo, long seedHi)`.
    /// Used by `XoroshiroPositionalRandomFactory.at` after the
    /// per-position seed mixing.
    pub fn from_pair(seed_lo: i64, seed_hi: i64) -> Self {
        Self { generator: Xoroshiro128PlusPlus::new(seed_lo, seed_hi) }
    }

    /// Vanilla `nextLong()` — passthrough.
    #[inline]
    pub fn next_long(&mut self) -> i64 {
        self.generator.next_long()
    }

    /// Vanilla `nextInt()` — `(int) nextLong()` (truncates the high 32 bits).
    #[inline]
    pub fn next_int(&mut self) -> i32 {
        self.next_long() as i32
    }

    /// Vanilla `nextBoolean()` — `(nextLong() & 1) != 0`.
    #[inline]
    pub fn next_boolean(&mut self) -> bool {
        (self.next_long() & 1) != 0
    }

    /// Vanilla `nextBits(int bits)`: `nextLong() >>> (64 - bits)`.
    /// Java's `>>>` is unsigned shift; we use u64.
    #[inline]
    pub fn next_bits(&mut self, bits: u32) -> i64 {
        let raw = self.next_long() as u64;
        (raw >> (64 - bits)) as i64
    }

    /// Vanilla `nextFloat()` — `(float) nextBits(24) * 5.9604645E-8F`.
    /// This is the call OP_VERT_GRADIENT uses for per-block PRNG.
    #[inline]
    pub fn next_float(&mut self) -> f32 {
        (self.next_bits(24) as f32) * FLOAT_UNIT_F32
    }

    /// Vanilla `nextDouble()` — `(double) nextBits(53) * 1.110223E-16F`.
    /// Note: vanilla declares the constant as `float` (loses precision)
    /// then promotes to double — this is a vanilla quirk; we mirror it.
    #[inline]
    pub fn next_double(&mut self) -> f64 {
        (self.next_bits(53) as f64) * (DOUBLE_UNIT_F32 as f64)
    }
}

// ============================================================================
// XoroshiroPositionalRandomFactory — per-position random derivation
// ============================================================================

/// Direct port of vanilla's `XoroshiroPositionalRandomFactory`. Holds
/// two i64 "factory seeds" derived from the world seed, and produces a
/// `XoroshiroRandomSource` deterministically for any (x, y, z).
///
/// This is the core primitive vanilla uses for per-block randomness:
/// `OP_VERT_GRADIENT` calls `factory.at(x, y, z).nextFloat()` for the
/// bedrock-floor and deepslate-transition probability roll.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct XoroshiroPositionalRandomFactory {
    pub seed_lo: i64,
    pub seed_hi: i64,
}

impl XoroshiroPositionalRandomFactory {
    pub fn new(seed_lo: i64, seed_hi: i64) -> Self {
        Self { seed_lo, seed_hi }
    }

    /// Vanilla `at(x, y, z)`:
    /// ```text
    /// long positionalSeed = Mth.getSeed(x, y, z);
    /// long randomSeed = positionalSeed ^ this.seedLo;
    /// return new XoroshiroRandomSource(randomSeed, this.seedHi);
    /// ```
    #[inline]
    pub fn at(&self, x: i32, y: i32, z: i32) -> XoroshiroRandomSource {
        let positional_seed = get_seed(x, y, z);
        let random_seed = positional_seed ^ self.seed_lo;
        XoroshiroRandomSource::from_pair(random_seed, self.seed_hi)
    }
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    /// Sanity: zero-zero seed gets replaced by golden+silver constants.
    #[test]
    fn zero_seed_is_replaced() {
        let r = Xoroshiro128PlusPlus::new(0, 0);
        assert_eq!(r.seed_lo, GOLDEN_RATIO_64);
        assert_eq!(r.seed_hi, SILVER_RATIO_64);
    }

    /// Non-zero seed is preserved as-is.
    #[test]
    fn nonzero_seed_passes_through() {
        let r = Xoroshiro128PlusPlus::new(1, 2);
        assert_eq!(r.seed_lo, 1);
        assert_eq!(r.seed_hi, 2);
    }

    /// Hand-traced: with seed (1, 2), nextLong should compute
    ///   sum = 3
    ///   result = rotateLeft(3, 17) + 1 = (3 << 17) + 1 = 393216 + 1 = 393217
    /// Then mutates seedLo/seedHi for next call.
    #[test]
    fn next_long_first_call_with_known_seed() {
        let mut r = Xoroshiro128PlusPlus::new(1, 2);
        let v = r.next_long();
        assert_eq!(v, 393217, "first nextLong with seed (1, 2) should be 393217");
    }

    /// Mix Stafford 13 of zero is zero (the algorithm fixed point).
    #[test]
    fn mix_stafford_13_zero_is_zero() {
        assert_eq!(mix_stafford_13(0), 0);
    }

    /// upgradeSeedTo128bit(0) = mixStafford13(SILVER) ↔ mixStafford13(SILVER + GOLDEN).
    /// Verifies the seed-upgrade path for a known input.
    #[test]
    fn upgrade_seed_zero_matches_constants() {
        let (lo_unmixed, hi_unmixed) = upgrade_seed_to_128bit_unmixed(0);
        assert_eq!(lo_unmixed, SILVER_RATIO_64);
        assert_eq!(hi_unmixed, SILVER_RATIO_64.wrapping_add(GOLDEN_RATIO_64));

        // Mixed via Stafford 13 — just verify the two halves get mixed
        // (i.e. they change). Exact values are tested via downstream
        // sample comparisons against vanilla.
        let (lo_mixed, hi_mixed) = upgrade_seed_to_128bit(0);
        assert_ne!(lo_mixed, lo_unmixed);
        assert_ne!(hi_mixed, hi_unmixed);
    }

    /// Mth.getSeed(0, 0, 0) — all coords zero → seed is 0 before
    /// shifting (0 * 0 * 42317861 + 0 * 11 = 0 → >> 16 = 0).
    #[test]
    fn get_seed_origin_is_zero() {
        assert_eq!(get_seed(0, 0, 0), 0);
    }

    /// Mth.getSeed(1, 0, 0) — x=1 → x * 3129871 = 3129871 (mod i32),
    /// XOR with 0 ^ 0 = 3129871. Then 3129871^2 * 42317861 + 3129871 * 11
    /// then >> 16. We don't trust hand math; we just verify nonzero and
    /// stable across calls.
    #[test]
    fn get_seed_is_deterministic_and_nonzero() {
        let a = get_seed(1, 0, 0);
        let b = get_seed(1, 0, 0);
        assert_eq!(a, b);
        assert_ne!(a, 0);
    }

    /// Two PositionalRandomFactory.at calls at the same (x, y, z) with
    /// the same factory seeds produce the same nextFloat sequence.
    #[test]
    fn positional_factory_is_deterministic() {
        let factory = XoroshiroPositionalRandomFactory::new(0xDEADBEEF, 0x1234_5678);
        let mut r1 = factory.at(10, 64, 20);
        let mut r2 = factory.at(10, 64, 20);
        for _ in 0..16 {
            assert_eq!(r1.next_float(), r2.next_float());
        }
    }

    /// Different positions yield different sequences.
    #[test]
    fn positional_factory_varies_with_position() {
        let factory = XoroshiroPositionalRandomFactory::new(0xDEADBEEF, 0x1234_5678);
        let f1 = factory.at(0, 0, 0).next_float();
        let f2 = factory.at(0, 0, 1).next_float();
        let f3 = factory.at(1, 0, 0).next_float();
        // All three should be distinct floats.
        assert_ne!(f1, f2);
        assert_ne!(f1, f3);
        assert_ne!(f2, f3);
    }

    /// next_float must be in [0.0, 1.0) per vanilla's contract.
    #[test]
    fn next_float_is_in_unit_interval() {
        let factory = XoroshiroPositionalRandomFactory::new(1, 2);
        for x in -5..=5 {
            for z in -5..=5 {
                let f = factory.at(x, -60, z).next_float();
                assert!((0.0..1.0).contains(&f), "next_float at ({},-60,{}) = {} out of [0,1)", x, z, f);
            }
        }
    }

    /// next_double must be in [0.0, 1.0).
    #[test]
    fn next_double_is_in_unit_interval() {
        let mut r = XoroshiroRandomSource::from_legacy_seed(12345);
        for _ in 0..1000 {
            let d = r.next_double();
            assert!((0.0..1.0).contains(&d));
        }
    }
}
