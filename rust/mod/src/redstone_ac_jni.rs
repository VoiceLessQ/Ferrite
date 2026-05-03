//! JNI entry for the AC offer-based wire propagation kernel.
//!
//! Sister to `redstone_jni.rs`. Same shape: Java packs the wire network
//! into a direct ByteBuffer of `RedstoneAcNode` structs, Rust runs the
//! offer propagation, writes deltas (`RedstoneAcResult`) to the output
//! buffer in priority order. Return value is the count Java should
//! apply.
//!
//! Single-threaded. AC's drain ordering is inherently sequential.

use std::slice;

use rosttasse::jni::objects::{JByteBuffer, JClass};
use rosttasse::jni::sys::jint;
use rosttasse::JNIEnv;

use crate::redstone_ac::{compute_wire_power_ac, RedstoneAcNode, RedstoneAcResult};

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_computeRedstoneAc<'local>(
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

    // Buffer resolution must never panic across the JNI boundary. Any
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

    let request_bytes = count * std::mem::size_of::<RedstoneAcNode>();
    let result_bytes = count * std::mem::size_of::<RedstoneAcResult>();
    if req_cap < request_bytes || res_cap < result_bytes {
        return 0;
    }

    let requests: &[RedstoneAcNode] =
        unsafe { slice::from_raw_parts(req_ptr as *const RedstoneAcNode, count) };
    let results: &mut [RedstoneAcResult] =
        unsafe { slice::from_raw_parts_mut(res_ptr as *mut RedstoneAcResult, count) };

    compute_wire_power_ac(requests, results) as jint
}
