//! JNI entry for the batched cramming push.
//!
//! Unlike physics, cramming has no world state — just packed entity
//! inputs (positions + AABB + flags) in, packed accumulators out.
//! Single-threaded compute: the inner loop accumulates to both
//! endpoints of each pair, which doesn't vectorize to Rayon cleanly
//! without per-thread reduction scaffolding we don't need yet.

use std::slice;

use rosttasse::jni::objects::{JByteBuffer, JClass};
use rosttasse::jni::sys::jint;
use rosttasse::JNIEnv;

use crate::cramming::{compute_cramming, CrammingInput, CrammingResult};

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_computeCramming<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    requests_buf: JByteBuffer<'local>,
    results_buf: JByteBuffer<'local>,
    entity_count: jint,
) {
    let count = entity_count.max(0) as usize;
    if count == 0 {
        return;
    }

    let req_ptr = env
        .get_direct_buffer_address(&requests_buf)
        .expect("requests buffer must be direct");
    let req_cap = env
        .get_direct_buffer_capacity(&requests_buf)
        .expect("requests capacity unavailable");
    let res_ptr = env
        .get_direct_buffer_address(&results_buf)
        .expect("results buffer must be direct");
    let res_cap = env
        .get_direct_buffer_capacity(&results_buf)
        .expect("results capacity unavailable");

    let request_bytes = count * std::mem::size_of::<CrammingInput>();
    let result_bytes = count * std::mem::size_of::<CrammingResult>();
    if req_cap < request_bytes || res_cap < result_bytes {
        // Bounds failure — zero the accessible result prefix so Java
        // can't apply stale accumulators from a prior tick.
        let safe_count = res_cap / std::mem::size_of::<CrammingResult>();
        if safe_count > 0 {
            let results = unsafe {
                slice::from_raw_parts_mut(res_ptr as *mut CrammingResult, safe_count)
            };
            for r in results.iter_mut() {
                *r = CrammingResult::default();
            }
        }
        return;
    }

    let requests: &[CrammingInput] =
        unsafe { slice::from_raw_parts(req_ptr as *const CrammingInput, count) };
    let results: &mut [CrammingResult] =
        unsafe { slice::from_raw_parts_mut(res_ptr as *mut CrammingResult, count) };

    compute_cramming(requests, results);
}
