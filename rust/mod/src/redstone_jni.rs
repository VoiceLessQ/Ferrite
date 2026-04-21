//! JNI entry for the redstone wire-power BFS.
//!
//! Java serializes the connected wire network (typically ≤64 nodes per
//! gate tick) into a direct ByteBuffer of RedstoneNode structs. Rust
//! runs the relaxation and writes deltas (RedstoneResult) to the output
//! buffer; return value is the count of delta entries Java should apply.
//!
//! Single-threaded compute — a typical network is small enough that
//! Rayon's pool setup overhead would dominate the BFS work itself.
//! Keep in reserve for v2 if profiling finds networks large enough
//! that per-node work dominates.

use std::slice;

use rosttasse::jni::objects::{JByteBuffer, JClass};
use rosttasse::jni::sys::jint;
use rosttasse::JNIEnv;

use crate::redstone::{compute_wire_power, RedstoneNode, RedstoneResult};

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_computeRedstoneBfs<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    requests_buf: JByteBuffer<'local>,
    results_buf: JByteBuffer<'local>,
    node_count: jint,
) -> jint {
    let count = node_count.max(0) as usize;
    if count == 0 {
        return 0;
    }

    // Buffer resolution must never panic across the JNI boundary; any
    // failure here returns 0 deltas so Java applies nothing this tick.
    let Some(req_ptr) = env.get_direct_buffer_address(&requests_buf).ok() else {
        return 0;
    };
    let Some(req_cap) = env.get_direct_buffer_capacity(&requests_buf).ok() else {
        return 0;
    };
    let Some(res_ptr) = env.get_direct_buffer_address(&results_buf).ok() else {
        return 0;
    };
    let Some(res_cap) = env.get_direct_buffer_capacity(&results_buf).ok() else {
        return 0;
    };

    let request_bytes = count * std::mem::size_of::<RedstoneNode>();
    let result_bytes = count * std::mem::size_of::<RedstoneResult>();
    if req_cap < request_bytes || res_cap < result_bytes {
        // Bounds failure — return 0 so Java applies no deltas.
        return 0;
    }

    let requests: &[RedstoneNode] =
        unsafe { slice::from_raw_parts(req_ptr as *const RedstoneNode, count) };
    let results: &mut [RedstoneResult] =
        unsafe { slice::from_raw_parts_mut(res_ptr as *mut RedstoneResult, count) };

    compute_wire_power(requests, results) as jint
}
