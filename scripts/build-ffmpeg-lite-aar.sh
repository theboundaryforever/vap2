#!/usr/bin/env bash
set -euo pipefail

FFMPEG_TAG="${FFMPEG_TAG:-n6.1.2}"
MIN_SDK="${MIN_SDK:-24}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$ROOT_DIR/.ffmpeg-lite-build"
SRC_DIR="$WORK_DIR/FFmpeg"
BUILD_DIR="$WORK_DIR/build"
PREFIX_DIR="$WORK_DIR/prefix"
AAR_STAGING_DIR="$WORK_DIR/aar-staging"
OUTPUT_DIR="$ROOT_DIR/local-libs"
OUTPUT_AAR="$OUTPUT_DIR/ffmpeg-lite-release.aar"

NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-/d/Android/sdk/ndk/27.0.12077973}}"
NDK_HOST_TAG="windows-x86_64"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$NDK_HOST_TAG"
PAGE_ALIGN_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"

die() {
    echo "error: $*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "missing command: $1"
}

find_jar() {
    if [[ -n "${JAVA_HOME:-}" ]]; then
        local java_home
        java_home="$(cygpath -u "$JAVA_HOME" 2>/dev/null || echo "$JAVA_HOME")"
        if [[ -x "$java_home/bin/jar.exe" ]]; then
            echo "$java_home/bin/jar.exe"
            return
        fi
        if [[ -x "$java_home/bin/jar" ]]; then
            echo "$java_home/bin/jar"
            return
        fi
    fi

    if command -v jar >/dev/null 2>&1; then
        command -v jar
        return
    fi

    die "missing jar command; set JAVA_HOME to a JDK"
}

configure_common_flags=(
    --target-os=android
    --enable-cross-compile
    --enable-shared
    --disable-static
    --disable-doc
    --disable-programs
    --disable-debug
    --disable-network
    --disable-autodetect
    --disable-everything
    --enable-small
    --disable-avdevice
    --disable-avfilter
    --disable-postproc
    --disable-swresample
    --enable-avcodec
    --enable-avformat
    --enable-avutil
    --enable-swscale
    --enable-decoder=h264
    --enable-decoder=hevc
    --enable-decoder=rawvideo
    --enable-parser=h264
    --enable-parser=hevc
    --enable-demuxer=mov
    --enable-demuxer=h264
    --enable-demuxer=hevc
    --enable-muxer=rawvideo
    --enable-protocol=file
    --enable-protocol=pipe
    --enable-bsf=h264_mp4toannexb
    --enable-bsf=hevc_mp4toannexb
    --disable-symver
)

prepare_source() {
    mkdir -p "$WORK_DIR" "$OUTPUT_DIR"

    if [[ ! -d "$SRC_DIR/.git" && -d "$WORK_DIR/FFmpeg-src/.git" ]]; then
        SRC_DIR="$WORK_DIR/FFmpeg-src"
        echo "Using existing FFmpeg source: $SRC_DIR"
    elif [[ ! -d "$SRC_DIR/.git" ]]; then
        echo "Cloning FFmpeg $FFMPEG_TAG..."
        rm -rf "$SRC_DIR"
        git clone --depth 1 --branch "$FFMPEG_TAG" https://github.com/FFmpeg/FFmpeg.git "$SRC_DIR"
    else
        echo "Using existing FFmpeg source: $SRC_DIR"
    fi
}

