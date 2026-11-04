#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK="$ROOT/work/runtime-build"
CACHE="$ROOT/.runtime-cache"
mkdir -p "$WORK" "$CACHE"

DROIDSPACES_COMMIT=b01a81197f57115da465af6ef196f65a2bd10c27
DROIDSPACES_RELEASE_APK=Droidspaces-universal-v7.1.0-4344dc5.apk
DROIDSPACES_RELEASE_SHA256=36ab4466c732dc6d47136013ebab9a6c1742f38ced41a3d2acf0f7f155b930b6
PROOT_DISTRO_COMMIT=e1f5351e0b0b77d92876101a32179bae4e1ad97a

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
    echo "Docker is required to build pinned runtime assets." >&2
    exit 1
fi

clone_pinned() {
    local url=$1
    local commit=$2
    local target=$3
    if [ ! -d "$target/.git" ]; then
        git clone --filter=blob:none "$url" "$target"
    fi
    git -C "$target" fetch --depth 1 origin "$commit"
    git -C "$target" checkout --detach "$commit"
}

clone_pinned https://github.com/termux/proot-distro.git "$PROOT_DISTRO_COMMIT" "$WORK/proot-distro"

echo "[1/3] Building Droidspaces aarch64"
if [ "${ANCT_BUILD_DROIDSPACES_FROM_SOURCE:-0}" = "1" ]; then
    clone_pinned https://github.com/ravindu644/Droidspaces-OSS.git "$DROIDSPACES_COMMIT" "$WORK/droidspaces"
    docker run --rm \
        -v "$WORK/droidspaces:/src" \
        -v "$CACHE/droidspaces-home:/home/builder" \
        -e HOME=/home/builder \
        -w /src \
        ubuntu:24.04 \
        bash -lc '
            set -euo pipefail
            apt-get update
            DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
              build-essential git wget curl patch bzip2 xz-utils ca-certificates python3
            cd /src
            ./scripts/install-musl.sh aarch64
            make aarch64
        '
    install -m 0755 "$WORK/droidspaces/output/droidspaces-aarch64" \
        "$ROOT/runtime-native/src/main/assets/native/droidspaces-aarch64"
    install -m 0755 "$WORK/droidspaces/Android/app/src/main/assets/binaries/busybox-aarch64" \
        "$ROOT/runtime-native/src/main/assets/native/busybox-aarch64"
else
    APK="$WORK/$DROIDSPACES_RELEASE_APK"
    if [ ! -f "$APK" ]; then
        curl -L --fail --retry 3 -o "$APK" \
            "https://github.com/ravindu644/Droidspaces-OSS/releases/download/v7.1.0/$DROIDSPACES_RELEASE_APK"
    fi
    if command -v shasum >/dev/null 2>&1; then
        actual=$(shasum -a 256 "$APK" | awk '{print $1}')
    else
        actual=$(sha256sum "$APK" | awk '{print $1}')
    fi
    if [ "$actual" != "$DROIDSPACES_RELEASE_SHA256" ]; then
        echo "Droidspaces release checksum mismatch" >&2
        exit 1
    fi
    unzip -p "$APK" assets/binaries/droidspaces-aarch64 \
        > "$ROOT/runtime-native/src/main/assets/native/droidspaces-aarch64"
    unzip -p "$APK" assets/binaries/busybox-aarch64 \
        > "$ROOT/runtime-native/src/main/assets/native/busybox-aarch64"
    chmod 0755 \
        "$ROOT/runtime-native/src/main/assets/native/droidspaces-aarch64" \
        "$ROOT/runtime-native/src/main/assets/native/busybox-aarch64"
fi

echo "[2/3] Building the PRoot-only Termux bootstrap"
BOOTSTRAP="$ROOT/runtime-proot/src/main/assets/proot/bootstrap-aarch64.zip"
python3 "$ROOT/scripts/build_proot_bootstrap.py" \
    --work "$WORK/proot-bootstrap" \
    --output "$BOOTSTRAP" \
    --lock "$ROOT/third_party/termux-packages.lock"
if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$BOOTSTRAP" | awk '{print $1}'
else
    sha256sum "$BOOTSTRAP" | awk '{print $1}'
fi > "$ROOT/runtime-proot/src/main/assets/proot/bootstrap-aarch64.sha256"

echo "[3/3] Packing PRoot-Distro"
DISTRO_ZIP="$ROOT/runtime-proot/src/main/assets/proot/proot-distro.zip"
rm -f "$DISTRO_ZIP"
(
    cd "$WORK/proot-distro"
    if command -v zip >/dev/null 2>&1; then
        zip -qr "$DISTRO_ZIP" proot_distro
    else
        python3 -m zipfile -c "$DISTRO_ZIP" proot_distro
    fi
)
if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$DISTRO_ZIP" | awk '{print $1}'
else
    sha256sum "$DISTRO_ZIP" | awk '{print $1}'
fi > "$ROOT/runtime-proot/src/main/assets/proot/proot-distro.sha256"

echo "Runtime assets written."
echo "Run ./gradlew :app:assembleRelease for a distributable build."

