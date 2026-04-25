//! Bit-exact Rust port of vanilla Minecraft 1.21.11's
//! `Climate` parameter system — `Parameter`, `ParameterPoint`,
//! `TargetPoint`, and the 7-dimensional `RTree` nearest-neighbor
//! index that `MultiNoiseBiomeSource.findValue` uses to map a
//! climate sample (temperature, humidity, continentalness, erosion,
//! depth, weirdness) to a biome.
//!
//! Source file (mojmap):
//!   `26.1.2/server/net/minecraft/world/level/biome/Climate.java`
//!
//! # What this is
//!
//! Vanilla quantizes climate coordinates by `* 10_000` (lossy float→long
//! conversion) and stores everything as `i64`. Each biome registers a
//! 6-dimensional climate REGION it lives in (a `ParameterPoint`); the
//! R-tree finds the biome whose region is nearest (in squared-L2 sense)
//! to the sampled climate `TargetPoint`. The 7th dimension is "offset"
//! which biomes use as a tie-breaker.
//!
//! # What this is NOT
//!
//! The actual climate SAMPLING (temperature/humidity/etc. at a given
//! `(x, y, z)`) lives in the density function pipeline, not here.
//! Vanilla's `Climate.Sampler` evaluates 6 named density functions
//! per sample. Rust doesn't have density functions yet — see
//! `docs/SEED_DRIVEN_DISPATCH.md` for the layered roadmap.
//!
//! For now the JNI bootstrap for biomes will accept a pre-sampled
//! `TargetPoint` from Java (vanilla samples; we just look up the biome).
//! Once density functions are ported, the sampling moves to Rust too
//! and the cross-JNI roundtrip per biome lookup vanishes.
//!
//! # Subtleties
//!
//! - `quantize_coord(f) = (f * 10_000.0) as i64` — vanilla casts
//!   `float * 10000F` to `long`. `as i64` from f32 truncates toward
//!   zero and saturates on overflow; identical to Java's `(long) f`.
//! - `Parameter::distance(target)` mirrors vanilla's interval distance:
//!   0 if `target ∈ [min, max]`, else the gap to the nearest edge.
//! - `Mth.square(x)` = `x * x` (long multiply, wraps on overflow). We
//!   use `i64::wrapping_mul` to mirror Java's signed-overflow semantics
//!   exactly. In practice the values stay well within range (climate
//!   coords are bounded ~[-2, 2] × 10_000 → ~[-20_000, 20_000]; squared
//!   sums of 7 such terms fit easily in i64).
//! - The R-tree build is **deterministic**: vanilla sorts by SAH-style
//!   bucketing across each dimension and picks the dimension with the
//!   minimum cost. We mirror the comparator chain exactly so the tree
//!   shape matches vanilla's, and `search` returns identical biomes.

use std::cmp::Ordering;

/// Vanilla `Climate.QUANTIZATION_FACTOR` = 10000.
pub const QUANTIZATION_FACTOR: f32 = 10_000.0;

/// Vanilla `Climate.PARAMETER_COUNT` = 7 (6 climate axes + offset).
pub const PARAMETER_COUNT: usize = 7;

/// Vanilla `Climate.RTree.CHILDREN_PER_NODE` = 6.
const CHILDREN_PER_NODE: usize = 6;

/// Vanilla `quantizeCoord(float) = (long)(coord * 10000F)`.
#[inline]
pub fn quantize_coord(coord: f32) -> i64 {
    (coord * QUANTIZATION_FACTOR) as i64
}

/// Vanilla `unquantizeCoord(long)`. Provided for diagnostics.
#[inline]
pub fn unquantize_coord(coord: i64) -> f32 {
    (coord as f32) / QUANTIZATION_FACTOR
}

// ============================================================================
// Parameter — 1-dim quantized interval
// ============================================================================

/// Vanilla `Climate.Parameter(long min, long max)`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Parameter {
    pub min: i64,
    pub max: i64,
}

impl Parameter {
    pub const fn new(min: i64, max: i64) -> Self {
        Self { min, max }
    }

    /// Vanilla `Parameter.point(float)` = `span(min, min)`.
    pub fn point(coord: f32) -> Self {
        let q = quantize_coord(coord);
        Self { min: q, max: q }
    }

    /// Vanilla `Parameter.span(float, float)`.
    /// Panics if `min > max`, matching vanilla's `IllegalArgumentException`.
    pub fn span(min: f32, max: f32) -> Self {
        assert!(min <= max, "min > max: {} {}", min, max);
        Self { min: quantize_coord(min), max: quantize_coord(max) }
    }

