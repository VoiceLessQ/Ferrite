@echo off
echo Building Rust library...
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
cargo build --release --manifest-path=rust/mod/Cargo.toml
if %ERRORLEVEL% EQU 0 (
    echo Build successful!
    echo DLL location: rust\mod\target\release\rust_mod.dll
) else (
    echo Build failed!
)
pause
