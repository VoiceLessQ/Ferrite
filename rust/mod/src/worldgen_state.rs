//! Process-wide seed-derived worldgen state.
//!
//! Built once at world load via `init_worldgen_state(seed)` followed
//! by `register_noise(...)` for each named `NormalNoise.NoiseParameters`
//! entry the JVM exposes from its registry, then `finalize()`. After
//! finalization, `worldgen_state()` returns a read-only handle that
//! every Rust evaluator (surface, density functions, climate, etc.)
//! can query without crossing JNI again.
//!
//! Architectural rationale: see `docs/SEED_DRIVEN_DISPATCH.md`. The
//! single-i64 seed handoff is the cheapest possible JNI boundary; the
//! per-noise registration is one-shot at world load and never repeats
//! during chunk gen.
//!
//! Mirrors vanilla `RandomState` (`26.1.2/server/.../levelgen/RandomState.java`):
//! ```text
//! this.random = settings.getRandomSource().newInstance(seed).forkPositional();
//! // for each noise key:
//! NormalNoise.create(this.random.fromHashOf(key.identifier()), parameters)
//! ```
//!
//! Names passed to `register_noise` should be the full identifier
//! string vanilla would pass to `fromHashOf` — i.e.
//! `Identifier.toString()` form, e.g. `"minecraft:temperature"`,
//! `"minecraft:erosion"`. That's what `Noises.instantiate` does:
//! `context.fromHashOf(name.identifier())` resolves through
//! `PositionalRandomFactory.fromHashOf(Identifier).default → fromHashOf(name.toString())`.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use crate::climate::{ParameterPoint, RTree, TargetPoint};
use crate::density::{DensityFunction, FunctionContext};
use crate::perlin::{NoiseParameters, NormalNoise};
use crate::xoroshiro::{XoroshiroPositionalRandomFactory, XoroshiroRandomSource};

/// Sealed worldgen state. Constructed by `finalize_worldgen_init`,
/// then read-only for the rest of the world's lifetime.
pub struct WorldgenState {
    pub seed: i64,
    /// `random.forkPositional()` — vanilla's `RandomState.random`.
    /// Every `NormalNoise` here was built by drawing a child source
    /// from this factory via `from_hash_of(name)`.
    pub root_factory: XoroshiroPositionalRandomFactory,
    /// Map from full identifier string (e.g. `"minecraft:temperature"`)
    /// to the instantiated `NormalNoise`.
    pub noises: HashMap<String, NormalNoise>,
    /// Biome R-tree, populated when Java pushes the
    /// `MultiNoiseBiomeSource.parameters` list at world load. None
    /// before that handoff completes; queries fall back to "vanilla
    /// will answer" until then.
    pub biome_tree: Option<RTree<i32>>,
    /// Named density functions registered via
    /// `RustBridge.registerDensityFunction`. Java walks yarn's DF
    /// registry at world load, emits our bytecode format per entry,
    /// and pushes it here. Keyed by full identifier
    /// (e.g. `"minecraft:overworld/temperature"`).
    pub density_functions: HashMap<String, DensityFunction>,
}

impl WorldgenState {
    pub fn get_noise(&self, name: &str) -> Option<&NormalNoise> {
        self.noises.get(name)
    }

    /// Convenience wrapper — returns the sample value or `None` if the
    /// noise wasn't registered. Callers that hot-path a known noise
    /// should `get_noise` once and cache the reference instead.
    pub fn sample_noise(&self, name: &str, x: f64, y: f64, z: f64) -> Option<f64> {
        self.noises.get(name).map(|n| n.get_value(x, y, z))
    }

    /// Look up the biome at a sampled climate target. Returns `None`
    /// if the biome R-tree wasn't registered (Java hasn't pushed
    /// `MultiNoiseBiomeSource.parameters` yet).
    pub fn find_biome(&self, target: TargetPoint) -> Option<i32> {
        self.biome_tree.as_ref().map(|tree| *tree.find_value(target))
    }