    /// Vanilla `distance(long target)` — interval distance: 0 if
    /// `target` is inside `[min, max]`, else the gap to the nearest
    /// edge.
    #[inline]
    pub fn distance(self, target: i64) -> i64 {
        let above = target - self.max;
        let below = self.min - target;
        if above > 0 { above } else { below.max(0) }
    }

    /// Vanilla `span(Parameter)` — extend interval to include another.
    /// Returns `self` if `other` is None (Java passes `null`).
    pub fn span_with(self, other: Option<Parameter>) -> Parameter {
        match other {
            None => self,
            Some(o) => Parameter { min: self.min.min(o.min), max: self.max.max(o.max) },
        }
    }

    #[inline]
    fn center(self) -> i64 {
        // Vanilla uses signed integer division: (min + max) / 2L.
        // For our quantized ranges this never overflows.
        (self.min + self.max) / 2
    }
}

// ============================================================================
// ParameterPoint — 7-dim region (6 climate axes + offset)
// ============================================================================

/// Vanilla `Climate.ParameterPoint`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ParameterPoint {
    pub temperature: Parameter,
    pub humidity: Parameter,
    pub continentalness: Parameter,
    pub erosion: Parameter,
    pub depth: Parameter,
    pub weirdness: Parameter,
    /// Stored as `long offset` in vanilla — already quantized.
    pub offset: i64,
}

impl ParameterPoint {
    /// Construct from the 6 climate parameter intervals + a quantized
    /// offset. Mirrors `Climate.parameters(Parameter, Parameter, ..., float)`
    /// where `offset` is the already-quantized value.
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        temperature: Parameter, humidity: Parameter,
        continentalness: Parameter, erosion: Parameter,
        depth: Parameter, weirdness: Parameter,
        offset: i64,
    ) -> Self {
        Self { temperature, humidity, continentalness, erosion, depth, weirdness, offset }
    }

    /// Vanilla `parameterSpace()` — 7-element array of intervals,
    /// where the 7th is `[offset, offset]` (a point).
    fn parameter_space(&self) -> [Parameter; PARAMETER_COUNT] {
        [
            self.temperature, self.humidity, self.continentalness,
            self.erosion, self.depth, self.weirdness,
            Parameter::new(self.offset, self.offset),
        ]
    }

    /// Vanilla `fitness(TargetPoint)` — sum of squared interval
    /// distances on each climate axis + the offset (squared) since
    /// target's 7th coord is always 0.
    #[inline]
    pub fn fitness(&self, target: TargetPoint) -> i64 {
        let mut sum = 0_i64;
        sum = sum.wrapping_add(square(self.temperature.distance(target.temperature)));
        sum = sum.wrapping_add(square(self.humidity.distance(target.humidity)));
        sum = sum.wrapping_add(square(self.continentalness.distance(target.continentalness)));
        sum = sum.wrapping_add(square(self.erosion.distance(target.erosion)));
        sum = sum.wrapping_add(square(self.depth.distance(target.depth)));
        sum = sum.wrapping_add(square(self.weirdness.distance(target.weirdness)));
        sum = sum.wrapping_add(square(self.offset));
        sum
    }
}

#[inline]
fn square(x: i64) -> i64 {
    x.wrapping_mul(x)
}

// ============================================================================
// TargetPoint — 6-dim sample location
// ============================================================================

/// Vanilla `Climate.TargetPoint(long t, long h, long c, long e, long d, long w)`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TargetPoint {
    pub temperature: i64,
    pub humidity: i64,
    pub continentalness: i64,
    pub erosion: i64,
    pub depth: i64,
    pub weirdness: i64,
}

impl TargetPoint {
    pub const fn new(
        temperature: i64, humidity: i64,
        continentalness: i64, erosion: i64,
        depth: i64, weirdness: i64,
    ) -> Self {
        Self { temperature, humidity, continentalness, erosion, depth, weirdness }
    }

    /// Vanilla `target(float, float, ..., float)` — quantizes the 6
    /// climate floats into a `TargetPoint`.
    pub fn from_floats(
        temperature: f32, humidity: f32, continentalness: f32,
        erosion: f32, depth: f32, weirdness: f32,
    ) -> Self {
        Self {
            temperature: quantize_coord(temperature),
            humidity: quantize_coord(humidity),
            continentalness: quantize_coord(continentalness),
            erosion: quantize_coord(erosion),
            depth: quantize_coord(depth),
            weirdness: quantize_coord(weirdness),
        }
    }

