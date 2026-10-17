#!/usr/bin/env bash
set -euo pipefail

# Rebuild FFmpeg lite shared libs with 16 KB ELF LOAD alignment.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$ROOT_DIR/.ffmpeg-lite-build/FFmpeg-src"
if [[ ! -f "$SRC_DIR/configure" ]]; then
    SRC_DIR="$ROOT_DIR/.ffmpeg-lite-build/FFmpeg"
fi
[[ -f "$SRC_DIR/configure" ]] || { echo "error: FFmpeg source not found" >&2; exit 1; }

NDK_DIR="${ANDROID_NDK_HOME:-/d/Android/sdk/ndk/27.0.12077973}"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/windows-x86_64"
MIN_SDK="${MIN_SDK:-24}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"
PAGE_ALIGN_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
ABIS="${FFMPEG_ABIS:-arm64-v8a}"
WRAP_DIR="$ROOT_DIR/.ffmpeg-lite-build/wrappers"
TMPDIR="$ROOT_DIR/.ffmpeg-lite-build/tmp"
export TMPDIR
mkdir -p "$WRAP_DIR" "$TMPDIR"

win_path() {
    cygpath -m "$1"
}

write_native_wrapper() {
    local dest="$1"
    local exe="$2"
    shift 2
    {
        echo '#!/bin/bash'
        echo 'args=()'
        echo 'for a in "$@"; do'
        echo '  case "$a" in'
        echo '    /*) args+=("$(cygpath -m "$a")") ;;'
        echo '    *) args+=("$a") ;;'
        echo '  esac'
        echo 'done'
        printf 'exec "%s"' "$(win_path "$exe")"
        for preset in "$@"; do
            printf ' %q' "$preset"
        done
        echo ' "${args[@]}"'
    } > "$dest"
    chmod +x "$dest"
}

write_native_wrapper "$WRAP_DIR/llvm-ar" "$TOOLCHAIN/bin/llvm-ar.exe"
write_native_wrapper "$WRAP_DIR/llvm-nm" "$TOOLCHAIN/bin/llvm-nm.exe"
write_native_wrapper "$WRAP_DIR/llvm-strip" "$TOOLCHAIN/bin/llvm-strip.exe"
write_native_wrapper "$WRAP_DIR/llvm-ranlib" "$TOOLCHAIN/bin/llvm-ranlib.exe"
write_native_wrapper "$WRAP_DIR/aarch64-cc" "$TOOLCHAIN/bin/clang.exe" --target="aarch64-linux-android${MIN_SDK}"
write_native_wrapper "$WRAP_DIR/aarch64-cxx" "$TOOLCHAIN/bin/clang++.exe" --target="aarch64-linux-android${MIN_SDK}"
write_native_wrapper "$WRAP_DIR/armv7-cc" "$TOOLCHAIN/bin/clang.exe" --target="armv7a-linux-androideabi${MIN_SDK}"
write_native_wrapper "$WRAP_DIR/armv7-cxx" "$TOOLCHAIN/bin/clang++.exe" --target="armv7a-linux-androideabi${MIN_SDK}"

build_one() {
    local abi="$1"
    local arch cpu cc cxx extra_cflags extra_ldflags
    case "$abi" in
        arm64-v8a)
            arch="aarch64"
            cpu="armv8-a"
            cc="$WRAP_DIR/aarch64-cc"
            cxx="$WRAP_DIR/aarch64-cxx"
            extra_cflags="-Os -fPIC"
            extra_ldflags="$PAGE_ALIGN_LDFLAGS"
            ;;
        armeabi-v7a)
            arch="arm"
            cpu="armv7-a"
            cc="$WRAP_DIR/armv7-cc"
            cxx="$WRAP_DIR/armv7-cxx"
            extra_cflags="-Os -fPIC -march=armv7-a -mfpu=neon -mfloat-abi=softfp"
            extra_ldflags="-Wl,--fix-cortex-a8 $PAGE_ALIGN_LDFLAGS"
            ;;
        *)
            echo "error: unsupported ABI: $abi" >&2
            exit 1
            ;;
    esac

    local build_path="$ROOT_DIR/.ffmpeg-lite-build/build/$abi"
    local prefix_path="$ROOT_DIR/.ffmpeg-lite-build/prefix/$abi"
    rm -rf "$build_path"
    mkdir -p "$build_path" "$prefix_path"

    echo "Configuring FFmpeg for $abi..."
    (
        cd "$build_path"
        "$SRC_DIR/configure" \
            --prefix="$prefix_path" \
            --target-os=android \
            --enable-cross-compile \
            --arch="$arch" \
            --cpu="$cpu" \
            --cc="$cc" \
            --cxx="$cxx" \
            --ar="$WRAP_DIR/llvm-ar" \
            --nm="$WRAP_DIR/llvm-nm" \
            --strip="$WRAP_DIR/llvm-strip" \
            --ranlib="$WRAP_DIR/llvm-ranlib" \
            --sysroot="$(win_path "$TOOLCHAIN/sysroot")" \
            --extra-cflags="$extra_cflags" \
            --extra-ldflags="$extra_ldflags" \
            --enable-shared \
            --disable-static \
            --disable-doc \
            --disable-programs \
            --disable-debug \
            --disable-network \
            --disable-autodetect \
            --disable-everything \
            --enable-small \
            --disable-avdevice \
            --disable-avfilter \
            --disable-postproc \
            --disable-swresample \
            --enable-avcodec \
            --enable-avformat \
            --enable-avutil \
            --enable-swscale \
            --enable-decoder=h264 \
            --enable-decoder=hevc \
            --enable-decoder=rawvideo \
            --enable-parser=h264 \
            --enable-parser=hevc \
            --enable-demuxer=mov \
            --enable-demuxer=h264 \
            --enable-demuxer=hevc \
            --enable-muxer=rawvideo \
            --enable-protocol=file \
            --enable-protocol=pipe \
            --enable-bsf=h264_mp4toannexb \
            --enable-bsf=hevc_mp4toannexb \
            --disable-symver

        echo "Building FFmpeg for $abi..."
        make -j"$JOBS"
        make install
    )
}

copy_built_libs() {
    local abi="$1"
    local src_dir="$ROOT_DIR/.ffmpeg-lite-build/prefix/$abi/lib"
    local dst_dir="$ROOT_DIR/local-maven-tmp/ffmpeg-lite/jni/$abi"
    mkdir -p "$dst_dir"
    for lib in libavcodec libavformat libavutil libswscale; do
        local src
        src="$(ls -1 "$src_dir/$lib.so"* 2>/dev/null | head -n 1)"
        [[ -n "$src" && -e "$src" ]] || { echo "error: missing $lib for $abi" >&2; exit 1; }
        cp -L "$src" "$dst_dir/$lib.so"
        echo "Updated $dst_dir/$lib.so"
    done
}

for abi in $ABIS; do
    build_one "$abi"
    copy_built_libs "$abi"
done

echo "FFmpeg lite rebuild complete."
