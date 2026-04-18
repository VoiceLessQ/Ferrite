//! Particle-based hydraulic erosion for heightmaps.
//!
//! Each iteration spawns a single water droplet at a random position.
//! The droplet slides downhill, carrying sediment — picking it up on steep
//! slopes, depositing it in flat or uphill areas. After a fixed lifetime
//! (or when it runs out of water) the droplet dies. Running many droplets
//! produces natural-looking valleys, ridgelines, and river traces.
//!
//! Reference: the classic Hans-Olsen / Sebastian-Lague style particle model.
//! Single-threaded on purpose — droplets read-modify-write shared heightmap
//! cells, so parallelising them changes the result (and introduces races).

use rand::rngs::StdRng;
use rand::{Rng, SeedableRng};

use rosttasse::jni::objects::{JByteBuffer, JClass};
use rosttasse::jni::sys::{jint, jlong};
use rosttasse::JNIEnv;

// ---------------------------------------------------------------------------
// Parameters (hardcoded per Phase 1 spec — tune later once we see output)
// ---------------------------------------------------------------------------

const INERTIA: f32 = 0.05;
const CAPACITY_FACTOR: f32 = 4.0;
const DEPOSITION: f32 = 0.3;
const EROSION: f32 = 0.3;
const EVAPORATION: f32 = 0.02;
const MIN_SLOPE: f32 = 0.01;
const GRAVITY: f32 = 4.0;
const MAX_LIFETIME: u32 = 30;
const INITIAL_WATER: f32 = 1.0;
const INITIAL_SPEED: f32 = 1.0;

// ---------------------------------------------------------------------------
// Pure algorithm (no JNI, no I/O)
// ---------------------------------------------------------------------------

pub fn erode(heightmap: &mut [f32], width: usize, height: usize, iterations: u32, seed: u64) {
    assert_eq!(
        heightmap.len(),
        width * height,
        "heightmap length must equal width * height"
    );
    if width < 2 || height < 2 {
        return;
    }

    let mut rng = StdRng::seed_from_u64(seed);
    let max_x = (width - 1) as f32;
    let max_y = (height - 1) as f32;

    for _ in 0..iterations {
        simulate_droplet(heightmap, width, height, &mut rng, max_x, max_y);
    }
}

fn simulate_droplet(
    hm: &mut [f32],
    width: usize,
    height: usize,
    rng: &mut StdRng,
    max_x: f32,
    max_y: f32,
) {
    let mut pos_x: f32 = rng.gen_range(0.0..max_x);
    let mut pos_y: f32 = rng.gen_range(0.0..max_y);
    let mut dir_x: f32 = 0.0;
    let mut dir_y: f32 = 0.0;
    let mut speed: f32 = INITIAL_SPEED;
    let mut water: f32 = INITIAL_WATER;
    let mut sediment: f32 = 0.0;

    for _ in 0..MAX_LIFETIME {
        // Integer cell + fractional position at the droplet's OLD location.
        // We keep these to deposit / erode at the cell we're leaving.
        let node_x = pos_x as usize;
        let node_y = pos_y as usize;
        let frac_x = pos_x - node_x as f32;
        let frac_y = pos_y - node_y as f32;

        let (height_here, grad_x, grad_y) =
            sample_height_and_gradient(hm, width, height, pos_x, pos_y);

        // Update direction: blend old direction (inertia) with downhill gradient.
        dir_x = dir_x * INERTIA - grad_x * (1.0 - INERTIA);
        dir_y = dir_y * INERTIA - grad_y * (1.0 - INERTIA);

        // Normalize direction to unit step.
        let len = (dir_x * dir_x + dir_y * dir_y).sqrt();
        if len > 0.0 {
            dir_x /= len;
            dir_y /= len;
        } else {
            // Stuck on flat ground — stop.
            break;
        }

        pos_x += dir_x;
        pos_y += dir_y;

        // Left the grid — drop any carried sediment at the exit cell, then stop.
        if pos_x < 0.0 || pos_x >= max_x || pos_y < 0.0 || pos_y >= max_y {
            if sediment > 0.0 {
                deposit_bilinear(hm, width, node_x, node_y, frac_x, frac_y, sediment);
            }
            break;
        }

        let (new_height, _, _) = sample_height_and_gradient(hm, width, height, pos_x, pos_y);
        let delta_h = new_height - height_here;

        // Carrying capacity: steeper downhill + faster + more water = more load.
        let capacity = (-delta_h).max(MIN_SLOPE) * speed * water * CAPACITY_FACTOR;

        if sediment > capacity || delta_h > 0.0 {
            // Either over capacity, or we're going uphill — drop sediment.
            let to_deposit = if delta_h > 0.0 {
                // Moving uphill: fill the step we just climbed, capped at carried.
                delta_h.min(sediment)
            } else {
                (sediment - capacity) * DEPOSITION
            };
            sediment -= to_deposit;
            deposit_bilinear(hm, width, node_x, node_y, frac_x, frac_y, to_deposit);
        } else {
            // Under capacity and heading downhill — erode. Clamp so we never
            // cut a hole deeper than the downhill step we just took.
            let to_erode = ((capacity - sediment) * EROSION).min(-delta_h);
            erode_bilinear(hm, width, node_x, node_y, frac_x, frac_y, to_erode);
            sediment += to_erode;
        }

        // Gain speed as we fall, lose water to evaporation.
        speed = (speed * speed + delta_h * GRAVITY).max(0.0).sqrt();
        water *= 1.0 - EVAPORATION;

        if water < 0.01 {
            break;
        }
    }
}