    /// Vanilla `toParameterArray()` — 7-element with the 7th = 0.
    #[inline]
    pub fn to_array(self) -> [i64; PARAMETER_COUNT] {
        [
            self.temperature, self.humidity, self.continentalness,
            self.erosion, self.depth, self.weirdness, 0,
        ]
    }
}

// ============================================================================
// RTree — 7D nearest-neighbor search
// ============================================================================

/// Internal node of vanilla's `Climate.RTree`. We keep value as a
/// generic `T` so the tree can index biome IDs (jint) or anything
/// else that comes through the JNI bootstrap.
enum Node<T> {
    Leaf {
        parameter_space: [Parameter; PARAMETER_COUNT],
        value: T,
    },
    SubTree {
        parameter_space: [Parameter; PARAMETER_COUNT],
        children: Vec<Node<T>>,
    },
}

impl<T: Clone> Node<T> {
    fn parameter_space(&self) -> &[Parameter; PARAMETER_COUNT] {
        match self {
            Node::Leaf { parameter_space, .. } => parameter_space,
            Node::SubTree { parameter_space, .. } => parameter_space,
        }
    }

    /// Vanilla `Node.distance(long[] target)` — sum of squared interval
    /// distances across all 7 dimensions.
    #[inline]
    fn distance(&self, target: &[i64; PARAMETER_COUNT]) -> i64 {
        let space = self.parameter_space();
        let mut d = 0_i64;
        for i in 0..PARAMETER_COUNT {
            d = d.wrapping_add(square(space[i].distance(target[i])));
        }
        d
    }

    /// Vanilla `search(target, candidate, distanceMetric)`. Best-first
    /// traversal: visit children in some order, prune subtrees whose
    /// bounding box is farther than the current best. Returns the
    /// nearest leaf.
    fn search<'a>(
        &'a self,
        target: &[i64; PARAMETER_COUNT],
        candidate: Option<&'a Node<T>>,
    ) -> &'a Node<T> {
        match self {
            Node::Leaf { .. } => self,
            Node::SubTree { children, .. } => {
                let mut min_distance = match candidate {
                    Some(c) => c.distance(target),
                    None => i64::MAX,
                };
                let mut closest_leaf: Option<&Node<T>> = candidate;
                for child in children {
                    let child_distance = child.distance(target);
                    if min_distance > child_distance {
                        let leaf = child.search(target, closest_leaf);
                        let leaf_distance = if std::ptr::eq(child, leaf) {
                            child_distance
                        } else {
                            leaf.distance(target)
                        };
                        if min_distance > leaf_distance {
                            min_distance = leaf_distance;
                            closest_leaf = Some(leaf);
                        }
                    }
                }
                closest_leaf.expect("RTree::search invariant — every SubTree has children")
            }
        }
    }
}

/// Vanilla `Climate.ParameterList` + `Climate.RTree` rolled into one.
/// `RTree::new(values)` takes a list of `(ParameterPoint, T)` pairs and
/// builds the index. `find_value(target)` runs the R-tree nearest
/// search and returns the closest value.
pub struct RTree<T> {
    root: Node<T>,
}

impl<T: Clone> RTree<T> {
    /// Vanilla `Climate.RTree.create(values)` + `Climate.ParameterList::new`.
    /// Panics if `values` is empty (matches vanilla's
    /// `IllegalArgumentException`).
    pub fn new(values: Vec<(ParameterPoint, T)>) -> Self {
        assert!(!values.is_empty(), "Need at least one value to build the search tree.");
        let leaves: Vec<Node<T>> = values
            .into_iter()
            .map(|(p, v)| Node::Leaf { parameter_space: p.parameter_space(), value: v })
            .collect();
        let root = build(leaves);
        Self { root }
    }

    /// Vanilla `findValue(TargetPoint)` — defers to `findValueIndex`,
    /// which runs `RTree.search` with the standard `Node::distance`
    /// metric. Vanilla also caches the previous result per-thread as
    /// a starting candidate; we skip the cache here (it's a 5-10%
    /// speedup, not correctness — can add later if profiling warrants).
    pub fn find_value(&self, target: TargetPoint) -> &T {
        let arr = target.to_array();
        let leaf = self.root.search(&arr, None);
        match leaf {
            Node::Leaf { value, .. } => value,
            _ => unreachable!("search always lands on a Leaf"),
        }
    }
}

