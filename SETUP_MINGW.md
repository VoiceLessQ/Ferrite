# MinGW Setup Instructions

You need MinGW-w64 to build Rust with the GNU toolchain. Here are your options:

## Option 1: Download Portable MinGW (Easiest)
1. Download from: https://github.com/niXman/mingw-builds-binaries/releases/download/15.2.0-rt_v12-rev0/x86_64-15.2.0-release-posix-seh-ucrt-rt_v12-rev0.7z
2. Extract to `C:\mingw64\`
3. Add to PATH: `C:\mingw64\bin`
4. Restart PowerShell
5. Run: `cargo build --release --manifest-path=rust/mod/Cargo.toml`

## Option 2: Install MSYS2 (Recommended for long-term)
1. Download: https://www.msys2.org/
2. Install to default location `C:\msys64`
3. Open MSYS2 terminal and run: `pacman -S mingw-w64-x86_64-gcc`
4. Add to PATH: `C:\msys64\mingw64\bin`
5. Restart PowerShell
6. Run: `cargo build --release --manifest-path=rust/mod/Cargo.toml`

## Option 3: Fix Visual Studio (If you prefer MSVC)
1. Open "Visual Studio Installer"
2. Click "Modify" on VS 2022 Community
3. Ensure these are checked:
   - "Desktop development with C++"
   - Under "Individual Components": "Windows 11 SDK" (or Windows 10 SDK)
4. Click "Modify" to install
5. Delete `.cargo\config.toml` in this project
6. Run: `cargo build --release --manifest-path=rust/mod/Cargo.toml`

After successful build, the DLL will be at:
- GNU: `rust\mod\target\x86_64-pc-windows-gnu\release\rust_mod.dll`
- MSVC: `rust\mod\target\release\rust_mod.dll`