/// Bilinear height sample + gradient at an interior (x, y) position.
/// Assumes `0 <= pos_x < width - 1` and `0 <= pos_y < height - 1`.
fn sample_height_and_gradient(
    hm: &[f32],
    width: usize,
    _height: usize,
    pos_x: f32,
    pos_y: f32,
) -> (f32, f32, f32) {
    let node_x = pos_x as usize;
    let node_y = pos_y as usize;
    let frac_x = pos_x - node_x as f32;
    let frac_y = pos_y - node_y as f32;

    let nw = hm[node_y * width + node_x];
    let ne = hm[node_y * width + node_x + 1];
    let sw = hm[(node_y + 1) * width + node_x];
    let se = hm[(node_y + 1) * width + node_x + 1];

    // Gradient via forward differences along each axis, bilinearly blended.
    let grad_x = (ne - nw) * (1.0 - frac_y) + (se - sw) * frac_y;
    let grad_y = (sw - nw) * (1.0 - frac_x) + (se - ne) * frac_x;

    let h = nw * (1.0 - frac_x) * (1.0 - frac_y)
        + ne * frac_x * (1.0 - frac_y)
        + sw * (1.0 - frac_x) * frac_y
        + se * frac_x * frac_y;

    (h, grad_x, grad_y)
}

fn deposit_bilinear(
    hm: &mut [f32],
    width: usize,
    nx: usize,
    ny: usize,
    fx: f32,
    fy: f32,
    amount: f32,
) {
    hm[ny * width + nx] += amount * (1.0 - fx) * (1.0 - fy);
    hm[ny * width + nx + 1] += amount * fx * (1.0 - fy);
    hm[(ny + 1) * width + nx] += amount * (1.0 - fx) * fy;
    hm[(ny + 1) * width + nx + 1] += amount * fx * fy;
}

fn erode_bilinear(
    hm: &mut [f32],
    width: usize,
    nx: usize,
    ny: usize,
    fx: f32,
    fy: f32,
    amount: f32,
) {
    hm[ny * width + nx] -= amount * (1.0 - fx) * (1.0 - fy);
    hm[ny * width + nx + 1] -= amount * fx * (1.0 - fy);
    hm[(ny + 1) * width + nx] -= amount * (1.0 - fx) * fy;
    hm[(ny + 1) * width + nx + 1] -= amount * fx * fy;
}

// ---------------------------------------------------------------------------
// JNI entry point
// ---------------------------------------------------------------------------

/// Erode a direct ByteBuffer reinterpreted as f32 heightmap of size
/// `width * height` in row-major order. `iterations` is the number of
/// droplets to simulate. `seed` drives droplet spawn positions.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_erodeHeightmap<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    buffer: JByteBuffer<'local>,
    width: jint,
    height: jint,
    iterations: jint,
    seed: jlong,
) {
    let ptr = env
        .get_direct_buffer_address(&buffer)
        .expect("buffer must be a direct ByteBuffer");
    let byte_len = env
        .get_direct_buffer_capacity(&buffer)
        .expect("buffer capacity unavailable");

    let width = width as usize;
    let height = height as usize;
    let expected_bytes = width * height * 4;
    assert!(
        byte_len >= expected_bytes,
        "erode buffer too small: {} bytes, need {} for {}x{} f32 heightmap",
        byte_len,
        expected_bytes,
        width,
        height,
    );

    let slice: &mut [f32] =
        unsafe { std::slice::from_raw_parts_mut(ptr as *mut f32, width * height) };

    erode(slice, width, height, iterations as u32, seed as u64);
}