build_one_abi() {
    local abi="$1"
    local arch cpu cc cxx extra_cflags extra_ldflags

    case "$abi" in
        arm64-v8a)
            arch="aarch64"
            cpu="armv8-a"
            cc="$TOOLCHAIN/bin/aarch64-linux-android${MIN_SDK}-clang"
            cxx="$TOOLCHAIN/bin/aarch64-linux-android${MIN_SDK}-clang++"
            extra_cflags="-Os -fPIC"
            extra_ldflags="$PAGE_ALIGN_LDFLAGS"
            ;;
        armeabi-v7a)
            arch="arm"
            cpu="armv7-a"
            cc="$TOOLCHAIN/bin/armv7a-linux-androideabi${MIN_SDK}-clang"
            cxx="$TOOLCHAIN/bin/armv7a-linux-androideabi${MIN_SDK}-clang++"
            extra_cflags="-Os -fPIC -march=armv7-a -mfpu=neon -mfloat-abi=softfp"
            extra_ldflags="-Wl,--fix-cortex-a8 $PAGE_ALIGN_LDFLAGS"
            ;;
        *)
            die "unsupported ABI: $abi"
            ;;
    esac

    [[ -x "$cc" ]] || die "missing compiler: $cc"

    local build_path="$BUILD_DIR/$abi"
    local prefix_path="$PREFIX_DIR/$abi"

    rm -rf "$build_path" "$prefix_path"
    mkdir -p "$build_path" "$prefix_path"

    echo "Configuring FFmpeg for $abi..."
    (
        cd "$build_path"
        "$SRC_DIR/configure" \
            --prefix="$prefix_path" \
            --arch="$arch" \
            --cpu="$cpu" \
            --cc="$cc" \
            --cxx="$cxx" \
            --ar="$TOOLCHAIN/bin/llvm-ar" \
            --nm="$TOOLCHAIN/bin/llvm-nm" \
            --strip="$TOOLCHAIN/bin/llvm-strip" \
            --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
            --sysroot="$TOOLCHAIN/sysroot" \
            --extra-cflags="$extra_cflags" \
            --extra-ldflags="$extra_ldflags" \
            "${configure_common_flags[@]}"

        echo "Building FFmpeg for $abi..."
        make -j"$JOBS"
        make install
    )
}

copy_so() {
    local abi="$1"
    local lib="$2"
    local src="$PREFIX_DIR/$abi/lib/$lib.so"
    local dst="$AAR_STAGING_DIR/jni/$abi/$lib.so"

    [[ -e "$src" ]] || die "missing built library: $src"
    cp -L "$src" "$dst"
}

assemble_aar() {
    local jar_cmd
    jar_cmd="$(find_jar)"

    rm -rf "$AAR_STAGING_DIR" "$OUTPUT_AAR"
    mkdir -p "$AAR_STAGING_DIR/jni/arm64-v8a" "$AAR_STAGING_DIR/jni/armeabi-v7a"

    cat > "$AAR_STAGING_DIR/AndroidManifest.xml" <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
EOF

    mkdir -p "$AAR_STAGING_DIR/classes-empty/META-INF"
    cat > "$AAR_STAGING_DIR/classes-empty/META-INF/MANIFEST.MF" <<'EOF'
Manifest-Version: 1.0
EOF
    "$jar_cmd" cf "$AAR_STAGING_DIR/classes.jar" -C "$AAR_STAGING_DIR/classes-empty" .
    rm -rf "$AAR_STAGING_DIR/classes-empty"

    for abi in arm64-v8a armeabi-v7a; do
        copy_so "$abi" libavcodec
        copy_so "$abi" libavformat
        copy_so "$abi" libavutil
        copy_so "$abi" libswscale
    done

    (
        cd "$AAR_STAGING_DIR"
        zip -qr "$OUTPUT_AAR" AndroidManifest.xml classes.jar jni
    )

    echo "Generated: $OUTPUT_AAR"
}

main() {
    require_command git
    require_command make
    if ! command -v pkgconf >/dev/null 2>&1 && ! command -v pkg-config >/dev/null 2>&1; then
        echo "warning: pkgconf/pkg-config not found; continuing because autodetect is disabled"
    fi
    require_command zip

    [[ -d "$TOOLCHAIN" ]] || die "missing Android NDK toolchain: $TOOLCHAIN"

    prepare_source
    build_one_abi arm64-v8a
    build_one_abi armeabi-v7a
    assemble_aar
}

main "$@"