// ============================================================================
// R-tree build — bottom-up bucketing, mirrors vanilla's algorithm
// ============================================================================

/// Vanilla `Climate.RTree.build(dimensions, children)` — recursively
/// group nodes into a tree of fanout 6, sorting on the SAH-best
/// dimension at each level.
fn build<T: Clone>(mut children: Vec<Node<T>>) -> Node<T> {
    assert!(!children.is_empty(), "Need at least one child to build a node");
    if children.len() == 1 {
        return children.into_iter().next().unwrap();
    }
    if children.len() <= CHILDREN_PER_NODE {
        // Vanilla sorts by sum-of-|center| across all dimensions.
        children.sort_by_key(|node| {
            let mut total: i64 = 0;
            let space = node.parameter_space();
            for d in 0..PARAMETER_COUNT {
                total = total.wrapping_add(space[d].center().wrapping_abs());
            }
            total
        });
        let parameter_space = build_parameter_space(&children);
        return Node::SubTree { parameter_space, children };
    }

    // Pick the dimension with the lowest summed-bucket-cost. Each
    // iteration sorts by that dimension and computes bucket bounds
    // without taking ownership; the winning dimension's bucketization
    // is reproduced at the end via the absolute-sort + bucketize_take
    // pass (this matches vanilla, which also sorts twice).
    let mut min_cost = i64::MAX;
    let mut min_dimension = 0_usize;
    for d in 0..PARAMETER_COUNT {
        sort_by_dimension(&mut children, d, false);
        let costs = bucketize_for_cost(&children);
        let total_cost: i64 = costs.iter().map(cost).sum();
        if min_cost > total_cost {
            min_cost = total_cost;
            min_dimension = d;
        }
    }
    // Final pass: re-sort by the winning dimension (absolute), then
    // recursively build each bucket — same shape vanilla produces.
    sort_by_dimension(&mut children, min_dimension, true);
    let buckets = bucketize_take(&mut children);
    let built_children: Vec<Node<T>> = buckets.into_iter().map(build).collect();
    let parameter_space = build_parameter_space(&built_children);
    Node::SubTree { parameter_space, children: built_children }
}

/// Vanilla `bucketize` — split into `expectedChildrenCount`-sized
/// chunks where the count is `6 ^ floor(log_6(n - 0.01))`.
fn bucketize_take<T: Clone>(nodes: &mut Vec<Node<T>>) -> Vec<Vec<Node<T>>> {
    let n = nodes.len();
    let expected = expected_bucket_size(n);
    let mut buckets: Vec<Vec<Node<T>>> = Vec::new();
    let mut current: Vec<Node<T>> = Vec::with_capacity(expected);
    for child in std::mem::take(nodes) {
        current.push(child);
        if current.len() >= expected {
            buckets.push(std::mem::take(&mut current));
            current = Vec::with_capacity(expected);
        }
    }
    if !current.is_empty() {
        buckets.push(current);
    }
    buckets
}

/// Cheaper variant for the cost-evaluation pass: returns each bucket's
/// merged parameter space without taking ownership of the nodes.
fn bucketize_for_cost<T: Clone>(nodes: &[Node<T>]) -> Vec<[Parameter; PARAMETER_COUNT]> {
    let n = nodes.len();
    let expected = expected_bucket_size(n);
    let mut buckets: Vec<[Parameter; PARAMETER_COUNT]> = Vec::new();
    let mut current_count = 0;
    let mut current_space: Option<[Parameter; PARAMETER_COUNT]> = None;
    for child in nodes {
        let space = child.parameter_space();
        current_space = Some(match current_space {
            None => *space,
            Some(prev) => merge_spaces(&prev, space),
        });
        current_count += 1;
        if current_count >= expected {
            buckets.push(current_space.unwrap());
            current_space = None;
            current_count = 0;
        }
    }
    if let Some(space) = current_space {
        buckets.push(space);
    }
    buckets
}

#[inline]
fn expected_bucket_size(n: usize) -> usize {
    // Vanilla: (int)Math.pow(6.0, Math.floor(Math.log(n - 0.01) / Math.log(6.0)))
    let v = ((n as f64) - 0.01).ln() / (6.0_f64).ln();
    let floor = v.floor();
    (6.0_f64.powf(floor)) as usize
}

