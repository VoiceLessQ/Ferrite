//! JNI bench harness for the Rust redstone priority queue.
//!
//! Runs a workload composed entirely of "offer N then poll N" — same
//! operations the Java side runs — so the comparison is apples-to-apples
//! after JNI overhead is counted.
//!
//! Single batch entry per workload to amortize the boundary cost. The
//! Java side decides N; this code does fixed offer-then-poll.

use std::slice;

use rosttasse::jni::objects::{JByteBuffer, JClass};
use rosttasse::jni::sys::jint;
use rosttasse::JNIEnv;

use crate::redstone_queues::PriorityQueue;

/// Inputs:
///   pairs_buf — n × (u32 id, u8 priority) packed = 5 bytes per entry
///   results_buf — n × u32 ids in poll order
///   n — number of items
///
/// Operation: offer all pairs, then poll all. Mirrors the Java bench
/// shape exactly so the comparison is valid.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_benchRedstoneQueue<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    pairs_buf: JByteBuffer<'local>,
    results_buf: JByteBuffer<'local>,
    n: jint,
) {
    let count = n.max(0) as usize;
    if count == 0 {
        return;
    }

    let pairs_bytes = match get_byte_slice(&env, &pairs_buf, count * 5) {
        Some(s) => s,
        None => return,
    };
    let results_bytes = match get_byte_slice_mut(&env, &results_buf, count * 4) {
        Some(s) => s,
        None => return,
    };

    // Pre-size to avoid mid-bench allocation noise.
    let mut q = PriorityQueue::with_capacity(count / 8);

    // Offer phase.
    for i in 0..count {
        let off = i * 5;
        let id = (pairs_bytes[off] as u32)
            | ((pairs_bytes[off + 1] as u32) << 8)
            | ((pairs_bytes[off + 2] as u32) << 16)
            | ((pairs_bytes[off + 3] as u32) << 24);
        let priority = pairs_bytes[off + 4];
        q.offer(id, priority);
    }

    // Poll phase.
    for i in 0..count {
        if let Some(id) = q.poll() {
            let off = i * 4;
            results_bytes[off]     = (id        & 0xFF) as u8;
            results_bytes[off + 1] = ((id >> 8) & 0xFF) as u8;
            results_bytes[off + 2] = ((id >> 16) & 0xFF) as u8;
            results_bytes[off + 3] = ((id >> 24) & 0xFF) as u8;
        }
    }
}

fn get_byte_slice<'a, 'env>(
    env: &JNIEnv<'env>,
    buf: &'a JByteBuffer<'env>,
    len: usize,
) -> Option<&'a [u8]> {
    let ptr = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    if cap < len { return None; }
    Some(unsafe { slice::from_raw_parts(ptr, len) })
}

fn get_byte_slice_mut<'a, 'env>(
    env: &JNIEnv<'env>,
    buf: &'a JByteBuffer<'env>,
    len: usize,
) -> Option<&'a mut [u8]> {
    let ptr = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    if cap < len { return None; }
    Some(unsafe { slice::from_raw_parts_mut(ptr, len) })
}
