//! Boundary-tax benchmark kernels: the same three ops exported twice,
//! once over the C ABI (for java.lang.foreign downcalls) and once over
//! JNI, so `/ferrite ffm bench` can measure both boundaries inside the
//! live game JVM.

use rosttasse::jni::objects::{JClass, JPrimitiveArray, ReleaseMode};
use rosttasse::jni::sys::{jdouble, jint, jlong};
use rosttasse::JNIEnv;

fn sum_ints(slice: &[i32]) -> i64 {
    slice.iter().map(|&v| v as i64).sum()
}

fn fill_doubles(slice: &mut [f64], seed: f64) {
    for (i, v) in slice.iter_mut().enumerate() {
        *v = seed + i as f64 * 0.5;
    }
}

// C ABI, reached via FFM downcall handles

#[no_mangle]
pub extern "C" fn ffm_noop() {}

/// # Safety
/// `ptr` must point to `len` readable i32s.
#[no_mangle]
pub unsafe extern "C" fn ffm_sum(ptr: *const i32, len: usize) -> i64 {
    sum_ints(std::slice::from_raw_parts(ptr, len))
}

/// # Safety
/// `ptr` must point to `len` writable f64s.
#[no_mangle]
pub unsafe extern "C" fn ffm_fill(ptr: *mut f64, len: usize, seed: f64) {
    fill_doubles(std::slice::from_raw_parts_mut(ptr, len), seed);
}

// JNI, same ops

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_ffmBenchNoop(_env: JNIEnv, _class: JClass) {}

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_ffmBenchSum<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    arr: JPrimitiveArray<'local, jint>,
) -> jlong {
    let elems = unsafe { env.get_array_elements_critical(&arr, ReleaseMode::NoCopyBack) }
        .expect("ffmBenchSum: critical access");
    let slice: &[i32] =
        unsafe { std::slice::from_raw_parts(elems.as_ptr() as *const i32, elems.len()) };
    sum_ints(slice)
}

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_ffmBenchFill<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    arr: JPrimitiveArray<'local, jdouble>,
    seed: f64,
) {
    let len = env.get_array_length(&arr).expect("ffmBenchFill: len") as usize;
    let mut buf = vec![0f64; len];
    fill_doubles(&mut buf, seed);
    env.set_double_array_region(&arr, 0, &buf)
        .expect("ffmBenchFill: region write");
}
