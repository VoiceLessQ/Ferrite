use std::sync::OnceLock;
use std::thread::available_parallelism;

use rosttasse::jni::objects::JClass;
use rosttasse::jni::sys::jint;
use rosttasse::JNIEnv;

static ENGINE: OnceLock<EngineConfig> = OnceLock::new();
static POOL_INIT: std::sync::Once = std::sync::Once::new();

/// Builds the sized global Rayon pool on first parallel use. Deferred out
/// of initEngine so a session that never hits a parallel path (all Rayon
/// consumers are default-off) spawns no worker threads. Must be called
/// before any rayon:: use, or Rayon auto-builds an all-cores default pool.
pub fn ensure_pool() {
    POOL_INIT.call_once(|| {
        let cores = available_parallelism().map(|n| n.get()).unwrap_or(1);
        let threads = pick_thread_count(cores);
        // If a pool was already set elsewhere this errors; first-writer-wins.
        let _ = rayon::ThreadPoolBuilder::new()
            .num_threads(threads)
            .build_global();
    });
}

pub struct EngineConfig {
    pub thread_count: usize,
}

fn pick_thread_count(cores: usize) -> usize {
    match cores {
        0..=4 => 2,
        5..=8 => 4,
        _ => 6,
    }
}

// Unused since the Rayon strip; kept as the accessor future kernels use.
#[allow(dead_code)]
pub fn engine() -> &'static EngineConfig {
    ENGINE
        .get()
        .expect("engine not initialized — call RustBridge.initEngine() first")
}

/// Returns the thread count chosen for the Rayon pool, so Java can log it.
/// Safe to call multiple times — only initializes once.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_initEngine<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    let cfg = ENGINE.get_or_init(|| {
        let cores = available_parallelism().map(|n| n.get()).unwrap_or(1);
        let threads = pick_thread_count(cores);

        // Probe SIMD capability for the SIMD-noise port plan
        // (see docs/PIANO_STATUS.md → SIMD batch noise). Decides whether
        // we target f64x4 (AVX2, ~4× win) or f64x8 (AVX-512, ~8× win).
        #[cfg(target_arch = "x86_64")]
        {
            let avx512f = std::arch::is_x86_feature_detected!("avx512f");
            let avx2 = std::arch::is_x86_feature_detected!("avx2");
            let sse42 = std::arch::is_x86_feature_detected!("sse4.2");
            eprintln!(
                "[ferrite] SIMD: avx512f={} avx2={} sse4.2={}",
                avx512f, avx2, sse42
            );
        }
        #[cfg(not(target_arch = "x86_64"))]
        {
            eprintln!("[ferrite] SIMD: non-x86_64 host (target_arch={})", std::env::consts::ARCH);
        }

        EngineConfig {
            thread_count: threads,
        }
    });

    cfg.thread_count as jint
}
