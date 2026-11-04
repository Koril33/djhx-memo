#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/30.0.16248370}"
export PATH="$HOME/.cargo/bin:$PATH"
export CARGO_TARGET_DIR="$repo_root/core/target"
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-2}"
cd "$repo_root/core"
cargo build --locked --release --lib
cargo run --locked --release --features bindgen --bin bindgen -- generate \
    --library target/release/libdjhx_memo_core.so --language kotlin --no-format \
    --config "$repo_root/android/uniffi.toml" \
    --out-dir "$repo_root/android/app/build/generated/uniffi"
cargo ndk --platform 29 -t arm64-v8a -t x86_64 \
    -o "$repo_root/android/app/src/main/jniLibs" build --locked --release --lib