#[inline]
fn merge_spaces(
    a: &[Parameter; PARAMETER_COUNT],
    b: &[Parameter; PARAMETER_COUNT],
) -> [Parameter; PARAMETER_COUNT] {
    let mut out = *a;
    for d in 0..PARAMETER_COUNT {
        out[d] = a[d].span_with(Some(b[d]));
    }
    out
}

#[inline]
fn cost(space: &[Parameter; PARAMETER_COUNT]) -> i64 {
    let mut sum = 0_i64;
    for p in space.iter() {
        sum = sum.wrapping_add((p.max - p.min).wrapping_abs());
    }
    sum
}

/// Vanilla `RTree.sort(children, dimensions, dimension, absolute)` —
/// composite comparator: sort by `dimension`'s center, breaking ties
/// with `(dimension+1) % 7`, etc.
fn sort_by_dimension<T: Clone>(
    children: &mut [Node<T>],
    dimension: usize,
    absolute: bool,
) {
    children.sort_by(|a, b| {
        for offset in 0..PARAMETER_COUNT {
            let dim = (dimension + offset) % PARAMETER_COUNT;
            let ka = key_for(a, dim, absolute);
            let kb = key_for(b, dim, absolute);
            match ka.cmp(&kb) {
                Ordering::Equal => continue,
                ord => return ord,
            }
        }
        Ordering::Equal
    });
}

#[inline]
fn key_for<T: Clone>(node: &Node<T>, dimension: usize, absolute: bool) -> i64 {
    let center = node.parameter_space()[dimension].center();
    if absolute { center.wrapping_abs() } else { center }
}