    /// Sample a registered density function at `(x, y, z)`. Returns
    /// `None` if the name isn't registered.
    pub fn sample_density(&self, name: &str, x: i32, y: i32, z: i32) -> Option<f64> {
        self.density_functions.get(name).map(|df| {
            df.compute(&FunctionContext::new(x, y, z), self)
        })
    }
}

/// In-progress build state. Held in a `Mutex` for the duration of
/// `init` → `register_*` → `finalize`. Once finalized, the contents
/// are moved into `WORLDGEN_STATE` and the builder slot is cleared.
struct WorldgenBuilder {
    seed: i64,
    root_factory: XoroshiroPositionalRandomFactory,
    noises: HashMap<String, NormalNoise>,
    biome_entries: Vec<(ParameterPoint, i32)>,
    density_functions: HashMap<String, DensityFunction>,
}

static BUILDER: Mutex<Option<WorldgenBuilder>> = Mutex::new(None);
static WORLDGEN_STATE: OnceLock<WorldgenState> = OnceLock::new();

/// Begin a fresh worldgen-state build. Discards any in-progress build
/// (a re-init mid-build means the previous world load was abandoned).
///
/// Returns `Err` if the state has already been finalized — finalization
/// is one-shot per process. Re-loading a world in the same JVM is rare
/// and not currently supported here; if it becomes a need, swap
/// `OnceLock` for a parking-lot RwLock.
pub fn init_worldgen_init(seed: i64) -> Result<(), &'static str> {
    if WORLDGEN_STATE.get().is_some() {
        return Err("worldgen state already finalized");
    }
    let root_random = XoroshiroRandomSource::from_legacy_seed(seed);
    let mut root_random_mut = root_random;
    let root_factory = root_random_mut.fork_positional();
    *BUILDER.lock().unwrap() = Some(WorldgenBuilder {
        seed,
        root_factory,
        noises: HashMap::new(),
        biome_entries: Vec::new(),
        density_functions: HashMap::new(),
    });
    Ok(())
}

/// Register one named density function into the in-progress build.
/// `bytecode` is parsed via `density::parse_bytecode` and stored by
/// full identifier (e.g. `"minecraft:overworld/temperature"`).
pub fn register_density_function(
    name: &str,
    bytecode: &[u8],
) -> Result<(), String> {
    let df = crate::density::parse_bytecode(bytecode)?;
    let mut guard = BUILDER.lock().unwrap();
    let builder = guard.as_mut().ok_or_else(|| "init_worldgen_init not called".to_string())?;
    builder.density_functions.insert(name.to_string(), df);
    Ok(())
}

/// Append biome entries to the in-progress build. Called repeatedly
/// (or once with the full list) by the Java bootstrap as it walks the
/// `MultiNoiseBiomeSource.parameters` `Climate.ParameterList`.
///
/// `entries` are `(ParameterPoint, biome_id)` pairs. The R-tree is
/// built lazily at `finalize_worldgen_init` from the accumulated list.
pub fn register_biome_entries(
    entries: impl IntoIterator<Item = (ParameterPoint, i32)>,
) -> Result<(), &'static str> {
    let mut guard = BUILDER.lock().unwrap();
    let builder = guard.as_mut().ok_or("init_worldgen_init not called")?;
    builder.biome_entries.extend(entries);
    Ok(())
}

/// Register a noise into the current build. Vanilla equivalent:
/// `NormalNoise.create(root.fromHashOf(name), parameters)` from
/// `Noises.instantiate`.
///
/// `name` must be the full identifier string (e.g.
/// `"minecraft:temperature"`) — that's what vanilla hashes.
pub fn register_noise(name: &str, parameters: NoiseParameters) -> Result<(), &'static str> {
    let mut guard = BUILDER.lock().unwrap();
    let builder = guard.as_mut().ok_or("init_worldgen_init not called")?;
    let mut child = builder.root_factory.from_hash_of(name);
    let noise = NormalNoise::create(&mut child, parameters);
    builder.noises.insert(name.to_string(), noise);
    Ok(())
}

