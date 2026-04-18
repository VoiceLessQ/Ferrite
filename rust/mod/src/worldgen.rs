use noise::{NoiseFn, Perlin};
use rayon::prelude::*;
use rosttasse::jni::objects::{JByteBuffer, JClass};
use rosttasse::jni::sys::{jint, jlong};
use rosttasse::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_generateHeightmap<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    buffer: JByteBuffer<'local>,
    seed: jlong,
    origin_x: jint,
    origin_z: jint,
    size: jint,
) {
    let ptr = env
        .get_direct_buffer_address(&buffer)
        .expect("buffer must be a direct ByteBuffer");
    let byte_len = env
        .get_direct_buffer_capacity(&buffer)
        .expect("buffer capacity unavailable");

    let size = size as usize;
    let expected_bytes = size * size * 4;
    assert!(
        byte_len >= expected_bytes,
        "buffer too small: {} bytes, need {}",
        byte_len,
        expected_bytes,
    );

    let slice: &mut [f32] =
        unsafe { std::slice::from_raw_parts_mut(ptr as *mut f32, size * size) };

    let perlin = Perlin::new(seed as u32);
    let scale = 1.0 / 64.0;

    slice
        .par_chunks_mut(size)
        .enumerate()
        .for_each(|(row, line)| {
            let wz = origin_z as f64 + row as f64;
            for col in 0..size {
                let wx = origin_x as f64 + col as f64;
                let n = perlin.get([wx * scale, wz * scale]);
                line[col] = n as f32;
            }
        });
}