fn build_parameter_space<T: Clone>(
    children: &[Node<T>],
) -> [Parameter; PARAMETER_COUNT] {
    assert!(!children.is_empty(), "SubTree needs at least one child");
    // Initialize with the first child's space; merge the rest.
    let mut bounds = *children[0].parameter_space();
    for child in &children[1..] {
        let space = child.parameter_space();
        for d in 0..PARAMETER_COUNT {
            bounds[d] = bounds[d].span_with(Some(space[d]));
        }
    }
    bounds
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quantize_round_trip() {
        // 0.5 * 10000 = 5000 → 5000.0 / 10000 = 0.5
        let q = quantize_coord(0.5);
        assert_eq!(q, 5000);
        assert!((unquantize_coord(q) - 0.5).abs() < 1e-6);
    }

    #[test]
    fn parameter_distance_inside_zero() {
        let p = Parameter::new(-100, 100);
        assert_eq!(p.distance(-100), 0);
        assert_eq!(p.distance(0), 0);
        assert_eq!(p.distance(100), 0);
    }

    #[test]
    fn parameter_distance_above_below() {
        let p = Parameter::new(-100, 100);
        assert_eq!(p.distance(150), 50);   // 50 above max
        assert_eq!(p.distance(-150), 50);  // 50 below min
        assert_eq!(p.distance(101), 1);
        assert_eq!(p.distance(-101), 1);
    }

    #[test]
    fn parameter_span_with() {
        let a = Parameter::new(0, 10);
        let b = Parameter::new(5, 20);
        assert_eq!(a.span_with(Some(b)), Parameter::new(0, 20));
        assert_eq!(a.span_with(None), a);
    }

    #[test]
    fn fitness_inside_target_is_offset_squared() {
        // ParameterPoint covering (0, 0, 0, 0, 0, 0); target inside.
        let pt = ParameterPoint::new(
            Parameter::new(-100, 100), Parameter::new(-100, 100),
            Parameter::new(-100, 100), Parameter::new(-100, 100),
            Parameter::new(-100, 100), Parameter::new(-100, 100),
            17,
        );
        let target = TargetPoint::new(0, 0, 0, 0, 0, 0);
        assert_eq!(pt.fitness(target), 17 * 17);
    }

    #[test]
    fn fitness_outside_sums_squared_distances() {
        let pt = ParameterPoint::new(
            Parameter::new(0, 0), Parameter::new(0, 0),
            Parameter::new(0, 0), Parameter::new(0, 0),
            Parameter::new(0, 0), Parameter::new(0, 0),
            0,
        );
        // target at (3, 4, 0, 0, 0, 0) → distances (3, 4, 0, 0, 0, 0)
        // fitness = 9 + 16 + 0 + 0 + 0 + 0 + 0 = 25
        let target = TargetPoint::new(3, 4, 0, 0, 0, 0);
        assert_eq!(pt.fitness(target), 25);
    }

    /// Single-entry tree: any target returns that value.
    #[test]
    fn single_entry_tree() {
        let pt = ParameterPoint::new(
            Parameter::new(-100, 100), Parameter::new(-100, 100),
            Parameter::new(-100, 100), Parameter::new(-100, 100),
            Parameter::new(-100, 100), Parameter::new(-100, 100),
            0,
        );
        let tree: RTree<u32> = RTree::new(vec![(pt, 42)]);
        assert_eq!(*tree.find_value(TargetPoint::new(0, 0, 0, 0, 0, 0)), 42);
        assert_eq!(*tree.find_value(TargetPoint::new(99999, 0, 0, 0, 0, 0)), 42);
    }

    /// Two-entry tree: target inside one region's interval picks that one.
    #[test]
    fn two_entry_tree_picks_closer() {
        let p1 = ParameterPoint::new(
            Parameter::new(-100, -50), Parameter::new(0, 0),
            Parameter::new(0, 0), Parameter::new(0, 0),
            Parameter::new(0, 0), Parameter::new(0, 0),
            0,
        );
        let p2 = ParameterPoint::new(
            Parameter::new(50, 100), Parameter::new(0, 0),
            Parameter::new(0, 0), Parameter::new(0, 0),
            Parameter::new(0, 0), Parameter::new(0, 0),
            0,
        );
        let tree: RTree<u32> = RTree::new(vec![(p1, 1), (p2, 2)]);
        // target at temp=-75 → inside p1 → biome 1
        assert_eq!(*tree.find_value(TargetPoint::new(-75, 0, 0, 0, 0, 0)), 1);
        // target at temp=75 → inside p2 → biome 2
        assert_eq!(*tree.find_value(TargetPoint::new(75, 0, 0, 0, 0, 0)), 2);
        // target at temp=0 (between) → equal distance to both edges, ties broken
        // by build order / sort. Vanilla's tie behavior is deterministic; we
        // mirror it because we use the same sort comparator.
        // Just verify it returns ONE of the two without panicking.
        let mid = *tree.find_value(TargetPoint::new(0, 0, 0, 0, 0, 0));
        assert!(mid == 1 || mid == 2);
    }

    /// Larger tree — verify that for each registered ParameterPoint, the
    /// tree returns its own value when queried at its center.
    #[test]
    fn many_entries_self_lookup() {
        let mut entries: Vec<(ParameterPoint, u32)> = Vec::new();
        // Build a 3x3x3 grid of regions (27 entries — exercises bucketize).
        let mut id: u32 = 0;
        for ti in -1_i64..=1 {
            for hi in -1_i64..=1 {
                for ci in -1_i64..=1 {
                    let pt = ParameterPoint::new(
                        Parameter::new(ti * 200 - 50, ti * 200 + 50),
                        Parameter::new(hi * 200 - 50, hi * 200 + 50),
                        Parameter::new(ci * 200 - 50, ci * 200 + 50),
                        Parameter::new(0, 0),
                        Parameter::new(0, 0),
                        Parameter::new(0, 0),
                        0,
                    );
                    entries.push((pt, id));
                    id += 1;
                }
            }
        }
        let tree: RTree<u32> = RTree::new(entries.clone());
        // For each entry, query at its center; expect the same biome ID back.
        for (pt, expected) in &entries {
            let target = TargetPoint::new(
                pt.temperature.center(),
                pt.humidity.center(),
                pt.continentalness.center(),
                pt.erosion.center(),
                pt.depth.center(),
                pt.weirdness.center(),
            );
            let got = *tree.find_value(target);
            assert_eq!(got, *expected,
                "self-lookup mismatch: entry {} center sampled to {}", expected, got);
        }
    }

    /// expected_bucket_size matches vanilla's formula at known points.
    #[test]
    fn expected_bucket_size_known_points() {
        // n=7  → 6^floor(log6(6.99))  = 6^1 = 6
        assert_eq!(expected_bucket_size(7), 6);
        // n=36 → 6^floor(log6(35.99)) = 6^1 = 6  (log6(35.99) ≈ 1.999)
        // Wait: log6(35.99) is just under 2, so floor = 1, 6^1 = 6.
        assert_eq!(expected_bucket_size(36), 6);
        // n=37 → log6(36.99) ≈ 2.0001, floor = 2, 6^2 = 36
        assert_eq!(expected_bucket_size(37), 36);
        // n=2  → log6(1.99) ≈ 0.385, floor = 0, 6^0 = 1
        assert_eq!(expected_bucket_size(2), 1);
    }
}