/// Seal the build and publish it as the global worldgen state.
/// Subsequent `register_noise` calls fail.
pub fn finalize_worldgen_init() -> Result<(), &'static str> {
    let builder = BUILDER.lock().unwrap().take().ok_or("nothing to finalize")?;
    let biome_tree = if builder.biome_entries.is_empty() {
        None
    } else {
        Some(RTree::new(builder.biome_entries))
    };
    let state = WorldgenState {
        seed: builder.seed,
        root_factory: builder.root_factory,
        noises: builder.noises,
        biome_tree,
        density_functions: builder.density_functions,
    };
    WORLDGEN_STATE.set(state).map_err(|_| "worldgen state already finalized")
}

/// Read-only access to the finalized state. Returns `None` until
/// `finalize_worldgen_init` succeeds.
pub fn worldgen_state() -> Option<&'static WorldgenState> {
    WORLDGEN_STATE.get()
}

#[cfg(test)]
mod tests {
    use super::*;

    // The static OnceLock is shared across tests — we can't test the
    // full init→finalize→read cycle in isolation without poisoning the
    // global. Instead, build a fresh `WorldgenState` directly and
    // exercise the lookup API. The init/register/finalize flow is
    // exercised end-to-end from Java at world load.

    fn build_state(seed: i64, names: &[&str]) -> WorldgenState {
        let mut root_random = XoroshiroRandomSource::from_legacy_seed(seed);
        let root_factory = root_random.fork_positional();
        let mut noises = HashMap::new();
        for &name in names {
            let mut child = root_factory.from_hash_of(name);
            // Use a small but realistic NoiseParameters: 4 octaves @ 1.0.
            let params = NoiseParameters::new(-3, vec![1.0, 1.0, 1.0, 1.0]);
            let n = NormalNoise::create(&mut child, params);
            noises.insert(name.to_string(), n);
        }
        WorldgenState {
            seed, root_factory, noises,
            biome_tree: None,
            density_functions: HashMap::new(),
        }
    }

    #[test]
    fn lookup_returns_registered_noise() {
        let state = build_state(2026, &["minecraft:temperature", "minecraft:erosion"]);
        assert!(state.get_noise("minecraft:temperature").is_some());
        assert!(state.get_noise("minecraft:erosion").is_some());
        assert!(state.get_noise("minecraft:does_not_exist").is_none());
    }

    #[test]
    fn sample_noise_is_finite_and_deterministic() {
        let s1 = build_state(42, &["minecraft:temperature"]);
        let s2 = build_state(42, &["minecraft:temperature"]);
        let v1 = s1.sample_noise("minecraft:temperature", 1.5, 2.5, 3.5).unwrap();
        let v2 = s2.sample_noise("minecraft:temperature", 1.5, 2.5, 3.5).unwrap();
        assert_eq!(v1, v2);
        assert!(v1.is_finite());
    }

    #[test]
    fn different_seeds_produce_different_samples() {
        let s1 = build_state(42, &["minecraft:temperature"]);
        let s2 = build_state(43, &["minecraft:temperature"]);
        let v1 = s1.sample_noise("minecraft:temperature", 1.5, 2.5, 3.5).unwrap();
        let v2 = s2.sample_noise("minecraft:temperature", 1.5, 2.5, 3.5).unwrap();
        assert_ne!(v1, v2);
    }

    /// Same seed + same name → identical NormalNoise ⇒ identical max_value.
    #[test]
    fn same_seed_same_name_same_max_value() {
        let s1 = build_state(7, &["minecraft:erosion"]);
        let s2 = build_state(7, &["minecraft:erosion"]);
        assert_eq!(
            s1.get_noise("minecraft:erosion").unwrap().max_value(),
            s2.get_noise("minecraft:erosion").unwrap().max_value(),
        );
    }
}
