use std::sync::OnceLock;
use std::thread::available_parallelism;

use rosttasse::jni::objects::JClass;
use rosttasse::jni::sys::jint;
use rosttasse::JNIEnv;

static ENGINE: OnceLock<EngineConfig> = OnceLock::new();

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

        // Set the global Rayon pool size. If it was already set elsewhere this
        // will error; we ignore that — first-writer-wins is the intended policy.
        let _ = rayon::ThreadPoolBuilder::new()
            .num_threads(threads)
            .build_global();

        EngineConfig {
            thread_count: threads,
        }
    });

    cfg.thread_count as jint
}
